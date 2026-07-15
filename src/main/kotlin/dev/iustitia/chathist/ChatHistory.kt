package dev.iustitia.chathist

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.iustitia.Iustitia
import dev.iustitia.config.ConfigManager
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.abs

private const val DEBOUNCE_MS = 500L
private const val MAX_ROWS = 50000
private const val PAGE_SIZE = 8

/**
 * Cross-path dedup window. A single physical chat message can arrive on two of the three armed
 * packet hooks within the same tick — most often a signed [ChatMessageS2CPacket] (captured by
 * `onChatMessage`) AND a decorated [GameMessageS2CPacket] / [ProfilelessChatMessageS2CPacket]
 * (captured by `captureDecorated` / `captureProfileless`) when a Paper/Spigot chat-formatter plugin
 * sends the signed packet for compliance and re-broadcasts the rank-formatted text as a system
 * message. Both `record` calls land in the same tick, so [ChatHistory.record] drops an exact
 * (name, text) repeat within [DEDUP_WINDOW_TICKS] of an existing row. A legitimate same-player
 * repeat is many ticks apart (human typing), so this never drops real chat.
 */
private const val DEDUP_WINDOW_TICKS = 1
/** Only the most recent rows can be within the tick window, so the dedup scan checks the list tail. */
private const val DEDUP_SCAN_TAIL = 16

/**
 * Per-player chat history for `/ius chathist`. Captures messages sent by tracked OTHER players (not
 * the local player, not system/game messages) at the packet level — the
 * [dev.iustitia.mixin.ClientPlayNetworkHandlerMixin] `onChatMessage` inject pulls the raw sender UUID
 * + plaintext straight off `ChatMessageS2CPacket` before decoration, which is the only hook that
 * exposes a reliable sender UUID (`onGameMessage` has none; `ChatHud.addMessage` bakes the UUID into
 * a decorated `Text`). Each row stores the dual tick + wall-clock-ms convention (mirrors
 * [dev.iustitia.history.FlagHistory.Flag]) so the UI can show a human `[HH:mm:ss]` timestamp.
 *
 * ## Per-server, in-memory + optional persistence
 *
 * History is bucketed by server address (`currentServerEntry?.address`, or `"singleplayer"` when
 * null) — one append-only list per server, capped at [MAX_ROWS] (drop oldest). When
 * [dev.iustitia.config.IustitiaConfig.persistenceEnabled] is ON, each server's history is loaded on
 * join from + flushed on leave to `%APPDATA%/.iustitia/chathist/<sanitized-address>/history.jsonl`
 * (one JSON row per line) and survives reconnects. When OFF, history is in-memory only and dropped
 * on leave. The folder address is sanitized with the same `[^A-Za-z0-9_.-]→_` rule as
 * [dev.iustitia.replay.ClipStore] filenames.
 *
 * ## Lazy page fetch
 *
 * `/ius chathist` renders only the requested page: `rows.drop((page-1)*8).take(8)` — never the full
 * list — so a huge history is not laggy to browse. Capture is packet-driven (rare vs the per-tick
 * check load) and persistence is debounced on a daemon writer (mirrors
 * [dev.iustitia.persistence.PersistenceManager]) so a burst of chat coalesces into one write. All
 * paths fail-open — a corrupt line is skipped (never blocks load), an IO error never crashes the
 * client. **Local-only** — nothing is uploaded.
 */
object ChatHistory {

    /** One captured chat message. [tick] = [Iustitia.tickCounter]; [wallClockMs] for the `[HH:mm:ss]` UI. */
    data class Row(val tick: Int, val wallClockMs: Long, val uuid: UUID, val name: String, val text: String)

    /** Rows per page for `/ius chathist` (the user spec: ~8 rows). */
    const val PAGE_SIZE_ROWS = PAGE_SIZE

    private val gson: Gson = Gson()   // compact, one object per line (JSONL)

    private val byServer: MutableMap<String, MutableList<Row>> = LinkedHashMap()
    private var currentKey: String = "singleplayer"

    private val dataDir: Path by lazy {
        val appdata = try { System.getenv("APPDATA") } catch (_: Throwable) { null }
        if (appdata != null) Path.of(appdata).resolve(".iustitia")
        else FabricLoader.getInstance().gameDir.resolve(".iustitia")
    }
    private val chathistDir: Path get() = dataDir.resolve("chathist")

    private val enabled: Boolean get() = try { ConfigManager.config.chathistEnabled } catch (_: Throwable) { false }
    private val persist: Boolean get() = try { ConfigManager.config.persistenceEnabled } catch (_: Throwable) { false }
    /** Permissive / cross-server capture: also capture senders NOT in the tab list (Bungee/Velocity network chat). Defaults OFF. */
    private val captureUnknown: Boolean get() = try { ConfigManager.config.chathistCaptureUnknown } catch (_: Throwable) { false }

    // --- debounced writer ---
    private val queueLock = Object()
    @Volatile private var dirty = false
    @Volatile private var writerStarted = false
    private val writerThread: Thread by lazy {
        Thread(::writerLoop, "Iustitia-ChatHistWriter").apply { isDaemon = true }
    }
    private val flushHook = Thread({ try { flush() } catch (_: Throwable) {} }, "Iustitia-ChatHistFlush")
    @Volatile private var hookAdded = false

    /** The current server-address key (`currentServerEntry?.address` ?: `"singleplayer"`). Fail-open. */
    private fun serverKey(): String = try {
        MinecraftClient.getInstance().currentServerEntry?.address?.takeIf { it.isNotBlank() } ?: "singleplayer"
    } catch (_: Throwable) { "singleplayer" }

    private fun sanitize(address: String): String = address.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun fileFor(key: String): Path = chathistDir.resolve(sanitize(key)).resolve("history.jsonl")

    /** Capture one chat row from a tracked other player. Called from the `onChatMessage` mixin and the
     *  decorated/profileless capture paths. Drops an exact (name, text) repeat within
     *  [DEDUP_WINDOW_TICKS] of an existing row so a message double-broadcast on two packet hooks
     *  isn't stored twice. Fail-open. */
    fun record(tick: Int, wallClockMs: Long, uuid: UUID, name: String, text: String) {
        if (!enabled) return
        try {
            val list = synchronized(byServer) { byServer.getOrPut(currentKey) { ArrayList() } }
            synchronized(list) {
                // Cross-path dedup: scan the tail (same-tick rows are the most recent) and drop this
                // row if an identical (name, text) was just recorded within the tick window. Text is
                // trimmed for the comparison so a stray leading space (decorated path trimStart vs
                // signed raw content) doesn't defeat it. Fail-open: any throw → fall through to add.
                val n = list.size
                var i = n - 1
                val from = (n - DEDUP_SCAN_TAIL).coerceAtLeast(0)
                val keyText = text.trim()
                while (i >= from) {
                    val prev = list[i]
                    if (abs(tick - prev.tick) <= DEDUP_WINDOW_TICKS &&
                        keyText == prev.text.trim() &&
                        name.equals(prev.name, ignoreCase = true)
                    ) {
                        return // already captured this same message this tick — skip the duplicate
                    }
                    i--
                }
                list.add(Row(tick, wallClockMs, uuid, name, text))
                while (list.size > MAX_ROWS) list.removeAt(0)
            }
            if (persist) schedule()
        } catch (_: Throwable) {}
    }

    /**
     * Capture a decorated system/game message (`onGameMessage` path) — the chat route used by
     * Hypixel/ArchMC/Minemen/Cavern/Evox/Stray/mcpvp/PvpHQ/Mineplex, which send `[rank] username:
     * message` as a `GameMessageS2CPacket` with no sender UUID. [DecoratedChatParser] recovers the
     * `(username, message)` structurally and resolves the UUID from the tab list. Fail-open.
     */
    fun captureDecorated(text: Text) {
        if (!enabled) return
        try {
            val p = DecoratedChatParser.parseGameMessage(text, captureUnknown) ?: return
            record(Iustitia.tickCounter, System.currentTimeMillis(), p.uuid, p.name, p.text)
        } catch (_: Throwable) {}
    }

    /**
     * Capture a profileless chat message (`onProfilelessChatMessage` path) — the packet already
     * splits the sender display name from the message body, so this is the cleanest decorated path
     * when a server uses it. [DecoratedChatParser] resolves the display name → clean username +
     * UUID. Fail-open.
     */
    fun captureProfileless(nameText: Text, messageText: Text) {
        if (!enabled) return
        try {
            val p = DecoratedChatParser.parseProfileless(nameText, messageText, captureUnknown) ?: return
            record(Iustitia.tickCounter, System.currentTimeMillis(), p.uuid, p.name, p.text)
        } catch (_: Throwable) {}
    }

    /** Load the current server's persisted history on join. Called from `onGameJoin`. Fail-open. */
    fun onJoin() {
        try {
            currentKey = serverKey()
            if (!persist) return
            synchronized(byServer) { byServer[currentKey] = loadFile(currentKey) }
        } catch (_: Throwable) {}
    }

    /** Flush (persist ON) or drop (persist OFF) the current server's history on disconnect. Fail-open. */
    fun onLeave() {
        try {
            if (persist) { writeNow(currentKey); schedule() }
            else synchronized(byServer) { byServer.remove(currentKey) }
        } catch (_: Throwable) {}
    }

    /** Force any pending debounced write (shutdown hook). */
    fun flush() {
        try {
            synchronized(queueLock) { if (!dirty) return }
            writeNow(currentKey)
        } catch (_: Throwable) {}
    }

    /** All rows for [username] (case-insensitive, name-or-uuid match), newest-first. Fail-open. */
    fun rowsForUser(username: String): List<Row> = try {
        val key = username.trim()
        if (key.isEmpty()) emptyList()
        else allCurrent().filter { it.name.equals(key, ignoreCase = true) || it.uuid.toString().equals(key, ignoreCase = true) }.reversed()
    } catch (_: Throwable) { emptyList() }

    /** Every row whose text contains [phrase] (case-insensitive), newest-first. Fail-open. */
    fun rowsForPhrase(phrase: String): List<Row> = try {
        val key = phrase.trim()
        if (key.isEmpty()) emptyList()
        else allCurrent().filter { it.text.contains(key, ignoreCase = true) }.reversed()
    } catch (_: Throwable) { emptyList() }

    /** Rows for [username] whose text contains [phrase], newest-first. Fail-open. */
    fun rowsForUserPhrase(username: String, phrase: String): List<Row> = try {
        val u = username.trim(); val p = phrase.trim()
        if (u.isEmpty() || p.isEmpty()) emptyList()
        else allCurrent()
            .filter { it.name.equals(u, ignoreCase = true) || it.uuid.toString().equals(u, ignoreCase = true) }
            .filter { it.text.contains(p, ignoreCase = true) }
            .reversed()
    } catch (_: Throwable) { emptyList() }

    private fun allCurrent(): List<Row> = try {
        synchronized(byServer) { byServer[currentKey] }?.let { synchronized(it) { it.toList() } } ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    private fun loadFile(key: String): MutableList<Row> {
        val out = ArrayList<Row>()
        val path = fileFor(key)
        if (!Files.exists(path)) return out
        try {
            Files.lines(path).use { stream ->
                stream.forEach { line ->
                    if (line.isBlank()) return@forEach
                    try {
                        val o = JsonParser.parseString(line).asJsonObject
                        out.add(Row(
                            o.get("tick").asInt,
                            o.get("wallClockMs").asLong,
                            UUID.fromString(o.get("uuid").asString),
                            o.get("name")?.asString ?: "",
                            o.get("text")?.asString ?: "",
                        ))
                    } catch (_: Throwable) { /* skip malformed line */ }
                }
            }
        } catch (_: Throwable) {}
        return out
    }

    private fun writeNow(key: String) {
        try {
            val list = synchronized(byServer) { byServer[key] } ?: return
            val path = fileFor(key)
            synchronized(chathistDir) {
                Files.createDirectories(path.parent)
                val sb = StringBuilder()
                synchronized(list) { list.forEach { sb.append(gson.toJson(toJson(it))).append('\n') } }
                Files.writeString(path, sb.toString())
            }
        } catch (_: Throwable) {}
    }

    private fun toJson(r: Row): JsonObject = JsonObject().apply {
        addProperty("tick", r.tick)
        addProperty("wallClockMs", r.wallClockMs)
        addProperty("uuid", r.uuid.toString())
        addProperty("name", r.name)
        addProperty("text", r.text)
    }

    private fun schedule() {
        try {
            synchronized(queueLock) {
                dirty = true
                if (!writerStarted) { writerStarted = true; writerThread.start() }
                if (!hookAdded) {
                    hookAdded = true
                    try { Runtime.getRuntime().addShutdownHook(flushHook) } catch (_: Throwable) {}
                }
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (queueLock as Object).notify()
            }
        } catch (_: Throwable) {}
    }

    private fun writerLoop() {
        while (true) {
            try {
                synchronized(queueLock) {
                    while (!dirty) {
                        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                        (queueLock as Object).wait()
                    }
                }
                Thread.sleep(DEBOUNCE_MS)
                val key: String
                synchronized(queueLock) { key = currentKey; dirty = false }
                writeNow(key)
            } catch (_: Throwable) {
                // a daemon writer must never die
            }
        }
    }
}
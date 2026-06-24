package dev.iustitia.persistence

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.Vec3d
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private const val DEBOUNCE_MS = 500L

/**
 * Roaming persistence store for the Phase 2 operability features: moderator notes, the tier+flag
 * history, evidence snapshots, and transcript/evidence exports. Writes to `%APPDATA%/.iustitia` on
 * Windows (so Modrinth/multi-instance users find one dir regardless of instance path) and to the
 * game-dir `.iustitia` elsewhere. Everything is gated by [IustitiaConfig.persistenceEnabled] — when
 * off, nothing is read or written and the session is in-memory only as before.
 *
 * Notes + history are saved through a debounced daemon writer (mirroring [ConfigManager]'s pattern):
 * a burst of edits coalesces into one write after a [DEBOUNCE_MS] quiet period, and a JVM shutdown
 * hook forces a final [flush] so a change made right before quit is not lost. Snapshots + exports
 * are tiny one-off files written immediately (off the tick thread via [MinecraftClient.execute]).
 * All paths fail-open: a corrupt file falls back to defaults and is overwritten on the next save;
 * a write error never crashes the client or the tick pipeline. **Local-only** — nothing is sent to
 * the server. See the plan's Part 0b.
 */
object PersistenceManager {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** Roaming dir: `%APPDATA%/.iustitia` on Windows, `<gameDir>/.iustitia` elsewhere. */
    private val dataDir: Path by lazy {
        val appdata = try { System.getenv("APPDATA") } catch (_: Throwable) { null }
        if (appdata != null) Path.of(appdata).resolve(".iustitia")
        else FabricLoader.getInstance().gameDir.resolve(".iustitia")
    }
    private val notesPath: Path get() = dataDir.resolve("notes.json")
    private val historyPath: Path get() = dataDir.resolve("history.json")
    private val exemptionsPath: Path get() = dataDir.resolve("exemptions.json")
    private val snapshotsDir: Path get() = dataDir.resolve("snapshots")
    private val exportsDir: Path get() = dataDir.resolve("exports")

    private val enabled: Boolean get() = try { ConfigManager.config.persistenceEnabled } catch (_: Throwable) { false }

    // --- debounced writer (notes + history + exemptions share one writer thread) ---
    private val queueLock = Object()
    @Volatile private var notesDirty = false
    @Volatile private var historyDirty = false
    @Volatile private var exemptionsDirty = false
    @Volatile private var writerStarted = false
    private val writerThread: Thread by lazy {
        Thread(::writerLoop, "Iustitia-PersistenceWriter").apply { isDaemon = true }
    }
    private val flushHook = Thread({ try { flush() } catch (_: Throwable) {} }, "Iustitia-PersistenceFlush")
    @Volatile private var hookAdded = false

    /** Load notes + history into the in-memory stores. Called from [dev.iustitia.Iustitia.init]
     *  after [ConfigManager.load], and whenever the toggle flips on at runtime. No-op when disabled. */
    fun loadOnStartup() {
        if (!enabled) return
        try { loadNotes() } catch (_: Throwable) {}
        try { loadHistory() } catch (_: Throwable) {}
        try { loadExemptions() } catch (_: Throwable) {}
    }

    /** React to the persistence toggle changing: on → load + schedule a full save; off → stop. */
    fun onToggle(nowEnabled: Boolean) {
        if (nowEnabled) { try { loadOnStartup(); saveNote(); saveHistory(); saveExemptions() } catch (_: Throwable) {} }
        // when turning off we simply stop scheduling saves; existing in-memory state stays.
    }

    // ---- notes ----
    fun saveNote() { if (enabled) schedule(notesDirty = true) }
    private fun loadNotes() {
        val p = notesPath
        if (!Files.exists(p)) return
        val arr = JsonParser.parseString(Files.readString(p)).asJsonArray
        arr.forEach { e ->
            try {
                val o = e.asJsonObject
                val uuid = UUID.fromString(o.get("uuid").asString)
                val cat = NoteStore.parseCategory(o.get("category").asString) ?: NoteStore.Category.NEEDS_REVIEW
                NoteStore.set(uuid, o.get("name")?.asString ?: uuid.toString().take(8),
                    cat, o.get("text")?.asString ?: "", o.get("tick")?.asInt ?: 0)
            } catch (_: Throwable) {}
        }
    }
    private fun writeNotesNow() {
        try {
            val arr = JsonArray()
            NoteStore.all().forEach { n ->
                val o = JsonObject()
                o.addProperty("uuid", n.uuid.toString())
                o.addProperty("name", n.name)
                o.addProperty("category", n.category.name.lowercase())
                o.addProperty("text", n.text)
                o.addProperty("tick", n.tick)
                arr.add(o)
            }
            writeText(notesPath, gson.toJson(arr))
        } catch (_: Throwable) {}
    }

    // ---- history ----
    fun saveHistory() { if (enabled) schedule(historyDirty = true) }

    // ---- exemptions ----
    fun saveExemptions() { if (enabled) schedule(exemptionsDirty = true) }
    private fun loadExemptions() {
        val p = exemptionsPath
        if (!Files.exists(p)) return
        val arr = JsonParser.parseString(Files.readString(p)).asJsonArray
        arr.forEach { e ->
            try {
                val o = e.asJsonObject
                val uuid = UUID.fromString(o.get("uuid").asString)
                dev.iustitia.exempt.Exemptions.load(uuid, o.get("name")?.asString ?: uuid.toString().take(8))
            } catch (_: Throwable) {}
        }
    }
    private fun writeExemptionsNow() {
        try {
            val arr = JsonArray()
            dev.iustitia.exempt.Exemptions.all().forEach { (uuid, name) ->
                val o = JsonObject()
                o.addProperty("uuid", uuid.toString())
                o.addProperty("name", name)
                arr.add(o)
            }
            writeText(exemptionsPath, gson.toJson(arr))
        } catch (_: Throwable) {}
    }
    private fun loadHistory() {
        val p = historyPath
        if (!Files.exists(p)) return
        val root = JsonParser.parseString(Files.readString(p)).asJsonObject
        val players = root.getAsJsonArray("players") ?: return
        players.forEach { e ->
            try {
                val o = e.asJsonObject
                val uuid = UUID.fromString(o.get("uuid").asString)
                val name = o.get("name")?.asString ?: uuid.toString().take(8)
                val alertCount = o.get("alertCount")?.asInt ?: 0
                val alerted = LinkedHashSet<String>()
                o.getAsJsonArray("alertedChecks")?.forEach { c -> alerted.add(c.asString) }
                val lastMs = o.get("lastAlertWallMs")?.asLong ?: 0L
                val fc = HashMap<String, Int>()
                o.getAsJsonObject("flagCounts")?.entrySet()?.forEach { (k, v) -> fc[k] = v.asInt }
                val mv = HashMap<String, Double>()
                o.getAsJsonObject("maxVl")?.entrySet()?.forEach { (k, v) -> mv[k] = v.asDouble }
                val flags = ArrayList<FlagHistory.Flag>()
                o.getAsJsonArray("flags")?.forEach { f ->
                    flags.add(readFlag(f.asJsonObject))
                }
                FlagHistory.mergePersisted(uuid, name, alertCount, alerted, lastMs, fc, mv, flags)
            } catch (_: Throwable) {}
        }
    }
    private fun readFlag(o: JsonObject): FlagHistory.Flag {
        val tick = o.get("tick").asInt
        val checkId = o.get("checkId").asString
        val label = o.get("label")?.asString ?: ""
        val vl = o.get("vl")?.asDouble ?: 0.0
        val wall = o.get("wallClockMs")?.asLong ?: 0L
        var ev: dev.iustitia.history.Evidence? = null
        if (o.has("evidence") && !o.get("evidence").isJsonNull) {
            val e = o.getAsJsonObject("evidence")
            val pos = if (e.has("pos") && !e.get("pos").isJsonNull) {
                val pa = e.getAsJsonArray("pos"); Vec3d(pa[0].asDouble, pa[1].asDouble, pa[2].asDouble)
            } else null
            ev = dev.iustitia.history.Evidence(
                subLabel = e.get("subLabel")?.takeIf { !it.isJsonNull }?.asString,
                measurement = e.get("measurement")?.takeIf { !it.isJsonNull }?.asDouble,
                threshold = e.get("threshold")?.takeIf { !it.isJsonNull }?.asDouble,
                pos = pos,
                victim = e.get("victim")?.takeIf { !it.isJsonNull }?.asString?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                extra = e.get("extra")?.takeIf { !it.isJsonNull }?.asString,
            )
        }
        return FlagHistory.Flag(tick, checkId, label, vl, wall, ev)
    }
    private fun writeHistoryNow() {
        try {
            val root = JsonObject()
            val players = JsonArray()
            for (uuid in FlagHistory.knownUuids()) {
                val o = JsonObject()
                o.addProperty("uuid", uuid.toString())
                o.addProperty("name", FlagHistory.nameFor(uuid) ?: uuid.toString().take(8))
                o.addProperty("alertCount", FlagHistory.sessionAlertCount(uuid))
                val alerted = JsonArray(); FlagHistory.alertedChecksOf(uuid).forEach { alerted.add(it) }
                o.add("alertedChecks", alerted)
                o.addProperty("lastAlertWallMs", FlagHistory.lastAlertWallMsOf(uuid))
                val fc = JsonObject(); FlagHistory.flagCounts(uuid).forEach { (k, v) -> fc.addProperty(k, v) }
                o.add("flagCounts", fc)
                val mv = JsonObject(); FlagHistory.maxVlByCheck(uuid).forEach { (k, v) -> mv.addProperty(k, v) }
                o.add("maxVl", mv)
                val flags = JsonArray()
                // oldest→newest for mergePersisted; flags() returns newest-first, so reverse.
                FlagHistory.flags(uuid).reversed().forEach { f ->
                    val fo = JsonObject()
                    fo.addProperty("tick", f.tick)
                    fo.addProperty("checkId", f.checkId)
                    fo.addProperty("label", f.label)
                    fo.addProperty("vl", f.vl)
                    fo.addProperty("wallClockMs", f.wallClockMs)
                    f.evidence?.let { ev ->
                        val e = JsonObject()
                        ev.subLabel?.let { e.addProperty("subLabel", it) }
                        ev.measurement?.let { e.addProperty("measurement", it) }
                        ev.threshold?.let { e.addProperty("threshold", it) }
                        ev.pos?.let { val pa = JsonArray(); pa.add(it.x); pa.add(it.y); pa.add(it.z); e.add("pos", pa) }
                        ev.victim?.let { e.addProperty("victim", it.toString()) }
                        ev.extra?.let { e.addProperty("extra", it) }
                        fo.add("evidence", e)
                    }
                    flags.add(fo)
                }
                o.add("flags", flags)
                players.add(o)
            }
            root.add("players", players)
            writeText(historyPath, gson.toJson(root))
        } catch (_: Throwable) {}
    }

    // ---- one-off files (snapshots + exports) ----
    /** The `snapshots/` dir as a [java.io.File] for [net.minecraft.client.util.ScreenshotRecorder],
     *  created if missing. Null when persistence is off (caller bails → no PNG, chat/json summary
     *  still land). Called on the client thread. */
    fun screenshotsDirFile(): java.io.File? = try {
        if (!enabled) null
        else { Files.createDirectories(snapshotsDir); snapshotsDir.toFile() }
    } catch (_: Throwable) { null }

    /** Write `snapshots/<tick>_<name>.json`. Called from the client thread (snapshot keybind). */
    fun saveSnapshot(name: String, json: String) {
        if (!enabled) return
        MinecraftClient.getInstance().execute {
            try {
                Files.createDirectories(snapshotsDir)
                val tick = dev.iustitia.Iustitia.tickCounter
                val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                Files.writeString(snapshotsDir.resolve("${tick}_${safe}.json"), json)
            } catch (_: Throwable) {}
        }
    }

    /** Write `exports/<kind>_<tick>_<name>.txt`. Called from the client thread. */
    fun saveExport(kind: String, name: String, text: String) {
        if (!enabled) return
        MinecraftClient.getInstance().execute {
            try {
                Files.createDirectories(exportsDir)
                val tick = dev.iustitia.Iustitia.tickCounter
                val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                Files.writeString(exportsDir.resolve("${kind}_${tick}_${safe}.txt"), text)
            } catch (_: Throwable) {}
        }
    }

    // ---- debounced writer plumbing ----
    private fun schedule(notesDirty: Boolean = false, historyDirty: Boolean = false, exemptionsDirty: Boolean = false) {
        try {
            synchronized(queueLock) {
                if (notesDirty) this.notesDirty = true
                if (historyDirty) this.historyDirty = true
                if (exemptionsDirty) this.exemptionsDirty = true
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
                    while (!notesDirty && !historyDirty && !exemptionsDirty) {
                        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                        (queueLock as Object).wait()
                    }
                }
                Thread.sleep(DEBOUNCE_MS)
                val doNotes: Boolean; val doHistory: Boolean; val doExemptions: Boolean
                synchronized(queueLock) {
                    doNotes = notesDirty; doHistory = historyDirty; doExemptions = exemptionsDirty
                    notesDirty = false; historyDirty = false; exemptionsDirty = false
                }
                if (doNotes) writeNotesNow()
                if (doHistory) writeHistoryNow()
                if (doExemptions) writeExemptionsNow()
            } catch (_: Throwable) {
                // a daemon writer must never die
            }
        }
    }

    /** Force any pending debounced write to disk (shutdown hook). */
    fun flush() {
        try {
            val doNotes: Boolean; val doHistory: Boolean; val doExemptions: Boolean
            synchronized(queueLock) {
                doNotes = notesDirty; doHistory = historyDirty; doExemptions = exemptionsDirty
                notesDirty = false; historyDirty = false; exemptionsDirty = false
            }
            if (doNotes) writeNotesNow()
            if (doHistory) writeHistoryNow()
            if (doExemptions) writeExemptionsNow()
        } catch (_: Throwable) {}
    }

    private fun writeText(path: Path, text: String) {
        synchronized(dataDir) {
            try {
                Files.createDirectories(path.parent)
                Files.writeString(path, text)
            } catch (_: Throwable) {}
        }
    }
}
package dev.iustitia.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.iustitia.Iustitia
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/** Quiet period after the last [ConfigManager.save] before the writer flushes — coalesces bursts. */
private const val DEBOUNCE_MS = 350L

/**
 * Loads/saves [IustitiaConfig] to `config/iustitia.json`. Hand-rolled through Gson's
 * JsonObject so we stay independent of the kotlinx.serialization compiler plugin and
 * keep Kotlin default-arg constructors out of the deserialization path.
 *
 * Everything is fail-open: a corrupt or partial file falls back to defaults and is
 * overwritten on the next save.
 *
 * **Saving is async + debounced.** [save] serializes the live config on the *calling*
 * thread (so the volatile [config] reference is read consistently, never cross-thread),
 * hands the resulting JSON string to a single daemon writer thread, and returns
 * immediately — it never blocks the render/tick thread on disk I/O. A burst of [save]
 * calls (e.g. dragging a YACL slider fires one per step, or toggling several mutes at
 * once) coalesces into a single write: the writer waits [DEBOUNCE_MS] after the last
 * [save] before flushing, so only the latest snapshot hits disk. [flush] forces any
 * pending write synchronously and is wired to the client-stopping lifecycle event so a
 * pending config change is never lost on exit. All of it is wrapped in try/catch — a
 * config write never crashes the client.
 */
object ConfigManager {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path by lazy {
        FabricLoader.getInstance().configDir.resolve("iustitia.json")
    }

    @Volatile
    var config: IustitiaConfig = IustitiaConfig()
        private set

    // --- async/debounced save plumbing ------------------------------------------------

    /** Serializes disk writes so the writer thread and [flush] never interleave a file write. */
    private val writeLock = Any()
    /** Monitor guarding [dirty] / [pendingJson]; the writer thread waits on it. */
    private val queueLock = Object()
    @Volatile private var dirty: Boolean = false
    @Volatile private var pendingJson: String? = null
    @Volatile private var writerStarted: Boolean = false

    private val writerThread: Thread by lazy {
        Thread(::writerLoop, "Iustitia-ConfigWriter").apply { isDaemon = true }
    }

    /**
     * JVM shutdown hook that forces a final synchronous [flush] so a debounced config change
     * made right before the client closes is not lost (the writer is a daemon — the JVM won't
     * wait for it). Registered once on the first [save]; fail-open.
     */
    private val flushHook: Thread = Thread({ try { flush() } catch (_: Throwable) {} }, "Iustitia-ConfigFlush")
    @Volatile private var hookAdded: Boolean = false

    fun load() {
        config = try {
            if (Files.exists(path)) {
                val obj = JsonParser.parseString(Files.readString(path)).asJsonObject
                fromJson(obj)
            } else {
                IustitiaConfig()
            }
        } catch (_: Throwable) {
            IustitiaConfig()
        }
    }

    /**
     * Debounced, off-thread save. Serializes the config now (on the caller thread) and hands
     * the JSON to the writer thread, which flushes it to disk after a [DEBOUNCE_MS] quiet
     * period. Rapid successive calls coalesce — only the latest snapshot is written. Never
     * throws; never blocks the caller on disk I/O.
     */
    fun save() {
        try {
            val json = gson.toJson(toJson(config))
            synchronized(queueLock) {
                pendingJson = json
                dirty = true
                if (!writerStarted) {
                    writerStarted = true
                    writerThread.start()
                }
                if (!hookAdded) {
                    hookAdded = true
                    try { Runtime.getRuntime().addShutdownHook(flushHook) } catch (_: Throwable) {}
                }
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (queueLock as Object).notify()
            }
        } catch (_: Throwable) {
            // best-effort; never crash the caller over a config queue
        }
    }

    /**
     * Block until any pending debounced write has landed on disk. Called from the
     * client-stopping lifecycle hook so a config change made right before quit is not lost.
     * If nothing is pending this is a no-op. Never throws.
     */
    fun flush() {
        try {
            val snapshot: String?
            synchronized(queueLock) {
                snapshot = pendingJson
                if (snapshot != null) {
                    pendingJson = null
                    dirty = false
                }
            }
            if (snapshot != null) writeNow(snapshot)
        } catch (_: Throwable) {
            // best-effort
        }
    }

    private fun writerLoop() {
        while (true) {
            try {
                // Wait for a save() to mark dirty. Then sleep DEBOUNCE_MS *outside* the lock so a
                // burst of save() calls during that window keeps overwriting pendingJson (last
                // wins → one coalesced write). After the debounce, take the latest snapshot and
                // write it. fail-open: any error just loops back to waiting.
                synchronized(queueLock) {
                    while (!dirty) {
                        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                        (queueLock as Object).wait()
                    }
                }
                Thread.sleep(DEBOUNCE_MS)
                val snapshot: String?
                synchronized(queueLock) {
                    snapshot = pendingJson
                    if (snapshot != null) {
                        pendingJson = null
                        dirty = false
                    }
                }
                if (snapshot != null) writeNow(snapshot)
            } catch (_: Throwable) {
                // interrupted / etc — clear and continue; a daemon writer must never die
            }
        }
    }

    /** Performs the actual file write, serialized on [writeLock] so [flush] + writer can't race. */
    private fun writeNow(json: String) {
        synchronized(writeLock) {
            try {
                Files.createDirectories(path.parent)
                Files.writeString(path, json)
            } catch (_: Throwable) {
                // best-effort; never crash the writer thread over a config write
            }
        }
    }

    fun reload() {
        load()
        Iustitia.onConfigReloaded()
    }

    private fun toJson(c: IustitiaConfig): JsonObject = JsonObject().apply {
        addProperty("enabled", c.enabled)
        addProperty("verbose", c.verbose)
        addProperty("configVersion", c.configVersion)
        addProperty("alertThrottleTicks", c.alertThrottleTicks)
        addProperty("joinGraceTicks", c.joinGraceTicks)
        addProperty("legitScaffoldStrictGates", c.legitScaffoldStrictGates)
        addProperty("alertsEnabled", c.alertsEnabled)
        addProperty("nametagPrefixes", c.nametagPrefixes)
        addProperty("nametagGreenEnabled", c.nametagGreenEnabled)
        add("mutedChecks", JsonArray().apply { c.mutedChecks.forEach { add(it) } })
        add("mutedPlayers", JsonArray().apply { c.mutedPlayers.forEach { add(it) } })
        // Phase 2 UX fields (all additive — fromJson reads them conditionally, so a pre-Phase-2
        // config keeps its tuned check calibration and just gets these defaults).
        addProperty("persistenceEnabled", c.persistenceEnabled)
        addProperty("wizardCompleted", c.wizardCompleted)
        addProperty("alertLevel", c.alertLevel)
        addProperty("alertBatching", c.alertBatching)
        addProperty("alertBatchWindowTicks", c.alertBatchWindowTicks)
        addProperty("audioCues", c.audioCues)
        addProperty("audioVolume", c.audioVolume)
        addProperty("audioNuclear", c.audioNuclear)
        addProperty("lagSuppressAlerts", c.lagSuppressAlerts)
        addProperty("nametagBadge", c.nametagBadge)
        addProperty("nametagBurstPulse", c.nametagBurstPulse)
        addProperty("compactMode", c.compactMode)
        addProperty("evidenceWindowTicks", c.evidenceWindowTicks)
        addProperty("transcriptPanel", c.transcriptPanel)
        addProperty("lagHudIcon", c.lagHudIcon)
        addProperty("confidenceHud", c.confidenceHud)
        addProperty("targetHighlight", c.targetHighlight)
        addProperty("ghostTrail", c.ghostTrail)
        addProperty("watchFollowCam", c.watchFollowCam)
        addProperty("burstSparks", c.burstSparks)
        addProperty("hoverTooltip", c.hoverTooltip)
        addProperty("tabListBadge", c.tabListBadge)
        for ((key, cc) in c.checks()) add(key, checkToJson(cc))
    }

    private fun checkToJson(cc: IustitiaConfig.CheckConfig): JsonObject = JsonObject().apply {
        addProperty("enabled", cc.enabled)
        addProperty("setbackVL", cc.setbackVL)
        addProperty("decay", cc.decay)
        addProperty("threshold", cc.threshold)
    }

    private fun fromJson(o: JsonObject): IustitiaConfig {
        val c = IustitiaConfig()
        try {
            // Calibration migration: if the persisted config predates the current calibration
            // version, reset each check's CALIBRATION fields (setbackVL/decay/threshold) to the
            // code defaults while preserving user choices (per-check enabled, mutes, alerts,
            // nametag). Without this, a config/iustitia.json saved before a recalibration
            // silently overrides the tuned defaults — the round-1/2 decay/VL edits were being
            // clobbered this way (flyEnvelope decay stayed 1.0, throughWalls setbackVL stayed
            // 5.0, timerRate setbackVL stayed 5.0), so the config tuning had no effect on the
            // running game. c.configVersion is the fresh default (never overwritten from disk),
            // so the next save stamps the file current.
            val savedVersion = if (o.has("configVersion")) o.get("configVersion").asInt else 0
            val resetCalibration = savedVersion < c.configVersion
            if (o.has("enabled")) c.enabled = o.get("enabled").asBoolean
            if (o.has("verbose")) c.verbose = o.get("verbose").asBoolean
            if (o.has("alertThrottleTicks")) c.alertThrottleTicks = o.get("alertThrottleTicks").asInt
            if (o.has("joinGraceTicks")) c.joinGraceTicks = o.get("joinGraceTicks").asInt
            if (o.has("legitScaffoldStrictGates")) c.legitScaffoldStrictGates = o.get("legitScaffoldStrictGates").asBoolean
            if (o.has("alertsEnabled")) c.alertsEnabled = o.get("alertsEnabled").asBoolean
            if (o.has("nametagPrefixes")) c.nametagPrefixes = o.get("nametagPrefixes").asBoolean
            if (o.has("nametagGreenEnabled")) c.nametagGreenEnabled = o.get("nametagGreenEnabled").asBoolean
            readStringList(o, "mutedChecks", c.mutedChecks)
            readStringList(o, "mutedPlayers", c.mutedPlayers)
            // Phase 2 UX fields (conditional — a pre-Phase-2 config simply keeps the defaults).
            if (o.has("persistenceEnabled")) c.persistenceEnabled = o.get("persistenceEnabled").asBoolean
            if (o.has("wizardCompleted")) c.wizardCompleted = o.get("wizardCompleted").asBoolean
            if (o.has("alertLevel")) c.alertLevel = o.get("alertLevel").asInt
            if (o.has("alertBatching")) c.alertBatching = o.get("alertBatching").asBoolean
            if (o.has("alertBatchWindowTicks")) c.alertBatchWindowTicks = o.get("alertBatchWindowTicks").asInt
            if (o.has("audioCues")) c.audioCues = o.get("audioCues").asBoolean
            if (o.has("audioVolume")) c.audioVolume = o.get("audioVolume").asDouble
            if (o.has("audioNuclear")) c.audioNuclear = o.get("audioNuclear").asBoolean
            if (o.has("lagSuppressAlerts")) c.lagSuppressAlerts = o.get("lagSuppressAlerts").asBoolean
            if (o.has("nametagBadge")) c.nametagBadge = o.get("nametagBadge").asBoolean
            if (o.has("nametagBurstPulse")) c.nametagBurstPulse = o.get("nametagBurstPulse").asBoolean
            if (o.has("compactMode")) c.compactMode = o.get("compactMode").asBoolean
            if (o.has("evidenceWindowTicks")) c.evidenceWindowTicks = o.get("evidenceWindowTicks").asInt
            if (o.has("transcriptPanel")) c.transcriptPanel = o.get("transcriptPanel").asBoolean
            if (o.has("lagHudIcon")) c.lagHudIcon = o.get("lagHudIcon").asBoolean
            if (o.has("confidenceHud")) c.confidenceHud = o.get("confidenceHud").asBoolean
            if (o.has("targetHighlight")) c.targetHighlight = o.get("targetHighlight").asBoolean
            if (o.has("ghostTrail")) c.ghostTrail = o.get("ghostTrail").asBoolean
            if (o.has("watchFollowCam")) c.watchFollowCam = o.get("watchFollowCam").asBoolean
            if (o.has("burstSparks")) c.burstSparks = o.get("burstSparks").asBoolean
            if (o.has("hoverTooltip")) c.hoverTooltip = o.get("hoverTooltip").asBoolean
            if (o.has("tabListBadge")) c.tabListBadge = o.get("tabListBadge").asBoolean
            for ((key, cc) in c.checks()) {
                if (o.has(key)) readCheck(o.getAsJsonObject(key), cc, resetCalibration)
            }
        } catch (_: Throwable) {
            // partial parse → keep defaults for anything unread
        }
        return c
    }

    private fun readCheck(o: JsonObject, cc: IustitiaConfig.CheckConfig, resetCalibration: Boolean) {
        try {
            if (o.has("enabled")) cc.enabled = o.get("enabled").asBoolean
            if (resetCalibration) return // keep code-default setbackVL/decay/threshold (migration)
            if (o.has("setbackVL")) cc.setbackVL = o.get("setbackVL").asDouble
            if (o.has("decay")) cc.decay = o.get("decay").asDouble
            if (o.has("threshold")) cc.threshold = o.get("threshold").asDouble
        } catch (_: Throwable) {
            // ignore per-field errors
        }
    }

    private fun readStringList(o: JsonObject, key: String, out: MutableList<String>) {
        try {
            if (!o.has(key)) return
            val arr = o.getAsJsonArray(key) ?: return
            out.clear()
            arr.forEach { e -> if (e != null && !e.isJsonNull) out.add(e.asString) }
        } catch (_: Throwable) {
            // partial parse → keep what we have
        }
    }
}
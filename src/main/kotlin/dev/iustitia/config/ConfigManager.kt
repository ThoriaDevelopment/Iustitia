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
 * pending write synchronously and is wired to BOTH the client-stopping lifecycle event
 * ([IustitiaClientMod] registers it — the normal exit path) AND a JVM shutdown hook (the
 * last-resort path: /kill, crash, launcher-kill — the writer is a daemon, so the JVM won't
 * wait for it). Stale-write protection: every snapshot carries a monotonic id, and
 * [writeNow] skips any write whose id is not newer than the last one that landed — so the
 * writer thread holding an older snapshot can never clobber a [flush] that already wrote a
 * newer one (see [writtenSeq]). All of it is wrapped in try/catch — a pending config
 * change is never lost on exit, and a config write never crashes the client.
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
    /** Monotonic id of the latest queued snapshot ([save] bumps it every call). Guarded by [queueLock]. */
    private var saveSeq: Long = 0
    /** Id of the snapshot currently queued in [pendingJson] (0 when none). Guarded by [queueLock]. */
    private var pendingSeq: Long = 0
    /** Id of the newest snapshot that has actually LANDED on disk. Guarded by [writeLock] —
     *  [writeNow] skips any write whose id is not newer, so a writer thread holding an older
     *  snapshot (taken before a [flush] grabbed a newer one) can never clobber the flush's
     *  write after [flush] returned. */
    @Volatile private var writtenSeq: Long = 0
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
                saveSeq++
                pendingSeq = saveSeq
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
            var snapSeq = 0L
            synchronized(queueLock) {
                snapshot = pendingJson
                snapSeq = pendingSeq
                if (snapshot != null) {
                    pendingJson = null
                    dirty = false
                }
            }
            if (snapshot != null) writeNow(snapshot, snapSeq)
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
                var snapSeq = 0L
                synchronized(queueLock) {
                    snapshot = pendingJson
                    snapSeq = pendingSeq
                    if (snapshot != null) {
                        pendingJson = null
                        dirty = false
                    }
                }
                if (snapshot != null) writeNow(snapshot, snapSeq)
            } catch (_: Throwable) {
                // interrupted / etc — clear and continue; a daemon writer must never die
            }
        }
    }

    /** Performs the actual file write, serialized on [writeLock] so [flush] + writer can't
     *  interleave. [seq] is the snapshot's monotonic id (see [saveSeq]) — a write whose id is
     *  not newer than [writtenSeq] is SKIPPED: the caller holding that snapshot is stale
     *  relative to one that already landed (the flush-after-writer-snapshot race), and
     *  writing it would silently regress the file. */
    private fun writeNow(json: String, seq: Long) {
        synchronized(writeLock) {
            if (seq <= writtenSeq) return
            // AtomicFiles.write stages <path>.tmp and atomically moves it into place (and
            // creates parent dirs itself) — a crash mid-write can no longer truncate the config.
            if (dev.iustitia.util.AtomicFiles.write(path, json)) writtenSeq = seq
        }
    }

    fun reload() {
        load()
        Iustitia.onConfigReloaded()
    }

    /** Serialize a config to pretty JSON — reuses [toJson] so a custom preset round-trips
     *  byte-for-byte with the live config. Public for [PresetManager] preset export. */
    fun configToJson(c: IustitiaConfig): String = try { gson.toJson(toJson(c)) } catch (_: Throwable) { gson.toJson(toJson(IustitiaConfig())) }

    /** Serialize the PRESET-CONTENT view of a config to a fresh JSON object: the FULL schema
     *  (every field the main config serializer writes) minus [PRESET_EXCLUDED_KEYS]. `configVersion`
     *  is deliberately KEPT — it is the calibration-migration stamp [fromJsonInto] reads (see its
     *  doc above [PRESET_EXCLUDED_KEYS]). Public for [PresetManager]: the built-in templates and
     *  custom presets both flow through this, which is what makes preset content schema-derived
     *  (a new config field is preset content automatically, with no second hand-maintained list
     *  to drift). */
    fun presetContentObj(c: IustitiaConfig): JsonObject {
        val obj = try { toJson(c) } catch (_: Throwable) { toJson(IustitiaConfig()) }
        for (key in PRESET_EXCLUDED_KEYS) obj.remove(key)
        return obj
    }

    /** The string form of [presetContentObj] — what a custom preset FILE holds. Writes preset
     *  content only (no muted players/checks, no wizard gate, no persistence preference, no
     *  playclip-mode choice), so a preset file is portable and carries no per-user state. */
    fun presetContentJson(c: IustitiaConfig): String = try {
        gson.toJson(presetContentObj(c))
    } catch (_: Throwable) {
        gson.toJson(presetContentObj(IustitiaConfig()))
    }

    /** Config keys a preset apply must NEVER overwrite (the preset-content boundary — see
     *  [PresetManager]'s class doc). Everything else in the schema IS preset content. Kept here,
     *  next to the serializer, so adding a field to [toJson]/[fromJsonInto] needs no second
     *  decision: a new field is preset content unless it lands on this list.
     *
     *  `configVersion` is deliberately NOT on this list: it must stay in the preset JSON as the
     *  CALIBRATION-MIGRATION STAMP — [fromJsonInto] compares it against the live config's version
     *  to decide whether the preset's setbackVL/decay/threshold values are current (a preset saved
     *  before a recalibration round must not silently re-introduce stale calibration). It is still
     *  never WRITTEN into the target: [fromJsonInto] has no assignment for it, so the live
     *  config's version stays the code default regardless. */
    private val PRESET_EXCLUDED_KEYS = setOf(
        "mutedChecks",        // per-user session mutes
        "mutedPlayers",       // per-user session mutes
        "wizardCompleted",    // one-shot first-launch gate
        "persistenceEnabled", // environmental preference, not part of a detection/display profile
        "playclipMode",       // user-controlled generation selector (see IustitiaConfig.playclipMode)
    )

    /** Read the fields PRESENT in [obj] into the EXISTING [target] in place (never replacing the
     *  reference — other subsystems hold it). Absent keys keep the target's current values, and
     *  [PRESET_EXCLUDED_KEYS] are skipped entirely; `configVersion` is used as the calibration
     *  stamp (see above) but never assigned into [target]. This is [fromJson]'s field-reading
     *  logic restructured around an explicit [target] so [PresetManager] can apply a preset
     *  without round-tripping through a throwaway config: a field missing from an older preset
     *  file stays at its current value instead of resetting to the code default.
     *
     *  Returns true when the read ran to completion. A read that throws part-way (a mistyped
     *  value in a hand-edited file) PROPAGATES — [target] may then hold a PARTIAL apply (the
     *  fields read before the throw) and the caller picks the policy: the main-config path
     *  swallows the throw at [fromJson] (fail-open to defaults), while the preset path treats it
     *  as "preset not applied" — and reads into a throwaway scratch config first, so the throw
     *  never leaves the live config half-mutated. No catch here: that honest failure is the
     *  point. */
    fun configFromJsonInto(obj: JsonObject, target: IustitiaConfig): Boolean {
        for (key in PRESET_EXCLUDED_KEYS) obj.remove(key)
        fromJsonInto(obj, target)
        return true
    }

    private fun toJson(c: IustitiaConfig): JsonObject = JsonObject().apply {
        addProperty("enabled", c.enabled)
        addProperty("verbose", c.verbose)
        addProperty("configVersion", c.configVersion)
        addProperty("alertThrottleTicks", c.alertThrottleTicks)
        addProperty("joinGraceTicks", c.joinGraceTicks)
        addProperty("legitScaffoldStrictGates", c.legitScaffoldStrictGates)
        addProperty("sensitivitySubstrate", c.sensitivitySubstrate)
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
        addProperty("lagSuppressAlerts", c.lagSuppressAlerts)
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
        // Phase 2 instant-replay / clip (additive; fromJson reads them conditionally).
        addProperty("replayCapture", c.replayCapture)
        addProperty("replayHideLive", c.replayHideLive)
        addProperty("replayPlayerModels", c.replayPlayerModels)
        addProperty("replayRelocate", c.replayRelocate)
        addProperty("clipTerrain", c.clipTerrain)
        addProperty("clipChunkWorld", c.clipChunkWorld)
        addProperty("clipChunkRadius", c.clipChunkRadius)
        addProperty("clipChunkRenderDistance", c.clipChunkRenderDistance)
        addProperty("playclipMode", c.playclipMode.name)
        addProperty("replayKeybindSeconds", c.replayKeybindSeconds)
        addProperty("clipHealthIndicator", c.clipHealthIndicator)
        addProperty("clipTotemPopCounter", c.clipTotemPopCounter)
        addProperty("clipGhostEquipment", c.clipGhostEquipment)
        addProperty("clipEntities", c.clipEntities)
        addProperty("clipEntityCap", c.clipEntityCap)
        addProperty("clipRollingChunkCap", c.clipRollingChunkCap)
        addProperty("clipSegmentTeleportThreshold", c.clipSegmentTeleportThreshold)
        addProperty("chathistEnabled", c.chathistEnabled)
        addProperty("chathistCaptureUnknown", c.chathistCaptureUnknown)
        for ((key, cc) in c.checks()) add(key, checkToJson(cc))
        addProperty("blinkFreezeTicks", c.blinkFreezeTicks)
    }

    private fun checkToJson(cc: IustitiaConfig.CheckConfig): JsonObject = JsonObject().apply {
        addProperty("enabled", cc.enabled)
        addProperty("setbackVL", cc.setbackVL)
        addProperty("decay", cc.decay)
        addProperty("threshold", cc.threshold)
    }

    private fun fromJson(o: JsonObject): IustitiaConfig {
        val c = IustitiaConfig()
        // The partial-parse swallow lives HERE so the main config path stays fail-open (a mistyped
        // value mid-file yields a config with the fields read so far, defaults for the rest). The
        // preset path needs the throw instead, to report the failure honestly — see
        // [configFromJsonInto].
        try {
            fromJsonInto(o, c)
        } catch (_: Throwable) {
            // partial parse → keep what was read, defaults for the rest
        }
        return c
    }

    /** Read every field PRESENT in [o] into [c]. Throws on a malformed read (a mistyped value
     *  mid-body aborts the tail of the schema) — the caller picks the fail-open policy:
     *  [fromJson] swallows for the main config path, [configFromJsonInto] reports false for the
     *  preset path. Per-check slices and string lists guard themselves in [readCheck] /
     *  [readStringList]. */
    private fun fromJsonInto(o: JsonObject, c: IustitiaConfig) {
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
        // sensitivitySubstrate: additive — a pre-field config keeps the default (off = substrate
        // dropped, the dense-crowd FPS fix). No CONFIG_VERSION bump (not a check-calibration field).
        if (o.has("sensitivitySubstrate")) c.sensitivitySubstrate = o.get("sensitivitySubstrate").asBoolean
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
        // `audioNuclear` (the removed opt-in "nuclear" red cue) is deliberately NOT read: an
        // older config carrying the key keeps loading and the next save drops the key.
        if (o.has("lagSuppressAlerts")) c.lagSuppressAlerts = o.get("lagSuppressAlerts").asBoolean
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
        if (o.has("replayCapture")) c.replayCapture = o.get("replayCapture").asBoolean
        if (o.has("replayHideLive")) c.replayHideLive = o.get("replayHideLive").asBoolean
        if (o.has("replayPlayerModels")) c.replayPlayerModels = o.get("replayPlayerModels").asBoolean
        if (o.has("replayRelocate")) c.replayRelocate = o.get("replayRelocate").asBoolean
        if (o.has("clipTerrain")) c.clipTerrain = o.get("clipTerrain").asBoolean
        if (o.has("clipChunkWorld")) c.clipChunkWorld = o.get("clipChunkWorld").asBoolean
        if (o.has("clipChunkRadius")) c.clipChunkRadius = o.get("clipChunkRadius").asInt
        if (o.has("clipChunkRenderDistance")) c.clipChunkRenderDistance = o.get("clipChunkRenderDistance").asInt
        // playclipMode: additive enum. Bad/missing value keeps the LEGACY default (no CONFIG_VERSION bump).
        if (o.has("playclipMode")) {
            try { c.playclipMode = IustitiaConfig.PlayclipMode.valueOf(o.get("playclipMode").asString) } catch (_: Throwable) {}
        }
        if (o.has("replayKeybindSeconds")) c.replayKeybindSeconds = o.get("replayKeybindSeconds").asInt
        // clipHealthIndicator / clipTotemPopCounter / clipGhostEquipment: additive — a pre-field
        // config keeps the default (off/off/on). No CONFIG_VERSION bump (not check-calibration fields).
        if (o.has("clipHealthIndicator")) c.clipHealthIndicator = o.get("clipHealthIndicator").asBoolean
        if (o.has("clipTotemPopCounter")) c.clipTotemPopCounter = o.get("clipTotemPopCounter").asBoolean
        if (o.has("clipGhostEquipment")) c.clipGhostEquipment = o.get("clipGhostEquipment").asBoolean
        // clipEntities / clipEntityCap / clipRollingChunkCap / clipSegmentTeleportThreshold:
        // additive (SnapClip replay-engine port) — a pre-field config keeps the defaults
        // (true / 64 / 24000 / 64.0). No CONFIG_VERSION bump.
        if (o.has("clipEntities")) c.clipEntities = o.get("clipEntities").asBoolean
        if (o.has("clipEntityCap")) c.clipEntityCap = o.get("clipEntityCap").asInt
        if (o.has("clipRollingChunkCap")) c.clipRollingChunkCap = o.get("clipRollingChunkCap").asInt
        if (o.has("clipSegmentTeleportThreshold")) c.clipSegmentTeleportThreshold = o.get("clipSegmentTeleportThreshold").asDouble
        // chathistEnabled: additive — a pre-field config keeps the default (on). No CONFIG_VERSION bump.
        if (o.has("chathistEnabled")) c.chathistEnabled = o.get("chathistEnabled").asBoolean
        // chathistCaptureUnknown: additive — a pre-field config keeps the default (off). No CONFIG_VERSION bump.
        if (o.has("chathistCaptureUnknown")) c.chathistCaptureUnknown = o.get("chathistCaptureUnknown").asBoolean
        // blinkFreezeTicks: additive (Fly(Blink) 1v1 window) — a pre-field config keeps the
        // default (30). No CONFIG_VERSION bump.
        if (o.has("blinkFreezeTicks")) c.blinkFreezeTicks = o.get("blinkFreezeTicks").asInt
        for ((key, cc) in c.checks()) {
            if (o.has(key)) readCheck(o.getAsJsonObject(key), cc, resetCalibration)
        }
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
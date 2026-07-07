package dev.iustitia.config

import com.google.gson.JsonParser
import dev.iustitia.Iustitia
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Named configuration presets — five built-ins ([builtInNames]) plus user-created custom presets
 * persisted as JSON under `.iustitia/presets/<name>.json`. `/ius preset <name>` resolves a built-in
 * first, then a custom file, and **applies** it by copying every preset-content field into the live
 * [ConfigManager.config] **in place** (never replacing the reference — other subsystems hold it),
 * then [ConfigManager.save] + [Iustitia.onConfigReloaded]. Custom presets are full config snapshots
 * saved via [ConfigManager.configToJson] (the same serializer the main config uses), so they
 * round-trip the user's exact tuned values.
 *
 * ## What is and isn't preset content
 *
 * A preset captures detection calibration + display/UX + replay/sonar — everything that defines how
 * the mod behaves. It excludes per-user / runtime state: `configVersion` (always the code default —
 * never let a preset downgrade calibration migration), `mutedChecks`, `mutedPlayers`,
 * `wizardCompleted`, `persistenceEnabled` (a moderator's persistence preference is environmental,
 * not part of a detection profile). [copyPresetContent] is the single place this boundary is enforced.
 *
 * ## Built-in setbackVL scaling
 *
 * Strict scales setbackVL ×0.5 (alert sooner); Lenient ×2.0 (only sustained blatant alerts). Scaling
 * is from the **default** values (a fresh [IustitiaConfig]), not the user's current tuning, so a
 * preset always means the same thing regardless of prior tweaks. Custom presets capture exact values.
 *
 * Fail-open throughout: a bad custom file / a copy error degrades to "preset not applied" + a false
 * return, never a crash. No packets, no world edits — pure config-field mutation + a JSON write.
 */
object PresetManager {

    val builtInNames: List<String> = listOf("strict", "standard", "lenient", "debug", "moderation")

    fun isBuiltIn(name: String): Boolean = name.lowercase() in builtInNames

    /**
     * Apply the named preset: resolve built-in → custom, copy preset-content fields into the live
     * config in place, save, reload. Returns false when the name is neither built-in nor a custom
     * file (or apply threw). Fail-open.
     */
    fun apply(name: String): Boolean = try {
        val template = builtIn(name) ?: loadCustom(name) ?: return false
        copyPresetContent(ConfigManager.config, template)
        ConfigManager.save()
        Iustitia.onConfigReloaded()
        true
    } catch (_: Throwable) {
        false
    }

    /** Save the CURRENT live config as a custom preset `<name>.json`. Refuses built-in names
     *  (returns false). Fail-open: a disk error returns false, never throws. */
    fun saveCustom(name: String): Boolean {
        if (isBuiltIn(name)) return false
        return try {
            val p = customPath(name) ?: return false
            Files.createDirectories(p.parent)
            Files.writeString(p, ConfigManager.configToJson(ConfigManager.config))
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Delete a custom preset. Refuses built-in names (returns false). Returns true only if a file
     *  was actually removed. Fail-open. */
    fun deleteCustom(name: String): Boolean {
        if (isBuiltIn(name)) return false
        return try {
            val p = customPath(name) ?: return false
            Files.deleteIfExists(p)
        } catch (_: Throwable) {
            false
        }
    }

    /** Custom preset names (filename stems) in the presets dir, sorted. Empty if the dir doesn't
     *  exist or reads fail. Fail-open. */
    fun listCustom(): List<String> = try {
        val dir = presetsDir()
        if (!Files.exists(dir)) emptyList()
        else Files.list(dir).use { stream ->
            stream.filter { it.toString().endsWith(".json") }
                .map { it.fileName.toString().removeSuffix(".json") }
                .sorted()
                .toList()
        }
    } catch (_: Throwable) {
        emptyList()
    }

    /** Built-ins first, then customs (customs de-duped against built-in names just in case). */
    fun listAll(): List<String> = builtInNames + listCustom().filter { !isBuiltIn(it) }

    // ---- built-in templates ----

    /** A fresh [IustitiaConfig] with the per-preset overrides applied (or null for an unknown name).
     *  SetbackVL scaling + the Lenient subtle-check disable are applied here so the returned config
     *  is a fully-realized snapshot that [apply] can copy 1:1. */
    fun builtIn(name: String): IustitiaConfig? {
        val base = IustitiaConfig()
        return when (name.lowercase()) {
            "strict" -> base.apply {
                allVisuals(false); verbose = true; alertLevel = 2; alertBatching = false; compactMode = false
                sonarAlerts = false; replayCapture = true; nametagPrefixes = true; nametagGreenEnabled = true
            }.also { scaleSetbackVL(it, 0.5) }
            "standard" -> base.apply {
                allVisuals(false); alertLevel = 1; alertBatching = true; compactMode = false; verbose = false
                sonarAlerts = false; replayCapture = true; nametagPrefixes = true; nametagGreenEnabled = true
            }
            "lenient" -> base.apply {
                allVisuals(false); alertLevel = 1; alertBatching = true; compactMode = false; verbose = false
                sonarAlerts = false; replayCapture = true; nametagPrefixes = true; nametagGreenEnabled = true
                disableSubtleChecks()
            }.also { scaleSetbackVL(it, 2.0) }
            "debug" -> base.apply {
                allVisuals(true); verbose = true; alertLevel = 2; alertBatching = true; compactMode = false
                sonarAlerts = true; replayCapture = true; nametagPrefixes = true; nametagGreenEnabled = true
                audioCues = true; audioNuclear = true; transcriptPanel = true
            }
            "moderation" -> base.apply {
                allVisuals(false); hoverTooltip = true; confidenceHud = false
                verbose = false; compactMode = true; alertLevel = 1; alertBatching = true
                sonarAlerts = false; replayCapture = true; nametagPrefixes = true; nametagGreenEnabled = true
            }
            else -> null
        }
    }

    // ---- internals ----

    /** Copy every preset-content field from [src] into [target] IN PLACE (mutate the live config
     *  object — never replace it). Excludes `configVersion`/`mutedChecks`/`mutedPlayers`/
     *  `wizardCompleted`/`persistenceEnabled`. Each check slice's 4 fields copied in place too. */
    private fun copyPresetContent(target: IustitiaConfig, src: IustitiaConfig) {
        target.enabled = src.enabled
        target.verbose = src.verbose
        target.alertThrottleTicks = src.alertThrottleTicks
        target.joinGraceTicks = src.joinGraceTicks
        target.legitScaffoldStrictGates = src.legitScaffoldStrictGates
        target.alertsEnabled = src.alertsEnabled
        target.nametagPrefixes = src.nametagPrefixes
        target.nametagGreenEnabled = src.nametagGreenEnabled
        target.alertLevel = src.alertLevel
        target.alertBatching = src.alertBatching
        target.alertBatchWindowTicks = src.alertBatchWindowTicks
        target.audioCues = src.audioCues
        target.audioVolume = src.audioVolume
        target.audioNuclear = src.audioNuclear
        target.lagSuppressAlerts = src.lagSuppressAlerts
        target.nametagBurstPulse = src.nametagBurstPulse
        target.compactMode = src.compactMode
        target.evidenceWindowTicks = src.evidenceWindowTicks
        target.transcriptPanel = src.transcriptPanel
        target.lagHudIcon = src.lagHudIcon
        target.confidenceHud = src.confidenceHud
        target.targetHighlight = src.targetHighlight
        target.ghostTrail = src.ghostTrail
        target.watchFollowCam = src.watchFollowCam
        target.burstSparks = src.burstSparks
        target.hoverTooltip = src.hoverTooltip
        target.tabListBadge = src.tabListBadge
        target.replayCapture = src.replayCapture
        target.replayHideLive = src.replayHideLive
        target.replayPlayerModels = src.replayPlayerModels
        target.replayRelocate = src.replayRelocate
        target.clipTerrain = src.clipTerrain
        target.clipChunkWorld = src.clipChunkWorld
        target.clipChunkRadius = src.clipChunkRadius
        target.clipChunkRenderDistance = src.clipChunkRenderDistance
        target.sonarAlerts = src.sonarAlerts
        target.sonarVolume = src.sonarVolume
        for ((id, tcc) in target.checks()) {
            val scc = src.slice(id)
            tcc.enabled = scc.enabled
            tcc.setbackVL = scc.setbackVL
            tcc.decay = scc.decay
            tcc.threshold = scc.threshold
        }
    }

    /** Load a custom preset JSON → [IustitiaConfig], or null if no file / parse fails. Fail-open. */
    private fun loadCustom(name: String): IustitiaConfig? = try {
        val p = customPath(name) ?: return null
        if (!Files.exists(p)) return null
        val obj = JsonParser.parseString(Files.readString(p)).asJsonObject
        ConfigManager.configFromJson(obj)
    } catch (_: Throwable) {
        null
    }

    private fun customPath(name: String): Path? {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9_.-]"), "_")
        if (safe.isEmpty()) return null
        return presetsDir().resolve("$safe.json")
    }

    private fun presetsDir(): Path =
        FabricLoader.getInstance().gameDir.resolve(".iustitia/presets")

    /** Scale every check's setbackVL by [scale], reading the DEFAULT values from a fresh
     *  [IustitiaConfig] so the scale is independent of the user's current tuning. */
    private fun scaleSetbackVL(c: IustitiaConfig, scale: Double) {
        val defaults = IustitiaConfig()
        for ((id, cc) in c.checks()) {
            cc.setbackVL = defaults.slice(id).setbackVL * scale
        }
    }

    /** Disable the subtle / corroboration-tier checks for the Lenient preset. */
    private fun IustitiaConfig.disableSubtleChecks() {
        listOf(
            "hitsWithoutSwing", "hitFlick", "triggerbot", "packetGap", "rotationSnapBack",
            "wTap", "keepSprint", "jumpOnHurt", "aimWrap", "scaffoldRotation",
        ).forEach { slice(it).enabled = false }
    }

    /** Flip all overlay/HUD visual toggles. `on=false` = "no visuals" (strict/standard/lenient/
     *  moderation baseline); `on=true` = "every visual" (debug). */
    private fun IustitiaConfig.allVisuals(on: Boolean) {
        targetHighlight = on
        ghostTrail = on
        watchFollowCam = on
        burstSparks = on
        hoverTooltip = on
        tabListBadge = on
        nametagBurstPulse = on
        lagHudIcon = on
        confidenceHud = on
        transcriptPanel = on
    }
}
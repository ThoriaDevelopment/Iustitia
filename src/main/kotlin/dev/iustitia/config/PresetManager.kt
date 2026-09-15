package dev.iustitia.config

import com.google.gson.JsonParser
import dev.iustitia.Iustitia
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Named configuration presets — one built-in ([builtInNames]) plus user-created custom presets
 * persisted as JSON under the presets dir (see [presetsDir]). `/ius preset <name>` resolves a
 * built-in first, then a custom file, and **applies** it onto the live [ConfigManager.config] IN
 * PLACE (never replacing the reference — other subsystems hold it), then [ConfigManager.save] +
 * [Iustitia.onConfigReloaded]. Custom presets are snapshots of the current config's PRESET CONTENT
 * (saved via [ConfigManager.presetContentJson], the main serializer minus the per-user keys), so
 * they round-trip the user's exact tuned values and carry no per-user state.
 *
 * ## What is and isn't preset content (schema-derived)
 *
 * A preset captures detection calibration + display/UX + replay/chathist — everything that
 * defines how the mod behaves. It excludes per-user / runtime state; the exclusion list is
 * `ConfigManager.PRESET_EXCLUDED_KEYS` (`mutedChecks`, `mutedPlayers`, `wizardCompleted`,
 * `persistenceEnabled`, `playclipMode` — `configVersion` is deliberately kept in the file as the
 * calibration-migration stamp). The apply path is **schema-derived**:
 * [ConfigManager.presetContentObj] serializes the template's full field set and
 * [ConfigManager.configFromJsonInto] reads it into the live config, so a NEW config field is
 * preset content automatically — there is no hand-maintained copy list to drift behind the schema
 * (the previous one had fallen 13 fields behind, silently excluding the replay/clip/chathist
 * fields and the detection-affecting `sensitivitySubstrate` from every apply).
 *
 * Fail-open throughout: a bad custom file / an apply error degrades to "preset not applied" + a
 * false return, never a crash. No packets, no world edits — pure config-field mutation + a JSON
 * write.
 */
object PresetManager {

    val builtInNames: List<String> = listOf("standard")

    fun isBuiltIn(name: String): Boolean = name.lowercase() in builtInNames

    /**
     * Apply the named preset: resolve built-in → custom, read the template's schema-derived preset
     * content into the live config in place, save, reload. Returns false when the name is neither
     * built-in nor a custom file, when the preset file is unreadable, or when its read threw
     * part-way (a mistyped value in a hand-edited file) — in that case the config is NOT saved, so
     * no half-applied preset is persisted, and the LIVE config object is untouched too: the read
     * lands on a throwaway scratch config first, and only a completed read is promoted onto the
     * live config in place (the promotion re-read cannot throw — the scratch was just serialized
     * by the same serializer). Fail-open.
     */
    fun apply(name: String): Boolean = try {
        val template = builtIn(name) ?: loadCustom(name) ?: return false
        val scratch = IustitiaConfig()
        ConfigManager.configFromJsonInto(ConfigManager.presetContentObj(template), scratch)
        ConfigManager.configFromJsonInto(ConfigManager.presetContentObj(scratch), ConfigManager.config)
        ConfigManager.save()
        Iustitia.onConfigReloaded()
        true
    } catch (_: Throwable) {
        false
    }

    /** Save the CURRENT live config as a custom preset `<name>.json`. Refuses built-in names
     *  (returns false — the name is sanitized BEFORE the guard, so `"standard "` with a trailing
     *  space can't shadow a built-in with a dead file). Writes PRESET CONTENT ONLY
     *  ([ConfigManager.presetContentJson] — no muted players/checks, no wizard gate, no
     *  persistence preference, no playclip-mode choice; the `configVersion` stamp is kept).
     *  Fail-open: a disk error returns false, never throws. */
    fun saveCustom(name: String): Boolean {
        val safe = safeName(name) ?: return false
        if (isBuiltIn(safe)) return false
        return try {
            val p = customPath(safe) ?: return false
            // Atomic (staged temp + move): a crash mid-write can't truncate a preset file.
            dev.iustitia.util.AtomicFiles.write(p, ConfigManager.presetContentJson(ConfigManager.config))
        } catch (_: Throwable) {
            false
        }
    }

    /** Delete a custom preset. Refuses built-in names (returns false — the name is sanitized
     *  BEFORE the guard). Removes the file from BOTH locations — roaming and the legacy game-dir
     *  dir — because a same-named preset can exist in each (one save per build generation) and
     *  [loadCustom] falls through to the legacy copy: deleting only the roaming file would report
     *  a delete whose preset still loads and still lists. Returns true only if a file was
     *  actually removed. Fail-open. */
    fun deleteCustom(name: String): Boolean {
        val safe = safeName(name) ?: return false
        if (isBuiltIn(safe)) return false
        return try {
            val removedRoaming = Files.deleteIfExists(presetsDir().resolve("$safe.json"))
            val removedLegacy = Files.deleteIfExists(legacyGameDirPresets().resolve("$safe.json"))
            removedRoaming || removedLegacy
        } catch (_: Throwable) {
            false
        }
    }

    /** Custom preset names in the presets dir, sorted, de-duplicated. Reads the roaming dir
     *  first and falls back to (plus merges) the legacy game-dir location so presets saved by
     *  older builds stay listed and loadable. Empty list only when neither exists / reads fail.
     *  Fail-open. */
    fun listCustom(): List<String> = try {
        val dir = presetsDir()
        val legacy = legacyGameDirPresets()
        val current = if (!Files.isDirectory(dir)) emptyList() else Files.list(dir).use { stream ->
            stream.filter { it.toString().endsWith(".json") }
                .map { it.fileName.toString().removeSuffix(".json") }
                .sorted()
                .toList()
        }
        val old = if (!Files.isDirectory(legacy) || legacy == dir) emptyList() else Files.list(legacy).use { stream ->
            stream.filter { it.toString().endsWith(".json") }
                .map { it.fileName.toString().removeSuffix(".json") }
                .sorted()
                .toList()
        }
        // A name present in BOTH locations resolves to the roaming file (loadCustom checks it
        // first), so listing de-dupes toward the live one. Sorted for a stable listing.
        (current + old.filter { it !in current }).sorted()
    } catch (_: Throwable) {
        emptyList()
    }

    /** Built-ins first, then customs (customs de-duped against built-in names just in case). */
    fun listAll(): List<String> = builtInNames + listCustom().filter { !isBuiltIn(it) }

    // ---- built-in templates ----

    /** A fresh [IustitiaConfig] with the per-preset overrides applied (or null for an unknown name).
     *  The returned config is a fully-realized snapshot that [apply] can copy 1:1. */
    fun builtIn(name: String): IustitiaConfig? {
        val base = IustitiaConfig()
        return when (name.lowercase()) {
            "standard" -> base.apply {
                targetHighlight = false; watchFollowCam = false; burstSparks = false
                hoverTooltip = false; tabListBadge = false; nametagBurstPulse = false
                lagHudIcon = true   // the one light visual: explains WHY alerts soften during lag bursts
                confidenceHud = false; transcriptPanel = false
                alertLevel = 1; alertBatching = true; compactMode = false; verbose = false
                replayCapture = true; nametagPrefixes = true; nametagGreenEnabled = true
            }
            else -> null
        }
    }

    // ---- internals ----

    /** Load a custom preset JSON → [IustitiaConfig]. Reads the roaming presets dir first, then
     *  the legacy game-dir location, so a preset saved by an older build still applies —
     *  [listCustom] lists both locations, so every listed name must also be loadable.
     *
     *  The read goes through the HONEST path ([ConfigManager.configFromJsonInto], which throws on
     *  a mistyped value instead of swallowing it): a corrupt or hand-mangled preset file surfaces
     *  as a load failure ("preset not applied") rather than flowing a HALF-READ config into the
     *  live config as a successful apply — which is what the old fail-open [ConfigManager.fromJson]
     *  round-trip did. Fail-open: unreadable / corrupt → null, never a crash. */
    private fun loadCustom(name: String): IustitiaConfig? = try {
        val safe = safeName(name) ?: return null
        val roaming = presetsDir().resolve("$safe.json")
        val file = if (Files.exists(roaming)) roaming else legacyGameDirPresets().resolve("$safe.json")
        if (!Files.exists(file)) return null
        val obj = JsonParser.parseString(Files.readString(file)).asJsonObject
        val scratch = IustitiaConfig()
        ConfigManager.configFromJsonInto(obj, scratch)
        scratch
    } catch (_: Throwable) {
        null
    }

    /** Sanitize a preset name to `[A-Za-z0-9_.-]` (no traversal). Null when the sanitized name
     *  is empty. */
    private fun safeName(name: String): String? {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return safe.ifEmpty { null }
    }

    /** Resolve a preset's file path: `<roaming data dir>/presets/<safe>.json`. [safe] is already
     *  [safeName]-sanitized (re-sanitizing is idempotent). Null when it is empty. */
    private fun customPath(name: String): Path? {
        val safe = safeName(name) ?: return null
        return presetsDir().resolve("$safe.json")
    }

    /** Presets dir, matching the rest of Iustitia's data layout: `%APPDATA%/.iustitia/presets` on
     *  Windows (roaming, same tree as clips/exemptions/notes), `<gameDir>/.iustitia/presets`
     *  elsewhere. A legacy game-dir preset directory is still READ when the roaming one has no
     *  presets, so presets saved by older builds keep loading after the move. */
    private fun presetsDir(): Path {
        val base = roamingBaseDir()
        return base.resolve("presets")
    }

    /** Legacy write location (pre-rework builds): the game dir. Checked for backwards-compatible
     *  LOADS only — new saves always go to [presetsDir]. */
    private fun legacyGameDirPresets(): Path =
        FabricLoader.getInstance().gameDir.resolve(".iustitia/presets")

    /** `%APPDATA%` on Windows (the roaming store clips/exemptions/notes already use), else the
     *  game dir. Same resolution as [dev.iustitia.persistence.PersistenceManager] / ClipStore. */
    private fun roamingBaseDir(): Path {
        val appdata = try { System.getenv("APPDATA") } catch (_: Throwable) { null }
        return if (!appdata.isNullOrBlank()) java.nio.file.Path.of(appdata).resolve(".iustitia")
        else FabricLoader.getInstance().gameDir.resolve(".iustitia")
    }
}
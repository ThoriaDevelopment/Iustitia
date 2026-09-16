package dev.iustitia.config

import com.google.gson.JsonParser
import dev.iustitia.Iustitia
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Named configuration presets — five built-ins ([builtIns]) plus user-created custom presets
 * persisted as JSON under the presets dir (see [presetsDir]). `/ius preset <name>` resolves a
 * built-in first, then a custom file, and **applies** it onto the live [ConfigManager.config] IN
 * PLACE (never replacing the reference — other subsystems hold it), then [ConfigManager.save] +
 * [Iustitia.onConfigReloaded]. Custom presets are snapshots of the current config's PRESET CONTENT
 * (saved via [ConfigManager.presetContentJson], the main serializer minus the per-user keys), so
 * they round-trip the user's exact tuned values and carry no per-user state.
 *
 * ## The five built-ins
 *
 * They trade what gets caught against how much noise and how many false positives the reader has to
 * wade through. Only [IustitiaConfig.CheckConfig.setbackVL] moves between them, through
 * [scaleSetback]: `decay` and `threshold` keep their stock values everywhere, so a scaled profile
 * is the same detector with a different trip point rather than a different detector. Nothing here
 * recalibrates a default, so no `CONFIG_VERSION` bump is involved.
 *
 *  - `standard` is the everyday profile and the recommended one: stock sensitivity, the on-world
 *    and overlay extras off, the lag indicator on, and the server-normalized checks on
 *    [standardOffChecks] disabled.
 *  - `lenient` doubles setbackVL and takes the quiet alert tier, so a chat line needs roughly six
 *    times the stock deviation. It catches blatant combat modules and lets ghost cheats pass.
 *  - `strict` turns every check on (the seven included) and halves setbackVL, for competitive
 *    players who read their own alerts. It false-positives more, which is the trade it makes.
 *  - `moderation` scales setbackVL x0.75, reports every severity band one line per flag, plays audio
 *    cues, and shortens the join grace to 100 ticks and the throttle to 20, with the sensitivity
 *    substrate on. Staff review rather than everyday play.
 *  - `debug` sets every boolean in the config true, turns every check on, halves setbackVL, and
 *    zeroes both suppression timers. Diagnostic only, and the setup wizard does not offer it.
 *
 * `enabled` is preset content, so an apply rewrites the flag for all 36 checks: the profiles that
 * ship the seven off turn them off, and `strict`/`debug` turn every check back on, including one
 * the user had switched off by hand. User mutes live in `mutedChecks`, which is excluded.
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

    /**
     * A built-in preset's identity and the words used to describe it: the [name] `/ius preset <name>`
     * resolves, the [label] a wizard button or a listing line shows, whether it is the recommended
     * default, whether it is a diagnostic profile the setup wizard leaves out, and the [blurb] lines
     * that say what the profile costs.
     *
     * This list is the single source for the built-ins ([builtInNames] is derived from it), so the
     * wizard, the `/ius help` row, the preset listing, the apply-failure message and the docs cannot
     * disagree about which presets exist or what they do. [label] is deliberately the name in display
     * case: someone who picks "Strict" in the wizard then types `/ius preset strict`, and nothing has
     * to be translated in their head. The recommended and diagnostic markers are separate flags
     * rather than baked into the label, because each surface renders them its own way (the wizard
     * appends a marker, the listing colors one).
     *
     * Each [blurb] line is kept under ~55 characters so it fits a chat line and a wizard button
     * without wrapping. The wizard still wraps as a safety net, and drops lines on a short window.
     */
    data class BuiltIn(
        val name: String,
        val label: String,
        val recommended: Boolean = false,
        val diagnostic: Boolean = false,
        val blurb: List<String>,
    )

    /** The built-ins in listing order. Exactly one is [BuiltIn.recommended] (`standard`) and exactly
     *  one is [BuiltIn.diagnostic] (`debug`), which is what keeps the wizard at four buttons; both
     *  facts are asserted by `PresetBuiltInsTest`. */
    val builtIns: List<BuiltIn> = listOf(
        BuiltIn(
            "standard", "Standard", recommended = true,
            blurb = listOf(
                "Everyday play.",
                "Stock sensitivity, seven minigame checks off.",
            ),
        ),
        BuiltIn(
            "lenient", "Lenient",
            blurb = listOf(
                "Blatant combat cheats only.",
                "Needs about 6x the deviation, so most cheats pass.",
            ),
        ),
        BuiltIn(
            "strict", "Strict",
            blurb = listOf(
                "Every check on, twice as sensitive.",
                "Expect more false positives.",
            ),
        ),
        BuiltIn(
            "moderation", "Moderation",
            blurb = listOf(
                "Staff review.",
                "Every flag, one line each. Audio on, history saved.",
            ),
        ),
        BuiltIn(
            "debug", "Debug", diagnostic = true,
            blurb = listOf(
                "Everything on, every check, no timers.",
                "Diagnostic; not offered by the wizard.",
            ),
        ),
    )

    /** The built-in names in listing order, derived from [builtIns] so the two can never disagree. */
    val builtInNames: List<String> get() = builtIns.map { it.name }

    /** Check ids the built-in `standard` preset ships DISABLED.
     *
     *  Every one of these reads a behaviour that a minigame server is free to implement itself, and
     *  the client has no way to tell a server feature from a cheat: a dash or jump pad is a speed
     *  envelope violation, a lobby or double-jump is flight, a fall-damage-free arena is exactly the
     *  no-fall-damage signature, a warp or teleport pad is a position discontinuity, and a
     *  right-click ability deals damage with no swing packet behind it. On the server where that is
     *  normal the check fires on honest players, and the moderator reading the alert has no evidence
     *  to weigh it against, so the mod looks broken rather than the player looking cheaty. Standard
     *  is the everyday-play profile, so it leaves those seven out and keeps the checks whose signal
     *  a server feature cannot fake.
     *
     *  This is a detection-scope choice, not a statement that the checks are wrong. They stay
     *  implemented, they stay in the registry, they stay switchable in `/ius config`, and every one
     *  of them has a live-test gate. A competitive or moderation profile that wants the full set
     *  should turn them back on rather than assume this list is empty. Note that `enabled` is preset
     *  content, so applying this preset writes the flag for all 36 checks: these seven go off and
     *  every other check comes back on, including one you had switched off by hand.
     *
     *  Two of the seven (`speedEnvelope`, `longJump`) are also on the suite's declared detector-gap
     *  list, so they do not reach their setback at the default tuning in the first place; disabling
     *  them costs nothing today and changes the gap from "tuned too weak to alert" to "off in the
     *  everyday profile". See docs/automated-live-testing.md. */
    val standardOffChecks: Set<String> = linkedSetOf(
        "hitsWithoutSwing", "speedEnvelope", "flyEnvelope", "noFallDamage",
        "phaseClip", "longJump", "teleport",
    )

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

    /** The everyday profile every other built-in starts from: the shipped defaults with the
     *  on-world and overlay extras off, the lag indicator left on, `alertLevel = 1`, batching on,
     *  green nametag ticks on, and the seven checks on [standardOffChecks] disabled.
     *
     *  Every [builtIn] branch calls this FRESH rather than sharing one instance: an apply mutates
     *  the config it gets back, so a shared object would let one apply's deltas show up in the
     *  next one's template. `enabled` is preset content, so this writes the flag for all 36 checks.
     */
    private fun everyday(): IustitiaConfig = IustitiaConfig().apply {
        targetHighlight = false; watchFollowCam = false; burstSparks = false
        hoverTooltip = false; tabListBadge = false; nametagBurstPulse = false
        lagHudIcon = true   // the one light visual: explains WHY alerts soften during lag bursts
        confidenceHud = false; transcriptPanel = false
        alertLevel = 1; alertBatching = true; compactMode = false; verbose = false
        replayCapture = true; nametagPrefixes = true; nametagGreenEnabled = true
        // Server-normalized movement / damage checks off; the list and its reasoning are on
        // [standardOffChecks]. One source for the set so the preset, the live-test gate and the
        // JVM test can never disagree about which checks the profile turns off.
        for (id in standardOffChecks) slice(id).enabled = false
    }

    /** A fresh [IustitiaConfig] with the per-preset overrides applied (or null for an unknown name).
     *  The returned config is a fully-realized snapshot that [apply] can copy 1:1. Each branch builds
     *  its own object, so nothing is shared between calls. What each profile is for, and what it
     *  costs, is in the class KDoc. */
    fun builtIn(name: String): IustitiaConfig? = when (name.lowercase()) {
        "standard" -> everyday()

        // Least noise. A chat line needs roughly six times the stock deviation: setbackVL doubles
        // and alertLevel 0 drops everything below the red band, so only a blatant combat module
        // reaches a reader and a ghost cheat is meant to pass. The lag HUD indicator goes off with
        // the other extras; the nametag burst pulse stays as the one on-screen cue.
        "lenient" -> everyday().apply {
            scaleSetback(2.0)
            alertLevel = 0
            lagHudIcon = false
            nametagBurstPulse = true
        }

        // The opposite trade: every check on, half the deviation, so a flag lands at about the
        // stock setback value. Compact one-liners for a competitive player who reads their own
        // alerts and accepts the extra false positives.
        "strict" -> everyday().apply {
            for ((_, cc) in checks()) cc.enabled = true
            scaleSetback(0.5)
            compactMode = true
        }

        // Staff review: three quarters of the deviation, every band reported, and one line per flag
        // (batching off) instead of a collapsed summary. Audio cues and the sensitivity substrate
        // are on so a live read matches what a written report would show. The join grace and the
        // throttle are short because a staff session is watched rather than lived in.
        "moderation" -> everyday().apply {
            scaleSetback(0.75)
            alertLevel = 2
            alertBatching = false
            alertThrottleTicks = 20
            joinGraceTicks = 100
            audioCues = true
            audioVolume = 0.8
            compactMode = true
            transcriptPanel = true
            sensitivitySubstrate = true
        }

        // Diagnostic: every boolean in the config true, every check on, the same detection tuning as
        // strict, and neither suppression timer. `alertBatching` stays true because it IS a boolean;
        // `verbose` carries the per-flag detail that batching would collapse in chat. The setup
        // wizard does not offer this one; reach it with `/ius preset debug`.
        "debug" -> IustitiaConfig().apply {
            enabled = true; verbose = true; legitScaffoldStrictGates = true; sensitivitySubstrate = true
            alertsEnabled = true; nametagPrefixes = true; nametagGreenEnabled = true
            alertBatching = true; audioCues = true; lagSuppressAlerts = true; nametagBurstPulse = true
            compactMode = true; transcriptPanel = true; lagHudIcon = true; confidenceHud = true
            targetHighlight = true; watchFollowCam = true; burstSparks = true; hoverTooltip = true
            tabListBadge = true; replayCapture = true; replayHideLive = true; replayPlayerModels = true
            replayRelocate = true; clipTerrain = true; clipChunkWorld = true
            clipHealthIndicator = true; clipTotemPopCounter = true; clipGhostEquipment = true
            clipEntities = true; chathistEnabled = true; chathistCaptureUnknown = true
            alertLevel = 2
            joinGraceTicks = 0
            alertThrottleTicks = 0
            for ((_, cc) in checks()) cc.enabled = true
            scaleSetback(0.5)
        }

        else -> null
    }

    /** Scale every check's [IustitiaConfig.CheckConfig.setbackVL] by [factor], leaving `decay` and
     *  `threshold` wherever they are. One place to express "the same detector with a different trip
     *  point", so a profile's sensitivity is a single number instead of 36 hand-edited values that
     *  can drift apart. */
    private fun IustitiaConfig.scaleSetback(factor: Double) {
        for ((_, cc) in checks()) cc.setbackVL *= factor
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
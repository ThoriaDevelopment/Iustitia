package dev.iustitia.config

import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionDescription
import dev.isxander.yacl3.api.OptionGroup
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder
import dev.isxander.yacl3.api.controller.EnumControllerBuilder
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/**
 * Builds the YACL config screen for [ConfigManager.config]. Bindings read/write the
 * live config object in place (setters mutate fields directly), and the save function
 * persists via [ConfigManager.save] — so toggles/thresholds edited here take effect
 * immediately for the checks (which resolve their slice live by id).
 */
object YaclScreenBuilder {

    fun build(parent: Screen?): Screen {
        val cfg = ConfigManager.config
        val category = ConfigCategory.createBuilder()
            .name(Text.literal("Iustitia"))
            .tooltip(Text.literal("Client-sided anticheat — local-only chat alerts."))
            .group(
                OptionGroup.createBuilder()
                    .name(Text.literal("General"))
                    .option(bool("Enabled", "Master switch for all alerts.", { cfg.enabled }) { cfg.enabled = it })
                    .option(bool("Verbose", "Extra diagnostic logging (reserved).", { cfg.verbose }) { cfg.verbose = it })
                    .option(int("Alert throttle (ticks)", "Min ticks between repeats of the same alert.", { cfg.alertThrottleTicks }, 0, 600) { cfg.alertThrottleTicks = it })
                    .option(int("Join grace (ticks)", "Ticks after a player joins before alerts (30s = 600).", { cfg.joinGraceTicks }, 0, 2400) { cfg.joinGraceTicks = it })
                    .option(bool("LegitScaffold strict gates", "Extra motion/cadence gates for the LegitScaffold path (off = baseline Rain path). Flip on only to A/B-test a stationary-builder FP.", { cfg.legitScaffoldStrictGates }) { cfg.legitScaffoldStrictGates = it })
                    .option(bool("Sensitivity substrate (GCD sub-flags)", "Converges each player's mouse sensitivity to drive the KillAura + RotationTracking GCD (constant-rotation / too-clean pitch-step) sub-flags. OFF by default — the convergence is the dense-crowd FPS hog (profiled ~32% of render time on high-turnover servers); with it off both GCD sub-flags self-disable (fail-open) and every other heuristic keeps running. Flip on to re-enable them, accepting the FPS cost.", { cfg.sensitivitySubstrate }) { cfg.sensitivitySubstrate = it })
                    .option(bool("Chat alerts", "Master switch for chat alert lines. Off = silence all alerts (detection + nametag tier keep running). Same as bare /ius alerts.", { cfg.alertsEnabled }) { cfg.alertsEnabled = it })
                    .option(bool("Nametag prefixes", "Show a cheat-tier prefix (green [+] / yellow [!] / red [X]) above other players' names.", { cfg.nametagPrefixes }) { cfg.nametagPrefixes = it })
                    .option(bool("Show green tick on clean players", "Show the green [+] on low-flag players. Off = only mark yellow/red (less visual noise). Mute a check/player via /ius alerts.", { cfg.nametagGreenEnabled }) { cfg.nametagGreenEnabled = it })
                    .build()
            )
            .group(
                OptionGroup.createBuilder()
                    .name(Text.literal("Display & Alerts"))
                    .option(bool("Nametag flag-burst pulse", "Color-pulse the tier prefix for ~3s after a fresh yellow/red flag.", { cfg.nametagBurstPulse }) { cfg.nametagBurstPulse = it })
                    .option(int("Alert level", "0 = quiet (red-severity only) · 1 = normal (orange + red) · 2 = verbose (all). Display-only.", { cfg.alertLevel }, 0, 2) { cfg.alertLevel = it })
                    .option(bool("Smart alert batching", "Collapse rapid same-player flags into one line after a quiet window.", { cfg.alertBatching }) { cfg.alertBatching = it })
                    .option(int("Batch window (ticks)", "Quiet ticks before a batch flushes (100 = 5s).", { cfg.alertBatchWindowTicks }, 0, 600) { cfg.alertBatchWindowTicks = it })
                    .option(bool("Audio cues", "Play a note-block cue per flushed alert batch (yellow vs red).", { cfg.audioCues }) { cfg.audioCues = it })
                    .option(double("Audio volume", "Cue volume (0..1).", { cfg.audioVolume }, 0.0, 1.0) { cfg.audioVolume = it })
                    .option(bool("Nuclear cue", "Distinct cue when a flush reaches RED from ≥2 primary checks.", { cfg.audioNuclear }) { cfg.audioNuclear = it })
                    .option(bool("Soften alerts on server lag", "Prefix [lag] and (under quiet) drop non-red alerts during a lag burst.", { cfg.lagSuppressAlerts }) { cfg.lagSuppressAlerts = it })
                    .option(bool("Compact mode", "One-line alert + screen summaries (less clutter).", { cfg.compactMode }) { cfg.compactMode = it })
                    .option(int("Evidence window (ticks)", "/ius evidence lookback (200 = 10s).", { cfg.evidenceWindowTicks }, 20, 1200) { cfg.evidenceWindowTicks = it })
                    .option(bool("Transcript side panel", "Auto-show the transcript panel for your crosshair target.", { cfg.transcriptPanel }) { cfg.transcriptPanel = it })
                    .option(bool("Crosshair confidence HUD", "Draw a compact panel near the crosshair: the looked-at player's tier glyph + score + why-this-tier + FP hint. Display-only.", { cfg.confidenceHud }) { cfg.confidenceHud = it })
                    .option(bool("Server-lag HUD indicator", "Draw a top-left ⚠ lag marker while a server-lag burst is recent. Display-only.", { cfg.lagHudIcon }) { cfg.lagHudIcon = it })
                    .option(bool("On-world target highlight", "Draw a tier-colored wireframe box around the other player your crosshair is on. Depth-tested (no wallhack). Suspects always boxed; clean players only when nametag-green is on. Render-only.", { cfg.targetHighlight }) { cfg.targetHighlight = it })
                    .option(bool("Ghost trail", "Draw a fading breadcrumb trail of recent positions for suspect (yellow/red) players, so you can see where a cheater came from / is heading. Depth-tested (no wallhack); clean players never trailed. Render-only.", { cfg.ghostTrail }) { cfg.ghostTrail = it })
                    .option(bool("Watch follow-cam", "Press the watch keybind on a crosshair-targeted player to start a sustained slow auto-orbit camera around them; press again to stop. Auto-reverts to your view when you stop / they leave / the world changes. Render-only.", { cfg.watchFollowCam }) { cfg.watchFollowCam = it })
                    .option(bool("Burst sparks", "Spawn a brief tier-colored (red/yellow) particle burst at a player's eye when a fresh tier-relevant alert fires — a visual flag cue mid-fight. Client-only (no packet). Render-only.", { cfg.burstSparks }) { cfg.burstSparks = it })
                    .option(bool("Hover tooltip", "After the crosshair rests on one player for ~1.5s, show an expanded top-center banner (tier + score + why-this-tier + FP hint + most-flagged checks). Suppresses the compact crosshair panel while up. Display-only.", { cfg.hoverTooltip }) { cfg.hoverTooltip = it })
                    .option(bool("Tab-list badge", "Prepend the tier glyph (+ score when nametag-badge is on) to each OTHER player's row in the Tab list. Follows the nametag settings; never touches your own row. Display-only.", { cfg.tabListBadge }) { cfg.tabListBadge = it })
                    .option(bool("Persist across sessions", "Save notes, tier/flag history, snapshots & exports to %APPDATA%/.iustitia. Off = session-only.", { cfg.persistenceEnabled }) { cfg.persistenceEnabled = it })
                    .build()
            )
            .group(
                OptionGroup.createBuilder()
                    .name(Text.literal("Replay, Sonar & Clip"))
                    .option(bool("Replay capture buffer", "Keep a rolling 60s buffer of every tracked player's position + every alert, so /ius replay and /ius clip can rewind/export the last N seconds. Off = skip the per-tick capture (disables replay + clip; detection keeps running).", { cfg.replayCapture }) { cfg.replayCapture = it })
                    .option(bool("Replay hides live players", "While a replay (/ius replay or /ius playclip) is active, hide every live OTHER player so only the buffered ghost copies render — a rewind-the-world feel. Off = overlay ghosts on the live scene. Render-only.", { cfg.replayHideLive }) { cfg.replayHideLive = it })
                    .option(bool("Replay player models", "Render replay/clip ghosts as the real Minecraft player model wearing each player's REAL skin (fetched from the tab list; Steve/Alex fallback) instead of the tier-colored humanoid box outline. On by default. The block scale + pose transforms are runtime-verified only — if a ghost renders at the wrong size or not at all, the box outline shows as fallback.", { cfg.replayPlayerModels }) { cfg.replayPlayerModels = it })
                    .option(int("Replay keybind seconds", "Seconds of buffered scene replayed when the replay-toggle keybind (numpad * by default) is pressed. 1..60 (capped to the 60s replay buffer).", { cfg.replayKeybindSeconds }, 1, 60) { cfg.replayKeybindSeconds = it })
                    .option(bool("Sonar alerts", "On a flushed alert, play a DIRECTIONAL note at the offender's last position (pan = direction, pitch = distance) so you can keep fighting and listen for cheats. Additive to chat; gated by the same mute/preset rules.", { cfg.sonarAlerts }) { cfg.sonarAlerts = it })
                    .option(double("Sonar volume", "Sonar cue volume (0..1). Quieter than chat cues by design — positional pings are frequent.", { cfg.sonarVolume }, 0.0, 1.0) { cfg.sonarVolume = it })
                    .option(bool("Chat history capture", "Capture messages from tracked OTHER players (not you, not system messages) for /ius chathist <user> / phrase / target. Per-server: persists across reconnects when 'Persist across sessions' is on, else in-memory only. Additive.", { cfg.chathistEnabled }) { cfg.chathistEnabled = it })
                    .build()
            )
            // PlayClip: Legacy (v1.1.0 experience) vs Modern (current chunk-world + freecam feature
            // set). Default Modern (since v1.2.0). The post-v1.1.0 sub-options are only editable in
            // Modern — they're greyed while Legacy is selected, and the mode option's listener toggles
            // their availability live as the user switches (no need to reopen the screen).
            .group(
                OptionGroup.createBuilder()
                    .name(Text.literal("PlayClip"))
                    .apply {
                        fun modern() = cfg.playclipMode == IustitiaConfig.PlayclipMode.MODERN
                        // Built first so the mode listener below can grab references to toggle them.
                        val relocate = boolAvail("Relocate scene to me",
                            "On /ius playclip, translate the whole ghost scene (and chunk world) so the focus player starts at YOUR position instead of floating at the recorded coords. Modern-only — Legacy always plays a clip at the recorded coordinates.",
                            { cfg.replayRelocate }, { cfg.replayRelocate = it }, ::modern)
                        val terrain = boolAvail("Clip captures terrain",
                            "On /ius clip, also snapshot the loaded terrain around the action and render it (relocated to you) on playclip. Modern-only — Legacy clips are ghosts-only.",
                            { cfg.clipTerrain }, { cfg.clipTerrain = it }, ::modern)
                        val chunkWorld = boolAvail("Clip captures full world",
                            "On /ius clip, snapshot every loaded chunk around you (full 16×16 columns, ALL Y sections — includes underground) and render the clip's world as SOLID textured blocks (the real map) with the live world hidden — free-spectate anywhere incl. underground. Modern-only — Legacy never downloads the world.",
                            { cfg.clipChunkWorld }, { cfg.clipChunkWorld = it }, ::modern)
                        val chunkRadius = intAvail("Chunk capture radius",
                            "Radius (in chunks) around you that /ius clip captures for the full world. 8 → 17×17 = 289 chunks. Larger = more of the map but a bigger clip file + a heavier bake. Modern-only.",
                            { cfg.clipChunkRadius }, 4, 16, { cfg.clipChunkRadius = it }, ::modern)
                        val chunkRenderDist = intAvail("Chunk render distance",
                            "Max chunks from the camera that /ius playclip renders of the captured chunk world per frame. Bounds per-frame cost (and thus FPS). Modern-only.",
                            { cfg.clipChunkRenderDistance }, 4, 12, { cfg.clipChunkRenderDistance = it }, ::modern)
                        val healthInd = boolAvail("Health indicator on clips",
                            "Show a numeric health line (e.g. 14/20) above each ghost's nametag during /ius playclip, plus a transient -dmg popup when a recorded player is hit. Natively off. Modern-only.",
                            { cfg.clipHealthIndicator }, { cfg.clipHealthIndicator = it }, ::modern)
                        val totemInd = boolAvail("Totem pop counter on clips",
                            "Show a totem-pop count badge on each ghost's nametag during /ius playclip (Totem of Undying pops within the clip). Natively off. Modern-only.",
                            { cfg.clipTotemPopCounter }, { cfg.clipTotemPopCounter = it }, ::modern)
                        val ghostEquip = boolAvail("Ghost equipment on clips",
                            "Render each ghost's held items + armor (main hand, off hand, head, chest, legs, feet) during /ius playclip & /ius replay — what the player actually held/wore at the recorded tick. Lossy (no enchant glint). Natively on. Modern-only.",
                            { cfg.clipGhostEquipment }, { cfg.clipGhostEquipment = it }, ::modern)
                        val subs = listOf(relocate, terrain, chunkWorld, chunkRadius, chunkRenderDist, healthInd, totemInd, ghostEquip)
                        option(Option.createBuilder<IustitiaConfig.PlayclipMode>()
                            .name(Text.literal("Playclip mode"))
                            .description(OptionDescription.of(Text.literal("LEGACY = the v1.1.0 playclip: ghosts render over the LIVE world at their recorded coords, the player walks and acts normally, no world is downloaded. MODERN = the current feature set: solid captured chunk world (free-spectate anywhere, incl. underground), relocated scene, auto-freecam, and spectator-like input/packet suppression while the clip plays. Default MODERN (since v1.2.0 — the C2 FPS fix made Modern match the 120 cap); existing configs keep their saved choice.")))
                            .binding(cfg.playclipMode, { cfg.playclipMode }, { cfg.playclipMode = it })
                            .addListener { opt, _ ->
                                val avail = opt.pendingValue() == IustitiaConfig.PlayclipMode.MODERN
                                subs.forEach { s -> try { s.setAvailable(avail) } catch (_: Throwable) {} }
                            }
                            .controller { opt -> EnumControllerBuilder.create(opt).enumClass(IustitiaConfig.PlayclipMode::class.java) }
                            .build())
                        option(relocate)
                        option(terrain)
                        option(chunkWorld)
                        option(chunkRadius)
                        option(chunkRenderDist)
                        option(healthInd)
                        option(totemInd)
                        option(ghostEquip)
                    }
                    .build()
            )
        for ((id, cc) in cfg.checks()) {
            category.group(checkGroup(id, cc))
        }
        return YetAnotherConfigLib.createBuilder()
            .title(Text.literal("Iustitia"))
            .save {
                ConfigManager.save()
                // The persistence toggle may have just flipped — react so a freshly-enabled store
                // loads its notes/history and a freshly-disabled one stops scheduling saves.
                try { dev.iustitia.Iustitia.onConfigReloaded() } catch (_: Throwable) {}
            }
            .category(category.build())
            .build()
            .generateScreen(parent)
    }

    private fun checkGroup(id: String, cc: IustitiaConfig.CheckConfig) = OptionGroup.createBuilder()
        .name(Text.literal(id))
        .option(bool("Enabled", "Toggle the $id check.", { cc.enabled }) { cc.enabled = it })
        .option(double("Setback VL", "Alert only when VL exceeds this.", { cc.setbackVL }, 0.0, 100.0) { cc.setbackVL = it })
        .option(double("Decay / tick", "VL reduced per clean tick.", { cc.decay }, 0.0, 5.0) { cc.decay = it })
        // Threshold max 200 covers the largest default (aimWrap 165) with headroom; the range
        // is now actually applied to the controller, so the field is slider/keyboard-bounded
        // instead of accepting any double (the previous helper ignored its min/max args).
        .option(double("Threshold", "Primary numeric threshold (check-specific meaning).", { cc.threshold }, 0.0, 200.0) { cc.threshold = it })
        .build()

    private fun bool(name: String, desc: String, getter: () -> Boolean, setter: (Boolean) -> Unit): Option<Boolean> =
        Option.createBuilder<Boolean>()
            .name(Text.literal(name))
            .description(OptionDescription.of(Text.literal(desc)))
            .binding(getter(), getter, setter)
            .controller { opt -> BooleanControllerBuilder.create(opt) }
            .build()

    private fun int(name: String, desc: String, getter: () -> Int, min: Int, max: Int, setter: (Int) -> Unit): Option<Int> =
        Option.createBuilder<Int>()
            .name(Text.literal(name))
            .description(OptionDescription.of(Text.literal(desc)))
            .binding(getter(), getter, setter)
            .controller { opt -> IntegerFieldControllerBuilder.create(opt).range(min, max) }
            .build()

    private fun double(name: String, desc: String, getter: () -> Double, min: Double, max: Double, setter: (Double) -> Unit): Option<Double> =
        Option.createBuilder<Double>()
            .name(Text.literal(name))
            .description(OptionDescription.of(Text.literal(desc)))
            .binding(getter(), getter, setter)
            .controller { opt -> DoubleFieldControllerBuilder.create(opt).range(min, max) }
            .build()

    /** Same as [bool] but with an availability snapshot (YACL greys the option when false). Used for
     *  the PlayClip sub-options that are only editable in Modern mode. The mode option's listener
     *  re-toggles [Option.setAvailable] live on a switch, so the snapshot only fixes the initial state. */
    private fun boolAvail(name: String, desc: String, getter: () -> Boolean, setter: (Boolean) -> Unit, available: () -> Boolean): Option<Boolean> =
        Option.createBuilder<Boolean>()
            .name(Text.literal(name))
            .description(OptionDescription.of(Text.literal(desc)))
            .binding(getter(), getter, setter)
            .available(available())
            .controller { opt -> BooleanControllerBuilder.create(opt) }
            .build()

    /** Same as [int] but with an availability snapshot — see [boolAvail]. */
    private fun intAvail(name: String, desc: String, getter: () -> Int, min: Int, max: Int, setter: (Int) -> Unit, available: () -> Boolean): Option<Int> =
        Option.createBuilder<Int>()
            .name(Text.literal(name))
            .description(OptionDescription.of(Text.literal(desc)))
            .binding(getter(), getter, setter)
            .available(available())
            .controller { opt -> IntegerFieldControllerBuilder.create(opt).range(min, max) }
            .build()
}
package dev.iustitia.config

import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionDescription
import dev.isxander.yacl3.api.OptionGroup
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder
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
                    .option(bool("Relocate scene to me", "On /ius replay and /ius playclip, translate the whole ghost scene so the focus player starts at YOUR current position instead of the absolute recorded coords (which float in mid-air / sit in ground / clip walls when played elsewhere). On by default — turn off to replay at the original coordinates.", { cfg.replayRelocate }) { cfg.replayRelocate = it })
                    .option(bool("Clip captures terrain", "On /ius clip, also snapshot the loaded terrain around the action (client only has loaded chunks in render distance; volume-capped) and store it in the clip file. /ius playclip then renders that terrain shell around you — a clip recorded on server A is watchable in full on server B. /ius replay never carries terrain (same-map use). On by default.", { cfg.clipTerrain }) { cfg.clipTerrain = it })
                    .option(bool("Clip captures full world", "On /ius clip, snapshot every loaded chunk around you (full 16×16 columns, ALL Y sections — includes underground) into the clip file. /ius playclip then renders the clip's world as SOLID, textured blocks (the real map, relocated to you) and hides the live world, so you can free-spectate anywhere — including underground — like ReplayMod. One-shot at save time (only chunks loaded then are kept). On by default. The chunk capture radius bounds file size + bake cost.", { cfg.clipChunkWorld }) { cfg.clipChunkWorld = it })
                    .option(int("Chunk capture radius", "Radius (in chunks) around you that /ius clip captures for the full world. 8 → a 17×17 = 289-chunk square. Larger captures more of the map but makes a bigger clip file + a heavier bake when playback starts. Clamped 4..16 at capture.", { cfg.clipChunkRadius }, 4, 16) { cfg.clipChunkRadius = it })
                    .option(int("Chunk render distance", "Max chunks from the camera that /ius playclip renders of the captured chunk world per frame. Bounds per-frame cost (and thus FPS) — the captured area can be much larger; this is how much you actually draw at once. Default 6, clamped 4..12 at render. Lower it if playclip lags; raise it to see more of the world at once.", { cfg.clipChunkRenderDistance }, 4, 12) { cfg.clipChunkRenderDistance = it })
                    .option(bool("Sonar alerts", "On a flushed alert, play a DIRECTIONAL note at the offender's last position (pan = direction, pitch = distance) so you can keep fighting and listen for cheats. Additive to chat; gated by the same mute/preset rules.", { cfg.sonarAlerts }) { cfg.sonarAlerts = it })
                    .option(double("Sonar volume", "Sonar cue volume (0..1). Quieter than chat cues by design — positional pings are frequent.", { cfg.sonarVolume }, 0.0, 1.0) { cfg.sonarVolume = it })
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
}
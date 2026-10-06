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
import dev.iustitia.i18n.L10n
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
            .name(L10n.t("iustitia.cfg.title"))
            .tooltip(L10n.t("iustitia.cfg.tooltip"))
            .group(
                OptionGroup.createBuilder()
                    .name(L10n.t("iustitia.cfg.group.general"))
                    .option(bool("iustitia.cfg.option.enabled.name", "iustitia.cfg.option.enabled.desc", { cfg.enabled }) { cfg.enabled = it })
                    .option(bool("iustitia.cfg.option.verbose.name", "iustitia.cfg.option.verbose.desc", { cfg.verbose }) { cfg.verbose = it })
                    .option(int("iustitia.cfg.option.alertThrottle.name", "iustitia.cfg.option.alertThrottle.desc", { cfg.alertThrottleTicks }, 0, 600) { cfg.alertThrottleTicks = it })
                    .option(int("iustitia.cfg.option.joinGrace.name", "iustitia.cfg.option.joinGrace.desc", { cfg.joinGraceTicks }, 0, 2400) { cfg.joinGraceTicks = it })
                    .option(bool("iustitia.cfg.option.legitScaffoldStrictGates.name", "iustitia.cfg.option.legitScaffoldStrictGates.desc", { cfg.legitScaffoldStrictGates }) { cfg.legitScaffoldStrictGates = it })
                    .option(bool("iustitia.cfg.option.sensitivitySubstrate.name", "iustitia.cfg.option.sensitivitySubstrate.desc", { cfg.sensitivitySubstrate }) { cfg.sensitivitySubstrate = it })
                    .option(bool("iustitia.cfg.option.chatAlerts.name", "iustitia.cfg.option.chatAlerts.desc", { cfg.alertsEnabled }) { cfg.alertsEnabled = it })
                    .option(bool("iustitia.cfg.option.nametagPrefixes.name", "iustitia.cfg.option.nametagPrefixes.desc", { cfg.nametagPrefixes }) { cfg.nametagPrefixes = it })
                    .option(bool("iustitia.cfg.option.greenTick.name", "iustitia.cfg.option.greenTick.desc", { cfg.nametagGreenEnabled }) { cfg.nametagGreenEnabled = it })
                    .build()
            )
            .group(
                OptionGroup.createBuilder()
                    .name(L10n.t("iustitia.cfg.group.displayAlerts"))
                    .option(bool("iustitia.cfg.option.nametagBurstPulse.name", "iustitia.cfg.option.nametagBurstPulse.desc", { cfg.nametagBurstPulse }) { cfg.nametagBurstPulse = it })
                    .option(int("iustitia.cfg.option.alertLevel.name", "iustitia.cfg.option.alertLevel.desc", { cfg.alertLevel }, 0, 2) { cfg.alertLevel = it })
                    .option(bool("iustitia.cfg.option.alertBatching.name", "iustitia.cfg.option.alertBatching.desc", { cfg.alertBatching }) { cfg.alertBatching = it })
                    .option(int("iustitia.cfg.option.batchWindow.name", "iustitia.cfg.option.batchWindow.desc", { cfg.alertBatchWindowTicks }, 0, 600) { cfg.alertBatchWindowTicks = it })
                    .option(bool("iustitia.cfg.option.audioCues.name", "iustitia.cfg.option.audioCues.desc", { cfg.audioCues }) { cfg.audioCues = it })
                    .option(double("iustitia.cfg.option.audioVolume.name", "iustitia.cfg.option.audioVolume.desc", { cfg.audioVolume }, 0.0, 1.0) { cfg.audioVolume = it })
                    .option(bool("iustitia.cfg.option.softenOnLag.name", "iustitia.cfg.option.softenOnLag.desc", { cfg.lagSuppressAlerts }) { cfg.lagSuppressAlerts = it })
                    .option(bool("iustitia.cfg.option.compactMode.name", "iustitia.cfg.option.compactMode.desc", { cfg.compactMode }) { cfg.compactMode = it })
                    .option(int("iustitia.cfg.option.evidenceWindow.name", "iustitia.cfg.option.evidenceWindow.desc", { cfg.evidenceWindowTicks }, 20, 1200) { cfg.evidenceWindowTicks = it })
                    .option(bool("iustitia.cfg.option.transcriptPanel.name", "iustitia.cfg.option.transcriptPanel.desc", { cfg.transcriptPanel }) { cfg.transcriptPanel = it })
                    .option(bool("iustitia.cfg.option.confidenceHud.name", "iustitia.cfg.option.confidenceHud.desc", { cfg.confidenceHud }) { cfg.confidenceHud = it })
                    .option(bool("iustitia.cfg.option.lagHudIcon.name", "iustitia.cfg.option.lagHudIcon.desc", { cfg.lagHudIcon }) { cfg.lagHudIcon = it })
                    .option(bool("iustitia.cfg.option.targetHighlight.name", "iustitia.cfg.option.targetHighlight.desc", { cfg.targetHighlight }) { cfg.targetHighlight = it })
                    .option(bool("iustitia.cfg.option.watchFollowCam.name", "iustitia.cfg.option.watchFollowCam.desc", { cfg.watchFollowCam }) { cfg.watchFollowCam = it })
                    .option(bool("iustitia.cfg.option.burstSparks.name", "iustitia.cfg.option.burstSparks.desc", { cfg.burstSparks }) { cfg.burstSparks = it })
                    .option(bool("iustitia.cfg.option.hoverTooltip.name", "iustitia.cfg.option.hoverTooltip.desc", { cfg.hoverTooltip }) { cfg.hoverTooltip = it })
                    .option(bool("iustitia.cfg.option.tabListBadge.name", "iustitia.cfg.option.tabListBadge.desc", { cfg.tabListBadge }) { cfg.tabListBadge = it })
                    .option(bool("iustitia.cfg.option.persistence.name", "iustitia.cfg.option.persistence.desc", { cfg.persistenceEnabled }) { cfg.persistenceEnabled = it })
                    .build()
            )
            .group(
                OptionGroup.createBuilder()
                    .name(L10n.t("iustitia.cfg.group.replayClip"))
                    .option(bool("iustitia.cfg.option.replayCapture.name", "iustitia.cfg.option.replayCapture.desc", { cfg.replayCapture }) { cfg.replayCapture = it })
                    .option(bool("iustitia.cfg.option.replayHideLive.name", "iustitia.cfg.option.replayHideLive.desc", { cfg.replayHideLive }) { cfg.replayHideLive = it })
                    .option(bool("iustitia.cfg.option.replayPlayerModels.name", "iustitia.cfg.option.replayPlayerModels.desc", { cfg.replayPlayerModels }) { cfg.replayPlayerModels = it })
                    .option(int("iustitia.cfg.option.replayKeybindSeconds.name", "iustitia.cfg.option.replayKeybindSeconds.desc", { cfg.replayKeybindSeconds }, 1, 60) { cfg.replayKeybindSeconds = it })
                    .option(bool("iustitia.cfg.option.chathistCapture.name", "iustitia.cfg.option.chathistCapture.desc", { cfg.chathistEnabled }) { cfg.chathistEnabled = it })
                    .option(bool("iustitia.cfg.option.chathistUnknown.name", "iustitia.cfg.option.chathistUnknown.desc", { cfg.chathistCaptureUnknown }) { cfg.chathistCaptureUnknown = it })
                    .build()
            )
            // PlayClip: Legacy (v1.1.0 experience) vs Modern (current chunk-world + freecam feature
            // set). Default Modern (since v1.2.0). The post-v1.1.0 sub-options are only editable in
            // Modern — they're greyed while Legacy is selected, and the mode option's listener toggles
            // their availability live as the user switches (no need to reopen the screen).
            .group(
                OptionGroup.createBuilder()
                    .name(L10n.t("iustitia.cfg.group.playclip"))
                    .apply {
                        fun modern() = cfg.playclipMode == IustitiaConfig.PlayclipMode.MODERN
                        // Built first so the mode listener below can grab references to toggle them.
                        val relocate = boolAvail("iustitia.cfg.option.relocateScene.name",
                            "iustitia.cfg.option.relocateScene.desc",
                            { cfg.replayRelocate }, { cfg.replayRelocate = it }, ::modern)
                        val terrain = boolAvail("iustitia.cfg.option.clipTerrain.name",
                            "iustitia.cfg.option.clipTerrain.desc",
                            { cfg.clipTerrain }, { cfg.clipTerrain = it }, ::modern)
                        val chunkWorld = boolAvail("iustitia.cfg.option.clipFullWorld.name",
                            "iustitia.cfg.option.clipFullWorld.desc",
                            { cfg.clipChunkWorld }, { cfg.clipChunkWorld = it }, ::modern)
                        val chunkRadius = intAvail("iustitia.cfg.option.chunkCaptureRadius.name",
                            "iustitia.cfg.option.chunkCaptureRadius.desc",
                            { cfg.clipChunkRadius }, 4, 16, { cfg.clipChunkRadius = it }, ::modern)
                        val chunkRenderDist = intAvail("iustitia.cfg.option.chunkRenderDistance.name",
                            "iustitia.cfg.option.chunkRenderDistance.desc",
                            { cfg.clipChunkRenderDistance }, 4, 12, { cfg.clipChunkRenderDistance = it }, ::modern)
                        val healthInd = boolAvail("iustitia.cfg.option.healthIndicator.name",
                            "iustitia.cfg.option.healthIndicator.desc",
                            { cfg.clipHealthIndicator }, { cfg.clipHealthIndicator = it }, ::modern)
                        val totemInd = boolAvail("iustitia.cfg.option.totemPopCounter.name",
                            "iustitia.cfg.option.totemPopCounter.desc",
                            { cfg.clipTotemPopCounter }, { cfg.clipTotemPopCounter = it }, ::modern)
                        val ghostEquip = boolAvail("iustitia.cfg.option.ghostEquipment.name",
                            "iustitia.cfg.option.ghostEquipment.desc",
                            { cfg.clipGhostEquipment }, { cfg.clipGhostEquipment = it }, ::modern)
                        val clipEntities = boolAvail("iustitia.cfg.option.clipEntities.name",
                            "iustitia.cfg.option.clipEntities.desc",
                            { cfg.clipEntities }, { cfg.clipEntities = it }, ::modern)
                        val clipEntityCap = intAvail("iustitia.cfg.option.entityCaptureCap.name",
                            "iustitia.cfg.option.entityCaptureCap.desc",
                            { cfg.clipEntityCap }, 0, 256, { cfg.clipEntityCap = it }, ::modern)
                        val rollingCap = intAvail("iustitia.cfg.option.rollingWorldBudget.name",
                            "iustitia.cfg.option.rollingWorldBudget.desc",
                            { cfg.clipRollingChunkCap }, 4096, 131072, { cfg.clipRollingChunkCap = it }, ::modern)
                        val segThreshold = doubleAvail("iustitia.cfg.option.newSegmentDistance.name",
                            "iustitia.cfg.option.newSegmentDistance.desc",
                            { cfg.clipSegmentTeleportThreshold }, 8.0, 512.0, { cfg.clipSegmentTeleportThreshold = it }, ::modern)
                        val subs = listOf(relocate, terrain, chunkWorld, chunkRadius, chunkRenderDist, healthInd, totemInd, ghostEquip, clipEntities, clipEntityCap, rollingCap, segThreshold)
                        option(Option.createBuilder<IustitiaConfig.PlayclipMode>()
                            .name(L10n.t("iustitia.cfg.option.playclipMode.name"))
                            .description(OptionDescription.of(L10n.t("iustitia.cfg.option.playclipMode.desc")))
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
                        option(clipEntities)
                        option(clipEntityCap)
                        option(rollingCap)
                        option(segThreshold)
                    }
                    .build()
            )
        for ((id, cc) in cfg.checks()) {
            category.group(checkGroup(id, cc))
        }
        return YetAnotherConfigLib.createBuilder()
            .title(L10n.t("iustitia.cfg.title"))
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

    private fun checkGroup(id: String, cc: IustitiaConfig.CheckConfig): OptionGroup {
        val b = OptionGroup.createBuilder()
            .name(Text.literal(id))
            .option(bool("iustitia.cfg.option.enabled.name", "iustitia.cfg.option.checkEnabled.desc", { cc.enabled }, id) { cc.enabled = it })
            .option(double("iustitia.cfg.option.setbackVL.name", "iustitia.cfg.option.setbackVL.desc", { cc.setbackVL }, 0.0, 100.0) { cc.setbackVL = it })
            .option(double("iustitia.cfg.option.decayPerTick.name", "iustitia.cfg.option.decayPerTick.desc", { cc.decay }, 0.0, 5.0) { cc.decay = it })
            // Threshold max 200 covers the largest default (aimWrap 165) with headroom; the range
            // is now actually applied to the controller, so the field is slider/keyboard-bounded
            // instead of accepting any double (the previous helper ignored its min/max args).
            .option(double("iustitia.cfg.option.threshold.name", "iustitia.cfg.option.threshold.desc", { cc.threshold }, 0.0, 200.0) { cc.threshold = it })
        // The fly group also carries the Fly(Blink) sub-signal's freeze window (additive field —
        // see [IustitiaConfig.blinkFreezeTicks]). Clamped to the same 5..200 range at use.
        if (id == "flyEnvelope") {
            b.option(int("iustitia.cfg.option.blinkFreezeTicks.name", "iustitia.cfg.option.blinkFreezeTicks.desc", { ConfigManager.config.blinkFreezeTicks }, 5, 200) { ConfigManager.config.blinkFreezeTicks = it })
        }
        return b.build()
    }

    private fun bool(key: String, descKey: String, getter: () -> Boolean, vararg descArgs: Any?, setter: (Boolean) -> Unit): Option<Boolean> =
        Option.createBuilder<Boolean>()
            .name(L10n.t(key))
            .description(OptionDescription.of(L10n.t(descKey, *descArgs)))
            .binding(getter(), getter, setter)
            .controller { opt -> BooleanControllerBuilder.create(opt) }
            .build()

    private fun int(key: String, descKey: String, getter: () -> Int, min: Int, max: Int, vararg descArgs: Any?, setter: (Int) -> Unit): Option<Int> =
        Option.createBuilder<Int>()
            .name(L10n.t(key))
            .description(OptionDescription.of(L10n.t(descKey, *descArgs)))
            .binding(getter(), getter, setter)
            .controller { opt -> IntegerFieldControllerBuilder.create(opt).range(min, max) }
            .build()

    private fun double(key: String, descKey: String, getter: () -> Double, min: Double, max: Double, vararg descArgs: Any?, setter: (Double) -> Unit): Option<Double> =
        Option.createBuilder<Double>()
            .name(L10n.t(key))
            .description(OptionDescription.of(L10n.t(descKey, *descArgs)))
            .binding(getter(), getter, setter)
            .controller { opt -> DoubleFieldControllerBuilder.create(opt).range(min, max) }
            .build()

    /** [double] + an availability snapshot — see [boolAvail]. */
    private fun doubleAvail(key: String, descKey: String, getter: () -> Double, min: Double, max: Double, setter: (Double) -> Unit, available: () -> Boolean, vararg descArgs: Any?): Option<Double> =
        Option.createBuilder<Double>()
            .name(L10n.t(key))
            .description(OptionDescription.of(L10n.t(descKey, *descArgs)))
            .binding(getter(), getter, setter)
            .available(available())
            .controller { opt -> DoubleFieldControllerBuilder.create(opt).range(min, max) }
            .build()

    /** Same as [bool] but with an availability snapshot (YACL greys the option when false). Used for
     *  the PlayClip sub-options that are only editable in Modern mode. The mode option's listener
     *  re-toggles [Option.setAvailable] live on a switch, so the snapshot only fixes the initial state. */
    private fun boolAvail(key: String, descKey: String, getter: () -> Boolean, setter: (Boolean) -> Unit, available: () -> Boolean, vararg descArgs: Any?): Option<Boolean> =
        Option.createBuilder<Boolean>()
            .name(L10n.t(key))
            .description(OptionDescription.of(L10n.t(descKey, *descArgs)))
            .binding(getter(), getter, setter)
            .available(available())
            .controller { opt -> BooleanControllerBuilder.create(opt) }
            .build()

    /** Same as [int] but with an availability snapshot — see [boolAvail]. */
    private fun intAvail(key: String, descKey: String, getter: () -> Int, min: Int, max: Int, setter: (Int) -> Unit, available: () -> Boolean, vararg descArgs: Any?): Option<Int> =
        Option.createBuilder<Int>()
            .name(L10n.t(key))
            .description(OptionDescription.of(L10n.t(descKey, *descArgs)))
            .binding(getter(), getter, setter)
            .available(available())
            .controller { opt -> IntegerFieldControllerBuilder.create(opt).range(min, max) }
            .build()
}
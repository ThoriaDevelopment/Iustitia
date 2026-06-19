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
        for ((id, cc) in cfg.checks()) {
            category.group(checkGroup(id, cc))
        }
        return YetAnotherConfigLib.createBuilder()
            .title(Text.literal("Iustitia"))
            .save { ConfigManager.save() }
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
package dev.iustitia.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import dev.iustitia.config.ConfigManager
import dev.iustitia.config.YaclScreenBuilder
import dev.iustitia.history.FlagHistory
import dev.iustitia.info.CheckInfo
import dev.iustitia.protocol.ProtocolDetector
import dev.iustitia.tracking.EntityTrackerManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import java.util.UUID

/**
 * `/iustitia` and `/ius` — client-only control surface. Both aliases expose the same subcommands:
 *  - `list`                 show checks + enabled state
 *  - `toggle <check>`       flip a check's enabled flag
 *  - `threshold <check> <v>` set a check's primary threshold
 *  - `verbose`             toggle verbose
 *  - `reload`              reload config from disk
 *  - `reset`               reset all tracker/check/alert/history state
 *  - `config`              open the YACL config screen
 *  - `status`              health panel (master, enabled checks, tracked players, protocol, alerts)
 *  - `hist [name] [check]` session flag history — top offenders, or one player's recent flags
 *  - `help [topic]`        in-game help: subcommands list, or a check/subcommand description
 *  - `alerts [target] [on|off]` mute a check or player's chat alerts (detection/tier keep running)
 *
 * Tab-completion is wired for check ids, player names, on/off, and help topics. Every path is
 * fail-open and never sends anything to the server.
 */
object IustitiaCommand {

    private val tag: String get() = "§8[§diustitia§8]"

    // Derived from the live config so the command never drifts from the registry.
    private val checkIds: List<String>
        get() = ConfigManager.config.checks().map { it.first }

    private val subcommands: List<Pair<String, String>> = listOf(
        "list" to "show all checks + enabled state",
        "status" to "health panel: master, tracked players, protocol, alerts",
        "hist" to "flag history: /ius hist [name] [check]",
        "help" to "this help, or /ius help <check|subcommand>",
        "alerts" to "toggle all chat alerts: /ius alerts  (or mute one: /ius alerts <name|check> [on|off])",
        "toggle" to "enable/disable a check: /ius toggle <check>",
        "threshold" to "set a check's threshold: /ius threshold <check> <v>",
        "config" to "open the config screen",
        "verbose" to "toggle verbose console logging",
        "reload" to "reload config from disk",
        "reset" to "reset all tracker/check/alert/history state",
    )

    fun register(dispatcher: CommandDispatcher<FabricClientCommandSource>) {
        dispatcher.register(buildRoot("iustitia"))
        dispatcher.register(buildRoot("ius"))
    }

    private fun buildRoot(name: String): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommandManager.literal(name)
            .executes { list(it) }
            .then(ClientCommandManager.literal("list").executes { list(it) })
            .then(ClientCommandManager.literal("status").executes { status(it) })
            .then(ClientCommandManager.literal("verbose").executes { verbose(it) })
            .then(ClientCommandManager.literal("reload").executes { reload(it) })
            .then(ClientCommandManager.literal("reset").executes { reset(it) })
            .then(ClientCommandManager.literal("config").executes { openConfig(it) })
            .then(ClientCommandManager.literal("help")
                .executes { help(it, null) }
                .then(ClientCommandManager.argument("topic", StringArgumentType.word())
                    .suggests { _, b -> suggestHelpTopics(b); b.buildFuture() }
                    .executes { help(it, StringArgumentType.getString(it, "topic")) }))
            .then(ClientCommandManager.literal("hist")
                .executes { histTop(it) }
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .suggests { _, b -> suggestNames(b); b.buildFuture() }
                    .executes { histPlayer(it, null) }
                    .then(ClientCommandManager.argument("check", StringArgumentType.word())
                        .suggests { _, b -> checkIds.forEach(b::suggest); b.buildFuture() }
                        .executes { histPlayer(it, StringArgumentType.getString(it, "check")) })))
            .then(ClientCommandManager.literal("alerts")
                .executes { alertsList(it) }
                .then(ClientCommandManager.argument("target", StringArgumentType.word())
                    .suggests { _, b -> suggestTargets(b); b.buildFuture() }
                    .executes { alertsSet(it, null) }
                    .then(ClientCommandManager.argument("state", StringArgumentType.word())
                        .suggests { _, b -> listOf("on", "off").forEach(b::suggest); b.buildFuture() }
                        .executes { alertsSet(it, StringArgumentType.getString(it, "state")) })))
            .then(ClientCommandManager.literal("toggle")
                .executes { toggleUsage(it) }
                .then(ClientCommandManager.argument("check", StringArgumentType.word())
                    .suggests { _, b -> checkIds.forEach(b::suggest); b.buildFuture() }
                    .executes { toggle(it) }))
            .then(ClientCommandManager.literal("threshold")
                .executes { thresholdUsage(it) }
                .then(ClientCommandManager.argument("check", StringArgumentType.word())
                    .suggests { _, b -> checkIds.forEach(b::suggest); b.buildFuture() }
                    .then(ClientCommandManager.argument("value", DoubleArgumentType.doubleArg())
                        .executes { threshold(it) })))

    // ---- feedback helper ----
    private fun send(ctx: CommandContext<FabricClientCommandSource>, line: String) {
        ctx.source.sendFeedback(Text.literal(line))
    }

    // ---- existing subcommands ----
    private fun list(ctx: CommandContext<FabricClientCommandSource>): Int {
        val cfg = ConfigManager.config
        send(ctx, "$tag §7checks:")
        for ((id, cc) in cfg.checks()) {
            val state = if (cc.enabled) "§aON" else "§cOFF"
            val muted = if (id in cfg.mutedChecks) " §7[muted]" else ""
            send(ctx, " §f$id §7vl>${cc.setbackVL} §7decay=${cc.decay} §7thr=${cc.threshold} $state$muted")
        }
        val master = if (cfg.enabled) "§aON" else "§cOFF"
        send(ctx, "§7master=$master verbose=${cfg.verbose}")
        send(ctx, CheckInfo.SEVERITY_LEGEND)
        return 1
    }

    private fun status(ctx: CommandContext<FabricClientCommandSource>): Int {
        val cfg = ConfigManager.config
        val master = if (cfg.enabled) "§aON" else "§cOFF"
        val chatAlerts = if (cfg.alertsEnabled) "§aON" else "§cOFF (muted)"
        val total = cfg.checks().size
        val enabled = cfg.checks().count { it.second.enabled }
        val tracked = try { EntityTrackerManager.all().size } catch (_: Throwable) { -1 }
        val proto = ProtocolDetector.current
        val era = if (ProtocolDetector.is1_8OrLess) " §7(§e1.8 era§7)" else ""
        val lag = try { EntityTrackerManager.lastServerLagTick } catch (_: Throwable) { -10000 }
        val alerts = FlagHistory.totalAlerts
        send(ctx, "$tag §7status")
        send(ctx, " §7master: $master")
        send(ctx, " §7chat alerts: $chatAlerts")
        send(ctx, " §7checks: §f$enabled§7/§f$total §7enabled")
        send(ctx, " §7players tracked: §f$tracked")
        send(ctx, " §7protocol: §f$proto$era")
        send(ctx, " §7last server-lag tick: §f$lag")
        send(ctx, " §7alerts this session: §f$alerts")
        val top = FlagHistory.topOffenders(1)
        if (top.isNotEmpty()) send(ctx, " §7top offender: §f${top[0].first} §7(${top[0].second})")
        send(ctx, CheckInfo.SEVERITY_LEGEND)
        return 1
    }

    private fun verbose(ctx: CommandContext<FabricClientCommandSource>): Int {
        ConfigManager.config.verbose = !ConfigManager.config.verbose
        ConfigManager.save()
        send(ctx, "$tag §7verbose = ${ConfigManager.config.verbose}")
        return 1
    }

    private fun reload(ctx: CommandContext<FabricClientCommandSource>): Int {
        ConfigManager.reload()
        send(ctx, "$tag §7config reloaded.")
        return 1
    }

    private fun reset(ctx: CommandContext<FabricClientCommandSource>): Int {
        dev.iustitia.Iustitia.resetAll()
        send(ctx, "$tag §7state reset (history cleared, nametags back to green).")
        return 1
    }

    private fun openConfig(ctx: CommandContext<FabricClientCommandSource>): Int {
        MinecraftClient.getInstance().execute {
            try {
                MinecraftClient.getInstance().setScreen(YaclScreenBuilder.build(MinecraftClient.getInstance().currentScreen))
            } catch (_: Throwable) {
                MinecraftClient.getInstance().player?.sendMessage(
                    Text.literal("$tag §cfailed to open config screen"), false
                )
            }
        }
        return 1
    }

    private fun toggle(ctx: CommandContext<FabricClientCommandSource>): Int {
        val id = StringArgumentType.getString(ctx, "check")
        if (id !in checkIds) { send(ctx, "$tag §cunknown check: $id"); return 0 }
        val cc = ConfigManager.config.slice(id)
        cc.enabled = !cc.enabled
        ConfigManager.save()
        send(ctx, "$tag §7$id = ${if (cc.enabled) "§aON" else "§cOFF"}")
        return 1
    }

    /** Bare `/ius toggle` (no check arg) — print usage instead of Brigadier's cryptic
     *  "Unknown or incomplete command". Same for [thresholdUsage]. */
    private fun toggleUsage(ctx: CommandContext<FabricClientCommandSource>): Int {
        send(ctx, "$tag §7usage: §f/ius toggle <check>")
        send(ctx, " §7checks: §f${checkIds.joinToString(" ")}")
        return 0
    }

    private fun threshold(ctx: CommandContext<FabricClientCommandSource>): Int {
        val id = StringArgumentType.getString(ctx, "check")
        if (id !in checkIds) { send(ctx, "$tag §cunknown check: $id"); return 0 }
        val value = DoubleArgumentType.getDouble(ctx, "value")
        ConfigManager.config.slice(id).threshold = value
        ConfigManager.save()
        send(ctx, "$tag §7$id.threshold = $value")
        return 1
    }

    private fun thresholdUsage(ctx: CommandContext<FabricClientCommandSource>): Int {
        send(ctx, "$tag §7usage: §f/ius threshold <check> <value>")
        send(ctx, " §7checks: §f${checkIds.joinToString(" ")}")
        return 0
    }

    // ---- history ----
    private fun histTop(ctx: CommandContext<FabricClientCommandSource>): Int {
        val top = FlagHistory.topOffenders(8)
        if (top.isEmpty()) { send(ctx, "$tag §7no alerts yet this session."); return 1 }
        send(ctx, "$tag §7top offenders (by alert count):")
        top.forEach { (name, count) -> send(ctx, " §f$name §7$count") }
        return 1
    }

    private fun histPlayer(ctx: CommandContext<FabricClientCommandSource>, checkFilter: String?): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val uuid = FlagHistory.resolveName(name)
        if (uuid == null) { send(ctx, "$tag §cno history for §f$name§7 (must be tracked or have flagged first)."); return 0 }
        val flags = FlagHistory.flags(uuid)
        if (flags.isEmpty()) { send(ctx, "$tag §7no flags recorded for §f$name§7."); return 1 }
        val filtered = if (checkFilter == null) flags else flags.filter { it.checkId == checkFilter }
        if (filtered.isEmpty()) { send(ctx, "$tag §7no §f$checkFilter§7 flags for §f$name§7."); return 1 }
        val distinctChecks = filtered.map { it.checkId }.distinct().size
        send(ctx, "$tag §f$name §7— §f${filtered.size}§7 flag(s) across §f$distinctChecks§7 check(s):")
        filtered.take(20).forEach { f ->
            send(ctx, " §7@t${f.tick} §f${f.label} §8(${f.checkId}) §evl=${"%.2f".format(f.vl)}")
        }
        return 1
    }

    // ---- help ----
    private fun help(ctx: CommandContext<FabricClientCommandSource>, topic: String?): Int {
        if (topic == null) {
            send(ctx, "$tag §7commands (alias: §f/ius§7):")
            subcommands.forEach { (s, d) -> send(ctx, " §f$s §7— $d") }
            send(ctx, CheckInfo.SEVERITY_LEGEND)
            send(ctx, "§7nametag: §a[+] §7clean §e[!] §7suspect §c[X] §7proven. Hover an alert for details, click it for history.")
            return 1
        }
        // subcommand?
        val sub = subcommands.firstOrNull { it.first.equals(topic, ignoreCase = true) }
        if (sub != null) { send(ctx, "$tag §f${sub.first} §7— ${sub.second}"); return 1 }
        // check?
        if (topic in checkIds) {
            val cc = ConfigManager.config.slice(topic)
            val def = if (CheckInfo.isDefinitive(topic)) " §7(definitive → §cred nametag§7)" else ""
            send(ctx, "$tag §f$topic$def")
            send(ctx, " §7${CheckInfo.describe(topic)}")
            send(ctx, " §7enabled=${cc.enabled} §7setbackVL=${cc.setbackVL} §7decay=${cc.decay} §7threshold=${cc.threshold}")
            return 1
        }
        send(ctx, "$tag §cunknown topic: $topic §7(try a subcommand or check id; /ius help lists them).")
        return 0
    }

    // ---- alerts (mute) ----
    /** Bare `/ius alerts` (no args) — toggles ALL chat alerts on/off (a global chat mute).
     *  Detection, tiering and the nametag prefix keep running; only the chat lines are silenced.
     *  Reports the new state and the per-check / per-player mutes for reference. */
    private fun alertsList(ctx: CommandContext<FabricClientCommandSource>): Int {
        val cfg = ConfigManager.config
        cfg.alertsEnabled = !cfg.alertsEnabled
        ConfigManager.save()
        val state = if (cfg.alertsEnabled) "§aON" else "§cOFF (muted)"
        send(ctx, "$tag §7chat alerts: $state")
        send(ctx, "$tag §7muted checks: §f${cfg.mutedChecks.joinToString(", ").ifEmpty { "(none)" }}")
        val playerNames = cfg.mutedPlayers.joinToString(", ") { uuid ->
            try { FlagHistory.nameFor(UUID.fromString(uuid)) ?: uuid.take(8) }
            catch (_: Throwable) { uuid.take(8) }
        }
        send(ctx, "$tag §7muted players: §f${playerNames.ifEmpty { "(none)" }}")
        send(ctx, "§7detection/tier keep running — only chat is silenced. Per-check: /ius alerts <check>")
        return 1
    }

    private fun alertsSet(ctx: CommandContext<FabricClientCommandSource>, stateArg: String?): Int {
        val target = StringArgumentType.getString(ctx, "target")
        val cfg = ConfigManager.config
        val wantOn = when (stateArg?.lowercase()) {
            "on" -> true
            "off" -> false
            else -> null  // toggle
        }
        // check id?
        val checkMatch = checkIds.firstOrNull { it.equals(target, ignoreCase = true) }
        if (checkMatch != null) {
            val nowOn = when (wantOn) {
                true -> { if (checkMatch !in cfg.mutedChecks) cfg.mutedChecks.add(checkMatch); true }
                false -> { cfg.mutedChecks.remove(checkMatch); false }
                null -> if (checkMatch in cfg.mutedChecks) { cfg.mutedChecks.remove(checkMatch); false } else { cfg.mutedChecks.add(checkMatch); true }
            }
            ConfigManager.save()
            send(ctx, "$tag §7check §f$checkMatch §7chat-alerts ${if (nowOn) "§cOFF (muted)" else "§aON"}")
            return 1
        }
        // player name?
        val uuid = FlagHistory.resolveName(target)
        if (uuid == null) { send(ctx, "$tag §cunknown target: §f$target§7 (not a check id or known player)."); return 0 }
        val key = uuid.toString()
        val nowOn = when (wantOn) {
            true -> { if (key !in cfg.mutedPlayers) cfg.mutedPlayers.add(key); false /*muted*/ }
            false -> { cfg.mutedPlayers.remove(key); true }
            null -> if (key in cfg.mutedPlayers) { cfg.mutedPlayers.remove(key); true } else { cfg.mutedPlayers.add(key); false }
        }
        ConfigManager.save()
        // nowOn = chat alerts ON (unmuted) ; false = muted
        send(ctx, "$tag §7player §f$target §7chat-alerts ${if (nowOn) "§aON" else "§cOFF (muted)"}")
        return 1
    }

    // ---- tab-completion helpers ----
    private fun suggestNames(b: SuggestionsBuilder) {
        val names = LinkedHashSet<String>()
        try { FlagHistory.knownNames().forEach { names.add(it) } } catch (_: Throwable) {}
        try { EntityTrackerManager.all().forEach { tp -> tp.username().takeIf { it.isNotEmpty() }?.let { names.add(it) } } } catch (_: Throwable) {}
        names.forEach { b.suggest(it) }
    }

    private fun suggestTargets(b: SuggestionsBuilder) {
        checkIds.forEach { b.suggest(it) }
        suggestNames(b)
    }

    private fun suggestHelpTopics(b: SuggestionsBuilder) {
        subcommands.forEach { b.suggest(it.first) }
        checkIds.forEach { b.suggest(it) }
    }
}
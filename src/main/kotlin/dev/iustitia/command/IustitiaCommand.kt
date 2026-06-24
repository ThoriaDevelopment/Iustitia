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
import dev.iustitia.history.Evidence
import dev.iustitia.info.CheckInfo
import dev.iustitia.info.FeatureInfo
import dev.iustitia.persistence.NoteStore
import dev.iustitia.persistence.PersistenceManager
import dev.iustitia.protocol.ProtocolDetector
import dev.iustitia.session.SessionStats
import dev.iustitia.session.Snapshot
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.ui.KeybindHubScreen
import dev.iustitia.ui.PlayerHistoryScreen
import dev.iustitia.ui.PlayerSearchScreen
import dev.iustitia.ui.SessionScreen
import dev.iustitia.ui.SetupWizardScreen
import dev.iustitia.ui.TranscriptPanelScreen
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
        "report" to "copy a player's report card to clipboard: /ius report <name> [markdown|json]",
        "transcript" to "print a player's session timeline: /ius transcript <name>  (or /ius transcript panel [name] to toggle the side panel)",
        "evidence" to "one chat line of a player's last few seconds of flags: /ius evidence <name>",
        "note" to "moderator tag a player: /ius note <name> <closet|blatant|needsReview|legit> <text...>  (or /ius note <name> to view)",
        "session" to "session summary: tracked players, tier counts, who peaked highest",
        "snapshot" to "evidence snapshot of your crosshair target: /ius snapshot [name]",
        "spectate" to "watch follow-cam on a player: /ius spectate [name]  (no name = crosshair target; /ius spectate off to stop; same as the watch keybind)",
        "wizard" to "re-run the first-launch setup wizard",
        "keybinds" to "open the keybind hub screen (lists binds, highlights conflicts)",
        "help" to "this help, or /ius help <check|subcommand|feature>",
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
                        .suggests { _, b -> suggestFiltered(b, checkIds); b.buildFuture() }
                        .executes { histPlayer(it, StringArgumentType.getString(it, "check")) })))
            .then(ClientCommandManager.literal("report")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .suggests { _, b -> suggestNames(b); b.buildFuture() }
                    .executes { report(it, "markdown") }
                    .then(ClientCommandManager.argument("format", StringArgumentType.word())
                        .suggests { _, b -> suggestFiltered(b, listOf("markdown", "json")); b.buildFuture() }
                        .executes { report(it, StringArgumentType.getString(it, "format")) })))
            .then(ClientCommandManager.literal("transcript")
                .executes { transcriptToggle(it) }
                .then(ClientCommandManager.literal("panel")
                    .executes { transcriptToggle(it) }
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .suggests { _, b -> suggestNames(b); b.buildFuture() }
                        .executes { transcriptPanelNamed(it) }))
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .suggests { _, b -> suggestNames(b); b.buildFuture() }
                    .executes { transcriptPrint(it) }))
            .then(ClientCommandManager.literal("evidence")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .suggests { _, b -> suggestNames(b); b.buildFuture() }
                    .executes { evidence(it) }))
            .then(ClientCommandManager.literal("note")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .suggests { _, b -> suggestNames(b); b.buildFuture() }
                    .executes { noteShow(it) }
                    .then(ClientCommandManager.argument("category", StringArgumentType.word())
                        .suggests { _, b -> suggestFiltered(b, listOf("closet", "blatant", "needsReview", "legit")); b.buildFuture() }
                        .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                            .executes { noteSet(it) }))))
            .then(ClientCommandManager.literal("session")
                .executes { session(it) }
                .then(ClientCommandManager.literal("screen").executes { sessionScreen(it) }))
            .then(ClientCommandManager.literal("snapshot")
                .executes { snapshot(it, null) }
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .suggests { _, b -> suggestNames(b); b.buildFuture() }
                    .executes { snapshot(it, StringArgumentType.getString(it, "name")) }))
            .then(ClientCommandManager.literal("spectate")
                .executes { spectate(it, null) }
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .suggests { _, b -> suggestNames(b); b.buildFuture() }
                    .executes { spectate(it, StringArgumentType.getString(it, "name")) }))
            .then(ClientCommandManager.literal("wizard").executes { wizard(it) })
            .then(ClientCommandManager.literal("keybinds").executes { keybinds(it) })
            .then(ClientCommandManager.literal("alerts")
                .executes { alertsList(it) }
                .then(ClientCommandManager.argument("target", StringArgumentType.word())
                    .suggests { _, b -> suggestTargets(b); b.buildFuture() }
                    .executes { alertsSet(it, null) }
                    .then(ClientCommandManager.argument("state", StringArgumentType.word())
                        .suggests { _, b -> suggestFiltered(b, listOf("on", "off")); b.buildFuture() }
                        .executes { alertsSet(it, StringArgumentType.getString(it, "state")) })))
            .then(ClientCommandManager.literal("toggle")
                .executes { toggleUsage(it) }
                .then(ClientCommandManager.argument("check", StringArgumentType.word())
                    .suggests { _, b -> suggestFiltered(b, checkIds); b.buildFuture() }
                    .executes { toggle(it) }))
            .then(ClientCommandManager.literal("threshold")
                .executes { thresholdUsage(it) }
                .then(ClientCommandManager.argument("check", StringArgumentType.word())
                    .suggests { _, b -> suggestFiltered(b, checkIds); b.buildFuture() }
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
        val persist = ConfigManager.config.persistenceEnabled
        send(ctx, "$tag §7live state reset (detection vl, alerts, session stats cleared; nametags back to green)." +
            if (persist) " §7Persisted tier/flag history + notes reloaded from §f%APPDATA%/.iustitia§7." else "")
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
    /** Bare `/ius hist` — opens the searchable player list (#1). Mirrors the [openConfig]
     *  `MinecraftClient.execute{}` + fail-open pattern; falls back to the chat top-offenders
     *  dump if the screen can't be opened (e.g. opened from a non-foreground context). */
    private fun histTop(ctx: CommandContext<FabricClientCommandSource>): Int {
        val mc = MinecraftClient.getInstance()
        mc.execute {
            try {
                mc.setScreen(PlayerSearchScreen(mc.currentScreen))
            } catch (_: Throwable) {
                histTopChat(ctx)
            }
        }
        return 1
    }

    private fun histTopChat(ctx: CommandContext<FabricClientCommandSource>): Int {
        val top = FlagHistory.topOffenders(8)
        if (top.isEmpty()) { send(ctx, "$tag §7no alerts yet this session."); return 1 }
        send(ctx, "$tag §7top offenders (by alert count):")
        top.forEach { (name, count) -> send(ctx, " §f$name §7$count") }
        return 1
    }

    /** `/ius hist <name> [check]` — resolves the player (by flagged name, then by a
     *  tracked-player name match for fresh joins) and opens [PlayerHistoryScreen] (#1 + #2).
     *  Falls back to the existing chat dump if the uuid can't be resolved or the screen
     *  can't open, so the command never regresses. */
    private fun histPlayer(ctx: CommandContext<FabricClientCommandSource>, checkFilter: String?): Int {
        val name = StringArgumentType.getString(ctx, "name")
        var uuid = FlagHistory.resolveName(name)
        if (uuid == null) {
            // fresh join: tracked but not yet flagged — match by live username
            uuid = try {
                EntityTrackerManager.all().firstOrNull { it.username().equals(name, ignoreCase = true) }?.uuid
            } catch (_: Throwable) { null }
        }
        if (uuid == null) { send(ctx, "$tag §cno history for §f$name§7 (must be tracked or have flagged first)."); return 0 }
        val mc = MinecraftClient.getInstance()
        mc.execute {
            try {
                mc.setScreen(PlayerHistoryScreen(uuid, mc.currentScreen))
            } catch (_: Throwable) {
                histPlayerChat(ctx, uuid, name, checkFilter)
            }
        }
        return 1
    }

    /** Chat fallback for [histPlayer] — used if the GUI fails to open. Keeps the
     *  pre-GUI `/ius hist <name>` behavior intact as a safety net. */
    private fun histPlayerChat(ctx: CommandContext<FabricClientCommandSource>, uuid: UUID, name: String, checkFilter: String?): Int {
        val flags = FlagHistory.flags(uuid)
        if (flags.isEmpty()) { send(ctx, "$tag §7no flags recorded for §f$name§7."); return 1 }
        val filtered = if (checkFilter == null) flags else flags.filter { it.checkId == checkFilter }
        if (filtered.isEmpty()) { send(ctx, "$tag §7no §f$checkFilter§7 flags for §f$name§7."); return 1 }
        val distinctChecks = filtered.map { it.checkId }.distinct().size
        send(ctx, "$tag §f$name §7— §f${filtered.size}§7 flag(s) across §f$distinctChecks§7 check(s):")
        filtered.take(20).forEach { f ->
            send(ctx, " §7@t${f.tick} §f${f.label} §8(${f.checkId}) §evl=${fmt(f.vl)}")
        }
        return 1
    }

    // ---- report (#9) ----
    /** `/ius report <name> [format]` — builds a report card from the FlagHistory aggregators
     *  (same data as [PlayerHistoryScreen]) and copies it to the clipboard. `format` defaults
     *  to `markdown`; `json` emits the same data as a JSON object. Never sends anything to the
     *  server (clipboard is client-only). Fail-open. */
    private fun report(ctx: CommandContext<FabricClientCommandSource>, format: String): Int {
        val name = StringArgumentType.getString(ctx, "name")
        var uuid = FlagHistory.resolveName(name)
        if (uuid == null) {
            uuid = try {
                EntityTrackerManager.all().firstOrNull { it.username().equals(name, ignoreCase = true) }?.uuid
            } catch (_: Throwable) { null }
        }
        if (uuid == null) { send(ctx, "$tag §cno history for §f$name§7 (must be tracked or have flagged first)."); return 0 }
        val fmt = if (format.equals("json", ignoreCase = true)) "json" else "markdown"
        val text = try { if (fmt == "json") reportJson(uuid, name) else reportMarkdown(uuid, name) } catch (_: Throwable) {
            send(ctx, "$tag §cfailed to build report for §f$name§7."); return 0
        }
        try { MinecraftClient.getInstance().keyboard.setClipboard(text) } catch (_: Throwable) {
            send(ctx, "$tag §cfailed to write clipboard."); return 0
        }
        send(ctx, "$tag §7report for §f$name§7 copied to clipboard §8(${text.length} chars, $fmt)")
        return 1
    }

    private fun reportMarkdown(uuid: UUID, name: String): String {
        val sb = StringBuilder()
        val tier = FlagHistory.tierFor(uuid)
        val tierName = when (tier) {
            FlagHistory.Tier.GREEN -> "GREEN"; FlagHistory.Tier.YELLOW -> "YELLOW"; FlagHistory.Tier.RED -> "RED"
        }
        val sp = FlagHistory.span(uuid)
        val alerts = FlagHistory.sessionAlertCount(uuid)
        val counts = FlagHistory.flagCounts(uuid)
        val maxVlMap = FlagHistory.maxVlByCheck(uuid)
        val totalFlags = counts.values.sum()
        val maxVl = maxVlMap.values.maxOrNull() ?: 0.0
        val spanTxt = if (sp == null) "no flags" else "first flag @t${sp.first} | last flag @t${sp.second}"
        sb.append("# Iustitia report — $name\n\n")
        sb.append("tier: $tierName | $spanTxt | alerts: $alerts | flags: $totalFlags | max vl: ${fmt(maxVl)}\n")
        sb.append("confidence: ${FlagHistory.confidenceLine(uuid)}\n")
        val topCheck = FlagHistory.topCheck(uuid)
        if (topCheck != null) sb.append("top check: $topCheck\n")
        sb.append("\n## Flags by check (count / max vl)\n")
        if (counts.isEmpty()) sb.append("(no flags this session)\n")
        counts.forEach { (cid, c) ->
            val mv = maxVlMap[cid] ?: 0.0
            sb.append("- $cid: $c flags, max vl ${fmt(mv)}\n")
        }
        sb.append("\n## Timeline (last 50)\n")
        val flags = FlagHistory.flags(uuid)
        if (flags.isEmpty()) sb.append("(no flags recorded)\n")
        flags.forEach { f ->
            val ev = f.evidence
            val evTxt = if (ev == null) "" else " " + evidenceMd(ev)
            sb.append("@t${f.tick} ${f.checkId} (${f.label}) vl=${fmt(f.vl)}$evTxt\n")
        }
        return sb.toString()
    }

    private fun reportJson(uuid: UUID, name: String): String {
        val sb = StringBuilder()
        val tier = FlagHistory.tierFor(uuid)
        val tierName = when (tier) {
            FlagHistory.Tier.GREEN -> "GREEN"; FlagHistory.Tier.YELLOW -> "YELLOW"; FlagHistory.Tier.RED -> "RED"
        }
        val sp = FlagHistory.span(uuid)
        val counts = FlagHistory.flagCounts(uuid)
        val maxVlMap = FlagHistory.maxVlByCheck(uuid)
        sb.append("{\n")
        sb.append("  \"name\": ").append(jsonStr(name)).append(",\n")
        sb.append("  \"uuid\": ").append(jsonStr(uuid.toString())).append(",\n")
        sb.append("  \"tier\": ").append(jsonStr(tierName)).append(",\n")
        sb.append("  \"alerts\": ").append(FlagHistory.sessionAlertCount(uuid)).append(",\n")
        sb.append("  \"flags\": ").append(counts.values.sum()).append(",\n")
        sb.append("  \"maxVl\": ").append(fmt(maxVlMap.values.maxOrNull() ?: 0.0)).append(",\n")
        sb.append("  \"confidence\": ").append(jsonStr(FlagHistory.confidenceLine(uuid))).append(",\n")
        if (sp != null) sb.append("  \"firstTick\": ").append(sp.first).append(", \"lastTick\": ").append(sp.second).append(",\n")
        sb.append("  \"byCheck\": {")
        if (counts.isEmpty()) sb.append("},\n") else {
            sb.append("\n")
            counts.entries.forEachIndexed { i, e ->
                val mv = maxVlMap[e.key] ?: 0.0
                sb.append("    ").append(jsonStr(e.key)).append(": {\"count\": ").append(e.value)
                    .append(", \"maxVl\": ").append(fmt(mv)).append("}")
                sb.append(if (i == counts.size - 1) "\n  },\n" else ",\n")
            }
        }
        sb.append("  \"timeline\": [")
        val flags = FlagHistory.flags(uuid)
        if (flags.isEmpty()) sb.append("]\n") else {
            sb.append("\n")
            flags.forEachIndexed { i, f ->
                sb.append("    {\"tick\": ").append(f.tick)
                    .append(", \"check\": ").append(jsonStr(f.checkId))
                    .append(", \"label\": ").append(jsonStr(f.label))
                    .append(", \"vl\": ").append(fmt(f.vl))
                val ev = f.evidence
                if (ev != null) sb.append(", ").append(evidenceJson(ev))
                sb.append("}")
                sb.append(if (i == flags.size - 1) "\n  ]\n" else ",\n")
            }
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun evidenceMd(e: Evidence): String {
        val parts = ArrayList<String>()
        e.subLabel?.let { parts += it }
        if (e.measurement != null || e.threshold != null) parts += "${e.measurement ?: "?"}/${e.threshold ?: "?"}"
        e.pos?.let { parts += "pos=(${it.x.toInt()},${it.y.toInt()},${it.z.toInt()})" }
        e.victim?.let { parts += "victim=" + (FlagHistory.nameFor(it) ?: it.toString().take(8)) }
        e.extra?.let { parts += it }
        return parts.joinToString(" · ")
    }

    private fun evidenceJson(e: Evidence): String {
        val sb = StringBuilder("\"evidence\": {")
        val kvs = ArrayList<String>()
        e.subLabel?.let { kvs += "\"subLabel\": " + jsonStr(it) }
        e.measurement?.let { kvs += "\"measurement\": " + fmt(it, 4) }
        e.threshold?.let { kvs += "\"threshold\": " + fmt(it, 4) }
        e.pos?.let { kvs += "\"pos\": [${it.x}, ${it.y}, ${it.z}]" }
        e.victim?.let { kvs += "\"victim\": " + jsonStr(it.toString()) }
        e.extra?.let { kvs += "\"extra\": " + jsonStr(it) }
        sb.append(kvs.joinToString(", ")).append("}")
        return sb.toString()
    }

    private fun jsonStr(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        return "\"$escaped\""
    }

    /** Locale-stable decimal formatting for clipboard/JSON/Markdown output. `"%.2f".format(x)`
     *  uses the default locale and emits a comma decimal in e.g. de_DE / fr_FR, which breaks
     *  JSON parsing (`"vl": 1,5` is two tokens) and corrupts the Markdown report. Force
     *  [java.util.Locale.US] so the output is machine-stable on every client locale. */
    private fun fmt(v: Double, digits: Int = 2): String =
        String.format(java.util.Locale.US, "%.${digits}f", v)

    // ---- shared resolver ----
    private fun resolveUuid(name: String): java.util.UUID? {
        var uuid = FlagHistory.resolveName(name)
        if (uuid == null) uuid = try {
            EntityTrackerManager.all().firstOrNull { it.username().equals(name, ignoreCase = true) }?.uuid
        } catch (_: Throwable) { null }
        return uuid
    }

    /** The other player currently under the crosshair (for snapshot/transcript/snapshot keybinds). */
    private fun crosshairTarget(): Pair<java.util.UUID, String>? = try {
        val client = MinecraftClient.getInstance()
        val hit = client.crosshairTarget
        val ent = (hit as? net.minecraft.util.hit.EntityHitResult)?.entity
        val other = ent as? net.minecraft.client.network.OtherClientPlayerEntity
        if (other != null) other.uuid to (other.name.string.ifEmpty { other.uuid.toString().take(8) }) else null
    } catch (_: Throwable) { null }

    // ---- transcript (#4) ----
    private fun transcriptPrint(ctx: CommandContext<FabricClientCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val uuid = resolveUuid(name)
        if (uuid == null) { send(ctx, "$tag §cno data for §f$name§7 (must be tracked or have flagged first)."); return 0 }
        val text = try { buildTranscript(uuid, name) } catch (_: Throwable) {
            send(ctx, "$tag §cfailed to build transcript for §f$name§7."); return 0 }
        send(ctx, text)
        try { if (ConfigManager.config.persistenceEnabled) PersistenceManager.saveExport("transcript", name, text) } catch (_: Throwable) {}
        send(ctx, "$tag §7transcript for §f$name§7 printed §8(paste into a report)")
        return 1
    }

    private fun buildTranscript(uuid: java.util.UUID, name: String): String {
        val sb = StringBuilder()
        val tier = FlagHistory.tierFor(uuid)
        val tierName = when (tier) { FlagHistory.Tier.GREEN -> "GREEN"; FlagHistory.Tier.YELLOW -> "YELLOW"; FlagHistory.Tier.RED -> "RED" }
        val score = FlagHistory.confidenceScore(uuid)
        val st = SessionStats.stats(uuid)
        sb.append("=== Iustitia transcript: $name (tier $tierName [$score]) ===\n")
        sb.append("session: swings=${st.swings} hits=${st.hits} velocity=${st.velocity}\n")
        sb.append("alerts=${FlagHistory.sessionAlertCount(uuid)} flags=${FlagHistory.flagCounts(uuid).values.sum()}")
        FlagHistory.topCheck(uuid)?.let { sb.append(" top=$it") }
        sb.append("\n")
        NoteStore.get(uuid)?.let { n -> sb.append("note: ${n.category.name.lowercase()} (\"${n.text}\")\n") }
        sb.append("timeline (last ${FlagHistory.flags(uuid).size} flags):\n")
        val flags = FlagHistory.flags(uuid)
        if (flags.isEmpty()) sb.append("(no flags recorded)\n")
        flags.forEach { f ->
            sb.append(" @t${f.tick} ${f.checkId} (${f.label}) vl=${fmt(f.vl)}")
            f.evidence?.let { ev ->
                val parts = ArrayList<String>()
                ev.subLabel?.let { parts += it }
                if (ev.measurement != null || ev.threshold != null) parts += "${ev.measurement ?: "?"}/${ev.threshold ?: "?"}"
                ev.victim?.let { parts += "victim=" + (FlagHistory.nameFor(it) ?: it.toString().take(8)) }
                ev.extra?.let { parts += it }
                if (parts.isNotEmpty()) sb.append(" ").append(parts.joinToString(" · "))
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun transcriptToggle(ctx: CommandContext<FabricClientCommandSource>): Int {
        val mc = MinecraftClient.getInstance()
        try {
            if (mc.currentScreen is TranscriptPanelScreen) { mc.setScreen(null); send(ctx, "$tag §7transcript panel closed."); return 1 }
            val target = crosshairTarget()
            if (target == null) { send(ctx, "$tag §7look at a player to open their transcript panel (or use §f/ius transcript panel <name>§7)."); return 0 }
            mc.execute { try { mc.setScreen(TranscriptPanelScreen(target.first, target.second, null)) } catch (_: Throwable) {} }
            send(ctx, "$tag §7transcript panel: §f${target.second}")
        } catch (_: Throwable) { send(ctx, "$tag §cfailed to toggle transcript panel."); }
        return 1
    }

    /** `/ius transcript panel <name>` — open the side panel for a named player (no crosshair needed). */
    private fun transcriptPanelNamed(ctx: CommandContext<FabricClientCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val uuid = resolveUuid(name)
        if (uuid == null) { send(ctx, "$tag §cno data for §f$name§7 (must be tracked or have flagged first)."); return 0 }
        val mc = MinecraftClient.getInstance()
        mc.execute { try { mc.setScreen(TranscriptPanelScreen(uuid, name, null)) } catch (_: Throwable) {} }
        send(ctx, "$tag §7transcript panel: §f$name")
        return 1
    }

    // ---- evidence (#5) ----
    private fun evidence(ctx: CommandContext<FabricClientCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val uuid = resolveUuid(name)
        if (uuid == null) { send(ctx, "$tag §cno data for §f$name§7."); return 0 }
        val window = ConfigManager.config.evidenceWindowTicks
        val now = dev.iustitia.Iustitia.tickCounter
        val recent = try { FlagHistory.flags(uuid).filter { now - it.tick <= window } } catch (_: Throwable) { emptyList() }
        if (recent.isEmpty()) { send(ctx, "$tag §7no flags for §f$name§7 in the last ${window / 20}s."); return 1 }
        val grouped = recent.groupBy { it.checkId }.toList().sortedByDescending { it.second.size }
        val parts = grouped.take(5).map { (cid, list) ->
            val maxVl = list.maxOf { it.vl }
            val meas = list.mapNotNull { it.evidence?.measurement }.maxOrNull()
            val mv = if (meas != null) " ${fmt(meas)}" else ""
            if (list.size > 1) "$cid$mv ×${list.size}" else "$cid$mv"
        }
        val tier = FlagHistory.tierFor(uuid)
        val tierName = when (tier) { FlagHistory.Tier.GREEN -> "GREEN"; FlagHistory.Tier.YELLOW -> "YELLOW"; FlagHistory.Tier.RED -> "RED" }
        val line = "$tag §f$name §7(last ${window / 20}s): §e${parts.joinToString(", ")} §7| Tier: §f$tierName §7[${FlagHistory.confidenceScore(uuid)}]"
        send(ctx, line)
        try { if (ConfigManager.config.persistenceEnabled) PersistenceManager.saveExport("evidence", name, line) } catch (_: Throwable) {}
        return 1
    }

    // ---- note (#8) ----
    private fun noteShow(ctx: CommandContext<FabricClientCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val uuid = resolveUuid(name) ?: run { send(ctx, "$tag §cunknown player: §f$name§7."); return 0 }
        val note = NoteStore.get(uuid)
        if (note == null) { send(ctx, "$tag §f$name §7has no note."); return 1 }
        send(ctx, "$tag §f$name §7— ${NoteStore.categoryLabel(note.category)}§7: §f${note.text}")
        return 1
    }

    private fun noteSet(ctx: CommandContext<FabricClientCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val catRaw = StringArgumentType.getString(ctx, "category")
        val text = StringArgumentType.getString(ctx, "text")
        val cat = NoteStore.parseCategory(catRaw)
        if (cat == null) { send(ctx, "$tag §cunknown category: §f$catRaw§7 (closet / blatant / needsReview / legit)."); return 0 }
        val uuid = resolveUuid(name) ?: run { send(ctx, "$tag §cunknown player: §f$name§7."); return 0 }
        NoteStore.set(uuid, name, cat, text, dev.iustitia.Iustitia.tickCounter)
        send(ctx, "$tag §7noted §f$name §7as ${NoteStore.categoryLabel(cat)}§7: §f$text")
        return 1
    }

    // ---- session (#12) ----
    private fun session(ctx: CommandContext<FabricClientCommandSource>): Int {
        val uuids = LinkedHashSet<java.util.UUID>()
        try { EntityTrackerManager.all().forEach { uuids.add(it.uuid) } } catch (_: Throwable) {}
        try { FlagHistory.knownUuids().forEach { uuids.add(it) } } catch (_: Throwable) {}
        var green = 0; var yellow = 0; var red = 0
        var peakName: String? = null; var peakScore = -1
        for (u in uuids) {
            when (FlagHistory.tierFor(u)) {
                FlagHistory.Tier.GREEN -> green++
                FlagHistory.Tier.YELLOW -> yellow++
                FlagHistory.Tier.RED -> red++
            }
            val s = try { FlagHistory.confidenceScore(u) } catch (_: Throwable) { 0 }
            if (s > peakScore) { peakScore = s; peakName = FlagHistory.nameFor(u) ?: u.toString().take(8) }
        }
        send(ctx, "$tag §7session summary")
        send(ctx, " §7players tracked: §f${uuids.size}")
        send(ctx, " §aGREEN §f$green §7| §eYELLOW §f$yellow §7| §cRED §f$red")
        send(ctx, " §7alerts this session: §f${FlagHistory.totalAlerts}")
        if (peakName != null && peakScore > 0) send(ctx, " §7peaked highest: §f$peakName §7[$peakScore]")
        return 1
    }

    private fun sessionScreen(ctx: CommandContext<FabricClientCommandSource>): Int {
        val mc = MinecraftClient.getInstance()
        mc.execute { try { mc.setScreen(SessionScreen(mc.currentScreen)) } catch (_: Throwable) {} }
        return 1
    }

    // ---- snapshot (#3) ----
    private fun snapshot(ctx: CommandContext<FabricClientCommandSource>, nameArg: String?): Int {
        val target: Pair<java.util.UUID, String>? = if (nameArg != null) {
            val u = resolveUuid(nameArg); if (u != null) u to nameArg else null
        } else crosshairTarget()
        if (target == null) { send(ctx, "$tag §7look at a player (or use §f/ius snapshot <name>§7)."); return 0 }
        Snapshot.capture(target.first, target.second)
        send(ctx, "$tag §7snapshot of §f${target.second}§7 posted + copied to clipboard")
        return 1
    }

    // ---- spectate (watch follow-cam command form; same as the `watch` keybind) ----
    /** `/ius spectate [name]` — start the watch follow-cam on the named player, or the crosshair
     *  target when no name is given. `/ius spectate off` stops it. Bare `/ius spectate` toggles
     *  (stops if already watching). Mirrors the `watch` keybind but works by name without needing
     *  the target under the crosshair. The player must be currently loaded/rendered for the camera
     *  to position on them; switching targets mid-watch restores the saved HUD/perspective state
     *  first so the forced state isn't captured as the new baseline. Fail-open, client-thread only. */
    private fun spectate(ctx: CommandContext<FabricClientCommandSource>, nameArg: String?): Int {
        val mc = MinecraftClient.getInstance()
        val active = try { dev.iustitia.render.WatchState.active } catch (_: Throwable) { false }
        // Explicit stop.
        if (nameArg != null && nameArg.equals("off", ignoreCase = true)) {
            if (active) {
                val reason = dev.iustitia.render.WatchState.disableNow("disabled")
                send(ctx, "$tag §7watch follow-cam §c$reason§7 — view restored.")
            } else {
                send(ctx, "$tag §7not watching anyone.")
            }
            return 1
        }
        if (!ConfigManager.config.watchFollowCam) {
            send(ctx, "$tag §7watch follow-cam is §cdisabled§7 in config (enable it via §f/ius config§7).")
            return 0
        }
        // Bare command while already watching → toggle off (press-again semantics).
        if (nameArg == null && active) {
            val reason = dev.iustitia.render.WatchState.disableNow("disabled")
            send(ctx, "$tag §7watch follow-cam §c$reason§7 — view restored.")
            return 1
        }
        val target: Pair<java.util.UUID, String>? = if (nameArg != null) {
            resolveUuid(nameArg)?.let { it to nameArg }
        } else crosshairTarget()
        if (target == null) {
            send(ctx, if (nameArg == null)
                "$tag §7look at a player (or use §f/ius spectate <name>§7) to watch them."
                else "$tag §cno data for §f$nameArg§7 (must be tracked or have flagged first).")
            return 0
        }
        // The watched player must be currently loaded so the camera can position on them; if not,
        // the render-thread target-gone path would just immediately cancel the watch.
        val loaded = try { mc.world?.getPlayerByUuid(target.first) != null } catch (_: Throwable) { false }
        if (!loaded) {
            send(ctx, "$tag §c${target.second} §7isn't currently rendered — can't watch (move closer / unloaded).")
            return 0
        }
        // Switching from another target: restore the saved state first so the forced HUD/perspective
        // isn't re-saved as the new baseline (would lose the user's original view on exit).
        if (active) { try { dev.iustitia.render.WatchState.disableNow("switched") } catch (_: Throwable) {} }
        dev.iustitia.render.WatchState.enable(target.first)
        send(ctx, "$tag §7watching §f${target.second}§7 — orbit follow-cam §aON§7. Mouse to look around; move or get hit to stop (or §f/ius spectate off§7).")
        return 1
    }

    // ---- wizard (#13) + keybinds (#14) ----
    private fun wizard(ctx: CommandContext<FabricClientCommandSource>): Int {
        val mc = MinecraftClient.getInstance()
        mc.execute { try { mc.setScreen(SetupWizardScreen(null)) } catch (_: Throwable) {} }
        return 1
    }

    private fun keybinds(ctx: CommandContext<FabricClientCommandSource>): Int {
        val mc = MinecraftClient.getInstance()
        mc.execute { try { mc.setScreen(KeybindHubScreen(mc.currentScreen)) } catch (_: Throwable) {} }
        return 1
    }

    // ---- help ----
    private fun help(ctx: CommandContext<FabricClientCommandSource>, topic: String?): Int {
        if (topic == null) {
            send(ctx, "$tag §7commands (alias: §f/ius§7):")
            subcommands.forEach { (s, d) -> send(ctx, " §f$s §7— $d") }
            send(ctx, CheckInfo.SEVERITY_LEGEND)
            send(ctx, "§7nametag: §a[+] §7clean §e[!] §7suspect (≥1 red-capable or lone killAura) §c[X] §7caught (≥2 distinct)§7. Fades one tier per ~10 min idle. Hover an alert for details, click it for history.")
            return 1
        }
        // subcommand?
        val sub = subcommands.firstOrNull { it.first.equals(topic, ignoreCase = true) }
        if (sub != null) { send(ctx, "$tag §f${sub.first} §7— ${sub.second}"); return 1 }
        // check?
        if (topic in checkIds) {
            val cc = ConfigManager.config.slice(topic)
            val tier = when {
                CheckInfo.isDefinitive(topic) -> " §7(red-capable → §eyellow§7/§cred§7 nametag; §cred§7 needs ≥2 distinct)"
                topic == "killAura" -> " §7(corroborator → counts toward §cred§7 only with another red-capable alert)"
                else -> ""
            }
            send(ctx, "$tag §f$topic$tier")
            send(ctx, " §7${CheckInfo.describe(topic)}")
            send(ctx, " §7enabled=${cc.enabled} §7setbackVL=${cc.setbackVL} §7decay=${cc.decay} §7threshold=${cc.threshold}")
            return 1
        }
        // feature? (transcript/evidence/note/session/snapshot/wizard/keybinds/compact/hist/report/alerts/watch)
        val feature = FeatureInfo.describe(topic)
        if (feature != null) { send(ctx, "$tag §f$topic §7— $feature"); return 1 }
        send(ctx, "$tag §cunknown topic: $topic §7(try a subcommand, check id, or feature; /ius help lists them).")
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
            // `wantOn` is the user's intent: "on" = chat alerts ON (unmute), "off" = mute.
            // nowOn=true ⟺ alerts ON (not in the muted set); false ⟺ muted. Both branches and
            // the toggle (null) share this one semantics, and the display line below reads the
            // same way for checks and players. (The prior code inverted this: typing "on"
            // *muted* the check, and the check- and player-paths even disagreed on what nowOn
            // meant — so `/ius alerts reach on` silently muted reach.)
            val nowOn = when (wantOn) {
                true  -> { cfg.mutedChecks.remove(checkMatch); true }
                false -> { if (checkMatch !in cfg.mutedChecks) cfg.mutedChecks.add(checkMatch); false }
                null  -> if (checkMatch in cfg.mutedChecks) { cfg.mutedChecks.remove(checkMatch); true }
                         else { cfg.mutedChecks.add(checkMatch); false }
            }
            ConfigManager.save()
            send(ctx, "$tag §7check §f$checkMatch §7chat-alerts ${if (nowOn) "§aON" else "§cOFF (muted)"}")
            return 1
        }
        // player name?
        val uuid = FlagHistory.resolveName(target)
        if (uuid == null) { send(ctx, "$tag §cunknown target: §f$target§7 (not a check id or known player)."); return 0 }
        val key = uuid.toString()
        // Same semantics as the check path: nowOn=true ⟺ alerts ON (unmuted), false ⟺ muted.
        val nowOn = when (wantOn) {
            true  -> { cfg.mutedPlayers.remove(key); true }
            false -> { if (key !in cfg.mutedPlayers) cfg.mutedPlayers.add(key); false }
            null  -> if (key in cfg.mutedPlayers) { cfg.mutedPlayers.remove(key); true }
                     else { cfg.mutedPlayers.add(key); false }
        }
        ConfigManager.save()
        send(ctx, "$tag §7player §f$target §7chat-alerts ${if (nowOn) "§aON" else "§cOFF (muted)"}")
        return 1
    }

    // ---- tab-completion helpers ----
    /**
     * Suggest each value only if it starts with the user's current partial input
     * ([SuggestionsBuilder.remaining]). Brigadier's `suggest(String)` in this version does NOT
     * auto-filter by the typed prefix, so without this `/ius report M` would show every known name
     * instead of just the M-names, and typing `MA` wouldn't narrow it. Case-insensitive.
     */
    private fun suggestFiltered(b: SuggestionsBuilder, values: Collection<String>) {
        val q = b.remaining
        for (v in values) {
            if (q.isEmpty() || v.startsWith(q, ignoreCase = true)) b.suggest(v)
        }
    }

    private fun suggestNames(b: SuggestionsBuilder) {
        val names = LinkedHashSet<String>()
        try { FlagHistory.knownNames().forEach { names.add(it) } } catch (_: Throwable) {}
        try { EntityTrackerManager.all().forEach { tp -> tp.username().takeIf { it.isNotEmpty() }?.let { names.add(it) } } } catch (_: Throwable) {}
        suggestFiltered(b, names)
    }

    private fun suggestTargets(b: SuggestionsBuilder) {
        suggestFiltered(b, checkIds)
        suggestNames(b)
    }

    private fun suggestHelpTopics(b: SuggestionsBuilder) {
        suggestFiltered(b, subcommands.map { it.first })
        suggestFiltered(b, checkIds)
    }
}
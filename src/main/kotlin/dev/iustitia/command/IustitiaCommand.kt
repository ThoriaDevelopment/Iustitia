package dev.iustitia.command

import dev.iustitia.NumFmt
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import dev.iustitia.config.ConfigManager
import dev.iustitia.config.YaclScreenBuilder
import dev.iustitia.history.FlagHistory
import dev.iustitia.history.Evidence
import dev.iustitia.i18n.L10n
import dev.iustitia.info.CheckInfo
import dev.iustitia.info.FeatureInfo
import dev.iustitia.persistence.NoteStore
import dev.iustitia.persistence.PersistenceManager
import dev.iustitia.protocol.ProtocolDetector
import dev.iustitia.replay.ClipPlayback
import dev.iustitia.session.SessionStats
import dev.iustitia.session.Snapshot
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.ui.KeybindHubScreen
import dev.iustitia.ui.PlayerHistoryScreen
import dev.iustitia.ui.PlayerSearchScreen
import dev.iustitia.ui.SessionScreen
import dev.iustitia.ui.SetupWizardScreen
import dev.iustitia.ui.ChatHistPanelScreen
import dev.iustitia.ui.TranscriptPanelScreen
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.MinecraftClient
import net.minecraft.text.ClickEvent
import net.minecraft.text.HoverEvent
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import java.text.SimpleDateFormat
import java.util.UUID
import kotlin.math.ceil

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

    /** A getter, not an init-time `val`: `/ius help` prints these, so a language switch mid-session
     *  must be re-read instead of frozen at class load (see README "Language"). */
    private val subcommands: List<Pair<String, String>>
        get() = listOf(
        "list" to L10n.s("iustitia.cmd.sub.list"),
        "status" to L10n.s("iustitia.cmd.sub.status"),
        "hist" to L10n.s("iustitia.cmd.sub.hist"),
        "report" to L10n.s("iustitia.cmd.sub.report"),
        "transcript" to L10n.s("iustitia.cmd.sub.transcript"),
        "evidence" to L10n.s("iustitia.cmd.sub.evidence"),
        "note" to L10n.s("iustitia.cmd.sub.note"),
        "session" to L10n.s("iustitia.cmd.sub.session"),
        "snapshot" to L10n.s("iustitia.cmd.sub.snapshot"),
        "spectate" to L10n.s("iustitia.cmd.sub.spectate"),
        "replay" to L10n.s("iustitia.cmd.sub.replay"),
        "clip" to L10n.s("iustitia.cmd.sub.clip"),
        "playclip" to L10n.s("iustitia.cmd.sub.playclip"),
        "clips" to L10n.s("iustitia.cmd.sub.clips"),
        "deleteclip" to L10n.s("iustitia.cmd.sub.deleteclip"),
        "delclip" to L10n.s("iustitia.cmd.sub.delclip"),
        "record" to L10n.s("iustitia.cmd.sub.record"),
        "chathist" to L10n.s("iustitia.cmd.sub.chathist"),
        // The built-in list is derived, so adding a preset cannot leave this row claiming the old set.
        "preset" to L10n.s("iustitia.cmd.sub.preset", dev.iustitia.config.PresetManager.builtInNames.joinToString("/")),
        "presets" to L10n.s("iustitia.cmd.sub.presets"),
        "createpreset" to L10n.s("iustitia.cmd.sub.createpreset"),
        "deletepreset" to L10n.s("iustitia.cmd.sub.deletepreset"),
        "wizard" to L10n.s("iustitia.cmd.sub.wizard"),
        "keybinds" to L10n.s("iustitia.cmd.sub.keybinds"),
        "help" to L10n.s("iustitia.cmd.sub.help"),
        "alerts" to L10n.s("iustitia.cmd.sub.alerts"),
        "toggle" to L10n.s("iustitia.cmd.sub.toggle"),
        "threshold" to L10n.s("iustitia.cmd.sub.threshold"),
        "config" to L10n.s("iustitia.cmd.sub.config"),
        "verbose" to L10n.s("iustitia.cmd.sub.verbose"),
        "reload" to L10n.s("iustitia.cmd.sub.reload"),
        "reset" to L10n.s("iustitia.cmd.sub.reset"),
        "clear" to L10n.s("iustitia.cmd.sub.clear"),
        "exempt" to L10n.s("iustitia.cmd.sub.exempt"),
        "debugfps" to L10n.s("iustitia.cmd.sub.debugfps"),
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
                        .suggests { _, b -> suggestFiltered(b, listOf("markdown", "json", "text")); b.buildFuture() }
                        .executes { report(it, StringArgumentType.getString(it, "format")) })))
            .then(ClientCommandManager.literal("transcript")
                .executes { transcriptToggle(it) }
                .then(ClientCommandManager.literal("panel")
                    .executes { transcriptToggle(it) }
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .suggests { _, b -> suggestNames(b); b.buildFuture() }
                        .executes { transcriptPanelNamed(it) }))
                .then(ClientCommandManager.argument("name", SafeStringArgument.wordExcluding("panel"))
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
            .then(ClientCommandManager.literal("replay")
                .executes { replay(it, null, null) }
                .then(ClientCommandManager.literal("off").executes { replayStop(it) })
                .then(ClientCommandManager.literal("pause").executes { replayPause(it) })
                .then(ClientCommandManager.literal("resume").executes { replayResume(it) })
                .then(ClientCommandManager.literal("seek")
                    // negative = seek backward relative to now (same as the numpad -/+ seeks),
                    // positive = absolute seek — see replaySeek.
                    .then(ClientCommandManager.argument("seconds", DoubleArgumentType.doubleArg(-60.0, 60.0))
                        .executes { replaySeek(it) }))
                .then(ClientCommandManager.literal("step")
                    .then(ClientCommandManager.literal("+").executes { replayStep(it, 1) })
                    .then(ClientCommandManager.literal("-").executes { replayStep(it, -1) }))
                .then(ClientCommandManager.literal("speed")
                    .then(ClientCommandManager.argument("speed", StringArgumentType.word())
                        .suggests { _, b -> suggestFiltered(b, listOf("1", "0.5", "0.25")); b.buildFuture() }
                        .executes { replaySpeed(it, StringArgumentType.getString(it, "speed")) }))
                .then(ClientCommandManager.literal("cam")
                    .then(ClientCommandManager.argument("mode", StringArgumentType.word())
                        .suggests { _, b -> suggestFiltered(b, listOf("free", "follow", "pov", "freecam")); b.buildFuture() }
                        .executes { replayCam(it, StringArgumentType.getString(it, "mode")) }))
                .then(ClientCommandManager.literal("save")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .suggests { _, b -> suggestFiltered(b, dev.iustitia.replay.ClipStore.list()); b.buildFuture() }
                        .executes { replaySave(it, StringArgumentType.getString(it, "name")) }))
                // <target> is overloaded: a NUMBER = the seconds to replay (no focus, 1×), e.g.
                // `/ius replay 60`; a NAME = the focus player, optionally followed by <seconds> [speed],
                // e.g. `/ius replay thoria 60 0.5`. A bare `/ius replay` replays the default window.
                .then(ClientCommandManager.argument("target", SafeStringArgument.wordExcluding("off", "pause", "resume", "seek", "step", "speed", "cam", "save"))
                    .suggests { _, b -> suggestNames(b); b.buildFuture() }
                    .executes { replay(it, StringArgumentType.getString(it, "target"), null) }
                    .then(ClientCommandManager.argument("seconds", DoubleArgumentType.doubleArg(1.0, 60.0))
                        .executes { replay(it, StringArgumentType.getString(it, "target"), null) }
                        .then(ClientCommandManager.argument("speed", StringArgumentType.word())
                            .suggests { _, b -> suggestFiltered(b, listOf("1", "0.5", "0.25")); b.buildFuture() }
                            .executes { replay(it, StringArgumentType.getString(it, "target"), StringArgumentType.getString(it, "speed")) }))))
            .then(ClientCommandManager.literal("clip")
                .then(ClientCommandManager.argument("seconds", DoubleArgumentType.doubleArg(1.0, 60.0))
                    .executes { clip(it, null) }
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .suggests { _, b -> suggestFiltered(b, dev.iustitia.replay.ClipStore.list()); b.buildFuture() }
                        .executes { clip(it, StringArgumentType.getString(it, "name")) })))
            .then(ClientCommandManager.literal("playclip")
                .executes { playclip(it, null, null) }
                .then(ClientCommandManager.literal("off").executes { replayStop(it) })
                .then(ClientCommandManager.argument("name", SafeStringArgument.wordExcluding("off"))
                    .suggests { _, b -> suggestFiltered(b, dev.iustitia.replay.ClipStore.list()); b.buildFuture() }
                    .executes { playclip(it, StringArgumentType.getString(it, "name"), null) }
                    .then(ClientCommandManager.argument("speed", StringArgumentType.word())
                        .suggests { _, b -> suggestFiltered(b, listOf("1", "0.5", "0.25")); b.buildFuture() }
                        .executes { playclip(it, StringArgumentType.getString(it, "name"), StringArgumentType.getString(it, "speed")) })))
            .then(ClientCommandManager.literal("clips").executes { clipsScreen(it) })
            .then(ClientCommandManager.literal("deleteclip")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .suggests { _, b -> suggestFiltered(b, dev.iustitia.replay.ClipStore.list()); b.buildFuture() }
                    .executes { deleteClip(it) }))
            .then(ClientCommandManager.literal("delclip")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .suggests { _, b -> suggestFiltered(b, dev.iustitia.replay.ClipStore.list()); b.buildFuture() }
                    .executes { deleteClip(it) }))
            .then(ClientCommandManager.literal("preset")
                .executes { presetList(it) }
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .suggests { _, b -> suggestFiltered(b, dev.iustitia.config.PresetManager.listAll()); b.buildFuture() }
                    .executes { presetApply(it) }))
            .then(ClientCommandManager.literal("presets").executes { presetList(it) })
            .then(ClientCommandManager.literal("createpreset")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .executes { presetCreate(it) }))
            .then(ClientCommandManager.literal("deletepreset")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .suggests { _, b -> suggestFiltered(b, dev.iustitia.config.PresetManager.listCustom()); b.buildFuture() }
                    .executes { presetDelete(it) }))
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
                    .then(ClientCommandManager.argument("value", DoubleArgumentType.doubleArg(0.0, 1000.0))
                        .executes { threshold(it) })))
            .then(ClientCommandManager.literal("clear")
                .executes { clearUsage(it) }
                .then(ClientCommandManager.literal("all").executes { clearAll(it) })
                .then(ClientCommandManager.argument("name", SafeStringArgument.wordExcluding("all"))
                    .suggests { _, b -> suggestNames(b); b.buildFuture() }
                    .executes { clearPlayer(it) }))
            .then(ClientCommandManager.literal("exempt")
                .executes { exemptList(it) }
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .suggests { _, b -> suggestExempt(b); b.buildFuture() }
                    .executes { exemptToggle(it, null) }
                    .then(ClientCommandManager.argument("state", StringArgumentType.word())
                        .suggests { _, b -> suggestFiltered(b, listOf("on", "off")); b.buildFuture() }
                        .executes { exemptToggle(it, StringArgumentType.getString(it, "state")) })))
            .then(ClientCommandManager.literal("debugfps")
                .executes { profileStart(it) }
                .then(ClientCommandManager.literal("start").executes { profileStart(it) })
                .then(ClientCommandManager.literal("stop").executes { profileStop(it) }))
            .then(ClientCommandManager.literal("record")
                .executes { recordStart(it) }
                .then(ClientCommandManager.literal("start").executes { recordStart(it) })
                .then(ClientCommandManager.literal("stop")
                    .executes { recordStop(it, null) }
                    .then(ClientCommandManager.argument("name", StringArgumentType.string())
                        .executes { recordStop(it, StringArgumentType.getString(it, "name")) })))
            .then(ClientCommandManager.literal("chathist")
                .executes { chathistUsage(it) }
                .then(ClientCommandManager.literal("phrase")
                    .executes { chathistUsage(it) }
                    .then(ClientCommandManager.argument("phrase", StringArgumentType.string())
                        .executes { chathistPhrase(it, StringArgumentType.getString(it, "phrase"), 1) }
                        .then(ClientCommandManager.argument("page", IntegerArgumentType.integer(1))
                            .executes { chathistPhrase(it, StringArgumentType.getString(it, "phrase"), IntegerArgumentType.getInteger(it, "page")) })))
                .then(ClientCommandManager.literal("target")
                    .then(ClientCommandManager.argument("username", StringArgumentType.string())
                        .suggests { _, b -> suggestNames(b); b.buildFuture() }
                        .then(ClientCommandManager.argument("phrase", StringArgumentType.string())
                            .executes { chathistTarget(it, StringArgumentType.getString(it, "username"), StringArgumentType.getString(it, "phrase"), 1) }
                            .then(ClientCommandManager.argument("page", IntegerArgumentType.integer(1))
                                .executes { chathistTarget(it, StringArgumentType.getString(it, "username"), StringArgumentType.getString(it, "phrase"), IntegerArgumentType.getInteger(it, "page")) }))))
                // `/ius chathist panel <action> [username] [word] [pageamount]` — same queries as the
                // chat-print chathist, rendered in a live side panel that mirrors TranscriptPanelScreen.
                .then(ClientCommandManager.literal("panel")
                    .executes { chathistPanelUsage(it) }
                    .then(ClientCommandManager.literal("user")
                        .then(ClientCommandManager.argument("username", StringArgumentType.string())
                            .suggests { _, b -> suggestNames(b); b.buildFuture() }
                            .executes { chathistPanelUser(it, StringArgumentType.getString(it, "username"), 15) }
                            .then(ClientCommandManager.argument("pageamount", IntegerArgumentType.integer(1, 200))
                                .executes { chathistPanelUser(it, StringArgumentType.getString(it, "username"), IntegerArgumentType.getInteger(it, "pageamount")) })))
                    .then(ClientCommandManager.literal("phrase")
                        .then(ClientCommandManager.argument("word", StringArgumentType.string())
                            .executes { chathistPanelPhrase(it, StringArgumentType.getString(it, "word"), 15) }
                            .then(ClientCommandManager.argument("pageamount", IntegerArgumentType.integer(1, 200))
                                .executes { chathistPanelPhrase(it, StringArgumentType.getString(it, "word"), IntegerArgumentType.getInteger(it, "pageamount")) })))
                    .then(ClientCommandManager.literal("target")
                        .then(ClientCommandManager.argument("username", StringArgumentType.string())
                            .suggests { _, b -> suggestNames(b); b.buildFuture() }
                            .then(ClientCommandManager.argument("word", StringArgumentType.string())
                                .executes { chathistPanelTarget(it, StringArgumentType.getString(it, "username"), StringArgumentType.getString(it, "word"), 15) }
                                .then(ClientCommandManager.argument("pageamount", IntegerArgumentType.integer(1, 200))
                                    .executes { chathistPanelTarget(it, StringArgumentType.getString(it, "username"), StringArgumentType.getString(it, "word"), IntegerArgumentType.getInteger(it, "pageamount")) })))))
                .then(ClientCommandManager.argument("username", SafeStringArgument.stringExcluding("phrase", "target", "panel"))
                    .suggests { _, b -> suggestNames(b); b.buildFuture() }
                    .executes { chathistUser(it, StringArgumentType.getString(it, "username"), 1) }
                    .then(ClientCommandManager.argument("page", IntegerArgumentType.integer(1))
                        .executes { chathistUser(it, StringArgumentType.getString(it, "username"), IntegerArgumentType.getInteger(it, "page")) })))

    // ---- feedback helper ----
    private fun send(ctx: CommandContext<FabricClientCommandSource>, line: String) {
        ctx.source.sendFeedback(Text.literal(line))
    }

    // ---- companion-mod yield guards ----
    // SnapClip / Scrollback / FollowCam are single-feature extracts of Iustitia. When one is
    // installed it owns its feature, so the matching Iustitia commands defer with a pointer instead
    // of running a second copy (double capture / double camera / double chat store). Core detection
    // and every other command are unaffected. See dev.iustitia.compat.CompanionMods.
    private fun companionOwnsReplay(ctx: CommandContext<FabricClientCommandSource>): Boolean {
        if (!dev.iustitia.compat.CompanionMods.snapClip) return false
        send(ctx, L10n.s("iustitia.cmd.companionSnapClip", tag))
        return true
    }
    private fun companionOwnsChat(ctx: CommandContext<FabricClientCommandSource>): Boolean {
        if (!dev.iustitia.compat.CompanionMods.scrollback) return false
        send(ctx, L10n.s("iustitia.cmd.companionScrollback", tag))
        return true
    }
    private fun companionOwnsWatch(ctx: CommandContext<FabricClientCommandSource>): Boolean {
        if (!dev.iustitia.compat.CompanionMods.followCam) return false
        send(ctx, L10n.s("iustitia.cmd.companionFollowCam", tag))
        return true
    }

    // ---- existing subcommands ----
    private fun list(ctx: CommandContext<FabricClientCommandSource>): Int {
        val cfg = ConfigManager.config
        send(ctx, L10n.s("iustitia.cmd.checksHeader", tag))
        for ((id, cc) in cfg.checks()) {
            val state = if (cc.enabled) L10n.s("iustitia.cmd.stateOn") else L10n.s("iustitia.cmd.stateOff")
            val muted = if (id in cfg.mutedChecks) L10n.s("iustitia.cmd.mutedSuffix") else ""
            send(ctx, " §f$id §7vl>${cc.setbackVL} §7decay=${cc.decay} §7thr=${cc.threshold} $state$muted")
        }
        val master = if (cfg.enabled) L10n.s("iustitia.cmd.stateOn") else L10n.s("iustitia.cmd.stateOff")
        send(ctx, L10n.s("iustitia.cmd.statusMasterVerbose", master, cfg.verbose))
        send(ctx, CheckInfo.SEVERITY_LEGEND)
        return 1
    }

    private fun status(ctx: CommandContext<FabricClientCommandSource>): Int {
        val cfg = ConfigManager.config
        val master = if (cfg.enabled) L10n.s("iustitia.cmd.stateOn") else L10n.s("iustitia.cmd.stateOff")
        val chatAlerts = if (cfg.alertsEnabled) L10n.s("iustitia.cmd.stateOn") else L10n.s("iustitia.cmd.stateOffMuted")
        val total = cfg.checks().size
        val enabled = cfg.checks().count { it.second.enabled }
        val tracked = try { EntityTrackerManager.all().size } catch (_: Throwable) { -1 }
        val proto = ProtocolDetector.current
        val era = if (ProtocolDetector.is1_8OrLess) L10n.s("iustitia.cmd.statusEra18") else ""
        val lag = try { EntityTrackerManager.lastServerLagTick } catch (_: Throwable) { -10000 }
        val alerts = FlagHistory.totalAlerts
        send(ctx, L10n.s("iustitia.cmd.statusHeader", tag))
        send(ctx, L10n.s("iustitia.cmd.statusMaster", master))
        send(ctx, L10n.s("iustitia.cmd.statusChatAlerts", chatAlerts))
        send(ctx, L10n.s("iustitia.cmd.statusChecks", enabled, total))
        send(ctx, L10n.s("iustitia.cmd.playersTracked", tracked))
        send(ctx, L10n.s("iustitia.cmd.statusProtocol", proto, era))
        send(ctx, L10n.s("iustitia.cmd.statusLag", lag))
        send(ctx, L10n.s("iustitia.cmd.alertsThisSession", alerts))
        val top = FlagHistory.topOffenders(1)
        if (top.isNotEmpty()) send(ctx, L10n.s("iustitia.cmd.statusTopOffender", top[0].first, top[0].second))
        send(ctx, CheckInfo.SEVERITY_LEGEND)
        return 1
    }

    private fun verbose(ctx: CommandContext<FabricClientCommandSource>): Int {
        ConfigManager.config.verbose = !ConfigManager.config.verbose
        ConfigManager.save()
        send(ctx, "$tag §7verbose = ${ConfigManager.config.verbose}")
        if (!ConfigManager.config.verbose) {
            // Ending a capture. Backlog still writes itself — the drain thread keeps consuming
            // after verbose is switched off, so there is nothing to flush here and no reason to
            // block the client thread on I/O. What the operator needs to know is whether the
            // transcript they are about to compare is complete.
            val backlog = dev.iustitia.VerboseLog.backlog()
            val dropped = dev.iustitia.VerboseLog.dropCount()
            if (backlog > 0) {
                send(ctx, L10n.s("iustitia.cmd.verboseBacklog", backlog))
            }
            if (dropped > 0L) {
                send(ctx, L10n.s("iustitia.cmd.verboseDropped", dropped))
            }
        }
        return 1
    }

    private fun reload(ctx: CommandContext<FabricClientCommandSource>): Int {
        ConfigManager.reload()
        send(ctx, L10n.s("iustitia.cmd.configReloaded", tag))
        return 1
    }

    private fun reset(ctx: CommandContext<FabricClientCommandSource>): Int {
        dev.iustitia.Iustitia.resetAll()
        val persist = ConfigManager.config.persistenceEnabled
        send(ctx, L10n.s("iustitia.cmd.resetDone", tag) +
            if (persist) L10n.s("iustitia.cmd.resetPersisted") else "")
        return 1
    }

    private fun openConfig(ctx: CommandContext<FabricClientCommandSource>): Int {
        MinecraftClient.getInstance().execute {
            try {
                MinecraftClient.getInstance().setScreen(YaclScreenBuilder.build(MinecraftClient.getInstance().currentScreen))
            } catch (_: Throwable) {
                MinecraftClient.getInstance().player?.sendMessage(
                    L10n.t("iustitia.cmd.configOpenFail", tag), false
                )
            }
        }
        return 1
    }

    private fun toggle(ctx: CommandContext<FabricClientCommandSource>): Int {
        val id = StringArgumentType.getString(ctx, "check")
        if (id !in checkIds) { send(ctx, L10n.s("iustitia.cmd.unknownCheck", tag, id)); return 0 }
        val cc = ConfigManager.config.slice(id)
        cc.enabled = !cc.enabled
        ConfigManager.save()
        send(ctx, L10n.s("iustitia.cmd.toggleResult", tag, id, if (cc.enabled) L10n.s("iustitia.cmd.stateOn") else L10n.s("iustitia.cmd.stateOff")))
        return 1
    }

    /** Bare `/ius toggle` (no check arg) — print usage instead of Brigadier's cryptic
     *  "Unknown or incomplete command". Same for [thresholdUsage]. */
    private fun toggleUsage(ctx: CommandContext<FabricClientCommandSource>): Int {
        send(ctx, L10n.s("iustitia.cmd.toggleUsage", tag))
        send(ctx, L10n.s("iustitia.cmd.checksList", checkIds.joinToString(" ")))
        return 0
    }

    private fun threshold(ctx: CommandContext<FabricClientCommandSource>): Int {
        val id = StringArgumentType.getString(ctx, "check")
        if (id !in checkIds) { send(ctx, L10n.s("iustitia.cmd.unknownCheck", tag, id)); return 0 }
        val value = DoubleArgumentType.getDouble(ctx, "value")
        ConfigManager.config.slice(id).threshold = value
        ConfigManager.save()
        send(ctx, "$tag §7$id.threshold = $value")
        return 1
    }

    private fun thresholdUsage(ctx: CommandContext<FabricClientCommandSource>): Int {
        send(ctx, L10n.s("iustitia.cmd.thresholdUsage", tag))
        send(ctx, L10n.s("iustitia.cmd.checksList", checkIds.joinToString(" ")))
        return 0
    }

    // ---- clear (reset a player's or everyone's flags) ----
    /** Bare `/ius clear` — print usage (a bare clear is too easy to fat-finger into a wipe). */
    private fun clearUsage(ctx: CommandContext<FabricClientCommandSource>): Int {
        send(ctx, L10n.s("iustitia.cmd.clearUsage", tag))
        send(ctx, L10n.s("iustitia.cmd.clearUsageDetail"))
        return 0
    }

    private fun clearAll(ctx: CommandContext<FabricClientCommandSource>): Int {
        send(ctx, dev.iustitia.Iustitia.clearAllFlags())
        return 1
    }

    private fun clearPlayer(ctx: CommandContext<FabricClientCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val uuid = resolveUuid(name)
        if (uuid == null) { send(ctx, L10n.s("iustitia.cmd.noData", tag, name)); return 0 }
        send(ctx, dev.iustitia.Iustitia.clearPlayerFlags(uuid))
        return 1
    }

    // ---- exempt (skip a player at the Check.flag chokepoint) ----
    /** Bare `/ius exempt` — list currently-exempted players. */
    private fun exemptList(ctx: CommandContext<FabricClientCommandSource>): Int {
        val all = try { dev.iustitia.exempt.Exemptions.all() } catch (_: Throwable) { emptyList() }
        if (all.isEmpty()) { send(ctx, L10n.s("iustitia.cmd.exemptNone", tag)); return 1 }
        send(ctx, L10n.s("iustitia.cmd.exemptListHeader", tag, all.size))
        all.forEach { (uuid, name) -> send(ctx, " §f$name §8$uuid") }
        send(ctx, L10n.s("iustitia.cmd.exemptHint"))
        return 1
    }

    /** `/ius exempt <name> [on|off]` — set or toggle a player's exemption. Bare name = toggle. */
    private fun exemptToggle(ctx: CommandContext<FabricClientCommandSource>, stateArg: String?): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val uuid = resolveUuid(name)
        // Exempting a not-yet-tracked player is allowed (you may pre-exempt a trusted regular by
        // name before they join); but toggling OFF a name we can't resolve is ambiguous — bail.
        if (uuid == null) {
            send(ctx, L10n.s("iustitia.cmd.exemptUnknownTarget", tag, name))
            return 0
        }
        val want = when (stateArg?.lowercase()) { "on" -> true; "off" -> false; else -> null }
        val nowOn = when (want) {
            true -> dev.iustitia.exempt.Exemptions.set(uuid, name, true)
            false -> dev.iustitia.exempt.Exemptions.set(uuid, name, false)
            null -> dev.iustitia.exempt.Exemptions.toggle(uuid, name)
        }
        send(ctx, L10n.s("iustitia.cmd.exemptResult", tag, name, if (nowOn) L10n.s("iustitia.cmd.exemptOn") else L10n.s("iustitia.cmd.exemptOff")))
        if (nowOn) send(ctx, L10n.s("iustitia.cmd.exemptNotCleared", name))
        return 1
    }

    // ---- debugfps (live render-thread sampler; diagnostic for the FPS investigation) ----
    /** `/ius debugfps` (or `/ius debugfps start`) — start sampling the render thread every ~5ms.
     *  Not gated on verbose: the profiler's purpose is to measure the configuration we actually
     *  ship, and gating it on verbose meant every profile was taken with verbose on — i.e. it
     *  could not measure the verbose-off case at all. Start/stop is the only gate. MUST run on the
     *  render thread (the command handler does), so [dev.iustitia.profiling.RenderProfiler.start]
     *  captures the render thread. Fail-open. */
    private fun profileStart(ctx: CommandContext<FabricClientCommandSource>): Int {
        val err = dev.iustitia.profiling.RenderProfiler.start()
        if (err != null) { send(ctx, L10n.s("iustitia.cmd.profilerStartFail", tag, err)); return 0 }
        send(ctx, L10n.s("iustitia.cmd.profilerRunning", tag))
        return 1
    }

    /** `/ius debugfps stop` — stop sampling and write the text report to
     *  `%APPDATA%/.iustitia/debugfps/iustitia-debugfps-<timestamp>.txt`. Returns the path on success. */
    private fun profileStop(ctx: CommandContext<FabricClientCommandSource>): Int {
        val res = dev.iustitia.profiling.RenderProfiler.stop()
        when {
            res == "not running" -> { send(ctx, L10n.s("iustitia.cmd.profilerNotRunning", tag)); return 0 }
            res.startsWith("failed") -> { send(ctx, "$tag §c$res"); return 0 }
            else -> {
                send(ctx, L10n.s("iustitia.cmd.profilerStopped", tag, res))
                send(ctx, L10n.s("iustitia.cmd.profilerSendFile"))
            }
        }
        return 1
    }

    /** `/ius record start` — begin a manual long recording (capped at 10 min per segment; auto-saves
     *  a segment on world change + at the cap and keeps recording). The world map is rolled up in small
     *  per-tick pieces while recording (no save-time sweep), plus every block edit observed. Feedback
     *  via [dev.iustitia.replay.RecordManager.start]. */
    private fun recordStart(ctx: CommandContext<FabricClientCommandSource>): Int {
        if (companionOwnsReplay(ctx)) return 1
        if (!ConfigManager.config.replayCapture) {
            send(ctx, L10n.s("iustitia.cmd.recordCaptureDisabled", tag))
            return 0
        }
        send(ctx, dev.iustitia.replay.RecordManager.start())
        return 1
    }

    /** `/ius record stop [name]` — save the active recording as `<name>.iusclip` (default
     *  `record_<tick>_<idx>`) → plays back with `/ius playclip <name>`. Feedback via [RecordManager.stop]. */
    private fun recordStop(ctx: CommandContext<FabricClientCommandSource>, name: String?): Int {
        if (companionOwnsReplay(ctx)) return 1
        send(ctx, dev.iustitia.replay.RecordManager.stop(name))
        return 1
    }

    // ---- /ius chathist (per-player chat history, paginated + clickable) ----

    private val chatTimeFmt = SimpleDateFormat("HH:mm:ss")

    /** Bare `/ius chathist` / `/ius chathist phrase` usage hint. */
    private fun chathistUsage(ctx: CommandContext<FabricClientCommandSource>): Int {
        if (companionOwnsChat(ctx)) return 1
        send(ctx, L10n.s("iustitia.cmd.usageHeader", tag))
        send(ctx, L10n.s("iustitia.cmd.chathistUsageUser"))
        send(ctx, L10n.s("iustitia.cmd.chathistUsagePhrase"))
        send(ctx, L10n.s("iustitia.cmd.chathistUsageTarget"))
        send(ctx, L10n.s("iustitia.cmd.chathistUsagePanelUser"))
        send(ctx, L10n.s("iustitia.cmd.chathistUsagePanelPhrase"))
        send(ctx, L10n.s("iustitia.cmd.chathistUsagePanelTarget"))
        send(ctx, L10n.s("iustitia.cmd.chathistUsageNote"))
        return 1
    }

    /** `/ius chathist <username> [page]` — that player's messages, newest-first, 8/page. */
    private fun chathistUser(ctx: CommandContext<FabricClientCommandSource>, username: String, page: Int): Int {
        if (companionOwnsChat(ctx)) return 1
        val rows = dev.iustitia.chathist.ChatHistory.rowsForUser(username)
        renderChatPage(ctx, rows, page, "chathist $username")
        return 1
    }

    /** `/ius chathist phrase <phrase> [page]` — every player who said [phrase], in order, 8/page. */
    private fun chathistPhrase(ctx: CommandContext<FabricClientCommandSource>, phrase: String, page: Int): Int {
        if (companionOwnsChat(ctx)) return 1
        val rows = dev.iustitia.chathist.ChatHistory.rowsForPhrase(phrase)
        renderChatPage(ctx, rows, page, "chathist phrase $phrase")
        return 1
    }

    /** `/ius chathist target <username> <phrase> [page]` — [username]'s messages containing [phrase]. */
    private fun chathistTarget(ctx: CommandContext<FabricClientCommandSource>, username: String, phrase: String, page: Int): Int {
        if (companionOwnsChat(ctx)) return 1
        val rows = dev.iustitia.chathist.ChatHistory.rowsForUserPhrase(username, phrase)
        renderChatPage(ctx, rows, page, "chathist target $username $phrase")
        return 1
    }

    // ---- chathist panel — live side panel mirroring TranscriptPanelScreen ----

    /** Bare `/ius chathist panel` usage hint. */
    private fun chathistPanelUsage(ctx: CommandContext<FabricClientCommandSource>): Int {
        if (companionOwnsChat(ctx)) return 1
        send(ctx, L10n.s("iustitia.cmd.usageHeader", tag))
        send(ctx, L10n.s("iustitia.cmd.chathistUsagePanelUser"))
        send(ctx, L10n.s("iustitia.cmd.chathistUsagePanelPhrase"))
        send(ctx, L10n.s("iustitia.cmd.chathistUsagePanelTarget"))
        send(ctx, L10n.s("iustitia.cmd.chathistPanelUsageNote"))
        return 1
    }

    /** `/ius chathist panel user <username> [pageamount]` — live side panel of a player's newest messages. */
    private fun chathistPanelUser(ctx: CommandContext<FabricClientCommandSource>, username: String, limit: Int): Int {
        openChatPanel(ctx, username, { dev.iustitia.chathist.ChatHistory.rowsForUser(username) }, limit)
        return 1
    }

    /** `/ius chathist panel phrase <word> [pageamount]` — live side panel of every message containing [word]. */
    private fun chathistPanelPhrase(ctx: CommandContext<FabricClientCommandSource>, word: String, limit: Int): Int {
        openChatPanel(ctx, "phrase: $word", { dev.iustitia.chathist.ChatHistory.rowsForPhrase(word) }, limit)
        return 1
    }

    /** `/ius chathist panel target <username> <word> [pageamount]` — live side panel of [username]'s msgs with [word]. */
    private fun chathistPanelTarget(ctx: CommandContext<FabricClientCommandSource>, username: String, word: String, limit: Int): Int {
        openChatPanel(ctx, "$username · $word", { dev.iustitia.chathist.ChatHistory.rowsForUserPhrase(username, word) }, limit)
        return 1
    }

    /** Open the chathist side panel for a query (mirrors `transcriptPanelNamed`'s mc.execute pattern). */
    private fun openChatPanel(ctx: CommandContext<FabricClientCommandSource>, subtitle: String, rowsProvider: () -> List<dev.iustitia.chathist.ChatHistory.Row>, limit: Int) {
        if (companionOwnsChat(ctx)) return
        val mc = MinecraftClient.getInstance()
        mc.execute { try { mc.setScreen(ChatHistPanelScreen(subtitle, rowsProvider, limit, null)) } catch (_: Throwable) {} }
        send(ctx, L10n.s("iustitia.cmd.chathistPanelHeader", tag, subtitle, limit))
    }

    /**
     * Render one page of chat history to chat with `[HH:mm:ss] [name] text` rows + top/bottom
     * dividers. The bottom divider's `[<]` / `[>]` are clickable ([ClickEvent.RunCommand] re-runs the
     * same command variant with page-1 / page+1); they render inert on the first / last page. Lazy:
     * only the requested page's rows are read (`drop((page-1)*8).take(8)`). Empty set → a single "no
     * messages" line, no dividers. Output via `sendFeedback(Text)` (not [send]) since rows carry
     * click/hover events. Fail-open.
     */
    private fun renderChatPage(ctx: CommandContext<FabricClientCommandSource>, rows: List<dev.iustitia.chathist.ChatHistory.Row>, page: Int, cmdPrefix: String) {
        val src = ctx.source
        if (rows.isEmpty()) { send(ctx, L10n.s("iustitia.cmd.chatHistEmpty", tag)); return }
        val total = rows.size
        val totalPages = ceil(total.toDouble() / dev.iustitia.chathist.ChatHistory.PAGE_SIZE_ROWS).toInt().coerceAtLeast(1)
        val p = page.coerceIn(1, totalPages)
        val pageRows = rows.drop((p - 1) * dev.iustitia.chathist.ChatHistory.PAGE_SIZE_ROWS).take(dev.iustitia.chathist.ChatHistory.PAGE_SIZE_ROWS)
        // The `[iustitia]` tag prefix would shift the top divider right of the chat rows below it,
        // so emit the tag on its own line first, then the un-prefixed divider one row lower — it
        // left-aligns with the rows and the bottom divider.
        src.sendFeedback(Text.literal(tag))
        // top divider: ...---[IUS ChatHistory]---...
        src.sendFeedback(L10n.t("iustitia.cmd.chatHistDivider"))
        for (r in pageRows) {
            val ts = try { chatTimeFmt.format(java.util.Date(r.wallClockMs)) } catch (_: Throwable) { "??:??:??" }
            src.sendFeedback(Text.literal("§8[$ts] §7[§f${r.name}§7]§r ${r.text}"))
        }
        // bottom divider: [<]...---[Page N/M]---...[>], clickable where valid.
        val bottom: MutableText = Text.literal("")
        val prevCmd = "/ius $cmdPrefix ${p - 1}"
        val nextCmd = "/ius $cmdPrefix ${p + 1}"
        bottom.append(clickButton("§3<§r", if (p > 1) prevCmd else null, L10n.s("iustitia.cmd.prevPageHover")))
        bottom.append(L10n.t("iustitia.cmd.pageDivider", p, totalPages))
        bottom.append(clickButton("§3>§r", if (p < totalPages) nextCmd else null, L10n.s("iustitia.cmd.nextPageHover")))
        src.sendFeedback(bottom)
    }

    /** A `[<]`/`[>]` button: clickable (run-command) + hover-text when [cmd] is non-null, plain when null. */
    private fun clickButton(label: String, cmd: String?, hover: String): Text = try {
        if (cmd == null) Text.literal("§8$label")
        else Text.literal(label).styled { s ->
            s.withClickEvent(ClickEvent.RunCommand(cmd)).withHoverEvent(HoverEvent.ShowText(Text.literal(hover)))
        }
    } catch (_: Throwable) { Text.literal(label) }

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
        if (top.isEmpty()) { send(ctx, L10n.s("iustitia.cmd.histNoAlerts", tag)); return 1 }
        send(ctx, L10n.s("iustitia.cmd.histTopHeader", tag))
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
        if (uuid == null) { send(ctx, L10n.s("iustitia.cmd.noHistory", tag, name)); return 0 }
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
        if (flags.isEmpty()) { send(ctx, L10n.s("iustitia.cmd.histNoFlags", tag, name)); return 1 }
        val filtered = if (checkFilter == null) flags else flags.filter { it.checkId == checkFilter }
        if (filtered.isEmpty()) { send(ctx, L10n.s("iustitia.cmd.histNoFlagsForCheck", tag, checkFilter, name)); return 1 }
        val distinctChecks = filtered.map { it.checkId }.distinct().size
        send(ctx, L10n.s("iustitia.cmd.histFlagsHeader", tag, name, filtered.size, distinctChecks))
        filtered.take(20).forEach { f ->
            send(ctx, " §7@t${f.tick} §f${f.label} §8(${f.checkId}) §evl=${fmt(f.vl)}")
        }
        return 1
    }

    // ---- report (#9) ----
    /** `/ius report <name> [format]` — builds a report card from the FlagHistory aggregators
     *  (same data as [PlayerHistoryScreen]) and copies it to the clipboard. `format` defaults
     *  to `markdown`; `json` emits the same data as a JSON object; `text` emits the chat-friendly
     *  transcript form (the same builder `/ius transcript <name>` prints to chat — session stats +
     *  moderator note + timeline). One builder ([reportText]) backs both so the two commands can
     *  never drift. Never sends anything to the server (clipboard is client-only). Fail-open. */
    private fun report(ctx: CommandContext<FabricClientCommandSource>, format: String): Int {
        val name = StringArgumentType.getString(ctx, "name")
        var uuid = FlagHistory.resolveName(name)
        if (uuid == null) {
            uuid = try {
                EntityTrackerManager.all().firstOrNull { it.username().equals(name, ignoreCase = true) }?.uuid
            } catch (_: Throwable) { null }
        }
        if (uuid == null) { send(ctx, L10n.s("iustitia.cmd.noHistory", tag, name)); return 0 }
        val fmt = when (format.lowercase()) { "json" -> "json"; "text" -> "text"; else -> "markdown" }
        val text = try {
            when (fmt) {
                "json" -> reportJson(uuid, name)
                "text" -> reportText(uuid, name)
                else -> reportMarkdown(uuid, name)
            }
        } catch (_: Throwable) {
            send(ctx, L10n.s("iustitia.cmd.reportBuildFail", tag, name)); return 0
        }
        try { MinecraftClient.getInstance().keyboard.setClipboard(text) } catch (_: Throwable) {
            send(ctx, L10n.s("iustitia.cmd.clipboardFail", tag)); return 0
        }
        send(ctx, L10n.s("iustitia.cmd.reportCopied", tag, name, text.length, fmt))
        return 1
    }

    private fun reportMarkdown(uuid: UUID, name: String): String {
        val sb = StringBuilder()
        val tier = FlagHistory.tierFor(uuid)
        val tierName = tier.label
        val sp = FlagHistory.span(uuid)
        val alerts = FlagHistory.sessionAlertCount(uuid)
        val counts = FlagHistory.flagCounts(uuid)
        val maxVlMap = FlagHistory.maxVlByCheck(uuid)
        val totalFlags = counts.values.sum()
        val maxVl = maxVlMap.values.maxOrNull() ?: 0.0
        val spanTxt = if (sp == null) L10n.s("iustitia.cmd.report.spanNone")
            else L10n.s("iustitia.cmd.report.span", sp.first, sp.second)
        sb.append(L10n.s("iustitia.cmd.report.header", name))
        sb.append(L10n.s("iustitia.cmd.report.summary", tierName, spanTxt, alerts, totalFlags, fmt(maxVl)))
        sb.append(L10n.s("iustitia.cmd.report.confidence", FlagHistory.confidenceLine(uuid)))
        val topCheck = FlagHistory.topCheck(uuid)
        if (topCheck != null) sb.append(L10n.s("iustitia.cmd.report.topCheck", topCheck))
        sb.append(L10n.s("iustitia.cmd.report.flagsByCheck"))
        if (counts.isEmpty()) sb.append(L10n.s("iustitia.cmd.report.noFlagsSession"))
        counts.forEach { (cid, c) ->
            val mv = maxVlMap[cid] ?: 0.0
            sb.append(L10n.s("iustitia.cmd.report.checkRow", cid, c, fmt(mv)))
        }
        sb.append(L10n.s("iustitia.cmd.report.timelineHeader", REPORT_TIMELINE_CAP))
        val flags = FlagHistory.flags(uuid).takeLast(REPORT_TIMELINE_CAP)
        if (flags.isEmpty()) sb.append(L10n.s("iustitia.cmd.report.noFlagsRecorded"))
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
        val tierName = tier.label
        val sp = FlagHistory.span(uuid)
        val counts = FlagHistory.flagCounts(uuid)
        val maxVlMap = FlagHistory.maxVlByCheck(uuid)
        sb.append("{\n")
        sb.append("  \"name\": ").append(jsonStr(name)).append(",\n")
        sb.append("  \"uuid\": ").append(jsonStr(uuid.toString())).append(",\n")
        sb.append("  \"tier\": ").append(jsonStr(tierName)).append(",\n")
        sb.append("  \"alerts\": ").append(FlagHistory.sessionAlertCount(uuid)).append(",\n")
        sb.append("  \"flags\": ").append(counts.values.sum()).append(",\n")
        sb.append("  \"maxVl\": ").append(fmtJson(maxVlMap.values.maxOrNull() ?: 0.0)).append(",\n")
        sb.append("  \"confidence\": ").append(jsonStr(FlagHistory.confidenceLine(uuid))).append(",\n")
        if (sp != null) sb.append("  \"firstTick\": ").append(sp.first).append(", \"lastTick\": ").append(sp.second).append(",\n")
        sb.append("  \"byCheck\": {")
        if (counts.isEmpty()) sb.append("},\n") else {
            sb.append("\n")
            counts.entries.forEachIndexed { i, e ->
                val mv = maxVlMap[e.key] ?: 0.0
                sb.append("    ").append(jsonStr(e.key)).append(": {\"count\": ").append(e.value)
                    .append(", \"maxVl\": ").append(fmtJson(mv)).append("}")
                sb.append(if (i == counts.size - 1) "\n  },\n" else ",\n")
            }
        }
        sb.append("  \"timeline\": [")
        val flags = FlagHistory.flags(uuid).takeLast(REPORT_TIMELINE_CAP)
        if (flags.isEmpty()) sb.append("]\n") else {
            sb.append("\n")
            flags.forEachIndexed { i, f ->
                sb.append("    {\"tick\": ").append(f.tick)
                    .append(", \"check\": ").append(jsonStr(f.checkId))
                    .append(", \"label\": ").append(jsonStr(f.label))
                    .append(", \"vl\": ").append(fmtJson(f.vl))
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
        e.victim?.let { parts += "victim=" + FlagHistory.nameOrShort(it) }
        e.extra?.let { parts += it }
        return parts.joinToString(" · ")
    }

    private fun evidenceJson(e: Evidence): String {
        val sb = StringBuilder("\"evidence\": {")
        val kvs = ArrayList<String>()
        e.subLabel?.let { kvs += "\"subLabel\": " + jsonStr(it) }
        e.measurement?.let { kvs += "\"measurement\": " + fmtJson(it, 4) }
        e.threshold?.let { kvs += "\"threshold\": " + fmtJson(it, 4) }
        e.pos?.let { kvs += "\"pos\": [" + fmtJson(it.x) + ", " + fmtJson(it.y) + ", " + fmtJson(it.z) + "]" }
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

    /** Locale-stable decimal formatting for clipboard/JSON/Markdown output — delegates to
     *  [dev.iustitia.NumFmt], which forces [java.util.Locale.US] (see its KDoc for why the
     *  default locale breaks JSON parsing and Markdown reports on comma-decimal locales). */
    private fun fmt(v: Double, digits: Int = 2): String = NumFmt.d(v, digits)
    /** JSON number token for the report/evidence objects. A non-finite value becomes the JSON
     *  literal `null` ([NumFmt.json]) rather than [NumFmt.MISSING], which in an unquoted number
     *  position would be a bare word and would make the whole report unparseable. */
    private fun fmtJson(v: Double, digits: Int = 2): String = NumFmt.json(v, digits)

    // ---- shared resolver ----
    private fun resolveUuid(name: String): java.util.UUID? {
        var uuid = FlagHistory.resolveName(name)
        if (uuid == null) uuid = try {
            EntityTrackerManager.all().firstOrNull { it.username().equals(name, ignoreCase = true) }?.uuid
        } catch (_: Throwable) { null }
        return uuid
    }

    /** The other player currently under the crosshair (for snapshot/transcript/snapshot keybinds).
     *  Delegates to [dev.iustitia.Iustitia.currentTarget] — the byte-identical local copy this
     *  previously duplicated is gone, so both resolvers can't drift apart. */
    private fun crosshairTarget(): Pair<java.util.UUID, String>? = dev.iustitia.Iustitia.currentTarget()

    // ---- transcript (#4) — chat-print form of the report engine ----
    /** `/ius transcript <name>` — prints the [reportText] builder to chat (and saves it to an
     *  export file when persistence is on). This is the in-game, paste-ready form of `/ius report
     *  <name> text` — same builder, different output channel: transcript → chat + export file,
     *  report → clipboard. `panel` is a separate live overlay surface ([TranscriptPanelScreen]). */
    private fun transcriptPrint(ctx: CommandContext<FabricClientCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val uuid = resolveUuid(name)
        if (uuid == null) { send(ctx, L10n.s("iustitia.cmd.noData", tag, name)); return 0 }
        val text = try { reportText(uuid, name) } catch (_: Throwable) {
            send(ctx, L10n.s("iustitia.cmd.transcriptBuildFail", tag, name)); return 0 }
        send(ctx, text)
        // Honest save result: with persistence on, a disk error in the export write is
        // reported instead of hiding behind the unconditional "printed" line (the export
        // file is optional, but when it's promised it must land or say so).
        if (try { ConfigManager.config.persistenceEnabled } catch (_: Throwable) { false }) {
            val exported = try { PersistenceManager.saveExport("transcript", name, text) } catch (_: Throwable) { false }
            if (!exported) send(ctx, L10n.s("iustitia.cmd.transcriptExportFail", tag))
        }
        send(ctx, L10n.s("iustitia.cmd.transcriptPrinted", tag, name, name))
        // If the transcript side panel is enabled in config, also pop it open for this player —
        // same surface the keybind / `/ius transcript panel` use, so the chat print + the live panel
        // aren't mutually exclusive. Fail-open (a screen-open error never blocks the chat print).
        if (ConfigManager.config.transcriptPanel) {
            val mc = MinecraftClient.getInstance()
            mc.execute { try { mc.setScreen(TranscriptPanelScreen(uuid, name, null)) } catch (_: Throwable) {} }
        }
        return 1
    }

    /** Shared text-format builder for the report engine — the chat-friendly transcript form.
     *  Backs both `/ius transcript <name>` (printed to chat) and `/ius report <name> text`
     *  (copied to clipboard), so the two commands share one source of truth. Includes the
     *  session stats + moderator note that the markdown/json forms don't surface. */
    private fun reportText(uuid: java.util.UUID, name: String): String {
        val sb = StringBuilder()
        val tier = FlagHistory.tierFor(uuid)
        val score = FlagHistory.confidenceScore(uuid)
        val st = SessionStats.stats(uuid)
        sb.append(L10n.s("iustitia.cmd.report.transcriptHeader", name, tier.label, score))
        sb.append(L10n.s("iustitia.cmd.report.sessionStats", st.swings.get(), st.hits.get(), st.velocity.get()))
        sb.append(L10n.s("iustitia.cmd.report.alertsFlags", FlagHistory.sessionAlertCount(uuid), FlagHistory.flagCounts(uuid).values.sum()))
        FlagHistory.topCheck(uuid)?.let { sb.append(L10n.s("iustitia.cmd.report.topSuffix", it)) }
        sb.append("\n")
        NoteStore.get(uuid)?.let { n -> sb.append(L10n.s("iustitia.cmd.report.noteLine", n.category.name.lowercase(), n.text)) }
        // One flags snapshot (FlagHistory.flags locks + copies the deque) — reuse it for the header
        // count AND the iteration, instead of fetching it twice.
        val flags = FlagHistory.flags(uuid).takeLast(REPORT_TIMELINE_CAP)
        sb.append(L10n.s("iustitia.cmd.report.timelineList", flags.size))
        if (flags.isEmpty()) sb.append(L10n.s("iustitia.cmd.report.noFlagsRecorded"))
        flags.forEach { f ->
            sb.append(" @t${f.tick} ${f.checkId} (${f.label}) vl=${fmt(f.vl)}")
            // Reuse the shared [evidenceMd] formatter so the text form carries the same fields as
            // markdown/json (notably pos) instead of a near-duplicate that dropped the coordinate.
            f.evidence?.let { ev ->
                val evTxt = evidenceMd(ev)
                if (evTxt.isNotEmpty()) sb.append(" ").append(evTxt)
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun transcriptToggle(ctx: CommandContext<FabricClientCommandSource>): Int {
        val mc = MinecraftClient.getInstance()
        try {
            if (mc.currentScreen is TranscriptPanelScreen) { mc.setScreen(null); send(ctx, L10n.s("iustitia.cmd.transcriptPanelClosed", tag)); return 1 }
            val target = crosshairTarget()
            if (target == null) { send(ctx, L10n.s("iustitia.cmd.transcriptPanelLook", tag)); return 0 }
            mc.execute { try { mc.setScreen(TranscriptPanelScreen(target.first, target.second, null)) } catch (_: Throwable) {} }
            send(ctx, L10n.s("iustitia.cmd.transcriptPanelOpen", tag, target.second))
        } catch (_: Throwable) { send(ctx, L10n.s("iustitia.cmd.transcriptPanelFail", tag)); }
        return 1
    }

    /** `/ius transcript panel <name>` — open the side panel for a named player (no crosshair needed). */
    private fun transcriptPanelNamed(ctx: CommandContext<FabricClientCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val uuid = resolveUuid(name)
        if (uuid == null) { send(ctx, L10n.s("iustitia.cmd.noData", tag, name)); return 0 }
        val mc = MinecraftClient.getInstance()
        mc.execute { try { mc.setScreen(TranscriptPanelScreen(uuid, name, null)) } catch (_: Throwable) {} }
        send(ctx, L10n.s("iustitia.cmd.transcriptPanelOpen", tag, name))
        return 1
    }

    // ---- evidence (#5) ----
    private fun evidence(ctx: CommandContext<FabricClientCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val uuid = resolveUuid(name)
        if (uuid == null) { send(ctx, L10n.s("iustitia.cmd.noDataShort", tag, name)); return 0 }
        val window = ConfigManager.config.evidenceWindowTicks
        val now = dev.iustitia.Iustitia.tickCounter
        val recent = try { FlagHistory.flags(uuid).filter { now - it.tick <= window } } catch (_: Throwable) { emptyList() }
        if (recent.isEmpty()) { send(ctx, L10n.s("iustitia.cmd.evidenceNone", tag, name, window / 20)); return 1 }
        val grouped = recent.groupBy { it.checkId }.toList().sortedByDescending { it.second.size }
        val parts = grouped.take(5).map { (cid, list) ->
            val maxVl = list.maxOf { it.vl }
            val meas = list.mapNotNull { it.evidence?.measurement }.maxOrNull()
            val mv = if (meas != null) " ${fmt(meas)}" else ""
            if (list.size > 1) "$cid$mv ×${list.size}" else "$cid$mv"
        }
        val tier = FlagHistory.tierFor(uuid)
        val line = L10n.s("iustitia.cmd.evidenceLine", tag, name, window / 20, parts.joinToString(", "), tier.label, FlagHistory.confidenceScore(uuid))
        send(ctx, line)
        // Honest save result (mirrors the transcript export path).
        if (try { ConfigManager.config.persistenceEnabled } catch (_: Throwable) { false }) {
            val exported = try { PersistenceManager.saveExport("evidence", name, line) } catch (_: Throwable) { false }
            if (!exported) send(ctx, L10n.s("iustitia.cmd.evidenceExportFail", tag))
        }
        return 1
    }

    // ---- note (#8) ----
    private fun noteShow(ctx: CommandContext<FabricClientCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val uuid = resolveUuid(name) ?: run { send(ctx, L10n.s("iustitia.cmd.unknownPlayer", tag, name)); return 0 }
        val note = NoteStore.get(uuid)
        if (note == null) { send(ctx, L10n.s("iustitia.cmd.noteNone", tag, name)); return 1 }
        send(ctx, L10n.s("iustitia.cmd.noteShow", tag, name, NoteStore.categoryLabel(note.category), note.text))
        return 1
    }

    private fun noteSet(ctx: CommandContext<FabricClientCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val catRaw = StringArgumentType.getString(ctx, "category")
        val text = StringArgumentType.getString(ctx, "text")
        val cat = NoteStore.parseCategory(catRaw)
        if (cat == null) { send(ctx, L10n.s("iustitia.cmd.noteUnknownCategory", tag, catRaw)); return 0 }
        val uuid = resolveUuid(name) ?: run { send(ctx, L10n.s("iustitia.cmd.unknownPlayer", tag, name)); return 0 }
        NoteStore.set(uuid, name, cat, text, dev.iustitia.Iustitia.tickCounter)
        send(ctx, L10n.s("iustitia.cmd.noteSet", tag, name, NoteStore.categoryLabel(cat), text))
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
        send(ctx, L10n.s("iustitia.cmd.sessionHeader", tag))
        send(ctx, L10n.s("iustitia.cmd.playersTracked", uuids.size))
        send(ctx, L10n.s("iustitia.cmd.sessionTiers", green, yellow, red))
        send(ctx, L10n.s("iustitia.cmd.alertsThisSession", FlagHistory.totalAlerts))
        if (peakName != null && peakScore > 0) send(ctx, L10n.s("iustitia.cmd.sessionPeak", peakName, peakScore))
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
        if (target == null) { send(ctx, L10n.s("iustitia.cmd.snapshotLook", tag)); return 0 }
        Snapshot.capture(target.first, target.second)
        send(ctx, L10n.s("iustitia.cmd.snapshotPosted", tag, target.second))
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
        if (companionOwnsWatch(ctx)) return 1
        val mc = MinecraftClient.getInstance()
        val active = try { dev.iustitia.render.WatchState.active } catch (_: Throwable) { false }
        // Explicit stop.
        if (nameArg != null && nameArg.equals("off", ignoreCase = true)) {
            if (active) {
                val reason = dev.iustitia.render.WatchState.disableNow("disabled")
                send(ctx, L10n.s("iustitia.cmd.spectateStopped", tag, reason))
            } else {
                send(ctx, L10n.s("iustitia.cmd.spectateNotWatching", tag))
            }
            return 1
        }
        if (!ConfigManager.config.watchFollowCam) {
            send(ctx, L10n.s("iustitia.cmd.spectateDisabled", tag))
            return 0
        }
        // Bare command while already watching → toggle off (press-again semantics).
        if (nameArg == null && active) {
            val reason = dev.iustitia.render.WatchState.disableNow("disabled")
            send(ctx, L10n.s("iustitia.cmd.spectateStopped", tag, reason))
            return 1
        }
        val target: Pair<java.util.UUID, String>? = if (nameArg != null) {
            resolveUuid(nameArg)?.let { it to nameArg }
        } else crosshairTarget()
        if (target == null) {
            send(ctx, if (nameArg == null)
                L10n.s("iustitia.cmd.spectateLook", tag)
                else L10n.s("iustitia.cmd.noData", tag, nameArg))
            return 0
        }
        // The watched player must be currently loaded so the camera can position on them; if not,
        // the render-thread target-gone path would just immediately cancel the watch.
        val loaded = try { mc.world?.getPlayerByUuid(target.first) != null } catch (_: Throwable) { false }
        if (!loaded) {
            send(ctx, L10n.s("iustitia.cmd.spectateNotRendered", tag, target.second))
            return 0
        }
        // Switching from another target: restore the saved state first so the forced HUD/perspective
        // isn't re-saved as the new baseline (would lose the user's original view on exit).
        if (active) { try { dev.iustitia.render.WatchState.disableNow("switched") } catch (_: Throwable) {} }
        dev.iustitia.render.WatchState.enable(target.first)
        send(ctx, L10n.s("iustitia.cmd.spectateWatching", tag, target.second))
        return 1
    }

    // ---- replay / clip / playclip (Phase 2 instant-replay suite) ----
    /** `/ius replay off` + `/ius playclip off` — stop any active replay/clip-playback; live rendering
     *  snaps back immediately (hide-live mixin re-enables). Idempotent. */
    private fun replayStop(ctx: CommandContext<FabricClientCommandSource>): Int {
        if (companionOwnsReplay(ctx)) return 1
        val active = try { dev.iustitia.replay.ReplayState.active } catch (_: Throwable) { false }
        if (!active) { send(ctx, L10n.s("iustitia.cmd.replayNone", tag)); return 1 }
        dev.iustitia.replay.ReplayState.stop("stopped")
        send(ctx, L10n.s("iustitia.cmd.replayStopped", tag))
        return 1
    }

    // ---- replay playback controls (pause/seek/step/speed/cam) — all no-op + chat if no replay running ----

    private fun replayPause(ctx: CommandContext<FabricClientCommandSource>): Int {
        if (!replayActive(ctx)) return 1
        val paused = dev.iustitia.replay.ReplayState.togglePause()
        send(ctx, L10n.s("iustitia.cmd.replayPauseToggle", tag, if (paused) L10n.s("iustitia.cmd.replayPaused") else L10n.s("iustitia.cmd.replayResumed")))
        return 1
    }

    private fun replayResume(ctx: CommandContext<FabricClientCommandSource>): Int {
        if (!replayActive(ctx)) return 1
        if (dev.iustitia.replay.ReplayState.isPaused()) { dev.iustitia.replay.ReplayState.togglePause(); send(ctx, L10n.s("iustitia.cmd.replayResumedLine", tag)) }
        else send(ctx, L10n.s("iustitia.cmd.replayAlreadyPlaying", tag))
        return 1
    }

    private fun replaySeek(ctx: CommandContext<FabricClientCommandSource>): Int {
        if (!replayActive(ctx)) return 1
        val secs = DoubleArgumentType.getDouble(ctx, "seconds").toFloat()
        // negative = relative scrub backward (same as the numpad − keybind), positive = absolute
        // seek to that timestamp — matching the keybind capability the command previously lacked.
        if (secs < 0) {
            dev.iustitia.replay.ReplayState.seekBy(secs)
            send(ctx, L10n.s("iustitia.cmd.replaySeekBack", tag, NumFmt.d(digits = 1, v = -secs)))
        } else {
            dev.iustitia.replay.ReplayState.seekTo(secs)
            send(ctx, L10n.s("iustitia.cmd.replaySeekTo", tag, NumFmt.d(digits = 1, v = secs)))
        }
        return 1
    }

    private fun replayStep(ctx: CommandContext<FabricClientCommandSource>, dir: Int): Int {
        if (!replayActive(ctx)) return 1
        if (!dev.iustitia.replay.ReplayState.isPaused()) {
            send(ctx, L10n.s("iustitia.cmd.replayStepNeedsPause", tag)); return 1
        }
        dev.iustitia.replay.ReplayState.step(dir)
        send(ctx, L10n.s("iustitia.cmd.replayStepped", tag, if (dir > 0) L10n.s("iustitia.cmd.replayForward") else L10n.s("iustitia.cmd.replayBackward")))
        return 1
    }

    private fun replaySpeed(ctx: CommandContext<FabricClientCommandSource>, speedArg: String): Int {
        if (!replayActive(ctx)) return 1
        val sp = when (speedArg) { "1", "1.0" -> dev.iustitia.replay.ReplayState.SPEED_FULL
            "0.25" -> dev.iustitia.replay.ReplayState.SPEED_QUARTER
            "0.5" -> dev.iustitia.replay.ReplayState.SPEED_HALF
            else -> { send(ctx, L10n.s("iustitia.cmd.replaySpeedInvalid", tag)); return 0 } }
        dev.iustitia.replay.ReplayState.setSpeed(sp)
        send(ctx, L10n.s("iustitia.cmd.replaySpeedSet", tag, NumFmt.d(digits = 2, v = sp)))
        return 1
    }

    private fun replayCam(ctx: CommandContext<FabricClientCommandSource>, modeArg: String): Int {
        if (!replayActive(ctx)) return 1
        val mode = when (modeArg.lowercase()) {
            "free" -> dev.iustitia.replay.ReplayState.CameraMode.FREE
            "follow" -> dev.iustitia.replay.ReplayState.CameraMode.FOLLOW
            "pov" -> dev.iustitia.replay.ReplayState.CameraMode.POV
            "freecam" -> dev.iustitia.replay.ReplayState.CameraMode.FREECAM
            else -> { send(ctx, L10n.s("iustitia.cmd.replayCamInvalid", tag)); return 0 } }
        // FREECAM needs a chunk world to fly through — it's the free-spectate mode for a chunk-bearing
        // /ius playclip. Refuse (with a hint) when there's no chunk world so the camera doesn't end up
        // floating in void with nothing to look at.
        if (mode == dev.iustitia.replay.ReplayState.CameraMode.FREECAM &&
            dev.iustitia.replay.ReplayState.chunks == null) {
            send(ctx, L10n.s("iustitia.cmd.replayFreecamNeedsClip", tag))
            return 0
        }
        dev.iustitia.replay.ReplayState.setCameraMode(mode)
        val label = when (mode) { dev.iustitia.replay.ReplayState.CameraMode.FREE -> L10n.s("iustitia.cmd.camModeFree")
            dev.iustitia.replay.ReplayState.CameraMode.FOLLOW -> L10n.s("iustitia.cmd.camModeFollow")
            dev.iustitia.replay.ReplayState.CameraMode.POV -> L10n.s("iustitia.cmd.camModePov")
            dev.iustitia.replay.ReplayState.CameraMode.FREECAM -> L10n.s("iustitia.cmd.camModeFreecam") }
        send(ctx, L10n.s("iustitia.cmd.replayCamSet", tag, label))
        return 1
    }

    /** Common guard for the control subcommands: chat + return false if no replay is running. */
    private fun replayActive(ctx: CommandContext<FabricClientCommandSource>): Boolean {
        if (companionOwnsReplay(ctx)) return false
        val active = try { dev.iustitia.replay.ReplayState.active } catch (_: Throwable) { false }
        if (!active) send(ctx, L10n.s("iustitia.cmd.replayNoneHint", tag))
        return active
    }

    /** Map a "1"/"0.5"/"0.25" speed arg to a [dev.iustitia.replay.ReplayState] speed. `null` (and "1")
     *  → FULL speed — the default for both `/ius replay` and `/ius playclip`. Any other value sends a
     *  usage error and returns null (caller aborts). Shared by [replay] and [playclip]. */
    private fun parseSpeed(ctx: CommandContext<FabricClientCommandSource>, speedArg: String?): Float? = when (speedArg) {
        null, "1", "1.0" -> dev.iustitia.replay.ReplayState.SPEED_FULL
        "0.5" -> dev.iustitia.replay.ReplayState.SPEED_HALF
        "0.25" -> dev.iustitia.replay.ReplayState.SPEED_QUARTER
        else -> { send(ctx, L10n.s("iustitia.cmd.speedInvalid", tag)); null }
    }

    /** Default window (seconds) when none is given — bare `/ius replay` or `/ius replay <name>`. */
    private val DEFAULT_REPLAY_SECS: Int = 30

    /** The report/transcript timeline promises "last 50" — enforce it on every surface (markdown,
     *  JSON, text) so a heavy session can't bloat the clipboard copy or a chat dump. */
    private const val REPORT_TIMELINE_CAP = 50

    /** `/ius replay [<target>] [<seconds>] [1|0.5|0.25]` — reconstruct an "instant replay" from the rolling
     *  capture buffer: ghosts of every tracked player at their buffered positions, played back at FULL
     *  speed by default (add 0.5 or 0.25 for slow-mo), with the live world hidden (rewind feel) by
     *  default.
     *
     *  `<target>` is overloaded so the player arg is OPTIONAL:
     *   - `/ius replay 60`        → 60s, no focus, 1× (a NUMBER = the duration).
     *   - `/ius replay thoria`    → default window, focus thoria, 1× (a NAME = the focus player).
     *   - `/ius replay thoria 60` → 60s, focus thoria, 1×; add a speed for slow-mo.
     *   - `/ius replay`            → default window, no focus, 1×.
     *  A name that isn't tracked/online still replays everyone (no focus) with a warning. Stops on
     *  finish / world-change / re-run. Fail-open, client-only. */
    private fun replay(ctx: CommandContext<FabricClientCommandSource>, target: String?, speedArg: String?): Int {
        if (companionOwnsReplay(ctx)) return 1
        val cfg = ConfigManager.config
        if (!cfg.replayCapture) {
            send(ctx, L10n.s("iustitia.cmd.replayCaptureDisabled", tag))
            return 0
        }
        val speed = parseSpeed(ctx, speedArg) ?: return 0
        // Resolve focus + seconds from the overloaded <target>:
        //   null → default window, no focus · a number → that many seconds (fractional ok), no focus
        //   · a name → focus (if tracked) + the optional <seconds> arg (or default), no focus if the
        //   name is unknown.
        val focus: java.util.UUID?
        val secs: Int
        val focusTxt: String
        when {
            target == null -> { focus = null; secs = DEFAULT_REPLAY_SECS; focusTxt = L10n.s("iustitia.cmd.replayEveryone") }
            target.toDoubleOrNull() != null -> {
                focus = null
                // A trailing <seconds> arg wins over the target number (`/ius replay 60 30` parses
                // "60" as <target> and "30" as <seconds>). A value below 1.0 cannot reach this
                // node at all (the argument is `doubleArg(1.0, 60.0)`), so "0.5" is only ever
                // the [speed] word. Without this read the trailing value was silently
                // discarded and the target number used instead. A fractional <target>
                // (`/ius replay 1.5`) resolves as seconds here, not as a player name.
                val s = try { DoubleArgumentType.getDouble(ctx, "seconds") } catch (_: Throwable) { -1.0 }
                secs = (if (s >= 1.0) s else target.toDouble()).toInt()
                    .coerceIn(1, dev.iustitia.replay.ReplayBuffer.MAX_SECONDS)
                focusTxt = L10n.s("iustitia.cmd.replayEveryone")
            }
            else -> {
                val uuid = resolveUuid(target)
                focus = uuid
                val s = try { DoubleArgumentType.getDouble(ctx, "seconds") } catch (_: Throwable) { -1.0 }
                secs = if (s >= 1.0) s.toInt().coerceIn(1, dev.iustitia.replay.ReplayBuffer.MAX_SECONDS) else DEFAULT_REPLAY_SECS
                if (uuid == null) {
                    send(ctx, L10n.s("iustitia.cmd.replayNoTrack", tag, target))
                    focusTxt = L10n.s("iustitia.cmd.replayEveryone")
                } else {
                    focusTxt = target
                }
            }
        }
        val now = dev.iustitia.Iustitia.tickCounter
        val window = try { dev.iustitia.replay.ReplayBuffer.snapshot(secs, now) } catch (_: Throwable) {
            dev.iustitia.replay.ReplayBuffer.Window(emptyList(), emptyList())
        }
        if (window.frames.isEmpty()) {
            send(ctx, L10n.s("iustitia.cmd.replayNoBuffer", tag, secs))
            return 0
        }
        val started = try { dev.iustitia.replay.ReplayState.start(window, focus, speed, cfg.replayHideLive, relocate = false, legacy = false) } catch (_: Throwable) { false }
        if (!started) { send(ctx, L10n.s("iustitia.cmd.replayStartFail", tag)); return 0 }
        val hideTxt = if (cfg.replayHideLive) L10n.s("iustitia.cmd.replayHideLiveSuffix") else ""
        send(ctx, L10n.s("iustitia.cmd.replayStarted", tag, secs, focusTxt, NumFmt.d(digits = 2, v = speed), hideTxt))
        return 1
    }

    /** `/ius replay save <name>` — export the active replay's window to a `.iusclip` without exiting
     *  the replay. Requires an active replay (playing or held); errors if none. Contents match the
     *  configured playclipMode: MODERN → frames + alerts + the per-segment world/block edits from the
     *  rolling capture (a one-shot sweep is the fallback when nothing was rolled up) ; LEGACY → frames +
     *  alerts only. The replay keeps running after a save (you can save again / keep watching). Fail-open. */
    private fun replaySave(ctx: CommandContext<FabricClientCommandSource>, nameArg: String): Int {
        if (companionOwnsReplay(ctx)) return 1
        if (!dev.iustitia.replay.ReplayState.active) {
            send(ctx, L10n.s("iustitia.cmd.replaySaveNoActive", tag))
            return 0
        }
        val cfg = ConfigManager.config
        val focus = dev.iustitia.replay.ReplayState.focusUuid
        val base = try { dev.iustitia.replay.ReplayState.exportWindow() } catch (_: Throwable) {
            dev.iustitia.replay.ReplayBuffer.Window(emptyList(), emptyList())
        }
        if (base.frames.isEmpty()) { send(ctx, L10n.s("iustitia.cmd.replaySaveEmpty", tag)); return 0 }
        // Capture terrain + chunks at save time only when MODERN and the replay isn't already
        // carrying them (a /ius replay window has none; a playclip-modern window already has them).
        val modern = cfg.playclipMode == dev.iustitia.config.IustitiaConfig.PlayclipMode.MODERN
        // Attach the per-segment world + block deltas from the rolling capture. A live `/ius replay`
        // window carries none (it renders over the live world), so it's derived here; a
        // playclip-modern window already owns its segments and is kept as-is.
        val withSegments = if (modern && base.segments.isEmpty()) {
            try { base.copy(segments = dev.iustitia.replay.ReplayBuffer.segmentsFor(base.frames)) } catch (_: Throwable) { base }
        } else base
        val w1 = if (modern && cfg.clipTerrain && withSegments.terrain == null) {
            try { withSegments.copy(terrain = dev.iustitia.replay.TerrainCapture.capture(withSegments, focus)) } catch (_: Throwable) { withSegments }
        } else withSegments
        val w2 = if (modern && cfg.clipChunkWorld) ensureClipWorld(w1, cfg) else w1
        val saved = try { dev.iustitia.replay.ClipStore.save(nameArg, w2, focus) } catch (_: Throwable) { null }
        if (saved == null) { send(ctx, L10n.s("iustitia.cmd.clipWriteFail", tag)); return 0 }
        val frames = w2.frames.size
        val alerts = w2.alerts.size
        val blocks = w2.terrain?.nonAirCount() ?: 0
        val terrainTxt = if (blocks > 0) L10n.s("iustitia.cmd.clipTerrainSuffix", blocks) else ""
        val chunkSections = w2.chunks?.sectionCount()
            ?: w2.segments.sumOf { it.chunks?.sectionCount() ?: 0 }
        val chunksTxt = if (chunkSections > 0) L10n.s("iustitia.cmd.clipChunksSuffix", chunkSections) else ""
        val segCount = w2.segments.size
        val segTxt = if (segCount > 1) L10n.s("iustitia.cmd.clipSegmentsSuffix", segCount) else ""
        val deltaCount = w2.segments.sumOf { it.blockDeltas.size }
        val deltaTxt = if (deltaCount > 0) L10n.s("iustitia.cmd.clipDeltasSuffix", deltaCount) else ""
        send(ctx, L10n.s("iustitia.cmd.replaySavedAsClip", tag, saved, frames, alerts, terrainTxt, chunksTxt, segTxt, deltaTxt, dev.iustitia.replay.ClipStore.dirDisplay()))
        send(ctx, L10n.s("iustitia.cmd.replaySavedPlayHint", saved))
        return 1
    }

    /**
     * Guarantee an exported clip window carries a world. The per-segment rolling capture has normally
     * already rolled it up (no export-time sweep — the whole point of the v13 segments), so this is a
     * no-op then. It sweeps once, synchronously, only when a segment has nothing — attaching the result
     * to the first segment that lacks a world (or the top-level `chunks` when a window carries no
     * segments) so the clip still opens as a solid world instead of ghosts-only. Fail-open: an error
     * exports a world-less clip rather than failing the command.
     */
    private fun ensureClipWorld(
        window: dev.iustitia.replay.ReplayBuffer.Window,
        cfg: dev.iustitia.config.IustitiaConfig,
    ): dev.iustitia.replay.ReplayBuffer.Window {
        if (window.chunks != null) return window
        // Fill the FIRST segment that has no world (normally the only one — a teleport is what creates a
        // second). A clip whose later segments already carry their own capture therefore needs no sweep.
        val missing = window.segments.indexOfFirst { it.chunks == null }
        if (window.segments.isNotEmpty() && missing < 0) return window
        return try {
            val radius = try { cfg.clipChunkRadius } catch (_: Throwable) { 8 }
            val map = dev.iustitia.replay.ChunkCapture.capture(radius) ?: return window
            if (window.segments.isEmpty()) window.copy(chunks = map)
            else window.copy(segments = window.segments.mapIndexed { i, s -> if (i == missing) s.copy(chunks = map) else s })
        } catch (_: Throwable) { window }
    }

    /** `/ius clip <seconds> [name]` — dump the last N seconds of positions + alerts to a portable
     *  `.iusclip` file under `%APPDATA%/.iustitia/clips` (explicit export, always writes regardless
     *  of the persistence toggle). [name] is the clip's FILENAME (verbatim) so `/ius playclip <name>`
     *  round-trips; it also sets the focus player when it matches someone online. Omitted → `scene_<tick>`. Fail-open. */
    private fun clip(ctx: CommandContext<FabricClientCommandSource>, nameArg: String?): Int {
        if (companionOwnsReplay(ctx)) return 1
        val cfg = ConfigManager.config
        if (!cfg.replayCapture) {
            send(ctx, L10n.s("iustitia.cmd.clipCaptureDisabled", tag))
            return 0
        }
        val secs = DoubleArgumentType.getDouble(ctx, "seconds").toInt().coerceIn(1, dev.iustitia.replay.ReplayBuffer.MAX_SECONDS)
        val focus: java.util.UUID? = if (nameArg != null) resolveUuid(nameArg) else null
        // LEGACY exports frames + alerts only (v1.1.0 was ghosts over the live world), so it must not
        // pull in the captured world; MODERN exports the per-segment world + block deltas.
        val modern = cfg.playclipMode == dev.iustitia.config.IustitiaConfig.PlayclipMode.MODERN
        val now = dev.iustitia.Iustitia.tickCounter
        val window = try {
            if (modern) dev.iustitia.replay.ReplayBuffer.snapshotForExport(secs, now)
            else dev.iustitia.replay.ReplayBuffer.snapshot(secs, now)
        } catch (_: Throwable) {
            dev.iustitia.replay.ReplayBuffer.Window(emptyList(), emptyList())
        }
        if (window.frames.isEmpty()) {
            send(ctx, L10n.s("iustitia.cmd.replayNoBuffer", tag, secs))
            return 0
        }
        // The clip FILENAME is the user's [name] verbatim — so `/ius playclip <name>` round-trips.
        // Only auto-name (scene_<tick>) when [name] is omitted. [name] ALSO doubles as the focus
        // player: resolveUuid returns null for a non-player string, so `/ius clip 10 myclip` saves
        // `myclip.iusclip` with no focus, while `/ius clip 10 thoria` saves `thoria.iusclip` AND
        // highlights thoria if they're online. Filename is sanitized in ClipStore.save.
        val clipName = nameArg ?: "scene_${now}"
        // Optionally snapshot the loaded terrain around the action so /ius playclip can render the
        // map around the user on a different server/dimension. Client only has loaded chunks in
        // render distance, so capture is the action bbox + margin (volume-capped) — fail-open to a
        // terrain-less clip if capture throws or clipTerrain is off. Replay never carries terrain
        // (ReplayBuffer.snapshot builds a terrain-null window), so this only affects clips.
        // Legacy mode never downloads the world (v1.1.0 was ghosts-only) — terrain + chunk capture
        // are gated on Modern regardless of the clipTerrain/clipChunkWorld toggles.
        val windowWithTerrain = if (modern && cfg.clipTerrain) {
            try { window.copy(terrain = dev.iustitia.replay.TerrainCapture.capture(window, focus)) } catch (_: Throwable) { window }
        } else window
        // The world normally rides along per segment, already rolled up in small per-tick pieces while
        // the scene was live ([dev.iustitia.replay.ReplayBuffer.snapshotForExport]) — so saving a clip
        // no longer freezes the client sweeping ~300 chunks. Only when the rolling capture had nothing
        // for this window (fresh session / toggle just enabled) does [ensureClipWorld] sweep once.
        val windowWithWorld = if (modern && cfg.clipChunkWorld) ensureClipWorld(windowWithTerrain, cfg) else windowWithTerrain
        val saved = try { dev.iustitia.replay.ClipStore.save(clipName, windowWithWorld, focus) } catch (_: Throwable) { null }
        if (saved == null) {
            send(ctx, L10n.s("iustitia.cmd.clipWriteFail", tag))
            return 0
        }
        val frames = window.frames.size
        val alerts = window.alerts.size
        val blocks = windowWithWorld.terrain?.nonAirCount() ?: 0
        val terrainTxt = if (blocks > 0) L10n.s("iustitia.cmd.clipTerrainSuffix", blocks) else ""
        // Chunk sections can live on the top-level snapshot (pre-v13/world-less-segments export) or
        // inside per-segment snapshots; count both so the feedback line matches what was written.
        val chunkSections = windowWithWorld.chunks?.sectionCount()
            ?: windowWithWorld.segments.sumOf { it.chunks?.sectionCount() ?: 0 }
        val chunksTxt = if (chunkSections > 0) L10n.s("iustitia.cmd.clipChunksSuffix", chunkSections) else ""
        val segCount = windowWithWorld.segments.size
        val segTxt = if (segCount > 1) L10n.s("iustitia.cmd.clipSegmentsSuffix", segCount) else ""
        val deltaCount = windowWithWorld.segments.sumOf { it.blockDeltas.size }
        val deltaTxt = if (deltaCount > 0) L10n.s("iustitia.cmd.clipDeltasSuffix", deltaCount) else ""
        send(ctx, L10n.s("iustitia.cmd.clipSaved", tag, saved, frames, alerts, secs, terrainTxt, chunksTxt, segTxt, deltaTxt, dev.iustitia.replay.ClipStore.dirDisplay()))
        send(ctx, L10n.s("iustitia.cmd.replaySavedPlayHint", saved))
        return 1
    }

    /** `/ius playclip [name] [1|0.5|0.25]` — load a `.iusclip` and play it back in-world as ghost
     *  positions at FULL speed by default (like `/ius replay` but from a saved file). No name = list
     *  saved clips. Validates the speed arg, then delegates load → start to [ClipPlayback] (shared with
     *  the clip-manager screen's left-click Play) so the two entry points can't drift. Fail-open. */
    private fun playclip(ctx: CommandContext<FabricClientCommandSource>, nameArg: String?, speedArg: String?): Int {
        if (companionOwnsReplay(ctx)) return 1
        if (nameArg == null) {
            val clips = try { dev.iustitia.replay.ClipStore.list() } catch (_: Throwable) { emptyList() }
            if (clips.isEmpty()) { send(ctx, L10n.s("iustitia.cmd.clipsNone", tag)); return 1 }
            send(ctx, L10n.s("iustitia.cmd.clipsHeader", tag, dev.iustitia.replay.ClipStore.dirDisplay()))
            clips.forEach { send(ctx, " §f$it §7— §f/ius playclip $it") }
            return 1
        }
        val speed = parseSpeed(ctx, speedArg) ?: return 0
        when (val r = ClipPlayback.start(nameArg, speed)) {
            is ClipPlayback.Result.Started -> {
                val focusTxt = r.focus?.let { L10n.s("iustitia.cmd.clipFocusSuffix", FlagHistory.nameOrShort(it)) } ?: ""
                send(ctx, L10n.s("iustitia.cmd.clipPlaying", tag, nameArg, NumFmt.d(digits = 2, v = speed), r.frames, focusTxt))
                return 1
            }
            is ClipPlayback.Result.LoadFailed -> {
                val why = r.reason?.let { " §8— $it" } ?: ""
                send(ctx, L10n.s("iustitia.cmd.clipNotFound", tag, nameArg, why))
            }
            ClipPlayback.Result.StartFailed -> {
                send(ctx, L10n.s("iustitia.cmd.clipStartFail", tag))
            }
        }
        return 0
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

    /** `/ius clips` — open the clip manager (list saved `.iusclip` files with Play + Delete). */
    private fun clipsScreen(ctx: CommandContext<FabricClientCommandSource>): Int {
        if (companionOwnsReplay(ctx)) return 1
        val mc = MinecraftClient.getInstance()
        mc.execute { try { mc.setScreen(dev.iustitia.ui.ClipManagerScreen(mc.currentScreen)) } catch (_: Throwable) {} }
        return 1
    }

    /** `/ius deleteclip <name>` (alias `/ius delclip <name>`) — delete a saved `.iusclip` by name.
     *  Wires the existing [dev.iustitia.replay.ClipStore.delete]; fail-open with chat feedback. */
    private fun deleteClip(ctx: CommandContext<FabricClientCommandSource>): Int {
        if (companionOwnsReplay(ctx)) return 1
        val name = StringArgumentType.getString(ctx, "name")
        val ok = try { dev.iustitia.replay.ClipStore.delete(name) } catch (_: Throwable) { false }
        if (ok) {
            send(ctx, L10n.s("iustitia.cmd.clipDeleted", tag, name, dev.iustitia.replay.ClipStore.dirDisplay()))
        } else {
            send(ctx, L10n.s("iustitia.cmd.clipDeleteMissing", tag, name))
        }
        return if (ok) 1 else 0
    }

    /** `/ius preset` / `/ius presets` — list all presets (built-ins + customs). The built-in lines
     *  carry the profile's own blurb, so a reader learns what a profile costs before applying it and
     *  not after; the same wording the setup wizard's buttons show. */
    private fun presetList(ctx: CommandContext<FabricClientCommandSource>): Int {
        send(ctx, L10n.s("iustitia.cmd.presetsHeader", tag))
        for (b in dev.iustitia.config.PresetManager.builtIns) {
            val mark = when {
                b.recommended -> L10n.s("iustitia.cmd.presetMarkRecommended")
                b.diagnostic -> L10n.s("iustitia.cmd.presetMarkDiagnostic")
                else -> ""
            }
            send(ctx, L10n.s("iustitia.cmd.presetRow", L10n.s("iustitia.preset.${b.name}.label"), mark, b.name))
            b.blurb.indices.forEach { i ->
                send(ctx, L10n.s("iustitia.cmd.presetBlurb", L10n.s("iustitia.preset.${b.name}.blurb$i")))
            }
        }
        val customs = try { dev.iustitia.config.PresetManager.listCustom() } catch (_: Throwable) { emptyList() }
        if (customs.isEmpty()) {
            send(ctx, L10n.s("iustitia.cmd.presetsNone"))
        } else {
            customs.forEach { n -> send(ctx, L10n.s("iustitia.cmd.presetCustomRow", n, n, n)) }
        }
        return 1
    }

    /** `/ius preset <name>` — apply a built-in or custom preset to the live config + save. */
    private fun presetApply(ctx: CommandContext<FabricClientCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val ok = try { dev.iustitia.config.PresetManager.apply(name) } catch (_: Throwable) { false }
        if (ok) {
            val kind = if (dev.iustitia.config.PresetManager.isBuiltIn(name)) L10n.s("iustitia.cmd.presetKindBuiltin") else L10n.s("iustitia.cmd.presetKindCustom")
            send(ctx, L10n.s("iustitia.cmd.presetApplied", tag, kind, name))
        } else {
            send(ctx, L10n.s("iustitia.cmd.presetApplyFailed", tag, name, dev.iustitia.config.PresetManager.builtInNames.joinToString("/")))
        }
        return if (ok) 1 else 0
    }

    /** `/ius createpreset <name>` — save the current config as a custom preset. */
    private fun presetCreate(ctx: CommandContext<FabricClientCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        if (dev.iustitia.config.PresetManager.isBuiltIn(name)) {
            send(ctx, L10n.s("iustitia.cmd.presetCreateBuiltinTaken", tag, name))
            return 0
        }
        val ok = try { dev.iustitia.config.PresetManager.saveCustom(name) } catch (_: Throwable) { false }
        if (ok) send(ctx, L10n.s("iustitia.cmd.presetCreateOk", tag, name, name))
        else send(ctx, L10n.s("iustitia.cmd.presetCreateFail", tag, name))
        return if (ok) 1 else 0
    }

    /** `/ius deletepreset <name>` — delete a custom preset (built-ins can't be deleted). */
    private fun presetDelete(ctx: CommandContext<FabricClientCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        if (dev.iustitia.config.PresetManager.isBuiltIn(name)) {
            send(ctx, L10n.s("iustitia.cmd.presetDeleteBuiltin", tag))
            return 0
        }
        val ok = try { dev.iustitia.config.PresetManager.deleteCustom(name) } catch (_: Throwable) { false }
        if (ok) send(ctx, L10n.s("iustitia.cmd.presetDeleteOk", tag, name))
        else send(ctx, L10n.s("iustitia.cmd.presetDeleteMissing", tag, name))
        return if (ok) 1 else 0
    }

    // ---- help ----
    private fun help(ctx: CommandContext<FabricClientCommandSource>, topic: String?): Int {
        if (topic == null) {
            send(ctx, L10n.s("iustitia.cmd.helpHeader", tag))
            subcommands.forEach { (s, d) -> send(ctx, " §f$s §7— $d") }
            send(ctx, CheckInfo.SEVERITY_LEGEND)
            send(ctx, L10n.s("iustitia.cmd.nametagLegend"))
            return 1
        }
        // subcommand?
        val sub = subcommands.firstOrNull { it.first.equals(topic, ignoreCase = true) }
        if (sub != null) { send(ctx, "$tag §f${sub.first} §7— ${sub.second}"); return 1 }
        // check?
        if (topic in checkIds) {
            val cc = ConfigManager.config.slice(topic)
            val tier = when {
                CheckInfo.isDefinitive(topic) -> L10n.s("iustitia.cmd.helpTierDefinitive")
                topic == "killAura" -> L10n.s("iustitia.cmd.helpTierCorroborator")
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
        send(ctx, L10n.s("iustitia.cmd.helpUnknownTopic", tag, topic))
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
        val state = if (cfg.alertsEnabled) L10n.s("iustitia.cmd.stateOn") else L10n.s("iustitia.cmd.stateOffMuted")
        send(ctx, L10n.s("iustitia.cmd.alertsList", tag, state))
        send(ctx, L10n.s("iustitia.cmd.alertsMutedChecks", tag, cfg.mutedChecks.joinToString(", ").ifEmpty { L10n.s("iustitia.cmd.none") }))
        val playerNames = cfg.mutedPlayers.joinToString(", ") { uuid ->
            try { FlagHistory.nameFor(UUID.fromString(uuid)) ?: uuid.take(8) }
            catch (_: Throwable) { uuid.take(8) }
        }
        send(ctx, L10n.s("iustitia.cmd.alertsMutedPlayers", tag, playerNames.ifEmpty { L10n.s("iustitia.cmd.none") }))
        send(ctx, L10n.s("iustitia.cmd.alertsHint"))
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
            send(ctx, L10n.s("iustitia.cmd.alertsCheckState", tag, checkMatch, if (nowOn) L10n.s("iustitia.cmd.stateOn") else L10n.s("iustitia.cmd.stateOffMuted")))
            return 1
        }
        // player name?
        val uuid = FlagHistory.resolveName(target)
        if (uuid == null) { send(ctx, L10n.s("iustitia.cmd.alertsUnknownTarget", tag, target)); return 0 }
        val key = uuid.toString()
        // Same semantics as the check path: nowOn=true ⟺ alerts ON (unmuted), false ⟺ muted.
        val nowOn = when (wantOn) {
            true  -> { cfg.mutedPlayers.remove(key); true }
            false -> { if (key !in cfg.mutedPlayers) cfg.mutedPlayers.add(key); false }
            null  -> if (key in cfg.mutedPlayers) { cfg.mutedPlayers.remove(key); true }
                     else { cfg.mutedPlayers.add(key); false }
        }
        ConfigManager.save()
        send(ctx, L10n.s("iustitia.cmd.alertsPlayerState", tag, target, if (nowOn) L10n.s("iustitia.cmd.stateOn") else L10n.s("iustitia.cmd.stateOffMuted")))
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

    /** Suggest known player names + already-exempted names (so toggling off an exempted player
     *  tab-completes even after they leave the tab list). */
    private fun suggestExempt(b: SuggestionsBuilder) {
        suggestNames(b)
        try {
            dev.iustitia.exempt.Exemptions.all().forEach { (_, name) ->
                val q = b.remaining
                if (q.isEmpty() || name.startsWith(q, ignoreCase = true)) b.suggest(name)
            }
        } catch (_: Throwable) {}
    }

    private fun suggestHelpTopics(b: SuggestionsBuilder) {
        suggestFiltered(b, subcommands.map { it.first })
        suggestFiltered(b, checkIds)
    }
}
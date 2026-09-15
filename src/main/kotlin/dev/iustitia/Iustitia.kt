package dev.iustitia

import dev.iustitia.alert.AlertManager
import dev.iustitia.checks.Check
import dev.iustitia.config.ConfigManager
import dev.iustitia.event.AttackEvent
import dev.iustitia.event.EventBus
import dev.iustitia.event.HurtSignal
import dev.iustitia.inference.AttackInference
import dev.iustitia.persistence.NoteStore
import dev.iustitia.session.Snapshot
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.ui.KeybindHubScreen
import dev.iustitia.ui.SessionScreen
import dev.iustitia.ui.TranscriptPanelScreen
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import java.util.UUID

/**
 * Singleton facade wiring the subsystems together: the typed [bus], the check
 * registry, the per-tick driver, and config/reload hooks. The client entrypoint
 * ([IustitiaClientMod]) calls [init] once and feeds [onClientTick] from
 * `ClientTickEvents.END_CLIENT_TICK`.
 *
 * Everything is fail-open: a thrown check or tracker error is swallowed so one bad
 * player or check never stops the tick or crashes the client.
 */
object Iustitia {

    val bus = EventBus()

    /** Last completed client tick; read by the mixin so packet signals get a stable tick. */
    @Volatile
    var tickCounter: Int = 0

    /**
     * Netty→client-thread handoff queue. Packet-thread callers (mixin handlers) must not
     * mutate engine state inline: they would race the client-tick driver (VL decay, tracker
     * poll, check processing) on plain, unsynchronized fields. [defer] enqueues a block;
     * the tick driver drains the queue at the top of [onClientTick] — BEFORE poll /
     * decayAll / process — so all engine mutations run single-threaded on the client
     * thread. Every event carries its own explicit tick stamp, so the ≤1-tick latency does
     * not affect correlation or exemption windows (all tick-difference based).
     */
    private val deferred = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    /** True iff the current thread is the client main thread (== render thread on 1.21.11). */
    fun onClientThread(): Boolean = try {
        MinecraftClient.getInstance().isOnThread
    } catch (_: Throwable) {
        // fail-open: when we can't tell (headless unit tests, very early boot), behave as
        // if on-thread so callers dispatch inline — no behavior change vs pre-queue.
        true
    }

    /** Enqueue [block] to run on the client thread at the start of the next client tick. */
    fun defer(block: () -> Unit) {
        deferred.add(block)
    }

    private fun drainDeferred() {
        while (true) {
            val block = deferred.poll() ?: return
            try { block() } catch (e: Throwable) { warnChokepoint("deferred event", e) }
        }
    }

    private val checks = mutableListOf<Check>()

    fun register(check: Check) {
        if (check !in checks) checks.add(check)
    }

    val allChecks: List<Check> get() = checks

    private val logger = org.slf4j.LoggerFactory.getLogger("Iustitia")

    /** Last wall-clock ms a [warnChokepoint] was emitted per subsystem key (10s rate limit). */
    private val lastChokeWarnMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Chokepoint failure warn: the tick driver swallows every per-check/per-subsystem throwable
     * (fail-open — one broken check must never crash the client), but a check that throws on
     * real server data was otherwise indistinguishable from a quiet server. The FIRST failure
     * per subsystem logs at warn WITH the stack; repeats within 10s are counted silently (the
     * VerboseLog heartbeat's exc=N shows them under verbose) so a persistently-broken subsystem
     * can't spam latest.log. Fail-open itself — the warn must never throw into the driver.
     */
    internal fun warnChokepoint(where: String, e: Throwable) {
        try {
            VerboseLog.countException()
            val now = System.currentTimeMillis()
            if (now - (lastChokeWarnMs[where] ?: 0L) < 10_000L) return
            lastChokeWarnMs[where] = now
            logger.warn("[Iustitia] $where threw (fail-open, rate-limited 10s): $e", e)
        } catch (_: Throwable) {}
    }

    /** Self-check: registered check ids == config slice ids. Catches a forgotten [dev.iustitia.config.IustitiaConfig.slice]
     *  branch (which would otherwise fall to slice()'s silent safe-default) or an orphan config slice
     *  (a stale persisted key, or a slice() branch whose check was never registered). Logs only; runs
     *  once at startup after every register() in [IustitiaClientMod]. Fail-open. */
    fun verifyCheckRegistry() {
        try {
            val registered = allChecks.map { it.id }.toSet()
            val sliced = try {
                dev.iustitia.config.ConfigManager.config.checks().map { it.first }.toSet()
            } catch (_: Throwable) { emptySet() }
            val missingSlice = registered - sliced
            val orphanSlice = sliced - registered
            if (missingSlice.isNotEmpty()) {
                logger.warn("[Iustitia] checks registered without a config slice() branch: ${missingSlice.sorted()} — slice() returns the safe default (disabled + max threshold) for these")
            }
            if (orphanSlice.isNotEmpty()) {
                logger.warn("[Iustitia] config slice() ids with no registered check: ${orphanSlice.sorted()} — orphan slices (stale config key, or a check that wasn't registered)")
            }
            if (missingSlice.isEmpty() && orphanSlice.isEmpty()) {
                logger.info("[Iustitia] check registry self-check OK: ${registered.size} checks, ${sliced.size} slices")
            }
        } catch (_: Throwable) {}
    }

    fun init() {
        ConfigManager.load()
        // Load the roaming persistence store (notes + tier/flag history) when the toggle is on.
        // No-op when off (session is in-memory only as before).
        try { dev.iustitia.persistence.PersistenceManager.loadOnStartup() } catch (_: Throwable) {}
        AttackInference.bind(bus)
        // Per-player swing/hit/velocity counters for the transcript feature (read-only taps).
        try { dev.iustitia.session.SessionStats.bind(bus) } catch (_: Throwable) {}
        // Centralized hurt → knockback-exemption timestamp. Subscribed here (not in any
        // one check) so Speed/Fly can read tp.hurtTick regardless of which checks are
        // enabled. Fail-open: a missed hurt just means no exemption (stricter, safe).
        try {
            bus.subscribe<HurtSignal> { EntityTrackerManager.markHurt(it.victim, it.tick) }
        } catch (_: Throwable) {}
        // Centralized attack → combat-relevance timestamp for the sensitivity substrate feed
        // (FPS pass #3): the attacker is the cheater candidate whose sensitivity we want to
        // converge; non-combat players in a dense crowd are never fed. Mirrors the hurt
        // subscription above. Fail-open: a missed attack just means no feed (stricter, safe).
        try {
            bus.subscribe<AttackEvent> { EntityTrackerManager.markAttack(it.attacker, it.tick) }
        } catch (_: Throwable) {}
        // Wire per-check context purging to despawns so a long single-world session doesn't
        // leak one CheckContext per unique joiner per check (Check.purge existed but was never
        // called). The listener closes over `checks` (live list, not a snapshot), so despawns
        // after this point purge every registered check. Despawns are rare → cheap fan-out.
        try {
            EntityTrackerManager.onDespawn { uuid ->
                for (c in checks) { try { c.purge(uuid) } catch (_: Throwable) {} }
                // Same purge path, second leaker: [dev.iustitia.alert.AlertManager] keeps a
                // per-(player, check) throttle key FOREVER once a player alerts — despawned
                // players never release theirs, so a hub session accumulates one map entry
                // per (unique joiner × alerted check). clearPlayer drops the throttle keys +
                // any in-flight batch, which is exactly the despawn semantics we want (a
                // rejoining player starts fresh; join-grace re-applies anyway).
                try { dev.iustitia.alert.AlertManager.clearPlayer(uuid) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        // individual checks self-subscribe to the bus in their constructors.

        // Companion-mod coexistence: SnapClip / Scrollback / FollowCam are single-feature extracts
        // of Iustitia. When installed alongside it, Iustitia yields the overlapping feature to the
        // standalone mod (see dev.iustitia.compat.CompanionMods) so the two never run the same
        // machinery twice. One-line notice so the deferral is discoverable in latest.log.
        try {
            dev.iustitia.compat.CompanionMods.yieldSummary()?.let {
                logger.info("[Iustitia] companion mod(s) detected — yielding: $it (core detection unaffected)")
            }
        } catch (_: Throwable) {}
    }

    /** Called every END_CLIENT_TICK by the entrypoint. */
    fun onClientTick(tick: Int) {
        tickCounter = tick
        // Drain the netty→client handoff queue FIRST so packet-side mutations (bus events,
        // tracker marks, world-change resets) are all applied before this tick's engine pass.
        try { drainDeferred() } catch (_: Throwable) {}
        try {
            val client = MinecraftClient.getInstance()
            val world = client.world
            val tracked = EntityTrackerManager.poll(world, tick)

            AttackInference.tick(tick)

            // decay every check's per-player VL by one tick (clean-tick drift to 0)
            for (c in checks) { try { c.decayAll() } catch (e: Throwable) { warnChokepoint("decayAll(${c.id})", e) } }

            // Phase 2 instant-replay capture: record one tick of the scene into the rolling buffer
            // (gated by config.replayCapture inside). Runs before the replay playhead advance so a
            // live recordTick and a playback tick never overlap on the same frame confusingly.
            try { dev.iustitia.replay.ReplayBuffer.recordTick(tick, tracked) } catch (_: Throwable) {}
            // Manual long-recording (`/ius record`): same per-tick snap work, into a growable 10-min
            // buffer. No-op unless a recording is active (gated inside). Fail-open.
            try { dev.iustitia.replay.RecordManager.recordTick(tick, tracked) } catch (_: Throwable) {}

            // Phase 2 instant-replay playback: advance the playhead one tick; if the replay just
            // finished, chat the reason + restore rendering (hide-live snaps back automatically).
            try {
                val done = dev.iustitia.replay.ReplayState.tick()
                if (done != null) chat(client, "§8[§diustitia§8] §7replay §c$done§7 — live view restored.")
            } catch (_: Throwable) {}

            // FREECAM free-spectate (v1.2.0 pure camera-override): advance the freecam pose
            // primitives (fcX/fcY/fcZ/fcYaw/fcPitch) one client tick from the held vanilla
            // KeyBindings — camera-relative WASD, sprint×1.2, noclip (no collision/gravity). The
            // player's own walking is suppressed by ClientPlayerEntityMixin, and mouse-look is
            // redirected onto the pose by FreecamEntityMixin → ReplayState.applyFreecamLook. The
            // camera itself is written each frame by CameraMixin's FREECAM branch. Read-only input
            // + fail-open. See [dev.iustitia.replay.ReplayState.tickFreecam].
            try { dev.iustitia.replay.ReplayState.tickFreecam() } catch (_: Throwable) {}

            // verbose heartbeat: confirms the tracker is polling and how many players are
            // observed, plus the per-interval swing/hurt/attack/flag counts. Console-only.
            VerboseLog.maybeHeartbeat(tick, tracked.size)

            if (!ConfigManager.config.enabled) return
            // movement checks run per player per tick; combat checks are bus-driven.
            for (tp in tracked) {
                for (c in checks) {
                    if (!c.enabled) continue
                    try { c.process(tp, tick) } catch (e: Throwable) { warnChokepoint("process(${c.id})", e) }
                }
            }
            // Phase 2: flush any alert batches whose quiet window / max age elapsed (smart batching).
            try { AlertManager.tickFlush(tick) } catch (_: Throwable) {}

            // Phase B watch follow-cam safety: clamp perspective to first-person (ignore F5),
            // auto-exit on movement >0.5 blocks / on being hit, and consume any render-thread
            // requested exit (target left the world). Restores the saved HUD/perspective state on
            // exit and chats the reason. All client-thread-safe (option writes stay here).
            try {
                val exitReason = dev.iustitia.render.WatchState.tickSafety()
                if (exitReason != null) chat(client, "§8[§diustitia§8] §7watch follow-cam §c$exitReason§7 — view restored.")
            } catch (_: Throwable) {}
        } catch (e: Throwable) {
            // the driver itself must never crash the client — but the failure must be visible
            warnChokepoint("tick driver", e)
        }
    }

    fun onConfigReloaded() {
        // checks read cfg.enabled live; nothing structural to rebuild.
        // The persistence toggle may have flipped in the YACL screen — react so a freshly-enabled
        // store loads its notes/history, and a freshly-disabled one stops scheduling saves.
        try {
            dev.iustitia.persistence.PersistenceManager.onToggle(
                dev.iustitia.config.ConfigManager.config.persistenceEnabled
            )
        } catch (_: Throwable) {}
    }

    /**
     * Phase 2 keybind dispatch — called from [dev.iustitia.keybind.Keybinds.poll] on a bind's
     * rising edge. The whole body is fail-open: a thrown handler is swallowed so one bad bind
     * never stalls the tick. Nothing here sends packets or touches the local player; the binds are
     * a control surface for the observer features (snapshot/transcript/session/note/compact/open
     * screens). `watch` toggles the Phase B follow-cam orbit on the crosshair target (mouse-driven,
     * F1-locked, auto-exits on movement/hit/target-gone — see [dev.iustitia.render.WatchState]).
     */
    fun onKeybind(id: String) {
        try {
            val mc = MinecraftClient.getInstance()
            when (id) {
                "snapshot" -> {
                    val t = crosshairTarget()
                    if (t == null) chat(mc, "§8[§diustitia§8] §7look at a player to snapshot them.")
                    else { Snapshot.capture(t.first, t.second); chat(mc, "§8[§diustitia§8] §7snapshot of §f${t.second}§7 posted + copied to clipboard") }
                }
                "transcript" -> {
                    if (mc.currentScreen is TranscriptPanelScreen) { mc.setScreen(null); return }
                    val t = crosshairTarget()
                    if (t == null) chat(mc, "§8[§diustitia§8] §7look at a player to open their transcript panel.")
                    else mc.execute { try { mc.setScreen(TranscriptPanelScreen(t.first, t.second, null)) } catch (_: Throwable) {} }
                }
                "session" -> mc.execute { try { mc.setScreen(SessionScreen(mc.currentScreen)) } catch (_: Throwable) {} }
                "keybinds" -> mc.execute { try { mc.setScreen(KeybindHubScreen(mc.currentScreen)) } catch (_: Throwable) {} }
                "config" -> {
                    mc.execute {
                        try { mc.setScreen(dev.iustitia.config.YaclScreenBuilder.build(mc.currentScreen)) }
                        catch (_: Throwable) { chat(mc, "§8[§diustitia§8] §cfailed to open config screen") }
                    }
                }
                "note" -> {
                    val t = crosshairTarget()
                    if (t == null) { chat(mc, "§8[§diustitia§8] §7look at a player to read their note."); return }
                    val n = NoteStore.get(t.first)
                    if (n == null) chat(mc, "§8[§diustitia§8] §f${t.second} §7has no note.")
                    else chat(mc, "§8[§diustitia§8] §f${t.second} §7— ${NoteStore.categoryLabel(n.category)}§7: §f${n.text}")
                }
                "compact" -> {
                    ConfigManager.config.compactMode = !ConfigManager.config.compactMode
                    try { ConfigManager.save() } catch (_: Throwable) {}
                    chat(mc, "§8[§diustitia§8] §7compact mode = ${if (ConfigManager.config.compactMode) "§aON" else "§cOFF"}")
                }
                "watch" -> {
                    // Companion ownership: FollowCam owns the follow-cam when installed.
                    if (dev.iustitia.compat.CompanionMods.followCam) {
                        chat(mc, "§8[§diustitia§8] §7the follow-cam is handled by §fFollowCam§7 (installed) — use §f/follow§7.")
                        return
                    }
                    // Feature disabled → stay silent (don't print a watch chat line when the user
                    // has the follow-cam off — the 1.7 bug: pressing F9 with watchFollowCam=false
                    // still printed "look at a player to watch them"). The camera mixin also no-ops.
                    if (!ConfigManager.config.watchFollowCam) return
                    if (dev.iustitia.render.WatchState.active) {
                        val reason = dev.iustitia.render.WatchState.disableNow("disabled")
                        chat(mc, "§8[§diustitia§8] §7watch follow-cam §c$reason§7 — view restored.")
                        return
                    }
                    val t = crosshairTarget()
                    if (t == null) {
                        chat(mc, "§8[§diustitia§8] §7look at a player to watch them, then press the bind again to stop.")
                    } else {
                        dev.iustitia.render.WatchState.enable(t.first)
                        chat(mc, "§8[§diustitia§8] §7watching §f${t.second}§7 — orbit follow-cam §aON§7. Mouse to look around; move or get hit to stop.")
                    }
                }
                "replayPause" -> {
                    if (!dev.iustitia.replay.ReplayState.active) return
                    val paused = dev.iustitia.replay.ReplayState.togglePause()
                    chat(mc, "§8[§diustitia§8] §7replay ${if (paused) "§e⏸ paused" else "§aresumed"}§7.")
                }
                "replaySeekBack" -> {
                    if (!dev.iustitia.replay.ReplayState.active) return
                    dev.iustitia.replay.ReplayState.seekBy(-5f)
                    chat(mc, "§8[§diustitia§8] §7replay §e−5s§7.")
                }
                "replaySeekFwd" -> {
                    if (!dev.iustitia.replay.ReplayState.active) return
                    dev.iustitia.replay.ReplayState.seekBy(5f)
                    chat(mc, "§8[§diustitia§8] §7replay §e+5s§7.")
                }
                "replayExit" -> {
                    if (!dev.iustitia.replay.ReplayState.active) return
                    dev.iustitia.replay.ReplayState.stop("stopped")
                    chat(mc, "§8[§diustitia§8] §7replay §cstopped§7 — live view restored.")
                }
                "replayToggle" -> {
                    // Companion ownership: SnapClip owns replay/clip/record when installed.
                    if (dev.iustitia.compat.CompanionMods.snapClip) {
                        chat(mc, "§8[§diustitia§8] §7replay is handled by §fSnapClip§7 (installed) — use its §f/replay§7.")
                        return
                    }
                    if (dev.iustitia.replay.ReplayState.active) {
                        dev.iustitia.replay.ReplayState.stop("stopped")
                        chat(mc, "§8[§diustitia§8] §7replay §cstopped§7 — live view restored.")
                        return
                    }
                    val cfg = ConfigManager.config
                    if (!cfg.replayCapture) { chat(mc, "§8[§diustitia§8] §7replay capture is §cdisabled§7 in config (enable via §f/ius config§7)."); return }
                    val secs = try { cfg.replayKeybindSeconds.coerceIn(1, dev.iustitia.replay.ReplayBuffer.MAX_SECONDS) } catch (_: Throwable) { 30 }
                    val speed = dev.iustitia.replay.ReplayState.SPEED_FULL
                    val now = tickCounter
                    val window = try { dev.iustitia.replay.ReplayBuffer.snapshot(secs, now) } catch (_: Throwable) { dev.iustitia.replay.ReplayBuffer.Window(emptyList(), emptyList()) }
                    if (window.frames.isEmpty()) { chat(mc, "§8[§diustitia§8] §7no buffered data for the last §f${secs}s§7."); return }
                    val started = try { dev.iustitia.replay.ReplayState.start(window, null, speed, cfg.replayHideLive, relocate = false, legacy = false) } catch (_: Throwable) { false }
                    if (!started) { chat(mc, "§8[§diustitia§8] §7couldn't start the replay (empty window)."); return }
                    val hideTxt = if (cfg.replayHideLive) " §7(live players hidden)" else ""
                    chat(mc, "§8[§diustitia§8] §7replaying last §f${secs}s§7 at §f${NumFmt.d(digits = 2, v = speed)}×§7 — ghosts drawn in-world$hideTxt. Press again (or §f/ius replay off§7) to stop.")
                }
                else -> { /* unknown id: no-op */ }
            }
        } catch (_: Throwable) {
            // fail-open: a keybind handler must never crash the client
        }
    }

    /**
     * Public read of the other player under the crosshair (uuid → name), for the HUD overlay + any
     * read-only consumer. Same fail-open cast as [crosshairTarget]; never throws.
     */
    fun currentTarget(): Pair<UUID, String>? = crosshairTarget()

    /** The other player under the crosshair (uuid → name), or null. Fail-open. */
    private fun crosshairTarget(): Pair<UUID, String>? = try {
        val client = MinecraftClient.getInstance()
        val hit = client.crosshairTarget
        val ent = (hit as? net.minecraft.util.hit.EntityHitResult)?.entity
        val other = ent as? net.minecraft.client.network.OtherClientPlayerEntity
        if (other != null) other.uuid to (other.name.string.ifEmpty { other.uuid.toString().take(8) }) else null
    } catch (_: Throwable) { null }

    /** One chat line to the local player (fail-open). */
    private fun chat(mc: MinecraftClient, line: String) {
        try { mc.execute { try { mc.player?.sendMessage(Text.literal(line), false) } catch (_: Throwable) {} } }
        catch (_: Throwable) {}
    }

    /**
     * Wipe one player's flags mid-session (`/ius clear <player>`): purges every check's per-player
     * VL context, clears the flag timeline + tier + alert routing for [uuid], and persists the
     * cleared history. Tracking/replay/render keep running (the player is still tracked); only the
     * detection record is reset, so the player's tier snaps to GREEN. Exemption is untouched —
     * clearing does not exempt. Fail-open; the result message is returned for the command to chat.
     */
    fun clearPlayerFlags(uuid: UUID): String = try {
        for (c in checks) { try { c.purge(uuid) } catch (_: Throwable) {} }
        dev.iustitia.history.FlagHistory.clearPlayer(uuid)
        AlertManager.clearPlayer(uuid)
        try { dev.iustitia.persistence.PersistenceManager.saveHistory() } catch (_: Throwable) {}
        val name = dev.iustitia.history.FlagHistory.nameFor(uuid) ?: uuid.toString().take(8)
        "§8[§diustitia§8] §7cleared all flags for §f$name§7 — tier reset to §aGREEN§7."
    } catch (_: Throwable) {
        "§8[§diustitia§8] §cclear failed."
    }

    /**
     * Wipe EVERY player's flags mid-session (`/ius clear all`): resets every check, the full flag
     * timeline + tiers, and the alert routing, then persists. A clean slate for the whole session.
     * Exemptions are NOT touched (a trusted-player list shouldn't wipe on a flag clear). Fail-open.
     */
    fun clearAllFlags(): String = try {
        for (c in checks) { try { c.resetAll() } catch (_: Throwable) {} }
        dev.iustitia.history.FlagHistory.reset()
        AlertManager.reset()
        try { dev.iustitia.persistence.PersistenceManager.saveHistory() } catch (_: Throwable) {}
        "§8[§diustitia§8] §7cleared §fall§7 flags — every player's tier reset to §aGREEN§7."
    } catch (_: Throwable) {
        "§8[§diustitia§8] §cclear failed."
    }

    /** Full reset on dimension change / game-join. */
    fun resetAll() {
        try {
            checks.forEach { it.resetAll() }
            EntityTrackerManager.reset()
            AttackInference.reset()
            AlertManager.reset()
            dev.iustitia.session.SessionStats.reset()
            dev.iustitia.history.FlagHistory.reset()
            // Drop any armed selfie request so a world/dimension change can't leave the camera
            // pointing at a now-gone offender (the single-frame guarantee already bounds it, but
            // this keeps it tidy on disconnect).
            try { dev.iustitia.render.OffenderCapture.reset() } catch (_: Throwable) {}
            // Stop any active watch follow-cam so a world change can't leave the camera orbiting a
            // player from the previous dimension (vanilla re-derives next frame, but this keeps it
            // tidy and clears the toggle state). disableNow restores the saved HUD/perspective state.
            try { dev.iustitia.render.WatchState.disableNow("world changed") } catch (_: Throwable) {}
            // Stop any active instant-replay + clear the rolling capture buffer so a world/dimension
            // change can't play back ghosts from the previous dimension (the hide-live mixin snaps
            // back to live rendering the instant active flips false).
            try { dev.iustitia.replay.ReplayState.stop("world changed") } catch (_: Throwable) {}
            try { dev.iustitia.replay.ReplayBuffer.reset() } catch (_: Throwable) {}
            // Manual long-recording auto-split: if `/ius record` is active, silently save the current
            // segment + capture a fresh map for the new world + keep recording (one segment per world).
            // No-op when not recording. Fail-open.
            try { dev.iustitia.replay.RecordManager.onWorldChange() } catch (_: Throwable) {}
            // Drop the per-UUID skin cache — skins are per-server (tab list changes), so a world
            // change shouldn't keep the previous server's resolved skins around.
            try { dev.iustitia.render.ReplaySkins.reset() } catch (_: Throwable) {}
            // When persistence is on, flush any pending write then reload the cross-session history
            // + notes so a world-join reset doesn't wipe the persisted record (live detection vl is
            // still reset; only the historical timeline is reloaded from disk).
            try {
                if (dev.iustitia.config.ConfigManager.config.persistenceEnabled) {
                    dev.iustitia.persistence.PersistenceManager.flush()
                    dev.iustitia.persistence.PersistenceManager.loadOnStartup()
                }
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }
}
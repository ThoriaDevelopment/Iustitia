package dev.iustitia

import dev.iustitia.alert.AlertManager
import dev.iustitia.checks.Check
import dev.iustitia.config.ConfigManager
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
     * Ghost-trail sample cadence in ticks. Sampling every tick would clump consecutive
     * positions (a walking player moves <0.2 blocks/tick); every 3 ticks spreads the
     * breadcrumbs enough to read as a trail (~0.6 blocks apart walking). Used by
     * [dev.iustitia.render.GhostTrailRenderer].
     */
    const val SAMPLE_EVERY: Int = 3

    private val checks = mutableListOf<Check>()

    fun register(check: Check) {
        if (check !in checks) checks.add(check)
    }

    val allChecks: List<Check> get() = checks

    private val logger = org.slf4j.LoggerFactory.getLogger("Iustitia")

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
        // Wire per-check context purging to despawns so a long single-world session doesn't
        // leak one CheckContext per unique joiner per check (Check.purge existed but was never
        // called). The listener closes over `checks` (live list, not a snapshot), so despawns
        // after this point purge every registered check. Despawns are rare → cheap fan-out.
        try {
            EntityTrackerManager.onDespawn { uuid ->
                for (c in checks) { try { c.purge(uuid) } catch (_: Throwable) {} }
            }
        } catch (_: Throwable) {}
        // individual checks self-subscribe to the bus in their constructors.
    }

    /** Called every END_CLIENT_TICK by the entrypoint. */
    fun onClientTick(tick: Int) {
        tickCounter = tick
        try {
            val client = MinecraftClient.getInstance()
            val world = client.world
            val tracked = EntityTrackerManager.poll(world, tick)

            AttackInference.tick(tick)

            // decay every check's per-player VL by one tick (clean-tick drift to 0)
            for (c in checks) { try { c.decayAll() } catch (_: Throwable) {} }

            // Phase 2 instant-replay capture: record one tick of the scene into the rolling buffer
            // (gated by config.replayCapture inside). Runs before the replay playhead advance so a
            // live recordTick and a playback tick never overlap on the same frame confusingly.
            try { dev.iustitia.replay.ReplayBuffer.recordTick(tick, tracked) } catch (_: Throwable) {}

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
                    try { c.process(tp, tick) } catch (_: Throwable) {}
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
        } catch (_: Throwable) {
            // the driver itself must never crash the client
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
            // Clear ghost trails so a world/dimension change doesn't leave stale breadcrumbs
            // pointing at world-coord positions from the previous dimension.
            try { dev.iustitia.render.GhostTrailRenderer.reset() } catch (_: Throwable) {}
            // Stop any active watch follow-cam so a world change can't leave the camera orbiting a
            // player from the previous dimension (vanilla re-derives next frame, but this keeps it
            // tidy and clears the toggle state). disableNow restores the saved HUD/perspective state.
            try { dev.iustitia.render.WatchState.disableNow("world changed") } catch (_: Throwable) {}
            // Stop any active instant-replay + clear the rolling capture buffer so a world/dimension
            // change can't play back ghosts from the previous dimension (the hide-live mixin snaps
            // back to live rendering the instant active flips false).
            try { dev.iustitia.replay.ReplayState.stop("world changed") } catch (_: Throwable) {}
            try { dev.iustitia.replay.ReplayBuffer.reset() } catch (_: Throwable) {}
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
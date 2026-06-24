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
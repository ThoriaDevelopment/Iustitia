package dev.iustitia

import dev.iustitia.alert.AlertManager
import dev.iustitia.checks.Check
import dev.iustitia.config.ConfigManager
import dev.iustitia.event.EventBus
import dev.iustitia.event.HurtSignal
import dev.iustitia.inference.AttackInference
import dev.iustitia.tracking.EntityTrackerManager
import net.minecraft.client.MinecraftClient

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

    private val checks = mutableListOf<Check>()

    fun register(check: Check) {
        if (check !in checks) checks.add(check)
    }

    val allChecks: List<Check> get() = checks

    fun init() {
        ConfigManager.load()
        AttackInference.bind(bus)
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
        } catch (_: Throwable) {
            // the driver itself must never crash the client
        }
    }

    fun onConfigReloaded() {
        // checks read cfg.enabled live; nothing structural to rebuild.
    }

    /** Full reset on dimension change / game-join. */
    fun resetAll() {
        try {
            checks.forEach { it.resetAll() }
            EntityTrackerManager.reset()
            AttackInference.reset()
            AlertManager.reset()
            dev.iustitia.history.FlagHistory.reset()
        } catch (_: Throwable) {}
    }
}
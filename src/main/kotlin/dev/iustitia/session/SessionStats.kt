package dev.iustitia.session

import dev.iustitia.event.AttackEvent
import dev.iustitia.event.EventBus
import dev.iustitia.event.SwingSignal
import dev.iustitia.event.VelocitySignal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-player observable counters for the transcript feature (`/ius transcript <name>` + the side
 * panel): swings, inferred hits, and velocity packets received. These are **read-only taps** on the
 * existing signals ([SwingSignal], [AttackEvent], [VelocitySignal]) — they change no detection
 * logic. Since the Phase A defer queue, bus subscribers run on the client tick thread; the
 * counters are still [AtomicInteger]-backed as defense-in-depth (a future publisher that bypasses
 * the queue can't lose increments — `++` on a volatile int was a torn read-modify-write).
 * Session-only (cleared by [reset] on game-join).
 */
object SessionStats {

    class Stats(
        val swings: AtomicInteger = AtomicInteger(),
        val hits: AtomicInteger = AtomicInteger(),
        val velocity: AtomicInteger = AtomicInteger(),
        @Volatile var lastSwingTick: Int = -10000,
        @Volatile var lastHitTick: Int = -10000,
        @Volatile var lastVelocityTick: Int = -10000,
    )

    private val map = ConcurrentHashMap<UUID, Stats>()

    fun bind(bus: EventBus) {
        try {
            bus.subscribe<SwingSignal> {
                try {
                    val s = map.computeIfAbsent(it.attacker) { Stats() }
                    s.swings.incrementAndGet(); s.lastSwingTick = it.tick
                } catch (_: Throwable) {}
            }
            bus.subscribe<AttackEvent> {
                try {
                    val s = map.computeIfAbsent(it.attacker) { Stats() }
                    s.hits.incrementAndGet(); s.lastHitTick = it.tick
                } catch (_: Throwable) {}
            }
            bus.subscribe<VelocitySignal> {
                try {
                    val s = map.computeIfAbsent(it.entity) { Stats() }
                    s.velocity.incrementAndGet(); s.lastVelocityTick = it.tick
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun stats(uuid: UUID): Stats = try { map[uuid] ?: Stats() } catch (_: Throwable) { Stats() }

    fun reset() { try { map.clear() } catch (_: Throwable) {} }
}
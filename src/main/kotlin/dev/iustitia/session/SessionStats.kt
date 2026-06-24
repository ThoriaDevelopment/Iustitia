package dev.iustitia.session

import dev.iustitia.event.AttackEvent
import dev.iustitia.event.EventBus
import dev.iustitia.event.SwingSignal
import dev.iustitia.event.VelocitySignal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player observable counters for the transcript feature (`/ius transcript <name>` + the side
 * panel): swings, inferred hits, and velocity packets received. These are **read-only taps** on the
 * existing signals ([SwingSignal], [AttackEvent], [VelocitySignal]) — they change no detection
 * logic. Signals are emitted on the network thread (see the audit memory note on threading), so the
 * counters are [ConcurrentHashMap]-backed and the int fields are read best-effort (benign one-tick
 * staleness, fail-open). Session-only (cleared by [reset] on game-join).
 */
object SessionStats {

    data class Stats(
        @Volatile var swings: Int = 0,
        @Volatile var hits: Int = 0,
        @Volatile var velocity: Int = 0,
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
                    s.swings++; s.lastSwingTick = it.tick
                } catch (_: Throwable) {}
            }
            bus.subscribe<AttackEvent> {
                try {
                    val s = map.computeIfAbsent(it.attacker) { Stats() }
                    s.hits++; s.lastHitTick = it.tick
                } catch (_: Throwable) {}
            }
            bus.subscribe<VelocitySignal> {
                try {
                    val s = map.computeIfAbsent(it.entity) { Stats() }
                    s.velocity++; s.lastVelocityTick = it.tick
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun stats(uuid: UUID): Stats = try { map[uuid] ?: Stats() } catch (_: Throwable) { Stats() }

    fun reset() { try { map.clear() } catch (_: Throwable) {} }
}
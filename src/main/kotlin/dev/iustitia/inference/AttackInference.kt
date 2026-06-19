package dev.iustitia.inference

import dev.iustitia.Iustitia
import dev.iustitia.VerboseLog
import dev.iustitia.event.AttackEvent
import dev.iustitia.event.EventBus
import dev.iustitia.event.HurtSignal
import dev.iustitia.event.HurtSource
import dev.iustitia.event.SwingSignal
import dev.iustitia.event.VelocitySignal
import dev.iustitia.protocol.ProtocolDetector
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Infers "attacker A struck victim V" by correlating A's swing with V's hurt inside a
 * version-gated tick window, picking the nearest qualifying attacker. The attack
 * packet is never observed client-side (Iustitia sees only server rebroadcasts), so
 * this correlation is the only attack signal Reach/MultiTarget can consume.
 *
 * Natural filters keep it honest: projectile/potion hurts have no attacker swing → no
 * event; missed air-swings have no hurt → no event; legit single-target hits yield
 * exactly one event per (attacker, victim) thanks to the 2-tick dedup (the three hurt
 * channels — status/damage/tilt — all fire for one real hit).
 */
object AttackInference {

    private data class SwingSample(val tick: Int, val nano: Long)

    private val pendingSwings = ConcurrentHashMap<UUID, MutableList<SwingSample>>()
    private val lastEmit = ConcurrentHashMap<UUID, MutableMap<UUID, Int>>() // attacker -> (victim -> tick)

    fun bind(bus: EventBus) {
        bus.subscribe<SwingSignal> { onSwing(it) }
        bus.subscribe<HurtSignal> { onHurt(it) }
        bus.subscribe<VelocitySignal> { onVelocity(it) }
    }

    fun reset() {
        try {
            pendingSwings.clear()
            lastEmit.clear()
        } catch (_: Throwable) {}
    }

    private fun onSwing(s: SwingSignal) {
        try {
            VerboseLog.countSwing()
            val list = pendingSwings.getOrPut(s.attacker) { java.util.Collections.synchronizedList(mutableListOf()) }
            list.add(SwingSample(s.tick, s.nanoTime))
            // cap to avoid pathological growth
            if (list.size > 16) list.subList(0, list.size - 16).clear()
        } catch (_: Throwable) {}
    }

    private fun onHurt(h: HurtSignal) {
        try { correlate(h) } catch (_: Throwable) {}
    }

    private fun onVelocity(v: VelocitySignal) {
        try {
            // Only count a velocity update as a "hurt" if it's a notable knockback impulse;
            // routine smoothing velocities are ignored. Correlation still requires a
            // matching swing, so a stray impulse can't manufacture an attack.
            val vh = Math.hypot(v.velocity.x, v.velocity.z)
            if (vh <= 0.05 && v.velocity.y <= 0.1) return
            correlate(HurtSignal(v.entity, v.tick, -1, HurtSource.VELOCITY))
        } catch (_: Throwable) {}
    }

    private fun correlate(h: HurtSignal) {
        VerboseLog.countHurt()
        val victimTp = EntityTrackerManager.get(h.victim) ?: return
        val victimPos = victimTp.pos
        val back = ProtocolDetector.hurtLookback
        val fwd = ProtocolDetector.hurtLookahead

        var best: UUID? = null
        var bestDist = Double.MAX_VALUE
        var bestNano = 0L
        var bestTp: TrackedPlayer? = null
        for ((attacker, samples) in pendingSwings) {
            if (attacker == h.victim) continue
            val tp = EntityTrackerManager.get(attacker) ?: continue
            val d = tp.pos.distanceTo(victimPos)
            if (d > 8.0) continue
            // any swing within the window?
            var matchedNano = 0L
            var matched = false
            synchronized(samples) {
                for (s in samples) {
                    if (s.tick in (h.tick - back)..(h.tick + fwd)) {
                        matched = true
                        matchedNano = s.nano
                        break
                    }
                }
            }
            if (!matched) continue
            if (d < bestDist) { bestDist = d; best = attacker; bestNano = matchedNano; bestTp = tp }
        }

        val a = best ?: return
        // dedup: at most one AttackEvent per (attacker, victim) per 2 ticks
        val inner = lastEmit.getOrPut(a) { java.util.Collections.synchronizedMap(mutableMapOf()) }
        val last = inner[h.victim] ?: -100000
        if (h.tick - last < 2) { inner[h.victim] = h.tick; return }
        inner[h.victim] = h.tick

        try {
            val aName = VerboseLog.nameOf(bestTp?.username(), a)
            val vName = VerboseLog.nameOf(victimTp.username(), h.victim)
            VerboseLog.log("AttackEvent $aName→$vName dist=${"%.2f".format(bestDist)} @tick ${h.tick}")
            VerboseLog.countAttack()
            Iustitia.bus.publish(AttackEvent(a, h.victim, h.tick, bestNano))
        } catch (_: Throwable) {}
    }

    /** Per-tick purge of stale pending swings and dedup tables. */
    fun tick(tick: Int) {
        try {
            val sit = pendingSwings.entries.iterator()
            while (sit.hasNext()) {
                val (_, list) = sit.next()
                synchronized(list) { list.removeAll { tick - it.tick > 3 } }
                if (list.isEmpty()) sit.remove()
            }
            val eit = lastEmit.entries.iterator()
            while (eit.hasNext()) {
                val inner = eit.next().value
                synchronized(inner) { inner.entries.removeAll { tick - it.value > 5 } }
                if (inner.isEmpty()) eit.remove()
            }
        } catch (_: Throwable) {}
    }
}
package dev.iustitia.inference

import dev.iustitia.NumFmt
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
 * exactly one event per (attacker, victim) thanks to the 2-tick dedup (one real hit
 * reaches an observer on more than one channel — the damage packet that names it and the
 * knockback impulse that does not — so the channels have to be collapsed). A correlated
 * swing is SPENT: it can still correlate further hurts AT ITS OWN TICK (a multi-aura is
 * one swing followed by several victims being hurt the same tick), but never a hurt on a
 * later tick — so one swing can never forge a steady stream of events across its window.
 *
 * Attribution policy. A hurt is attributed to the player the server named, and on a
 * protocol that names damage causes ([ProtocolDetector.namesDamageCauses]) an unnamed hurt
 * is not attributed at all. Proximity is a guess, and guessing was the mechanism behind two
 * measured false positives: a digger's server-relayed swing stream made them a permanently
 * eligible attacker for a teammate's environmental damage, and a mob's, an arrow's or a TNT
 * block's knockback landed on whoever swung nearby. The knockback impulse is the one channel
 * that has to keep working unnamed, because on a legacy protocol (1.9-1.19.3, or 1.8 through
 * ViaFabricPlus) it is the only damage evidence there is; it is therefore credited only when
 * the damage it came from was never seen.
 */
object AttackInference {

    private data class SwingSample(val tick: Int, val nano: Long) {
        /** Tick this sample already correlated, or -1 while unspent. A spent sample can still
         *  match hurts at [spentTick] itself (a multi-aura is ONE swing + N same-tick hurts)
         *  but never another tick — cross-tick forging stays impossible. */
        var spentTick: Int = -1
    }

    private val pendingSwings = ConcurrentHashMap<UUID, MutableList<SwingSample>>()
    private val lastEmit = ConcurrentHashMap<UUID, MutableMap<UUID, Int>>() // attacker -> (victim -> tick)
    /** victim -> tick of the last named damage event seen for them (see [EXPLAINED_TICKS]). */
    private val explainedDamage = ConcurrentHashMap<UUID, Int>()

    fun bind(bus: EventBus) {
        bus.subscribe<SwingSignal> { onSwing(it) }
        bus.subscribe<HurtSignal> { onHurt(it) }
        bus.subscribe<VelocitySignal> { onVelocity(it) }
    }

    fun reset() {
        try {
            pendingSwings.clear()
            lastEmit.clear()
            explainedDamage.clear()
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
        try {
            // The damage packet names its cause, which is the server telling us who did what. Two
            // things follow from seeing one, and both are recorded here rather than in the packet
            // mixin so a signal published on the bus (the live-test harness does exactly that)
            // teaches the same lesson as the packet path.
            if (h.source == HurtSource.ENTITY_DAMAGE) {
                ProtocolDetector.noteDamagePacket()
                // This victim's damage is accounted for, so the knockback impulse that follows it
                // is a duplicate and not independent evidence (see the VELOCITY guard below).
                explainedDamage[h.victim] = h.tick
            }
            correlate(h)
        } catch (_: Throwable) {}
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
        var bestSample: SwingSample? = null
        for ((attacker, samples) in pendingSwings) {
            if (attacker == h.victim) continue
            val tp = EntityTrackerManager.get(attacker) ?: continue
            val d = tp.pos.distanceTo(victimPos)
            if (d > 8.0) continue
            // A hurt that NAMES its attacker can only be claimed by that attacker
            // (EntityDamageS2CPacket.sourceCauseId, which the client cannot choose). Proximity is
            // a guess; this is not. It also stops the wider misattribution class the dig false
            // positive belonged to: mob, arrow, potion and TNT damage name an entity that is not
            // a tracked player, and that must fall open rather than land on whoever swung nearby.
            if (h.attackerEntityId >= 0 && tp.entityId != h.attackerEntityId) continue
            // On a protocol that names damage causes, a hurt that names nobody is not evidence of
            // a player attack at all: it is fall, fire, drowning or void damage, and it is not
            // attributable to anyone. Nothing below this line is reached for those channels there.
            if (h.attackerEntityId < 0 && h.source != HurtSource.VELOCITY &&
                ProtocolDetector.namesDamageCauses
            ) continue
            // A swing relayed while a dig is live is the DIG, not a swing this player chose: the
            // server animates a digger's arm on its own clock, so a digger always holds an
            // "unspent swing" inside any window and every unattributed hurt within range lands on
            // them (the measured reach false positive). This is the guard for a LEGACY protocol,
            // where nothing is named and the guessing path is the only attribution there is; on a
            // naming protocol the id-less rule above already covers it, digger or not, and the
            // VELOCITY carve-out below is still deliberate: a knockback impulse is evidence of a
            // real hit, and silencing it for diggers would hand a damage-suppressing aura a
            // bypass. The direct path above is never gated here, so a player who digs AND lands
            // named hits is still attributed on every hit.
            if (h.attackerEntityId < 0 && h.source != HurtSource.VELOCITY && tp.digging) continue
            // A knockback impulse is unnamed by construction, so it is the one channel that has to
            // stay attributable while unnamed. Crediting it is only honest when the damage it came
            // from was never observed: on a legacy protocol that damage event does not exist, so
            // the impulse is the hit; on a naming protocol it is a same-tick duplicate of a named
            // hurt that was already correlated, and a mob's, an arrow's or a TNT block's knockback
            // would otherwise land on whoever happened to swing nearby. An impulse with no
            // accompanying damage event stays attributable exactly as before.
            if (h.source == HurtSource.VELOCITY) {
                val explained = explainedDamage[h.victim]
                if (explained != null && h.tick - explained in 0..EXPLAINED_TICKS) continue
            }
            // any swing within the window, unspent or spent at this same tick?
            var matchedSample: SwingSample? = null
            synchronized(samples) {
                for (s in samples) {
                    if (s.tick in (h.tick - back)..(h.tick + fwd) &&
                        (s.spentTick == -1 || s.spentTick == h.tick)
                    ) {
                        matchedSample = s
                        break
                    }
                }
            }
            val ms = matchedSample ?: continue
            if (d < bestDist) { bestDist = d; best = attacker; bestNano = ms.nano; bestTp = tp; bestSample = ms }
        }

        val a = best ?: return
        // Spend the matched swing: stamp it with this hurt's tick so it can still correlate
        // further hurts AT THE SAME TICK (a multi-aura is one swing + N same-tick victims —
        // removing the sample entirely would silence victims 2..N and break multiTarget) but
        // never a hurt on a later tick (cross-tick forging stays impossible). Stamping happens
        // before the dedup return so a second channel for the same hit (the knockback impulse that
        // follows a named damage packet) doesn't advance the spend. Losing candidates'
        // swings are left in place — only the winning correlation spends its swing.
        bestSample?.let { ms ->
            pendingSwings[a]?.let { list -> synchronized(list) { ms.spentTick = h.tick } }
        }
        // dedup: at most one AttackEvent per (attacker, victim) per 2 ticks. The watermark is
        // advanced ONLY when an event is actually emitted: one real hit reaches an observer on
        // more than one channel (the damage packet that names it and the knockback impulse that
        // does not, plus the legacy status byte), and that is what this collapses. Advancing the
        // watermark on the suppressed repeat instead makes a hurt stream
        // that arrives every tick suppress itself forever (tick N skips and stamps N, tick N+1
        // then measures 1 against N, ...), which silently starves every combat check of attacks
        // for a fast hitter — the exact case a cheat (or the harness's own per-tick drive)
        // produces.
        val inner = lastEmit.getOrPut(a) { java.util.Collections.synchronizedMap(mutableMapOf()) }
        val last = inner[h.victim] ?: -100000
        if (h.tick - last < 2) return
        inner[h.victim] = h.tick

        try {
            val aName = VerboseLog.nameOf(bestTp?.username(), a)
            val vName = VerboseLog.nameOf(victimTp.username(), h.victim)
            VerboseLog.log("AttackEvent $aName→$vName dist=${NumFmt.d(digits = 2, v = bestDist)} @tick ${h.tick}")
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
            explainedDamage.entries.removeAll { tick - it.value > EXPLAINED_TICKS }
        } catch (_: Throwable) {}
    }

    /**
     * Ticks after a named damage event during which that victim's knockback impulse counts as
     * explained. `LivingEntity.damage` sends the damage packet before it applies the knockback,
     * and the entity tracker rebroadcasts the resulting velocity, so the impulse lands on the same
     * tick or the next one; two ticks covers either order. See the VELOCITY guard in [correlate].
     */
    private const val EXPLAINED_TICKS = 2
}

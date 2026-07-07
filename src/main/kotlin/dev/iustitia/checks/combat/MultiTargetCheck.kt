package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.history.Evidence
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Multi-target / multi-aura detector, Grim `MultiInteractA` proxy. Tracks the set of
 * distinct player victims an attacker hits per tick; ≥2 in one tick, or ≥3 across a
 * 2-tick lag-absorb window with ≥2 same-tick, flags. Self-hurt and non-player victims
 * are already filtered by AttackInference.
 *
 * setbackVL 2, decay 1/tick, level = distinctCount - 1.
 */
class MultiTargetCheck : Check() {

    override val id: String = "multiTarget"

    init {
        try {
            Iustitia.bus.subscribe<AttackEvent> { onAttack(it) }
        } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = MultiTargetContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            val attacker = EntityTrackerManager.get(ev.attacker) ?: return
            val ctx = contextOf(ev.attacker) as MultiTargetContext
            // onAttack runs on the netty thread (AttackEvent is published from the packet handler
            // via AttackInference), while process() iterates+purges this map on the client tick
            // thread. A plain HashMap here can throw ConcurrentModificationException (swallowed by
            // the surrounding try → a silently-dropped flag) or structurally corrupt under
            // concurrent iterator-remove. ConcurrentHashMap is weakly-consistent: iteration never
            // CMEs and entrySet().iterator().remove() is safe. computeIfAbsent is atomic so two
            // attacks on the same tick (serial on netty anyway) can't lose a victim-set.
            val set = ctx.tickVictims.computeIfAbsent(ev.tick) { HashSet() }
            set.add(ev.victim)

            // threshold (default 2.0): min distinct same-tick victims for the same-tick flag.
            // The window flag fires on threshold + 1 (default 3.0) distinct victims across the
            // 2-tick lag-absorb window. Both default to the prior hardcoded 2 / 3.
            val thresh = cfg.threshold
            val sameTick = set.size
            if (sameTick.toDouble() >= thresh) {
                flag(attacker, ctx, max(1.0, (sameTick - 1).toDouble()), "MultiTarget", ev.tick, Evidence(
                    subLabel = "same-tick", measurement = sameTick.toDouble(), threshold = thresh,
                    pos = attacker.pos, extra = "victims=${set.size}"))
            }

            // lag-absorb: union this tick + previous tick
            val prev = ctx.tickVictims[ev.tick - 1]
            if (prev != null && sameTick.toDouble() >= thresh) {
                val union = HashSet<UUID>(set.size + prev.size)
                union.addAll(set)
                union.addAll(prev)
                if (union.size.toDouble() >= thresh + 1.0) {
                    flag(attacker, ctx, max(1.0, (union.size - 1).toDouble()), "MultiTarget", ev.tick, Evidence(
                        subLabel = "window", measurement = union.size.toDouble(), threshold = thresh + 1.0,
                        pos = attacker.pos, extra = "victims=${union.size}"))
                }
            }
            // lag-absorb (independent detector): a multi-aura spread across two ticks — e.g.
            // 2 victims on the previous tick + 1 now, or 1 + 2 — sums to >=3 across the 2-tick
            // window even when no single tick reached 2. The branch above is gated on
            // sameTick >= threshold (the same gate as the same-tick flag, so it only double-counts
            // and is dead as an independent detector); this one fires when sameTick < threshold,
            // a strictly additional detector that never reduces the existing same-tick vl.
            if (prev != null && sameTick.toDouble() < thresh) {
                val union = HashSet<UUID>(set.size + prev.size)
                union.addAll(set)
                union.addAll(prev)
                if (union.size.toDouble() >= thresh + 1.0) {
                    flag(attacker, ctx, max(1.0, (union.size - 1).toDouble()), "MultiTarget", ev.tick, Evidence(
                        subLabel = "window", measurement = union.size.toDouble(), threshold = thresh + 1.0,
                        pos = attacker.pos, extra = "victims=${union.size}"))
                }
            }
        } catch (_: Throwable) {}
    }

    /** Per-tick purge of stale tick→victim maps (called by the driver for every player). */
    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            val ctx = contextOf(tp.uuid) as? MultiTargetContext ?: return
            val it = ctx.tickVictims.entries.iterator()
            while (it.hasNext()) {
                if (it.next().key < tick - 2) it.remove()
            }
        } catch (_: Throwable) {}
    }

    private class MultiTargetContext : CheckContext() {
        val tickVictims = ConcurrentHashMap<Int, MutableSet<UUID>>()
    }
}
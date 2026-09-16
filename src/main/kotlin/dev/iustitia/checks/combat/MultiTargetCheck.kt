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
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.max

/**
 * Multi-target / multi-aura detector, Grim `MultiInteractA` proxy. Tracks the set of
 * distinct player victims an attacker hits per tick; ≥2 in one tick, or ≥3 across a
 * 2-tick lag-absorb window with ≥2 same-tick, flags. Self-hurt and non-player victims
 * are already filtered by AttackInference.
 *
 * Two paths, because they have different arithmetic:
 *
 *  - the level path (`distinctCount - 1`) climbs normally for a 3+-victim aura;
 *  - the **pair path** covers the documented minimum (LiquidBounce `MultiTargets = 2`), where the
 *    level is exactly `1.0` and the flag fires at most once per tick against a `1.0`/tick decay —
 *    a measured break-even, so a two-target aura could run forever and never alert. The pair path
 *    demands the pair repeat across distinct ticks (see [pairSustained]/[Check.flagEpisode]):
 *    hitting two different players in the same tick is not something a legitimate client does even
 *    once, let alone on most of the last four ticks.
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

            // Pair path: 2 same-tick victims repeated across DISTINCT ticks — a sustained
            // multi-aura whose per-tick level (1.0) exactly equals the decay. One pair is not
            // asserted (a single same-tick pair is the documented minimum and the instantaneous
            // form cannot alert); a pair on ≥PAIR_MIN of the last PAIR_WINDOW ticks is a module,
            // and alerts one-shot per episode.
            //
            // Tick-keyed, not event-keyed. A vanilla 1.9+ sword sweep damages every entity in the
            // arc on the SAME tick, so an event-keyed ring reaches `size >= PAIR_WINDOW` from a
            // single sweep's packets — two separate sweeps ten ticks apart were enough to satisfy a
            // gate whose whole point is *repetition*, and `flagEpisode` then added setbackVL+1 on
            // top of the sweep's own same-tick flag and alerted a legitimate player. One sample per
            // tick, upgraded in place by that tick's later victims, is what [PAIR_WINDOW] and
            // [PAIR_MIN] have always claimed to measure.
            val pairNow = pairSustained(ctx, sameTick >= 2, ev.tick)
            if (pairNow) {
                flagEpisode(attacker, ctx, "MultiTarget", ev.tick, Evidence(
                    subLabel = "pair-sustained", measurement = sameTick.toDouble(), threshold = 2.0,
                    pos = attacker.pos, extra = "victims=${set.size}"))
            } else {
                rearmEpisode(ctx, pairNow)
            }

            // lag-absorb (independent detector): a multi-aura spread across two ticks — e.g.
            // 2 victims on the previous tick + 1 now, or 1 + 2 — sums to >=3 across the 2-tick
            // window even when no single tick reached 2. It fires only when sameTick < thresh:
            // when sameTick >= thresh the same-tick flag above already covers it, and the union
            // branch could only double-count the same evidence. A strictly additional detector
            // that never reduces the existing same-tick vl.
            val prev = ctx.tickVictims[ev.tick - 1]
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

    /**
     * Tick-keyed sustained gate for the pair path: at least [PAIR_MIN] of the last [PAIR_WINDOW]
     * **ticks** carried a same-tick pair.
     *
     * One sample per tick, recorded in place — the first attack of a sweep lands before the second
     * victim of the same sweep is known, so an append-per-event ring records `false` for the very
     * tick that is about to become a pair. The upgrade assumes same-tick attacks are serial, which
     * they are: [onAttack] is driven by the local client's own attack packets, and netty delivers
     * those in order on one thread.
     *
     * Recency is enforced by *tick distance*, not by the ring's length, and that part is load
     * bearing. The ring is advanced by attack ticks only — a tick with no attack contributes no
     * sample — so a size-bounded ring fills over any span of time: a legitimate 2v1 player sweeping
     * two adjacent opponents once per vanilla sword cooldown (~12 ticks) would satisfy "2 of the
     * last 4" after four such sweeps, a minute into the fight, on nothing but ordinary melee.
     * Dropping samples older than the window is what makes this the last [PAIR_WINDOW] *ticks*,
     * which is what the class doc has always claimed it measures.
     */
    private fun pairSustained(ctx: MultiTargetContext, violating: Boolean, tick: Int): Boolean {
        val pairs = ctx.pairTicks
        if (violating && pairs.peekFirst() != tick) pairs.addFirst(tick)
        while (true) {
            val oldest = pairs.peekLast() ?: break
            if (oldest < tick - PAIR_WINDOW + 1) pairs.pollLast() else break
        }
        return pairs.size >= PAIR_MIN
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
        /**
         * Ticks that carried a same-tick pair, newest first, pruned to the last [PAIR_WINDOW]
         * ticks by [pairSustained]. Pushed from [onAttack] (netty thread) while [process] iterates
         * [tickVictims] on the client tick thread, hence the concurrent deque — the same
         * reasoning as [tickVictims] itself.
         */
        val pairTicks: ConcurrentLinkedDeque<Int> = ConcurrentLinkedDeque()
    }

    private companion object {
        /** Rolling window of attack ticks the same-tick-pair verdict is judged over. */
        const val PAIR_WINDOW = 4
        /** Same-tick pairs required in the window (2 of the last 4 ticks). */
        const val PAIR_MIN = 2
    }
}
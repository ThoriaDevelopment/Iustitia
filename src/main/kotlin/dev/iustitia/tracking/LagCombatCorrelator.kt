package dev.iustitia.tracking

import dev.iustitia.Iustitia
import dev.iustitia.event.HurtSignal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Axis B — lag-vs-combat-state correlation (plan §2.2 / §6). The hardest evasion
 * class (Vape/Slinky Blink, FakeLag Dynamic/Repel, Lag Range, KnockbackDelay,
 * Backtrack, Criticals Packet mode) re-implements a violation as a packet delay to
 * exploit lag tolerance. The countermeasure is a *correlation* signal, not a gate:
 * **real lag is global** — it freezes every entity's stream at once, which
 * [EntityTrackerManager.lastServerLagTick] / [EntityTrackerManager.lastLagBurstTick]
 * already detect and the checks already *exempt*; **cheat lag is entity-local and
 * combat-timed** — a single entity's stream freezes alone, right around a combat
 * event on or by that entity, while everyone else keeps moving. Removing the
 * correlation means removing the bypass, so this is the one axis the cheats have the
 * hardest time defeating.
 *
 * Per entity this object tracks:
 *  - the most recent **entity-local freeze episode** — a run of near-zero-Δ ticks
 *    *while NOT in a global-lag window* (so a server hitch that freezes everyone is
 *    excluded) and while the entity actually moved recently (an AFK player frozen
 *    alone never counts — matches EntityTrackerManager's mass-freeze AFK guard).
 *  - the most recent **combat hurt** on this entity — a [HurtSignal] with an
 *    identified attacker (`attackerEntityId >= 0`), which excludes fall/fire/lava
 *    (no sourceCauseId). FakeLag Dynamic flushes its packet queue *on* damage, so a
 *    combat hurt is the right coincidence marker.
 *
 * Two queries are exposed for the combat/packet checks to pull as a sub-weight
 * amplifier (plan §6: "a scalar `combatCorrelatedLag`"):
 *  - [combatCorrelatedLag] — the entity's local-freeze episode overlaps a combat
 *    tick (the "self-induced freeze coincident with combat" kernel). Attacker-side:
 *    `reach` (Lag Range) / `backtrack` / `criticals` froze the attacker's own stream
 *    to land a stale / over-distance / lag-timed hit. Victim-side: `noKnockback`
 *    (KnockbackDelay) froze the cheater's own stream through its knockback.
 *  - [combatHurtNear] — a combat hurt landed on this entity within ±window of a tick.
 *    `packetGap` uses it to confirm its freeze-snap coincides with a damage event on
 *    the frozen entity (FakeLag Dynamic flush-on-damage).
 *
 * Read-only capture, no outgoing packets, no new check id, no config. Fail-open:
 * any miss yields 0 / false (no amplification, never a suppression). Driven from the
 * tick driver — [bind] once at startup, [update] every tick after the poll.
 */
object LagCombatCorrelator {

    /** Per-entity local-freeze episode `[start..end]`; `-10000` = none / pruned. */
    private class Episode(var start: Int = -10000, var end: Int = -10000)

    private val episodes = ConcurrentHashMap<UUID, Episode>()
    /** Last tick this entity was hurt by COMBAT (a [HurtSignal] with an identified attacker). */
    private val lastCombatHurt = ConcurrentHashMap<UUID, Int>()

    /** Bind the bus + despawn subscriptions. Call once from [dev.iustitia.Iustitia.init]. */
    fun bind() {
        try {
            Iustitia.bus.subscribe<HurtSignal> { onHurt(it) }
        } catch (_: Throwable) {}
        try {
            // drop a leaving player's episode + hurt stamp so a later same-uuid rejoined
            // player (rare) doesn't inherit stale correlation state. Despawns are rare.
            EntityTrackerManager.onDespawn { purge(it) }
        } catch (_: Throwable) {}
    }

    private fun onHurt(s: HurtSignal) {
        try {
            // Combat hurt only: an identified attacker entity excludes fall/fire/lava (no
            // sourceCauseId). FakeLag Dynamic flushes *on* a combat hit, so the attacker id
            // is present. Fail-open: an unknown-attacker combat hurt just doesn't stamp (no
            // amplification), never a false amplification.
            if (s.attackerEntityId < 0) return
            lastCombatHurt[s.victim] = s.tick
        } catch (_: Throwable) {}
    }

    /**
     * Per-tick update of each tracked entity's local-freeze episode. Call from the tick
     * driver AFTER [EntityTrackerManager.poll] (so Δ / `lastMoveTick` / the just-published
     * global-lag signals are fresh for this tick) and BEFORE [dev.iustitia.inference.AttackInference]
     * + the check loop (so a combat event / a check's `process` fired this tick sees the
     * up-to-date episode). Fail-open per entity: one bad player never stops the update.
     */
    fun update(tracked: Collection<TrackedPlayer>, tick: Int) {
        for (tp in tracked) {
            try {
                updateEntity(tp, tick)
            } catch (_: Throwable) {
                // skip this entity, keep going
            }
        }
    }

    private fun updateEntity(tp: TrackedPlayer, tick: Int) {
        // Global lag = everyone froze (lastServerLagTick) or a batched catch-up snapped
        // (lastLagBurstTick). During a global-lag window this entity's near-zero Δ is the
        // server's freeze, not a self-induced one → do NOT stamp (this is the negative
        // control that separates real lag from cheat lag). Same windows the checks exempt.
        val globalLag = tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW ||
            tick - EntityTrackerManager.lastLagBurstTick <= BURST_WINDOW
        val dx = tp.delta.x
        val dy = tp.delta.y
        val dz = tp.delta.z
        val mag2 = dx * dx + dy * dy + dz * dz
        // AFK guard: an entity that hasn't moved in >RECENT_MOVE ticks is idle, not Blinking —
        // mirrors EntityTrackerManager's mass-freeze tally guard so an AFK player frozen alone
        // (e.g. standing next to a Blinker) never reads as a self-induced freeze.
        val recent = tick - tp.lastMoveTick <= RECENT_MOVE
        val frozenLocally = !globalLag && mag2 < FREEZE_MAG2 && recent
        val ep = episodes.getOrPut(tp.uuid) { Episode() }
        if (frozenLocally) {
            if (ep.end == tick - 1) {
                ep.end = tick              // continuing the current episode
            } else {
                ep.start = tick            // new episode (prior episode ended/gapped)
                ep.end = tick
            }
        } else if (ep.end > -10000 && tick - ep.end > EPISODE_PRUNE) {
            // episode ended a while ago — prune so a far-future combat event doesn't match a
            // long-dead freeze. (Until pruned the ended episode is still queryable, which is
            // what we want: a freeze that ended right before a combat event must still match.)
            ep.start = -10000
            ep.end = -10000
        }
    }

    /**
     * The entity-local-freeze overlap magnitude (ticks) around [combatTick]: the count of
     * ticks in this entity's most-recent local-freeze episode that fall inside
     * `[combatTick - window, combatTick + window]`. 0 if no episode overlaps — i.e. no
     * self-induced combat-timed freeze. This is the Axis-B amplifier kernel.
     *
     * - Attacker-side: the attacker froze its own outgoing stream to land a stale /
     *   over-distance / lag-timed hit (Lag Range, Backtrack, Criticals Packet mode).
     * - Victim-side (`noKnockback`): the cheater froze its own stream through the
     *   knockback it just took (KnockbackDelay). Server-dependent / partially visible —
     *   when the cheat only buffers incoming (not outgoing), no local freeze is seen and
     *   this returns 0 (fail-open: no amplification, never a suppression).
     *
     * Read-only and fail-open: 0 on any miss. Callers gate on `>= MIN_LAG_FREEZE` to
     * filter a 1-tick micro-stutter (a real Blink/Lag-Range freeze lasts several ticks).
     */
    fun combatCorrelatedLag(uuid: UUID, combatTick: Int, window: Int): Int {
        val ep = episodes[uuid] ?: return 0
        if (ep.start < 0) return 0
        val lo = combatTick - window
        val hi = combatTick + window
        if (ep.end < lo || ep.start > hi) return 0        // episode entirely outside the window
        val a = maxOf(ep.start, lo)
        val b = minOf(ep.end, hi)
        return (b - a + 1).coerceAtLeast(0)
    }

    /**
     * True if a COMBAT hurt (a [HurtSignal] with an identified attacker) landed on this
     * entity within ±[window] ticks of [tick]. FakeLag Dynamic flushes its packet queue
     * *on* damage, so the freeze-snap lands right at a combat hurt on the frozen entity —
     * this is the `packetGap` coincidence gate (§2.2). Fall/fire/lava hurts have no
     * attacker id and are not recorded, so environmental damage never amplifies.
     */
    fun combatHurtNear(uuid: UUID, tick: Int, window: Int): Boolean {
        val h = lastCombatHurt[uuid] ?: return false
        return abs(tick - h) <= window
    }

    private fun purge(uuid: UUID) {
        episodes.remove(uuid)
        lastCombatHurt.remove(uuid)
    }

    private const val FREEZE_MAG2 = 0.0001
    /** AFK guard — matches EntityTrackerManager's mass-freeze tally window. */
    private const val RECENT_MOVE = 20
    /** Global-lag exemption windows — a local freeze during these is the server's, not a cheat's. */
    private const val LAG_WINDOW = 8
    private const val BURST_WINDOW = 3
    /** Ticks after an episode ends before it's pruned (kept queryable so a freeze that ended
     *  right before a combat event still matches). */
    private const val EPISODE_PRUNE = 40
}
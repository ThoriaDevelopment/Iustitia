package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.history.Evidence
import dev.iustitia.math.AABB
import dev.iustitia.math.HitboxSizes
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.LagCombatCorrelator
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Backtrack detector (StalePositionReach), weak / low-VL. The cheat delays incoming packets
 * about the victim by ~80–150ms so it can hit the victim at an older (closer) position. We
 * can't see the cheater's outgoing attack, but we *can* see the victim's position history: if
 * the attacker's eye is OUT of reach of the victim's current position yet IN reach of the
 * victim's position ~3–4 ticks ago, the hit landed on a stale position.
 *
 * Uses the victim's [PositionRingBuffer]. Indirect signal, so it alerts on a **sustained
 * pattern** rather than one stale hit: a clean hit (victim already in reach, or no packet-hold
 * freeze) pushes `false`, a stale-position hit pushes `true`, and [MIN_VIOLATIONS] of the last
 * [WINDOW] hits must be stale before the episode flags once at a level that clears setbackVL (see
 * [Check.flagEpisode]). The old per-hit level-1.0 form could never accumulate: a stale snap needs
 * ≥3 frozen samples first (a ≥4-tick cycle), so its flag rate is ≤0.25/tick against a 0.25/tick
 * decay — measured live, the drive recorded in `/ius hist` and never alerted.
 */
class BacktrackCheck : Check() {

    override val id: String = "backtrack"

    init {
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = BacktrackContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            val attacker = EntityTrackerManager.get(ev.attacker) ?: return
            val victim = EntityTrackerManager.get(ev.victim) ?: return
            if (attacker.inVehicle) return
            val reach = cfg.threshold
            val eye = attacker.pos.add(0.0, attacker.eyeHeight(), 0.0)
            val margin = 0.5
            // victim hitbox is pose-aware (sneak → 0.6×1.5, glide/swim/riptide → 0.6×0.6).
            val vh = HitboxSizes.forPose(victim)
            // current position out of reach?
            val curBox = AABB.around(victim.pos.x, victim.pos.y, victim.pos.z, vh.width, vh.height).expand(0.0005)
            val curDist = sqrt(curBox.closestPointSqDistance(eye.x, eye.y, eye.z))
            if (curDist <= reach + margin) {
                // current pos already in reach — a normal hit, the window's counter-example
                judge(contextOf(ev.attacker), attacker, ev.tick, false)
                return
            }
            // Victim-freeze gate (lag-correlation axis). Real Backtrack holds the victim's
            // incoming packet stream, so the victim appears FROZEN for several ticks then snaps
            // to a far position — the attacker hits the stale (close) pos. Without this gate the
            // check fired on ANY victim that simply walked out of reach (1,545 flags, not the
            // intended low-VL corroborating signal). Only scan the stale-pos history when the
            // victim was actually static for ≥3 consecutive samples (a ≥2-tick freeze) within
            // the last 4 ticks — which no smoothly-retreating victim shows. The in-reach scan
            // below then confirms the stale pos was in reach while the current (post-snap) pos
            // is out; an AFK victim has old==current==out-of-reach and still never flags.
            val recent = victim.ring.getPositions(4, ev.tick)
            var frozen = false
            for (i in 0 until recent.size - 2) {
                val a = recent[i]; val b = recent[i + 1]; val c = recent[i + 2]
                val d1 = hypot(a.x - b.x, a.z - b.z)
                val d2 = hypot(b.x - c.x, b.z - c.z)
                if (d1 < FREEZE_MOVE && d2 < FREEZE_MOVE) { frozen = true; break }
            }
            if (!frozen) {
                judge(contextOf(ev.attacker), attacker, ev.tick, false)
                return
            }
            // any recent position in reach?
            for (p in victim.ring.getPositions(4, ev.tick)) {
                val oldBox = AABB.around(p.x, p.y, p.z, vh.width, vh.height).expand(0.0005)
                val oldDist = sqrt(oldBox.closestPointSqDistance(eye.x, eye.y, eye.z))
                if (oldDist <= reach + margin) {
                    val ctx = contextOf(attacker.uuid)
                    judge(ctx, attacker, ev.tick, true)
                    // Axis B amplifier (plan §2.2/§6): the attacker's position stream shows a
                    // self-induced freeze-burst around the stale-pos hit. The victim-freeze gate
                    // above confirms the victim was held; this confirms the attacker froze to
                    // exploit it. Distinct label, shares `backtrack`'s VL pool (no new check id).
                    try {
                        val lag = LagCombatCorrelator.combatCorrelatedLag(attacker.uuid, ev.tick, LAG_CORR_WINDOW)
                        if (lag >= MIN_LAG_FREEZE) {
                            flag(attacker, ctx, VL_LAG_CORR, "Backtrack(LagCorr)", ev.tick, Evidence(
                                subLabel = "lag-correlated", measurement = lag.toDouble(),
                                threshold = MIN_LAG_FREEZE.toDouble(), pos = eye, victim = victim.uuid,
                                extra = "attacker self-freeze around stale-pos hit"))
                        }
                    } catch (_: Throwable) {}
                    return
                }
            }
            // out of reach of every recent sample too — not exploitable, no stale hit
            judge(contextOf(ev.attacker), attacker, ev.tick, false)
        } catch (_: Throwable) {}
    }

    /**
     * Record one hit's stale-position verdict and alert once when the pattern is sustained. The
     * flag level clears setbackVL in a single flag, because a Backtrack cheater repeats the
     * pattern every lag-timed hit and the per-hit cadence can never accumulate (see the class doc).
     */
    private fun judge(ctx: CheckContext, attacker: TrackedPlayer, tick: Int, stale: Boolean) {
        val sustainedNow = sustained(ctx, stale, WINDOW, MIN_VIOLATIONS)
        if (sustainedNow) {
            flagEpisode(attacker, ctx, "Backtrack", tick, Evidence(
                subLabel = "stale-position", measurement = MIN_VIOLATIONS.toDouble(),
                threshold = WINDOW.toDouble(), pos = attacker.pos,
                extra = "hit the victim's stale (frozen) position on ≥$MIN_VIOLATIONS of the last " +
                    "$WINDOW hits"))
        } else {
            rearmEpisode(ctx, sustainedNow)
        }
    }

    private class BacktrackContext : CheckContext()

    private companion object {
        /** Rolling window of hits the stale-position verdict is judged over. */
        const val WINDOW = 6
        /** Stale-position hits required in the window (2 of the last 6). */
        const val MIN_VIOLATIONS = 2
        /** Max horizontal Δpos (blocks) between two consecutive ring samples still counted as
         *  "static". A ≥3-sample run under this is a real packet-hold freeze (Backtrack). */
        const val FREEZE_MOVE = 0.05
        // -- Lag-correlation amplifier (Axis B, plan §3/§8 step 6) --
        /** Window (ticks) around the attack within which an attacker self-freeze corroborates the
         *  stale-pos hit. Tuned in step 14. */
        const val LAG_CORR_WINDOW = 8
        /** Min local-freeze ticks (coincident with combat) to amplify. */
        const val MIN_LAG_FREEZE = 2
        /** Amplifier sub-flag level — small, backtrack is a low-VL corroborator. Tuned in step 14. */
        const val VL_LAG_CORR = 1.0
    }
}
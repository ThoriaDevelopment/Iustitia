package dev.iustitia.checks.movement

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.math.Vectors
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Snap-back aura detector (RotationSnapBack). A tell-tale KillAura pattern: the attacker is
 * traveling one way, yaw snaps to the victim on the attack tick, then snaps *back to the
 * pre-attack travel bearing* within 1–3 ticks (target acquired → attack → reset). On
 * [AttackEvent] we check whether the attacker was facing the victim at the attack tick (yaw
 * within 15° of the eye→victim bearing) and prime a watch recording both the attack-tick yaw
 * and the *pre-attack* yaw (the travel bearing before the snap). In [process] we flag only
 * when the yaw has since moved > [threshold] (30°) away from the attack yaw AND returned
 * within [SNAP_BACK_TOL] of the pre-attack travel bearing — i.e. a genuine snap-back-to-travel.
 *
 * Requiring the return-to-travel is what separates the KillAura tell from a legit
 * target-switch (snapping to a *different* opponent after a hit, which moves the yaw far from
 * the travel bearing too, so it doesn't match) — the original "any >30° change within 3
 * ticks" form flagged legit multi-opponent target-switches. Requires correlation with a
 * damage event, so legit flicks (rare, slower) don't trip it. setbackVL 5, decay 0.5/tick.
 */
class RotationSnapBackCheck : Check() {

    override val id: String = "rotationSnapBack"

    init {
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = SnapContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            val attacker = EntityTrackerManager.get(ev.attacker) ?: return
            val victim = EntityTrackerManager.get(ev.victim) ?: return
            val eye = attacker.pos.add(0.0, attacker.eyeHeight(), 0.0)
            val dx = victim.pos.x - eye.x
            val dz = victim.pos.z - eye.z
            if (dx * dx + dz * dz < 0.09) return
            val expYaw = Math.toDegrees(atan2(-dx, dz))
            val ctx = contextOf(ev.attacker) as SnapContext
            if (abs(Vectors.angleDiff(attacker.yaw.toDouble(), expYaw)) < 15.0) {
                ctx.prime = true
                ctx.snapTick = ev.tick
                ctx.attackYaw = attacker.yaw.toDouble()      // facing the victim
                ctx.preAttackYaw = attacker.lastYaw.toDouble() // travel bearing before the snap
            } else {
                // A hit that was not even facing the victim is the window's counter-example: the
                // episode gate must see the normal hits, or a 1v1 cheater's every hit would look
                // like a snap-back and the "2 of the last 6" pattern would mean nothing.
                judge(ctx, attacker, ev.tick, false)
            }
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            val ctx = contextOf(tp.uuid) as SnapContext
            if (!ctx.prime) return
            val since = tick - ctx.snapTick
            if (since in 1..3) {
                val yaw = tp.yaw.toDouble()
                val fromAttack = abs(Vectors.angleDiff(yaw, ctx.attackYaw))
                if (fromAttack > cfg.threshold) {
                    // large deviation — is it a snap-BACK to travel, or a target-switch to
                    // another opponent? Only flag if the yaw returned near the pre-attack
                    // travel bearing (the KillAura reset signature).
                    val fromTravel = abs(Vectors.angleDiff(yaw, ctx.preAttackYaw))
                    ctx.prime = false
                    judge(ctx, tp, tick, fromTravel <= SNAP_BACK_TOL)
                }
            } else if (since > 3) {
                // The watch expired without the yaw ever leaving the attack bearing: this hit is
                // not a snap-back, so it is a counter-example in the episode window.
                ctx.prime = false
                judge(ctx, tp, tick, false)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Record one hit's snap-back verdict and alert once when the pattern is sustained.
     *
     * The old form flagged a flat `1.0` per snap-back, and the snap-back signature needs two
     * ticks (attack tick + return tick), so its maximum rate is exactly `0.5`/tick -- equal to the
     * `0.5` decay, so the VL oscillated around 1.0 and **never** alerted no matter how many
     * snap-backs a KillAura produced. Requiring the pattern to repeat and then flagging the episode
     * at a level that clears setbackVL is what makes the signal actionable while still ignoring a
     * one-off flick or a single legit target-switch.
     */
    private fun judge(ctx: SnapContext, tp: TrackedPlayer, tick: Int, snapped: Boolean) {
        val sustainedNow = sustained(ctx, snapped, WINDOW, MIN_VIOLATIONS)
        if (sustainedNow) flagEpisode(tp, ctx, "SnapBack", tick) else rearmEpisode(ctx, sustainedNow)
    }

    private class SnapContext : CheckContext() {
        var prime: Boolean = false
        var snapTick: Int = -10000
        var attackYaw: Double = 0.0
        var preAttackYaw: Double = 0.0
    }

    private companion object {
        /** Max yaw deviation from the pre-attack travel bearing that still counts as a
         *  snap-back-to-travel (vs. a target-switch to another opponent). */
        const val SNAP_BACK_TOL = 20.0
        /** Rolling window of hits the snap-back verdict is judged over. */
        const val WINDOW = 8
        /** Snap-backs required in the window. 3 of 8 rather than 2 of 6: the per-event flag is
         *  already superhuman, but a legit player in a multi-opponent fight can produce a single
         *  apparent target-switch, and the gate must not turn two unrelated hits into an alert. */
        const val MIN_VIOLATIONS = 3
    }
}
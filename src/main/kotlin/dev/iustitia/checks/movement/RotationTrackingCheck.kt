package dev.iustitia.checks.movement

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.math.Vectors
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Silent-aim / AimAssist detector (RotationTracking). The cheat silently rotates to face a
 * target; with MovementCorrection the velocity-yaw looks vanilla, but the player's look
 * *consistently* points at one nearby entity's body center — a real player micro-adjusts and
 * looks around. GCD-quantization doesn't hide "always faces the target."
 *
 * Each tick we find the nearest other player within 6 blocks, compute the expected yaw/pitch
 * from the player's eye to that target's center, and mark a "match" if the broadcast
 * yaw/pitch land within a tolerance. Over a rolling window we flag when the match rate
 * exceeds [threshold] (0.92) with ≥60 samples — a sustained near-perfect lock. setbackVL 5,
 * decay 0.05/tick (slow — tracking is a sustained behavior, one clean tick shouldn't reset).
 *
 * FP guards over the original 50-sample / ±8°±10° / always-on form:
 *  - **Distance floor with tighter tolerance.** Inside ~2 blocks the target hitbox spans a
 *    huge angular range, so "within ±8°" is meaningless and a legit close-range tracker can
 *    sustain it. Inside 2 blocks we require a *much* tighter ±3°/±4° match; outside, the
 *    normal ±8°/±10° applies.
 *  - **Combat-window gate.** Samples only accumulate while the attacker has been in combat
 *    recently (an attack within [COMBAT_WINDOW]); idle "looking at a teammate" never feeds
 *    the window. AimAssist activates to land hits, so combat-gating targets exactly the cheat
 *    window while removing the idle-stare FP.
 *  - **Higher sample bar (60).** Raises the bar above short lucky stretches.
 */
class RotationTrackingCheck : Check() {

    override val id: String = "rotationTracking"

    init {
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = TrackContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            (contextOf(ev.attacker) as TrackContext).lastAttackTick = ev.tick
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle) return
            val ctx = contextOf(tp.uuid) as TrackContext
            // combat gate: only accumulate while in/around a fight; idle looking never feeds VL.
            if (tick - ctx.lastAttackTick > COMBAT_WINDOW) return
            // find nearest other player within 6 blocks
            var best: TrackedPlayer? = null
            var bestD = 6.0
            for (other in EntityTrackerManager.all()) {
                if (other.uuid == tp.uuid) continue
                if (other.inVehicle) continue
                val d = tp.pos.distanceTo(other.pos)
                if (d < bestD) { bestD = d; best = other }
            }
            if (best == null) { push(ctx, false); return }
            val target = best!!
            val eye = tp.pos.add(0.0, tp.eyeHeight(), 0.0)
            val cx = target.pos.x - eye.x
            // Pose-aware target body center: 0.9 standing (1.8 hitbox) / 0.75 sneaking (1.5
            // hitbox). The attacker eye is already pose-aware (eyeHeight), so a pose-blind
            // target center let aimbots locking onto sneak-bridgers fall outside the ±10°
            // pitch tolerance and escape. (Gliding/swimming target centers remain ~0.4 —
            // out of scope for melee-PvP tracking; a future follow-up can extend tcenter.)
            val tcenter = if (target.sneaking) 0.75 else 0.9
            val cy = (target.pos.y + tcenter) - eye.y
            val cz = target.pos.z - eye.z
            val horiz = sqrt(cx * cx + cz * cz)
            if (horiz < 0.3) { push(ctx, false); return }
            val expYaw = Math.toDegrees(atan2(-cx, cz))
            val expPitch = Math.toDegrees(atan2(-cy, horiz))
            val yDiff = abs(Vectors.angleDiff(tp.yaw.toDouble(), expYaw))
            val pDiff = abs(tp.pitch.toDouble() - expPitch)
            // distance floor: inside 2 blocks the hitbox spans a wide angle, so ±8° is
            // trivially held by a legit close-range tracker — require a tight ±3°/±4° there.
            val match = if (horiz < 2.0) yDiff < 3.0 && pDiff < 4.0
                        else yDiff < 8.0 && pDiff < 10.0
            push(ctx, match)

            // evaluate over the rolling window
            val samples = ctx.window.size
            if (samples >= MIN_SAMPLES) {
                val rate = ctx.window.count { it }.toDouble() / samples
                if (rate > cfg.threshold) flag(tp, ctx, 1.0, "AimTrack", tick)
            }
        } catch (_: Throwable) {}
    }

    private fun push(ctx: TrackContext, v: Boolean) {
        ctx.window.addFirst(v)
        while (ctx.window.size > WINDOW) ctx.window.removeLast()
    }

    private class TrackContext : CheckContext() {
        val window = ArrayDeque<Boolean>()
        var lastAttackTick: Int = -10000
    }

    private companion object {
        /** Rolling sample window (ticks). */
        const val WINDOW = 60
        /** Min samples before judging the match rate. */
        const val MIN_SAMPLES = 60
        /** Ticks after an attack during which samples accumulate (combat window). */
        const val COMBAT_WINDOW = 60
    }
}
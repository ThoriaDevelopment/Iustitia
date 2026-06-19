package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.math.Vectors
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Silent-aim / AimAssist detector (RotationTracking). The cheat silently rotates to face a
 * target; with MovementCorrection the velocity-yaw looks vanilla, but the player's look
 * *consistently* points at one nearby entity's body center — a real player micro-adjusts and
 * looks around. GCD-quantization doesn't hide "always faces the target."
 *
 * Each tick we find the nearest other player within 6 blocks, compute the expected yaw/pitch
 * from the player's eye to that target's center, and mark a "match" if the broadcast
 * yaw/pitch land within a tight tolerance (±8° yaw / ±10° pitch — a real aimbot centers on
 * the hitbox; legit PvP micro-adjusts and looks around, rarely holding ±8° for long). Over
 * a rolling window we flag when the match rate exceeds [threshold] (0.92) with ≥50 samples
 * — a sustained near-perfect lock. Legit focused 1v1 PvP tracks the opponent ~60–80% of
 * ticks and never sustains 92% within 8°, so the borderline vl~6 baseline disappears; a real
 * aimlock (~99%) climbs. setbackVL 5, decay 0.05/tick (slow — tracking is a sustained
 * behavior, one clean tick shouldn't reset it).
 */
class RotationTrackingCheck : Check() {

    override val id: String = "rotationTracking"

    override fun newContext(uuid: UUID): CheckContext = TrackContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle) return
            val ctx = contextOf(tp.uuid) as TrackContext
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
            push(ctx, yDiff < 8.0 && pDiff < 10.0)

            // evaluate over the rolling window
            val samples = ctx.window.size
            if (samples >= 50) {
                val rate = ctx.window.count { it }.toDouble() / samples
                if (rate > cfg.threshold) flag(tp, ctx, 1.0, "AimTrack", tick)
            }
        } catch (_: Throwable) {}
    }

    private fun push(ctx: TrackContext, v: Boolean) {
        ctx.window.addFirst(v)
        while (ctx.window.size > 60) ctx.window.removeLast()
    }

    private class TrackContext : CheckContext() {
        val window = ArrayDeque<Boolean>()
    }
}
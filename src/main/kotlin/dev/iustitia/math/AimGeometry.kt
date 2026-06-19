package dev.iustitia.math

import dev.iustitia.tracking.TrackedPlayer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Shared client-side aim geometry helpers (ported from Rain-Anticheat's
 * `KillauraCheck`), used by combat checks that judge an attacker's broadcast yaw
 * against a target's hitbox bearing. All angles are in degrees in the MC yaw
 * convention (yaw 0 = +Z / south, 90 = -X / west), matching [Vectors].
 */
object AimGeometry {

    /** Half hitbox width (0.3) plus margin, for horizontal bearing-span tests. */
    const val HITBOX_HALF_WIDTH = 0.4

    /** Wrap an angle to [-180, 180). */
    fun wrapDegrees(angle: Float): Float {
        var a = angle % 360.0f
        if (a >= 180.0f) a -= 360.0f
        if (a < -180.0f) a += 360.0f
        return a
    }

    /** Yaw bearing from [attacker] to a world position (vanilla faceEntity formula). */
    fun bearingTo(attacker: TrackedPlayer, x: Double, z: Double): Float {
        val dx = x - attacker.pos.x
        val dz = z - attacker.pos.z
        return (Math.toDegrees(atan2(dz, dx)).toFloat()) - 90.0f
    }

    /**
     * Angular distance (deg) from [yaw] to the OUTSIDE of the target's horizontal hitbox
     * span at a single position — 0 means the yaw points inside the hitbox, >0 means it
     * points past the hitbox edge. Returns [Float.MAX_VALUE] when the attacker overlaps
     * the target (horizDist < 0.5), where bearing is meaningless.
     */
    fun minInsideError(attacker: TrackedPlayer, targetX: Double, targetZ: Double, yaw: Float): Float {
        val dx = targetX - attacker.pos.x
        val dz = targetZ - attacker.pos.z
        val horizDist = sqrt(dx * dx + dz * dz)
        if (horizDist < 0.5) return Float.MAX_VALUE
        val bearing = (Math.toDegrees(atan2(dz, dx)).toFloat()) - 90.0f
        val err = abs(wrapDegrees(yaw - bearing))
        val halfWidth = Math.toDegrees(atan2(HITBOX_HALF_WIDTH, horizDist)).toFloat()
        return maxOf(0.0f, err - halfWidth)
    }
}
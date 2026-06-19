package dev.iustitia.math

import net.minecraft.util.math.Vec3d
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rotation math ported from Grim `ReachUtils.getLook` and Nemesis
 * `CustomLocation.getDirection`. Uses the MC 1.13+ convention:
 *   x = -sin(yaw) * cos(pitch)
 *   y = -sin(pitch)
 *   z =  cos(yaw) * cos(pitch)
 * with yaw/pitch taken in *degrees* (as broadcast by entity look packets).
 */
object Vectors {

    fun lookVector(yawDeg: Double, pitchDeg: Double): Vec3d {
        val yaw = Math.toRadians(yawDeg)
        val pitch = Math.toRadians(pitchDeg)
        val cp = cos(pitch)
        val x = -sin(yaw) * cp
        val y = -sin(pitch)
        val z = cos(yaw) * cp
        return Vec3d(x, y, z)
    }

    /** Wrap an angle to [-180, 180). */
    fun wrap180(a: Double): Double {
        var v = a % 360.0
        if (v >= 180.0) v -= 360.0
        if (v < -180.0) v += 360.0
        return v
    }

    /** Shortest signed difference a - b wrapped to [-180, 180). */
    fun angleDiff(a: Double, b: Double): Double = wrap180(a - b)
}
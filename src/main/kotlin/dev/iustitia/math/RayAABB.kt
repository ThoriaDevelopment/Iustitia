package dev.iustitia.math

import net.minecraft.util.math.Vec3d
import kotlin.math.abs

/**
 * Ray-vs-AABB slab intersection, ported from Grim `ReachUtils.calculateIntercept`.
 * Returns the entry intersection point of the segment [origin] -> [end] with [box],
 * or null if the segment does not pass through the box. Used by ReachCheck to measure
 * the attacker eye -> victim hitbox distance.
 */
object RayAABB {
    private const val EPS = 1.0E-7

    fun calculateIntercept(box: AABB, origin: Vec3d, end: Vec3d): Vec3d? {
        val ox = origin.x
        val oy = origin.y
        val oz = origin.z
        val dx = end.x - ox
        val dy = end.y - oy
        val dz = end.z - oz

        var tmin = Double.NEGATIVE_INFINITY
        var tmax = Double.POSITIVE_INFINITY

        // X slab
        if (abs(dx) < EPS) {
            if (ox < box.minX || ox > box.maxX) return null
        } else {
            val inv = 1.0 / dx
            var t1 = (box.minX - ox) * inv
            var t2 = (box.maxX - ox) * inv
            if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
            tmin = if (t1 > tmin) t1 else tmin
            tmax = if (t2 < tmax) t2 else tmax
            if (tmin > tmax) return null
        }
        // Y slab
        if (abs(dy) < EPS) {
            if (oy < box.minY || oy > box.maxY) return null
        } else {
            val inv = 1.0 / dy
            var t1 = (box.minY - oy) * inv
            var t2 = (box.maxY - oy) * inv
            if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
            tmin = if (t1 > tmin) t1 else tmin
            tmax = if (t2 < tmax) t2 else tmax
            if (tmin > tmax) return null
        }
        // Z slab
        if (abs(dz) < EPS) {
            if (oz < box.minZ || oz > box.maxZ) return null
        } else {
            val inv = 1.0 / dz
            var t1 = (box.minZ - oz) * inv
            var t2 = (box.maxZ - oz) * inv
            if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
            tmin = if (t1 > tmin) t1 else tmin
            tmax = if (t2 < tmax) t2 else tmax
            if (tmin > tmax) return null
        }

        // Intersection with the infinite line exists in [tmin, tmax]; require the segment
        // (param in [0,1]) to actually touch the box.
        if (tmax < 0.0 || tmin > 1.0) return null
        // When the origin is INSIDE the box, tmin < 0 (the entry plane is behind the origin).
        // The intersection along the forward segment is then the origin itself (t=0) — the eye is
        // already touching/inside the hitbox, so the reach distance is 0. The old form returned
        // tmax here (the EXIT plane, which can be >1 → a point beyond `end`), which INFLATED the
        // measured reach for point-blank/overlapping positions and could false-flag ReachCheck.
        // (Grim's calculateIntercept clamps identically.) Normal outside-origin hits are unaffected:
        // tmin ≥ 0 ⇒ t = tmin (entry point), unchanged.
        val t = if (tmin >= 0.0) tmin else 0.0
        if (t < 0.0) return null
        return Vec3d(ox + dx * t, oy + dy * t, oz + dz * t)
    }
}
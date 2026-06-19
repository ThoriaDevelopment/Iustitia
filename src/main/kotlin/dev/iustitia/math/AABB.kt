package dev.iustitia.math

/**
 * Axis-aligned bounding box, ported from Nemesis `util/data/BoundingBox.java` /
 * Grim `SimpleCollisionBox`. Pure Kotlin (no MC types) so it can be unit-reasoned.
 * Coordinates are world-space; the box is stored as min/max corners.
 */
data class AABB(
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double
) {
    /** Uniform outward expansion by [m] (used for hitbox margin / 1.8 +0.1). */
    fun expand(m: Double): AABB =
        AABB(minX - m, minY - m, minZ - m, maxX + m, maxY + m, maxZ + m)

    fun isInside(x: Double, y: Double, z: Double): Boolean =
        x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ

    fun isIntersected(other: AABB): Boolean =
        minX <= other.maxX && maxX >= other.minX &&
            minY <= other.maxY && maxY >= other.minY &&
            minZ <= other.maxZ && maxZ >= other.minZ

    /** Squared distance from (x,y,z) to the closest point on this box; 0 if inside. */
    fun closestPointSqDistance(x: Double, y: Double, z: Double): Double {
        val cx = x.coerceIn(minX, maxX)
        val cy = y.coerceIn(minY, maxY)
        val cz = z.coerceIn(minZ, maxZ)
        val dx = x - cx
        val dy = y - cy
        val dz = z - cz
        return dx * dx + dy * dy + dz * dz
    }

    companion object {
        /**
         * Box centered horizontally on (cx,cz) with feet at cy, given [width] and [height].
         * Matches the MC player hitbox layout (feet at pos.y, body extends upward).
         */
        fun around(cx: Double, cy: Double, cz: Double, width: Double, height: Double): AABB {
            val w = width / 2.0
            return AABB(cx - w, cy, cz - w, cx + w, cy + height, cz + w)
        }
    }
}
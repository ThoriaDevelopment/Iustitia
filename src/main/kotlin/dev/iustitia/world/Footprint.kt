package dev.iustitia.world

/**
 * Pure geometry for a player's horizontal hitbox footprint (no Minecraft objects, so it is
 * unit-testable). A player's collision box is [PLAYER_HALF_WIDTH]·2 = 0.6 wide in both the
 * standing and the sneaking pose, so the feet rest on *every* block column the 0.6×0.6 footprint
 * overlaps — not only the column under the center. A player sneaking at a ledge with the center
 * past the edge is still supported by the column the rest of the box overlaps, which is how
 * vanilla resolves onGround (a collision against the whole box).
 */
object Footprint {

    /** Half the player hitbox width (vanilla `EntityType.PLAYER` is 0.6 wide, sneaking included). */
    const val PLAYER_HALF_WIDTH = 0.3

    /**
     * Inset applied to each footprint edge so a box that only *touches* a neighbouring column's
     * face (box edge exactly on an integer boundary) does not count it as support — vanilla
     * collision needs a real overlap, and a touching face carries no weight.
     */
    const val EDGE_EPS = 1.0e-3

    /**
     * Distinct block columns `(bx, bz)` overlapped by a footprint of [halfWidth] centered at
     * ([x], [z]). The center column is always first (the common supported case short-circuits on
     * it); the result has 1, 2 or 4 entries and never repeats a column.
     */
    fun columns(x: Double, z: Double, halfWidth: Double = PLAYER_HALF_WIDTH): List<Pair<Int, Int>> {
        val inset = (halfWidth - EDGE_EPS).coerceAtLeast(0.0)
        val xs = linkedSetOf(Math.floor(x).toInt(), Math.floor(x - inset).toInt(), Math.floor(x + inset).toInt())
        val zs = linkedSetOf(Math.floor(z).toInt(), Math.floor(z - inset).toInt(), Math.floor(z + inset).toInt())
        val out = ArrayList<Pair<Int, Int>>(xs.size * zs.size)
        for (bx in xs) for (bz in zs) out.add(bx to bz)
        return out
    }
}

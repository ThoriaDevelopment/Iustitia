package dev.iustitia.world

import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.vehicle.BoatEntity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.entity.Entity
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.world.RaycastContext
import net.minecraft.world.chunk.ChunkStatus

/**
 * Read-only world queries used by movement/reach sub-branches. Every method is
 * chunk-gated and fail-open: if the relevant chunk is not loaded or anything throws,
 * it returns the *safe* value (no false-positive) — never propagates an exception.
 *
 * All checks that touch world state must route through here so chunk-gating lives in
 * exactly one place.
 */
object WorldQueries {

    /** True iff the chunk containing the given block coords is loaded as a FULL chunk. */
    fun isChunkLoaded(world: ClientWorld?, blockX: Int, blockZ: Int): Boolean {
        if (world == null) return false
        return try {
            world.chunkManager.getChunk(blockX shr 4, blockZ shr 4, ChunkStatus.FULL, false) != null
        } catch (_: Throwable) {
            false
        }
    }

    /** Block state at the given block coords, or null if the chunk is not loaded. */
    fun blockStateAt(world: ClientWorld?, x: Int, y: Int, z: Int): BlockState? {
        if (world == null) return null
        return try {
            if (!isChunkLoaded(world, x, z)) return null
            world.getBlockState(BlockPos(x, y, z))
        } catch (_: Throwable) {
            null
        }
    }

    /** True iff the block at (x,y,z) has a non-empty collision shape (a standable surface). */
    fun isSolidAt(world: ClientWorld?, x: Int, y: Int, z: Int): Boolean {
        return try {
            val state = blockStateAt(world, x, y, z) ?: return false
            val pos = BlockPos(x, y, z)
            !state.isAir && !state.getCollisionShape(world, pos).isEmpty
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * True iff the block at (x,y,z) fills the *entire* 1×1×1 cell with collision — i.e. it is a
     * full cube (stone, planks, bedrock, glass, …). Used by PhaseClip: a real phase cheat moves
     * through full-cube walls, while blocks a player is *meant* to occupy (ladders, scaffolding,
     * bamboo, cobwebs, honey, slabs, stairs, fences) all have partial collision shapes (a thin
     * face-slab, an inset column, a half-cube, …) and so return false here. Gating phase on a full
     * cube removes the "legit climber inside a 2-tall ladder/scaffold stack" false-positive class
     * without weakening detection of phasing through a real wall. Partial-block phase (through
     * stairs/slabs/fences) is not caught — an acceptable, rare trade for losing a large FP class.
     */
    fun isFullCubeSolidAt(world: ClientWorld?, x: Int, y: Int, z: Int): Boolean {
        return try {
            val state = blockStateAt(world, x, y, z) ?: return false
            if (state.isAir) return false
            val pos = BlockPos(x, y, z)
            val shape = state.getCollisionShape(world, pos)
            if (shape.isEmpty) return false
            val boxes = shape.boundingBoxes
            if (boxes.size != 1) return false
            val b = boxes[0]
            b.minX <= 0.001 && b.minY <= 0.001 && b.minZ <= 0.001 &&
                b.maxX >= 0.999 && b.maxY >= 0.999 && b.maxZ >= 0.999
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * True iff there is a solid block within [offset] blocks directly below (x, y, z).
     * Used by the onGround proxy: `|Δy| < 0.01 && isSolidBelow(0.05)` → grounded.
     * Scans a small vertical band so a 0.05 floor gap still counts as supported.
     */
    fun isSolidBelow(world: ClientWorld?, x: Double, y: Double, z: Double, offset: Double): Boolean {
        return try {
            val bx = Math.floor(x).toInt()
            val bz = Math.floor(z).toInt()
            val baseY = Math.floor(y - offset).toInt()
            // scan two block band to absorb slab/stair edges
            isSolidAt(world, bx, baseY, bz) ||
                isSolidAt(world, bx, baseY - 1, bz)
        } catch (_: Throwable) {
            false
        }
    }

    /** True iff the block at (x,y,z) is a liquid (water/lava source or flowing). */
    fun isLiquidAt(world: ClientWorld?, x: Int, y: Int, z: Int): Boolean {
        return try {
            val state = blockStateAt(world, x, y, z) ?: return false
            state.isOf(Blocks.WATER) || state.isOf(Blocks.LAVA) || !state.fluidState.isEmpty
        } catch (_: Throwable) {
            false
        }
    }

    /** True iff the block at (x,y,z) is climbable (ladder / vine / scaffolding). */
    fun isClimbableAt(world: ClientWorld?, x: Int, y: Int, z: Int): Boolean {
        return try {
            val state = blockStateAt(world, x, y, z) ?: return false
            state.isIn(net.minecraft.registry.tag.BlockTags.CLIMBABLE) ||
                state.isOf(Blocks.VINE) || state.isOf(Blocks.LADDER) ||
                state.isOf(Blocks.SCAFFOLDING)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * True iff the block at (x,y,z) legitimately dampens knockback — liquids (water/lava),
     * cobweb, soul sand/soil, and honey. A victim on/in these can move <0.1/tick after a
     * sprint-hit without using a Velocity cheat, so NoKnockback exempts them. Chunk-gated.
     */
    fun isKbDampeningAt(world: ClientWorld?, x: Int, y: Int, z: Int): Boolean {
        return try {
            if (isLiquidAt(world, x, y, z)) return true
            val state = blockStateAt(world, x, y, z) ?: return false
            state.isOf(Blocks.COBWEB) || state.isOf(Blocks.SOUL_SAND) ||
                state.isOf(Blocks.SOUL_SOIL) || state.isOf(Blocks.HONEY_BLOCK)
        } catch (_: Throwable) {
            false
        }
    }

    /** True iff a lily pad sits at/just above (x,y,z) — a player can stand on a lily pad
     *  over water (it has no collision, so isSolidAt returns false) and must not be flagged
     *  as WaterWalk. Chunk-gated. */
    fun isLilyPadAt(world: ClientWorld?, x: Int, y: Int, z: Int): Boolean {
        return try {
            val state = blockStateAt(world, x, y, z) ?: return false
            state.isOf(Blocks.LILY_PAD)
        } catch (_: Throwable) {
            false
        }
    }

    /** True iff a boat entity sits within ~1 block below (x,y,z) — a player standing ON a
     *  boat (not riding it) has water at the feet but is supported by the boat, not the
     *  WaterWalk cheat. `tp.inVehicle` only catches riding, not standing-on. */
    fun isBoatBelow(world: ClientWorld?, x: Double, y: Double, z: Double): Boolean {
        if (world == null) return false
        return try {
            val player = MinecraftClient.getInstance().player ?: return false
            val box = Box(x - 1.0, y - 1.5, z - 1.0, x + 1.0, y + 0.3, z + 1.0)
            world.getOtherEntities(player, box) { it is BoatEntity }.isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    /** True iff a solid block stands within [reach] blocks ahead of (x,z) along the (dx,dz)
     *  unit direction, sweeping several vertical bands — a wall anywhere along the push
     *  legitimately stops the victim. Used by NoKnockback to exempt a blocked KB. */
    fun isWallAhead(world: ClientWorld?, x: Double, y: Double, z: Double, dx: Double, dz: Double, reach: Double): Boolean {
        if (world == null) return false
        return try {
            val len = hypotOf(dx, dz)
            if (len < 1.0E-6) return false
            val ux = dx / len
            val uz = dz / len
            val footY = Math.floor(y).toInt()
            // sample at 0.3 / 0.6 / 0.9 ahead across three body bands (foot, torso, head)
            for (off in doubleArrayOf(0.3, 0.6, 0.9)) {
                val bx = Math.floor(x + ux * off).toInt()
                val bz = Math.floor(z + uz * off).toInt()
                if (isSolidAt(world, bx, footY, bz) ||
                    isSolidAt(world, bx, footY + 1, bz) ||
                    isSolidAt(world, bx, footY + 2, bz)
                ) return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun hypotOf(a: Double, b: Double): Double {
        val x = Math.abs(a); val y = Math.abs(b)
        return if (x > y) x * Math.sqrt(1.0 + (y / x) * (y / x))
        else if (y > 0.0) y * Math.sqrt(1.0 + (x / y) * (x / y))
        else 0.0
    }

    /**
     * True iff no opaque block intersects the segment [from] -> [to].
     * Uses a block-only colliding raycast, then treats a hit on a **non-opaque** block
     * (glass, leaves, ice, fences, panes, iron bars, …) as line-of-sight — those are
     * see-through and vanilla melee reaches entities poking past them, so flagging a hit
     * "through" them was a map-dependent false positive. A real wall (opaque full cube:
     * stone/planks/…) still blocks. A clean MISS (or a hit essentially at the endpoint)
     * counts as line-of-sight.
     *
     * Fail-open = LOS (true): on an unloaded endpoint chunk or any raycast error, this
     * returns `true` ("can see"), so [ThroughWallsCheck] (which flags only when ALL victim
     * body rays are `!hasLineOfSight`) does NOT flag. (The old `false`-on-error return was
     * an inverted fail-open — it made ThroughWalls *more* likely to flag on a raycast
     * error, the opposite of the documented intent. ThroughWalls also pre-gates both
     * endpoint chunks via [isChunkLoaded], so the common unloaded case returns early there;
     * this just closes the rare exception path.) Coordinates are floored (not truncated
     * via toInt) so negative X/Z map to the correct chunk.
     */
    fun hasLineOfSight(world: ClientWorld?, from: Vec3d, to: Vec3d): Boolean {
        if (world == null) return true
        return try {
            if (!isChunkLoaded(world, Math.floor(from.x).toInt(), Math.floor(from.z).toInt()) ||
                !isChunkLoaded(world, Math.floor(to.x).toInt(), Math.floor(to.z).toInt())
            ) return true
            val ctx = RaycastContext(
                from, to,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                null as Entity?,
            )
            val hit = world.raycast(ctx)
            // MISS (lands at `to`) = clear. A block hit short of `to` = blocked — UNLESS the
            // hit block is non-opaque (see-through), in which case the melee is legit.
            if (hit.type == HitResult.Type.MISS) return true
            if (hit is BlockHitResult) {
                val state = world.getBlockState(hit.blockPos)
                if (!state.isAir && !state.isOpaque) return true
            }
            hit.getPos().squaredDistanceTo(to) < 1.0E-6
        } catch (_: Throwable) {
            true
        }
    }
}
package dev.iustitia.world

import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.entity.Entity
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
     * True iff no opaque/solid block intersects the segment [from] -> [to].
     * Uses a block-only colliding raycast. A clean MISS (or a hit essentially at the
     * endpoint) counts as line-of-sight. Chunk-gated per endpoint block.
     */
    fun hasLineOfSight(world: ClientWorld?, from: Vec3d, to: Vec3d): Boolean {
        if (world == null) return false
        return try {
            if (!isChunkLoaded(world, from.x.toInt(), from.z.toInt()) ||
                !isChunkLoaded(world, to.x.toInt(), to.z.toInt())
            ) return false
            val ctx = RaycastContext(
                from, to,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                null as Entity?,
            )
            val hit = world.raycast(ctx)
            // A MISS lands at `to`; a block *between* from and to lands short of `to`.
            // "Clear" iff the hit point is essentially at the destination.
            hit.getPos().squaredDistanceTo(to) < 1.0E-6
        } catch (_: Throwable) {
            false
        }
    }
}
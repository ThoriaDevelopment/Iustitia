package dev.iustitia.replay

import java.util.ArrayDeque

/**
 * Rolling buffer of **block changes** observed during a recording, so a replay/clip can play back
 * world edits (blocks placed/broken by players) on top of the captured chunk snapshot — the "the
 * world changed while you watched" layer. Ported from SnapClip.
 *
 * Fed by [dev.iustitia.mixin.WorldChunkMixin] (`WorldChunk.setBlockState` HEAD) with the block name
 * before + after; consumed by [ReplayState]'s delta overlay, which applies the deltas up to the
 * playhead onto [ChunkSnapshot]'s overlay map. Only changes within the captured chunk area matter,
 * but the buffer is region-agnostic (the overlay filters by chunk key).
 *
 * Bounded by [MAX_BLOCK_DELTAS] (drop oldest). Fail-open: any error is swallowed. All access is
 * synchronized on the deque (coarse but correct — the per-tick work is tiny).
 */
object BlockDeltaBuffer {

    /** Hard cap on buffered deltas (drop oldest past this). */
    private const val MAX_BLOCK_DELTAS = 20_000

    /** One block change: the tick it happened, its coords, and the block name before → after. */
    data class BlockDelta(val tick: Int, val x: Int, val y: Int, val z: Int, val before: String, val after: String)

    private val deltas: ArrayDeque<BlockDelta> = ArrayDeque()

    /** Record one change. No-op when [before] == [after] (a set to the same state). Fail-open. */
    fun record(tick: Int, x: Int, y: Int, z: Int, before: String, after: String) {
        try {
            if (before == after) return
            synchronized(deltas) {
                deltas.addLast(BlockDelta(tick, x, y, z, before, after))
                while (deltas.size > MAX_BLOCK_DELTAS) deltas.removeFirst()
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Copy out the deltas in `[minTick, maxTick]`, optionally restricted to [chunkKeys]
     * (`chunkKey(x shr 4, z shr 4)`). Pass `chunkKeys = null` for every buffered delta. Fail-open:
     * empty on error.
     */
    fun snapshot(minTick: Int, maxTick: Int, chunkKeys: Set<Long>?): List<BlockDelta> = try {
        synchronized(deltas) {
            deltas.filter { d ->
                d.tick in minTick..maxTick &&
                    (chunkKeys == null || chunkKeys.contains(chunkKey(d.x shr 4, d.z shr 4)))
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    /** Clear the buffer (world/dimension change). Fail-open. */
    fun reset() {
        try { synchronized(deltas) { deltas.clear() } } catch (_: Throwable) {}
    }

    /** Currently buffered delta count (diagnostic). Fail-open. */
    fun count(): Int = try { synchronized(deltas) { deltas.size } } catch (_: Throwable) { 0 }

    /**
     * Pack a chunk coord pair into one long key — **the** definition, called by every other site that
     * needs one ([ChunkSnapshot]'s index, [ChunkRollingCapture]'s store, [ChunkMesher]'s bake cache,
     * [RecordManager]'s delta filter). High 32 bits = chunkX, low 32 = chunkZ, so the pair fits one
     * `HashMap<Long, _>` key without boxing. The `and 0xFFFFFFFFL` masks sign extension: a negative
     * chunkZ (any chunk at z < 0) would otherwise smear 1-bits across the whole high half and collide
     * with unrelated chunks. It was copy-pasted into five more places before this one was made
     * canonical, and every copy had to get that mask right — so the mask lives here, once.
     */
    fun chunkKey(chunkX: Int, chunkZ: Int): Long =
        (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)
}

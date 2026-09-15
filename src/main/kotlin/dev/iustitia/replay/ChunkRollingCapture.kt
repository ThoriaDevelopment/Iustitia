package dev.iustitia.replay

import net.minecraft.client.MinecraftClient

/**
 * Incremental, per-segment chunk snapshotting. Instead of one big synchronous [ChunkCapture.capture]
 * sweep at export time, this fills a per-segment chunk map a few chunks per tick while the scene is
 * captured, so `/ius replay`, `/ius clip` and `/ius record` can all bundle the world without a hitch —
 * and a long recording keeps the world it had at each segment.
 *
 * Fed from [ReplayBuffer.updateSegment] (`onSegmentStart` on a new segment, `tickCapture` every tick)
 * and from [RecordManager] for the manual long recording. The assembled [ChunkSnapshot] for a segment
 * is read back by the export path ([RecordManager.saveSegment] / the clip command) via
 * [snapshotForSegment].
 *
 * ## Bounds
 *
 * [rollingChunkCap] is a total non-empty-section budget across ALL live segments; when it is
 * exceeded the oldest segment (never the current one) is evicted. [PER_TICK] bounds the per-tick work
 * so a large radius streams in over a few seconds rather than stalling one frame. Fail-open
 * throughout — a capture error just leaves that chunk out.
 *
 * ## Relationship to SnapClip
 *
 * Ported from SnapClip's `ChunkRollingCapture`; the rolling cap reads
 * [dev.iustitia.config.IustitiaConfig.clipRollingChunkCap] (clamped 4096..131072).
 */
object ChunkRollingCapture {

    /** Chunks captured per tick (bounds the per-tick cost; the rest stream in over later ticks). */
    private const val PER_TICK = 16

    /** Total non-empty-section budget across all live segments before the oldest is evicted. Reads
     *  [dev.iustitia.config.IustitiaConfig.clipRollingChunkCap] (clamped 4096..131072); fail-open to
     *  24000 when the config can't be read. */
    private fun rollingChunkCap(): Int = try {
        dev.iustitia.config.ConfigManager.config.clipRollingChunkCap.coerceIn(4096, 131_072)
    } catch (_: Throwable) { 24_000 }

    /** segmentId → (chunkKey → captured chunk). */
    private val store: HashMap<Int, HashMap<Long, ChunkSnapshot.ChunkRec>> = HashMap()
    private var totalSections: Int = 0

    private fun chunkKey(chunkX: Int, chunkZ: Int): Long =
        (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)

    /** Begin (or reset) [segmentId]'s capture, evicting the oldest segment(s) if the budget is full. */
    fun onSegmentStart(segmentId: Int, pcx: Int, pcz: Int, radius: Int) {
        try {
            val cap = rollingChunkCap()
            while (totalSections >= cap) {
                val oldest = store.keys.filter { it != segmentId }.minOrNull() ?: break
                val ev = store.remove(oldest) ?: break
                totalSections -= ev.values.sumOf { it.sections.size }
            }
            store.getOrPut(segmentId) { HashMap() }
        } catch (_: Throwable) {
        }
    }

    /** Capture up to [PER_TICK] not-yet-captured chunks around the player, nearest-first. */
    fun tickCapture(segmentId: Int, pcx: Int, pcz: Int, radius: Int) {
        try {
            val world = MinecraftClient.getInstance().world ?: return
            val st = store.getOrPut(segmentId) { HashMap() }
            val cap = rollingChunkCap()  // once per tick, not per chunk attempt
            val r = radius.coerceIn(1, 32)
            var n = 0
            for (d in 0..r) {
                if (d == 0) {
                    if (tryCapture(st, world, pcx, pcz, cap)) { n++; if (n >= PER_TICK) return }
                } else {
                    for (cx in (pcx - d)..(pcx + d)) {
                        if (tryCapture(st, world, cx, pcz - d, cap)) { n++; if (n >= PER_TICK) return }
                        if (tryCapture(st, world, cx, pcz + d, cap)) { n++; if (n >= PER_TICK) return }
                    }
                    for (cz in (pcz - d + 1)..(pcz + d - 1)) {
                        if (tryCapture(st, world, pcx - d, cz, cap)) { n++; if (n >= PER_TICK) return }
                        if (tryCapture(st, world, pcx + d, cz, cap)) { n++; if (n >= PER_TICK) return }
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun tryCapture(
        st: HashMap<Long, ChunkSnapshot.ChunkRec>,
        world: net.minecraft.client.world.ClientWorld,
        cx: Int, cz: Int,
        cap: Int,
    ): Boolean {
        val k = chunkKey(cx, cz)
        if (st.containsKey(k)) return false
        if (totalSections >= cap) return false
        val secs = try { ChunkCapture.captureChunkAt(world, cx, cz) } catch (_: Throwable) { null }
            ?: return false
        // Budget nearly full: DON'T store a truncated chunk. A truncated ChunkRec would be a
        // permanent hole in the replay world (containsKey -> never re-captured). Skip instead —
        // the chunk is retried on a later tick once segment eviction frees budget.
        if (totalSections + secs.size > cap) return false
        st[k] = ChunkSnapshot.ChunkRec(cx, cz, secs)
        totalSections += secs.size
        return true
    }

    /** Assemble the [ChunkSnapshot] for [segmentId], or null when nothing was captured for it. */
    fun snapshotForSegment(segmentId: Int): ChunkSnapshot? = try {
        val st = store[segmentId] ?: return null
        if (st.isEmpty()) return null
        ChunkSnapshot(st.values.toList())
    } catch (_: Throwable) { null }

    /** Drop every segment's capture (world/dimension change). */
    fun reset() {
        try {
            store.clear()
            totalSections = 0
        } catch (_: Throwable) {
        }
    }

    /** Drop one segment's capture (recording stop / auto-split). */
    fun clearSegment(segmentId: Int) {
        try {
            val st = store.remove(segmentId)
            if (st != null) totalSections -= st.values.sumOf { it.sections.size }
            if (totalSections < 0) totalSections = 0
        } catch (_: Throwable) {
        }
    }

    /** Chunk keys currently captured for [segmentId] (for filtering block deltas to a segment). */
    fun chunkKeysFor(segmentId: Int): Set<Long> = try {
        store[segmentId]?.keys?.toSet() ?: emptySet()
    } catch (_: Throwable) { emptySet() }
}

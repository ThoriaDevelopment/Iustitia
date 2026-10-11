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
 * exceeded the oldest segment (never the current one) is evicted. The check runs at a segment
 * boundary and again on every [tickCapture], so a single long segment can keep making progress
 * once the budget fills instead of stalling until the next split. [PER_TICK] bounds the per-tick
 * work so a large radius streams in over a few seconds rather than stalling one frame. Once a
 * segment's whole ring around the current centre is captured, [tickCapture] skips the ring scan
 * until the centre chunk, radius or segment changes ([complete]). Fail-open
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

    /** segmentId → packed (centre chunk, radius) whose full ring was found captured. While the caller's
     *  centre/radius match, there is nothing left to capture and [tickCapture] skips the scan. Captured
     *  chunks are never dropped from a live segment except by [clearSegment] / [reset] / eviction, which
     *  all drop this entry too. Keyed per segment because [RecordManager] ticks its own segment
     *  alongside the rolling one. */
    private val complete: HashMap<Int, Long> = HashMap()

    private fun ringKey(pcx: Int, pcz: Int, r: Int): Long =
        (pcx.toLong() and 0x3FFFFFFL) or ((pcz.toLong() and 0x3FFFFFFL) shl 26) or (r.toLong() shl 52)

    private const val CAPTURED = 0
    private const val PRESENT = 1
    private const val MISSED = 2

    /** Delegates to the shared encoding ([BlockDeltaBuffer.chunkKey]) — this store is read back by key
     *  from the export path, which uses the canonical form. */
    private fun chunkKey(chunkX: Int, chunkZ: Int): Long = BlockDeltaBuffer.chunkKey(chunkX, chunkZ)

    /**
     * Evict the oldest segment(s) other than [keepSegment] until the section count is below [cap].
     *
     * Returns true when there is budget room afterwards. False means only [keepSegment] is left, so
     * the budget is full of the segment being captured and nothing can be freed without dropping
     * it -- the cap is a hard ceiling, and the current segment simply stops growing.
     *
     * Called from both entry points: the segment boundary, and [tickCapture] (see the note there).
     */
    private fun evictToFit(cap: Int, keepSegment: Int): Boolean {
        while (totalSections >= cap) {
            val oldest = store.keys.filter { it != keepSegment }.minOrNull() ?: return false
            val ev = store.remove(oldest) ?: return false
            complete.remove(oldest)
            totalSections -= ev.values.sumOf { it.sections.size }
        }
        return true
    }

    /** Begin (or reset) [segmentId]'s capture, evicting the oldest segment(s) if the budget is full. */
    fun onSegmentStart(segmentId: Int, pcx: Int, pcz: Int, radius: Int) {
        try {
            evictToFit(rollingChunkCap(), segmentId)
            store.getOrPut(segmentId) { HashMap() }
            complete.remove(segmentId)
        } catch (_: Throwable) {
        }
    }

    /** Capture up to [PER_TICK] not-yet-captured chunks around the player, nearest-first. */
    fun tickCapture(segmentId: Int, pcx: Int, pcz: Int, radius: Int) {
        try {
            val world = MinecraftClient.getInstance().world ?: return
            val st = store.getOrPut(segmentId) { HashMap() }
            val cap = rollingChunkCap()  // once per tick, not per chunk attempt
            // Evict here, not only at a segment boundary. The budget was checked per capture attempt
            // but freed only by onSegmentStart, so once a long segment filled the cap every
            // tryCapture returned false and the "retried on a later tick once segment eviction frees
            // budget" promise in tryCapture's comment could not come true until the next auto-split,
            // ten minutes later. A fast-exploring recording captured nothing new in that window.
            //
            // A false return means the budget is full of the CURRENT segment and there is nothing
            // older to drop, so skip the scan: every attempt this tick would fail anyway, and the
            // scan is O(radius^2) hash lookups.
            if (!evictToFit(cap, segmentId)) return
            val r = radius.coerceIn(1, 32)
            // Steady state: the whole (2r+1)^2 ring was already captured for this centre, so every
            // lookup below would hit containsKey. Skip the scan until the player crosses a chunk
            // border or the radius changes.
            val ring = ringKey(pcx, pcz, r)
            if (complete[segmentId] == ring) return
            var n = 0
            var missed = 0
            fun visit(cx: Int, cz: Int): Boolean {
                when (tryCapture(st, world, cx, cz, cap)) {
                    CAPTURED -> n++
                    MISSED -> missed++
                }
                return n >= PER_TICK
            }
            for (d in 0..r) {
                if (d == 0) {
                    if (visit(pcx, pcz)) return
                } else {
                    for (cx in (pcx - d)..(pcx + d)) {
                        if (visit(cx, pcz - d)) return
                        if (visit(cx, pcz + d)) return
                    }
                    for (cz in (pcz - d + 1)..(pcz + d - 1)) {
                        if (visit(pcx - d, cz)) return
                        if (visit(pcx + d, cz)) return
                    }
                }
            }
            // Scanned the whole ring without hitting the per-tick budget. If nothing was missed (an
            // unloaded chunk or a full budget), every chunk in it is now captured.
            if (missed == 0) complete[segmentId] = ring
        } catch (_: Throwable) {
        }
    }

    private fun tryCapture(
        st: HashMap<Long, ChunkSnapshot.ChunkRec>,
        world: net.minecraft.client.world.ClientWorld,
        cx: Int, cz: Int,
        cap: Int,
    ): Int {
        val k = chunkKey(cx, cz)
        if (st.containsKey(k)) return PRESENT
        if (totalSections >= cap) return MISSED
        val secs = try { ChunkCapture.captureChunkAt(world, cx, cz) } catch (_: Throwable) { null }
            ?: return MISSED
        // Budget nearly full: DON'T store a truncated chunk. A truncated ChunkRec would be a
        // permanent hole in the replay world (containsKey -> never re-captured). Skip instead —
        // the chunk is retried on a later tick once segment eviction frees budget.
        if (totalSections + secs.size > cap) return MISSED
        st[k] = ChunkSnapshot.ChunkRec(cx, cz, secs)
        totalSections += secs.size
        return CAPTURED
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
            complete.clear()
            totalSections = 0
        } catch (_: Throwable) {
        }
    }

    /** Drop one segment's capture (recording stop / auto-split). */
    fun clearSegment(segmentId: Int) {
        try {
            val st = store.remove(segmentId)
            complete.remove(segmentId)
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

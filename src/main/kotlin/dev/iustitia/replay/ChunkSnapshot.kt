package dev.iustitia.replay

/**
 * A captured set of full chunks bundled into a v6+ `.iusclip`, so `/ius playclip` can render the
 * clip's world as **solid, textured blocks** (the real map) relocated around the user — letting
 * them free-spectate anywhere, including underground. See [ChunkCapture] for the capture (a
 * one-shot snapshot of every loaded chunk at `/ius clip` save time) and [dev.iustitia.render.ChunkMesher]
 * for the bake-into-`VertexBuffer` render path.
 *
 * ## Storage
 *
 * Each chunk stores its non-empty sections as a **palette + index array**: a section is
 * `(sectionY, palette: List<String>, data: ByteArray)` where `palette` is the block registry ids
 * (`"minecraft:stone"`, `"minecraft:air"`, … — names, not numeric state ids, so a clip is portable
 * across server versions and dimensions) and `data` is 4096 palette-index entries (1 byte each when
 * the palette has ≤ 256 entries, else 2 bytes big-endian). Air is a normal palette entry so a
 * section's full 4096 cells are represented; air-only sections are omitted entirely. Block
 * *properties* are dropped in v1 — the mesher renders each block's default state, so a stairs
 * renders as a stairs (recognizable) but not necessarily the recorded facing. Faithful orientations
 * are a documented follow-up.
 *
 * Iteration order within a section is y-major → z → x: `index = (localY * 16 + localZ) * 16 + localX`
 * (the inverse of [ChunkCapture]'s writer). `localX = x & 15`, `localY = y & 15`, `localZ = z & 15`,
 * `sectionY = y shr 4` (the world-section index, can be negative for y < 0).
 *
 * Compact: an arena's chunks are mostly one-block-type sections (palette size 1–3) → data is near-
 * uniform bytes; the file's chunk section is small. The [ChunkCapture] radius + section caps bound
 * the worst case.
 *
 * ## Lookup
 *
 * [nameAt] resolves an absolute block coord to its registry id (or null when the coord is outside
 * every captured chunk) in O(1) via a chunk-index map built lazily — the mesher uses it for
 * face-culling across section/chunk boundaries without building a giant global grid. Pure data:
 * no world access at render time, so a clip's world renders even after the source chunks unload or
 * on a different server. Fail-open throughout.
 */
data class ChunkSnapshot(val chunks: List<ChunkRec>) {

    /** One captured chunk: its world coords + its non-empty sections. */
    data class ChunkRec(val chunkX: Int, val chunkZ: Int, val sections: List<SectionRec>)

    /**
     * One captured section: the world-section index (`y shr 4`, may be negative), the palette of
     * block registry ids (incl. `"minecraft:air"`), and the 4096-entry index array. [data] is
     * 1 byte per entry when the palette has ≤ 256 entries, else 2 bytes big-endian per entry
     * (so [data].size is either 4096 or 8192).
     */
    data class SectionRec(val sectionY: Int, val palette: List<String>, val data: ByteArray) {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is SectionRec && sectionY == other.sectionY && palette == other.palette && data.contentEquals(other.data))
        override fun hashCode(): Int = 31 * (31 * sectionY + palette.hashCode()) + data.contentHashCode()
    }

    /** Lazy chunk-index map: `key(chunkX, chunkZ) → ChunkRec`, built on first [nameAt]. */
    @Volatile private var index: HashMap<Long, ChunkRec>? = null

    private fun key(chunkX: Int, chunkZ: Int): Long =
        (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)

    private fun ensureIndex(): HashMap<Long, ChunkRec> {
        index?.let { return it }
        val m = HashMap<Long, ChunkRec>(chunks.size)
        for (c in chunks) m[key(c.chunkX, c.chunkZ)] = c
        index = m
        return m
    }

    /** Total non-empty section count across all chunks (for [ClipCodec.ClipMeta] + the clip-manager row). */
    fun sectionCount(): Int = chunks.sumOf { it.sections.size }

    /** Non-air block count across the whole snapshot (for chat feedback / ClipMeta). */
    fun nonAirCount(): Int {
        var n = 0
        for (c in chunks) for (s in c.sections) {
            val pw = if (s.palette.size <= 256) 1 else 2
            if (pw == 1) {
                for (b in s.data) {
                    val name = s.palette.getOrNull(b.toInt() and 0xFF)
                    if (name != null && name != "minecraft:air") n++
                }
            } else {
                val lim = s.data.size - 1
                var i = 0
                while (i < lim) {
                    val idx = ((s.data[i].toInt() and 0xFF) shl 8) or (s.data[i + 1].toInt() and 0xFF)
                    val name = s.palette.getOrNull(idx)
                    if (name != null && name != "minecraft:air") n++
                    i += 2
                }
            }
        }
        return n
    }

    /**
     * The block registry id at absolute world coord (x,y,z), or null when the coord is outside every
     * captured chunk. In-range air returns `"minecraft:air"` (NOT null) — the mesher uses null to
     * mean "outside the captured area" (a face there is drawn, since the shell is closed at the
     * region edge) and `"minecraft:air"` to mean "air" (also drawn). O(1) via the chunk-index map.
     */
    fun nameAt(x: Int, y: Int, z: Int): String? {
        val cx = x shr 4; val cz = z shr 4
        val c = ensureIndex()[key(cx, cz)] ?: return null
        val sy = y shr 4
        val sec = c.sections.firstOrNull { it.sectionY == sy } ?: return null
        val localX = x and 15; val localY = y and 15; val localZ = z and 15
        val entry = (localY * 16 + localZ) * 16 + localX
        return if (sec.palette.size <= 256) {
            sec.palette.getOrNull(sec.data[entry].toInt() and 0xFF)
        } else {
            val i = entry * 2
            if (i + 1 >= sec.data.size) return null
            val idx = ((sec.data[i].toInt() and 0xFF) shl 8) or (sec.data[i + 1].toInt() and 0xFF)
            sec.palette.getOrNull(idx)
        }
    }
}
package dev.iustitia.replay

import net.minecraft.client.MinecraftClient
import net.minecraft.registry.Registries
import net.minecraft.world.chunk.ChunkStatus

/**
 * One-shot capture of every **loaded chunk** around the local player at `/ius clip` save time, stored
 * as a [ChunkSnapshot] (palette + index per non-empty section) so `/ius playclip` can later render the
 * clip's world as **solid, textured blocks** — the real map — relocated around the user, letting them
 * free-spectate anywhere including underground. This is the Iustitia-native equivalent of ReplayMod's
 * "download the world" without ReplayMod's connection-replacement (see the v1.2.0 plan).
 *
 * ## What it stores
 *
 * For each loaded chunk within [capture]'s `radius` (default 8 → 17×17 = 289 chunks), each non-empty
 * [net.minecraft.world.chunk.ChunkSection]: a `sectionY` (world-section coord = `y shr 4`, may be
 * negative), a `palette: List<String>` of block registry ids (`"minecraft:stone"`, `"minecraft:air"`,
 * … — **names, not numeric state ids**, so a clip is portable across server versions and dimensions),
 * and `data: ByteArray` (4096 palette-index entries, 1 byte when the palette has ≤ 256 entries else 2
 * bytes big-endian). Air is a normal palette entry; fully-air sections are omitted (`ChunkSection.isEmpty`).
 * Block *properties* + *block entities* are dropped in v1 (the mesher renders each block's default state;
 * chest/sign contents + faithful orientations are documented follow-ups).
 *
 * ## Caps + fail-open
 *
 * Bounded by `radius` (clamped 1..32), a chunk cap ([MAX_CHUNKS]) and a total-section budget
 * ([MAX_CAPTURE_SECTIONS]) so a max-render-distance client can't blow up the file. Unloaded chunks are
 * skipped (a chunk unloaded at save time is air in the clip — chunk-unloaded-never-FP). Any throw
 * returns null (the clip then saves chunks-null → v5 wireframe / ghosts render as before). No mixins,
 * no outgoing packets, no world edits — reads the live [net.minecraft.client.world.ClientWorld] only,
 * the same pattern as [TerrainCapture].
 */
object ChunkCapture {

    /** Hard chunk cap (sanity; the radius clamp already bounds the typical case to ≤289 chunks). */
    private const val MAX_CHUNKS = 1024

    /** Total non-empty-section budget across the whole snapshot, bounding file size + bake cost. */
    private const val MAX_CAPTURE_SECTIONS = 12_000

    /**
     * Capture every loaded chunk within [radius] (chunks) of the local player. Returns null when there
     * is no world/player, or on any throw (fail-open → clip saves chunks-null). Empty (all-air /
     * unloaded) chunks contribute no sections; an entirely empty result still returns a non-null
     * snapshot with an empty chunk list (the caller decides whether that's worth keeping — currently
     * it is, so the suppress mixin still hides the live world and the free-cam still works in void).
     */
    fun capture(radius: Int): ChunkSnapshot? = try {
        val mc = MinecraftClient.getInstance()
        val world = mc.world ?: return null
        val player = mc.player ?: return null
        val r = radius.coerceIn(1, 32)
        // Player chunk coords (floor-div handles negatives correctly; `x.toInt() shr 4` would round
        // toward zero for negative x and land on the wrong chunk).
        val pcx = Math.floorDiv(player.x.toInt(), 16)
        val pcz = Math.floorDiv(player.z.toInt(), 16)

        val chunks = ArrayList<ChunkSnapshot.ChunkRec>(Math.min((2 * r + 1) * (2 * r + 1), MAX_CHUNKS))
        var sections = 0
        val mgr = world.chunkManager

        outer@ for (dx in -r..r) {
            for (dz in -r..r) {
                if (chunks.size >= MAX_CHUNKS) break@outer
                val ccx = pcx + dx
                val ccz = pcz + dz
                // `create = false` → null when the chunk isn't loaded (fail-open: skip, treat as air).
                val chunk = mgr.getChunk(ccx, ccz, ChunkStatus.FULL, false) ?: continue
                val secs = captureChunk(chunk, ccx, ccz) ?: continue
                if (secs.isEmpty()) continue
                if (sections + secs.size > MAX_CAPTURE_SECTIONS) {
                    // Budget hit: include this chunk's sections up to the budget, then stop.
                    val room = MAX_CAPTURE_SECTIONS - sections
                    if (room <= 0) break@outer
                    chunks.add(ChunkSnapshot.ChunkRec(ccx, ccz, secs.take(room)))
                    sections += room
                    break@outer
                }
                chunks.add(ChunkSnapshot.ChunkRec(ccx, ccz, secs))
                sections += secs.size
            }
        }
        ChunkSnapshot(chunks)
    } catch (_: Throwable) { null }

    /**
     * Capture one chunk's non-empty sections. Returns null/empty on any throw (fail-open). `sectionY`
     * is the world-section coord = `(chunk.bottomY shr 4) + sectionIndex`, so it can be negative
     * (e.g. overworld bottom section is -4). Iteration order within a section is y-major → z → x:
     * `index = (localY * 16 + localZ) * 16 + localX` — the inverse of [ChunkSnapshot.nameAt].
     */
    private fun captureChunk(chunk: net.minecraft.world.chunk.Chunk, chunkX: Int, chunkZ: Int):
            List<ChunkSnapshot.SectionRec>? = try {
        val sectionArray = chunk.getSectionArray()
        if (sectionArray.isEmpty()) return emptyList()
        val bottomSectionCoord = chunk.getBottomY() shr 4
        val out = ArrayList<ChunkSnapshot.SectionRec>(sectionArray.size)
        for (i in sectionArray.indices) {
            val sec = sectionArray[i] ?: continue
            // Cheap all-air skip: an empty section's palette container is the singleton air container,
            // so we never iterate its 4096 cells. (A fluid-only section is non-empty and gets captured;
            // its fluid blocks resolve to no BakedModel at mesh time → render nothing, harmless.)
            if (sec.isEmpty()) continue
            val rec = captureSection(sec, bottomSectionCoord + i) ?: continue
            out.add(rec)
        }
        out
    } catch (_: Throwable) { null }

    /** Capture one section's 4096 cells into a palette + index array. Null on throw (skip the section). */
    private fun captureSection(sec: net.minecraft.world.chunk.ChunkSection, sectionY: Int):
            ChunkSnapshot.SectionRec? = try {
        val palette = LinkedHashMap<String, Int>(16)
        val idxArr = IntArray(4096)
        var hasNonAir = false
        for (ly in 0..15) {
            for (lz in 0..15) {
                for (lx in 0..15) {
                    val st = sec.getBlockState(lx, ly, lz)
                    val name = if (st.isAir) "minecraft:air" else {
                        hasNonAir = true
                        Registries.BLOCK.getId(st.block).toString()
                    }
                    // getOrPut on a LinkedHashMap preserves first-seen order → the palette list is stable.
                    val idx = palette.getOrPut(name) { palette.size }
                    idxArr[(ly * 16 + lz) * 16 + lx] = idx
                }
            }
        }
        // Shouldn't happen post-isEmpty, but a section that's all air yields nothing worth storing.
        if (!hasNonAir) return null
        val psize = palette.size
        // 1 byte per entry when the palette fits in a byte (≤ 256 entries), else 2 bytes big-endian.
        // A 16³ section almost never exceeds ~256 distinct block types, so the 2-byte path is rare.
        val data = if (psize <= 256) {
            ByteArray(4096) { idxArr[it].toByte() }
        } else {
            ByteArray(8192) { i ->
                val v = idxArr[i / 2]
                if (i % 2 == 0) (v shr 8).toByte() else v.toByte()
            }
        }
        ChunkSnapshot.SectionRec(sectionY, palette.keys.toList(), data)
    } catch (_: Throwable) { null }
}
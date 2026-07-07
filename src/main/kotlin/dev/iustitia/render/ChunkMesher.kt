package dev.iustitia.render

import dev.iustitia.replay.ChunkSnapshot
import net.minecraft.block.Block
import net.minecraft.block.BlockRenderType
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.client.color.block.BlockColors
import net.minecraft.client.render.BlockRenderLayers
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.model.BakedQuad
import net.minecraft.client.render.model.BlockModelPart
import net.minecraft.client.render.model.BlockStateModel
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import net.minecraft.util.math.Direction
import net.minecraft.util.math.random.Random

/**
 * Resolves a captured [ChunkSnapshot] into **per-block, face-culled, precomputed quads** grouped by
 * [RenderLayer], so [ChunkWorldRenderer] can draw the clip's solid world each frame by emitting those
 * quads directly — no per-frame model lookup, no buried faces. This replaces the old
 * `renderBlockAsEntity`-per-surface-block path (which emitted every block's full 6-face model every
 * frame, including faces buried against opaque neighbours — the ~380→40 FPS collapse on max-radius
 * clips) with a bake-once path that mirrors vanilla's own face-cull rule.
 *
 * ## Face cull = vanilla's rule (no holes)
 *
 * For each surface block, for each [BlockModelPart], we replicate
 * `BlockModelRenderer.renderFlat` exactly:
 *  - per [Direction] `d`: `part.getQuads(d)` is emitted only when
 *    `Block.shouldDrawSide(thisState, neighborState, d)` is true (neighbour resolved from the raw
 *    snapshot; region-edge / air → air state → shouldDrawSide returns true → the face draws, closing
 *    the shell at the captured-region edge, as before).
 *  - `part.getQuads(null)` (the general / non-culled quads — torches, fences, plants, and any model
 *    detail without a cull face) is **always** emitted. This is the rule that prevents holes in
 *    non-cube blocks; a naive per-direction cull that skipped `getQuads(null)` would drop them.
 *
 * `Block.shouldDrawSide` (vanilla-correct: opaque + transparent + same-block rules, e.g.
 * glass-vs-glass, leaves-vs-leaves) replaces the old crude `isOpaqueAt` approximation for the
 * **per-face** decision. `isOpaqueAt` is retained only for the block-level surface-shell skip (a block
 * whose 6 neighbours are all opaque is fully interior and never seen — pure overdraw).
 *
 * ## Lazy per-chunk baking (unchanged from v1.2.2)
 *
 * Each chunk bakes on demand the first time it enters the camera's render-distance ring, and
 * [ChunkWorldRenderer] only bakes a small per-frame block budget of the nearest unbaked in-range
 * chunks — so there's no single bake spike, far chunks only bake if you fly toward them, and a
 * scrolling-in chunk bakes alone. See [ChunkWorldRenderer] for the budget + ring walk.
 *
 * Neighbour lookups (both the block-level skip and the per-face cull) read the **raw snapshot** via
 * [ChunkSnapshot.nameAt] (O(1)), NOT the baked cache — so culling is correct regardless of which
 * chunks have baked yet. Bake order has no effect on correctness.
 *
 * ## Cache + fail-open
 *
 * Per-name caches: `BlockState` (`stateCache`), tint ARGB (`tintCache`), `RenderLayer` (`layerCache`)
 * — block names repeat heavily across chunks, so these persist across chunk bakes and are a big win.
 * A new snapshot (new clip) clears all of it via [ensureSnapshot]. `Registries.BLOCK` is a
 * DefaultedRegistry → an unknown id resolves to AIR (renders as nothing, counts as a transparent
 * neighbour) — the fail-open behaviour we want. [free] drops everything (called from
 * [dev.iustitia.replay.ReplayState.stop]). All wrapped fail-open so a render/bake error degrades
 * (missing blocks / no chunk world) rather than crashing the client.
 *
 * ## v1 limits (documented follow-ups, unchanged)
 *
 * Block **properties** are dropped — each block renders its default state, so a stairs renders as a
 * stairs (recognizable) but not the recorded facing. Block **entities** (chest contents, sign text,
 * banners) and **fluids** (`INVISIBLE` render type, skipped here) are not rendered. Lighting stays
 * fullbright (no AO/skylight). A full GPU static-mesh bake (`GpuBuffer` + `VertexBufferManager`, one
 * upload / one draw call per chunk per frame) is the perf follow-up if this win is insufficient.
 */
object ChunkMesher {

    /** One surface block's precomputed, face-culled quads at an absolute recorded coord, with the
     *  block's tint (r,g,b floats in 0..1; alpha is always 1 — replays are fullbright). Built once at
     *  bake time; [ChunkWorldRenderer] emits these directly each frame via `VertexConsumer.quad`. */
    data class BlockQuads(
        val x: Int, val y: Int, val z: Int,
        val r: Float, val g: Float, val b: Float,
        val quads: List<BakedQuad>,
    )

    /** The precomputed quads of one captured chunk, grouped by [RenderLayer] (so the renderer fetches
     *  each layer's VertexConsumer once per frame) + a [blockCount] (the number of surface blocks, for
     *  [ChunkWorldRenderer]'s per-frame lazy-bake budget — a chunk's bake cost scales with its
     *  surface-block count). */
    data class ChunkBlocks(
        val chunkX: Int, val chunkZ: Int,
        val byLayer: Map<RenderLayer, List<BlockQuads>>,
        val blockCount: Int,
    )

    @Volatile private var snapRef: ChunkSnapshot? = null
    @Volatile private var snapIndex: HashMap<Long, ChunkSnapshot.ChunkRec>? = null
    private var baked: LinkedHashMap<Long, ChunkBlocks> = LinkedHashMap()
    private var stateCache: HashMap<String, BlockState?> = HashMap(256)
    private var tintCache: HashMap<String, Int> = HashMap(256)
    private var layerCache: HashMap<String, RenderLayer> = HashMap(256)
    private val rand: Random = Random.create()
    private val directions: Array<Direction> = Direction.values()

    /** Chunk-coord → long key (same encoding as [ChunkSnapshot]'s internal index). */
    private fun key(chunkX: Int, chunkZ: Int): Long =
        (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)

    /**
     * Key the mesher to [snap]. A **different snapshot reference** (a new clip, or re-play of a reloaded
     * clip) clears the bake cache + all per-name caches + rebuilds the chunk index — so a new playclip
     * always starts a fresh lazy bake. Same reference is a no-op. Reassigns fresh map instances (rather
     * than `.clear()`) so a concurrent render thread holding the old `baked` ref keeps using a
     * consistent stale map instead of throwing `ConcurrentModificationException` — fail-open under a
     * stop/free race.
     */
    fun ensureSnapshot(snap: ChunkSnapshot) {
        if (snapRef === snap && snapIndex != null) return
        snapRef = snap
        baked = LinkedHashMap()
        stateCache = HashMap(256)
        tintCache = HashMap(256)
        layerCache = HashMap(256)
        val m = HashMap<Long, ChunkSnapshot.ChunkRec>(snap.chunks.size)
        for (c in snap.chunks) m[key(c.chunkX, c.chunkZ)] = c
        snapIndex = m
    }

    /** The captured [ChunkSnapshot.ChunkRec] at chunk coord (cx,cz), or null if the snapshot has no
     *  chunk there. Used by the renderer to know which in-range coords actually have data to bake. */
    fun chunkRecAt(cx: Int, cz: Int): ChunkSnapshot.ChunkRec? = snapIndex?.get(key(cx, cz))

    /** The already-baked [ChunkBlocks] at (cx,cz), or null if that chunk hasn't been baked yet (or the
     *  snapshot has no chunk there). Read every frame by the renderer. */
    fun bakedChunk(cx: Int, cz: Int): ChunkBlocks? = baked[key(cx, cz)]

    /**
     * Bake one chunk's face-culled quads **on demand** (the lazy-bake primitive). Idempotent — returns
     * the cached [ChunkBlocks] if (cx,cz) was already baked. Returns null when the snapshot has no
     * chunk at (cx,cz), or on any throw (fail-open → renderer skips that chunk, keeps going).
     * [ensureSnapshot] is called first so a bare [bakeChunk] still works.
     */
    fun bakeChunk(snap: ChunkSnapshot, cx: Int, cz: Int): ChunkBlocks? = try {
        ensureSnapshot(snap)
        val k = key(cx, cz)
        baked[k]?.let { return it }
        val rec = snapIndex?.get(k) ?: return null
        val byLayer = bakeChunkBlocks(snap, rec)
        val blockCount = byLayer.values.sumOf { it.size }
        val cb = ChunkBlocks(rec.chunkX, rec.chunkZ, byLayer, blockCount)
        baked[k] = cb
        cb
    } catch (_: Throwable) {
        null
    }

    /** Compute the face-culled quads of one chunk, grouped by RenderLayer. Block names + tint + layer
     *  resolve through the shared per-name caches. */
    private fun bakeChunkBlocks(snap: ChunkSnapshot, rec: ChunkSnapshot.ChunkRec): Map<RenderLayer, List<BlockQuads>> {
        val byLayer = HashMap<RenderLayer, MutableList<BlockQuads>>()
        val mc = MinecraftClient.getInstance()
        val brm = mc.blockRenderManager ?: return emptyMap()
        val blockColors = mc.blockColors
        val baseX = rec.chunkX shl 4
        val baseZ = rec.chunkZ shl 4
        for (sec in rec.sections) {
            val baseY = sec.sectionY shl 4
            val pw = if (sec.palette.size <= 256) 1 else 2
            for (entry in 0 until 4096) {
                val name = readIndex(sec, entry, pw) ?: continue
                if (name == "minecraft:air") continue
                val st = stateFor(name) ?: continue
                // Fluids + invisible-render-type blocks render nothing (matches the old
                // renderBlockAsEntity self-skip). MODEL + ENTITYBLOCK_ANIMATED proceed via their model.
                if (st.renderType == BlockRenderType.INVISIBLE) continue
                // Local cell coords (iteration order y-major → z → x, inverse of ChunkCapture).
                val lx = entry and 15
                val lz = (entry shr 4) and 15
                val ly = entry shr 8
                val x = baseX + lx
                val y = baseY + ly
                val z = baseZ + lz
                // Block-level surface-shell skip: all 6 neighbours opaque ⇒ fully interior ⇒ never seen.
                if (isOpaqueAt(snap, x + 1, y, z) &&
                    isOpaqueAt(snap, x - 1, y, z) &&
                    isOpaqueAt(snap, x, y + 1, z) &&
                    isOpaqueAt(snap, x, y - 1, z) &&
                    isOpaqueAt(snap, x, y, z + 1) &&
                    isOpaqueAt(snap, x, y, z - 1)
                ) continue
                val model = try { brm.getModel(st) } catch (_: Throwable) { null } ?: continue
                val parts = try { model.getParts(rand) } catch (_: Throwable) { emptyList() }
                if (parts.isEmpty()) continue
                val tint = tintFor(name, st, blockColors)
                val r = ((tint shr 16) and 0xFF) / 255f
                val g = ((tint shr 8) and 0xFF) / 255f
                val b = (tint and 0xFF) / 255f
                val layer = layerFor(name, st)
                val quads = ArrayList<BakedQuad>(8)
                for (part in parts) {
                    // Per-direction face quads: emit only when vanilla's shouldDrawSide says the face
                    // is visible (neighbour not opaque-and-same-block). This is the buried-face cull.
                    for (d in directions) {
                        val faceQuads = try { part.getQuads(d) } catch (_: Throwable) { emptyList() }
                        if (faceQuads.isEmpty()) continue
                        if (!shouldDrawSide(st, snap, x, y, z, d)) continue
                        quads.addAll(faceQuads)
                    }
                    // General / non-culled quads (getQuads(null)) — torches, fences, plants, model
                    // detail with no cull face. ALWAYS emitted (vanilla renderFlat does the same) —
                    // skipping these would punch holes in non-cube blocks.
                    val generalQuads = try { part.getQuads(null) } catch (_: Throwable) { emptyList() }
                    if (generalQuads.isNotEmpty()) quads.addAll(generalQuads)
                }
                if (quads.isEmpty()) continue
                byLayer.getOrPut(layer) { ArrayList() }.add(BlockQuads(x, y, z, r, g, b, quads))
            }
        }
        return byLayer
    }

    /**
     * Vanilla face-cull rule (mirrors `BlockModelRenderer.shouldDrawFace` with the cull flag set):
     * a face draws when [Block.shouldDrawSide] returns true for the neighbour in direction [d]. The
     * neighbour state resolves from the raw snapshot; region-edge / air → AIR default state →
     * shouldDrawSide returns true → the face draws (closes the shell at the captured-region edge).
     * Fail-open to true (draw the face) on any throw — drawing an extra face is harmless, dropping one
     * would leave a hole.
     */
    private fun shouldDrawSide(state: BlockState, snap: ChunkSnapshot, x: Int, y: Int, z: Int, d: Direction): Boolean = try {
        val nName = snap.nameAt(x + d.offsetX, y + d.offsetY, z + d.offsetZ)
        val nState = if (nName == null || nName == "minecraft:air") Blocks.AIR.defaultState else stateFor(nName)
        if (nState == null) true else Block.shouldDrawSide(state, nState, d)
    } catch (_: Throwable) { true }

    /** Resolve a palette name to its default BlockState, memoized in [stateCache]. null = bad name /
     *  throw. NB: Registries.BLOCK is a DefaultedRegistry, so get(unknownId) returns AIR (never null)
     *  — an unknown block resolves to air, which renders as nothing and is a transparent neighbour —
     *  exactly the fail-open behaviour we want, so no special unknown-skip needed. */
    private fun stateFor(name: String): BlockState? = stateCache.getOrPut(name) {
        try {
            val id = Identifier.tryParse(name) ?: return@getOrPut null
            Registries.BLOCK.get(id).defaultState
        } catch (_: Throwable) { null }
    }

    /** Resolve a block's tint colour (ARGB int) for tint index 0, memoized in [tintCache]. Same call
     *  the old renderBlockAsEntity made (BlockColors.getColor with a null BlockRenderView → the
     *  non-biome default for grass etc.; no-tint blocks return 0xFFFFFF). Fail-open to white. */
    private fun tintFor(name: String, st: BlockState, blockColors: BlockColors): Int = tintCache.getOrPut(name) {
        try { blockColors.getColor(st, null, null, 0) } catch (_: Throwable) { 0xFFFFFF }
    }

    /** Resolve a block's entity-block RenderLayer, memoized in [layerCache]. Same routing the old
     *  renderBlockAsEntity used (BlockRenderLayers.getEntityBlockLayer). Fail-open to SOLID. */
    private fun layerFor(name: String, st: BlockState): RenderLayer = layerCache.getOrPut(name) {
        try { BlockRenderLayers.getEntityBlockLayer(st) } catch (_: Throwable) { RenderLayers.solid() }
    }

    /** Read palette index [entry] (0..4095) → palette name, honouring the 1-vs-2 byte width. Fail-open null. */
    private fun readIndex(sec: ChunkSnapshot.SectionRec, entry: Int, pw: Int): String? = try {
        if (pw == 1) sec.palette.getOrNull(sec.data[entry].toInt() and 0xFF)
        else {
            val i = entry * 2
            if (i + 1 >= sec.data.size) return null
            val idx = ((sec.data[i].toInt() and 0xFF) shl 8) or (sec.data[i + 1].toInt() and 0xFF)
            sec.palette.getOrNull(idx)
        }
    } catch (_: Throwable) { null }

    /** True when the block at (x,y,z) is opaque (used for the block-level surface-shell skip only —
     *  the per-face cull uses [shouldDrawSide]/Block.shouldDrawSide). `null` (outside the captured
     *  region) and air count as NOT opaque. Reads the raw snapshot; fail-open to not-opaque. */
    private fun isOpaqueAt(snap: ChunkSnapshot, x: Int, y: Int, z: Int): Boolean = try {
        val n = snap.nameAt(x, y, z) ?: return false
        if (n == "minecraft:air") return false
        val st = stateFor(n) ?: return false
        st.isOpaque
    } catch (_: Throwable) { false }

    /** Drop the cache (called from [dev.iustitia.replay.ReplayState.stop]). Idempotent + fail-open.
     *  Reassigns fresh instances so a concurrent render thread can't see a half-cleared map. */
    fun free() {
        try {
            snapRef = null
            snapIndex = null
            baked = LinkedHashMap()
            stateCache = HashMap(256)
            tintCache = HashMap(256)
            layerCache = HashMap(256)
        } catch (_: Throwable) {}
    }
}
package dev.iustitia.render

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexFormat
import dev.iustitia.replay.ChunkSnapshot
import net.minecraft.block.Block
import net.minecraft.block.BlockRenderType
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.client.color.block.BlockColors
import net.minecraft.client.render.BlockRenderLayers
import net.minecraft.client.render.BufferBuilder
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.model.BakedQuad
import net.minecraft.client.render.model.BlockModelPart
import net.minecraft.client.render.model.BlockStateModel
import net.minecraft.client.util.BufferAllocator
import net.minecraft.client.util.math.MatrixStack
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

    /** One captured chunk's surface quads baked into a **static GPU vertex buffer** (the C2 perf
     *  rework). Baked ONCE at bake time into the layer's vertex format
     *  (`POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL`, `DrawMode.QUADS`) with **absolute recorded
     *  coords** already baked into the vertices (a per-block `translate(x,y,z)` was applied at bake
     *  time, so no per-frame per-block matrix is needed — the renderer binds one model-view matrix
     *  per frame as the `"DynamicTransforms"` uniform). [vertexCount] = 4 × quad count;
     *  [indexCount] = 6 × quad count (triangulated at draw time by the shared sequential quad index
     *  buffer). [vb] is a `GpuBuffer` (USAGE_VERTEX | USAGE_COPY_DST) owned here, closed in [free].
     *  A bake failure omits the layer from [ChunkBlocks.staticByLayer] entirely (so [vb] is always
     *  non-null when present) — the renderer then falls back to [ChunkBlocks.byLayer]'s per-frame
     *  `vc.quad` emit for that layer. Fail-open. */
    data class StaticChunkLayer(val vb: GpuBuffer, val vertexCount: Int, val indexCount: Int)

    /** The precomputed quads of one captured chunk, grouped by [RenderLayer] (so the renderer fetches
     *  each layer's VertexConsumer once per frame) + a [blockCount] (the number of surface blocks, for
     *  [ChunkWorldRenderer]'s per-frame lazy-bake budget — a chunk's bake cost scales with its
     *  surface-block count) + the baked-block AABB (`minX..maxX` etc, in **recorded** coords, true
     *  extent — `max` is `maxBlockCoord + 1` so the box covers the full 1-block cell). The AABB lets
     *  [ChunkWorldRenderer] cull chunks fully behind the camera plane without popping (an 8-corner
     *  test against the camera forward; a chunk is only dropped when EVERY corner is behind the
     *  camera, so a partly-in-front chunk always renders). All zero when the chunk has no blocks.
     *
     *  [staticByLayer] is the C2 static-GPU-mesh path (one [StaticChunkLayer] per layer; the renderer
     *  draws these via its own `RenderPass` with no per-frame re-emit/upload). [byLayer] is kept as the
     *  fail-open fallback — the per-frame `vc.quad` emit used when a chunk has no static buffer (GPU
     *  bake failed) or when the whole static-draw path throws for a frame. */
    data class ChunkBlocks(
        val chunkX: Int, val chunkZ: Int,
        val byLayer: Map<RenderLayer, List<BlockQuads>>,
        val staticByLayer: Map<RenderLayer, StaticChunkLayer>,
        val blockCount: Int,
        val minX: Int, val minY: Int, val minZ: Int,
        val maxX: Int, val maxY: Int, val maxZ: Int,
    )

    @Volatile private var snapRef: ChunkSnapshot? = null
    @Volatile private var snapIndex: HashMap<Long, ChunkSnapshot.ChunkRec>? = null
    private var baked: LinkedHashMap<Long, ChunkBlocks> = LinkedHashMap()

    /** Fullbright light coord (LightmapTextureManager.MAX_LIGHT_COORDINATE = 0xF000F0) — the same
     *  flat lighting the renderer applies; baked into the static buffer at bake time. */
    private const val FULL_LIGHT = 0xF000F0

    /** Initial CPU capacity for the per-chunk bake [BufferAllocator] (bytes). The allocator grows on
     *  demand; this just bounds regrow count. A worst-case dense arena chunk is ~15–30k quads ×
     *  4 verts × 36 B/vert (POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL) ≈ a few MB. */
    private const val STATIC_ALLOC_CAPACITY = 2_097_152
    private var stateCache: HashMap<String, BlockState?> = HashMap(256)
    private var tintCache: HashMap<String, Int> = HashMap(256)
    private var layerCache: HashMap<String, RenderLayer> = HashMap(256)
    private val rand: Random = Random.create()
    private val directions: Array<Direction> = Direction.entries.toTypedArray()

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
        closeStaticBaked(baked); baked = LinkedHashMap()
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
        // Baked-block AABB (recorded coords, true extent: max = coord+1). One pass over the baked
        // blocks at bake time so the renderer can do a pop-free 8-corner behind-camera cull each frame.
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        if (blockCount > 0) {
            for (bqs in byLayer.values) for (bq in bqs) {
                if (bq.x < minX) minX = bq.x
                if (bq.y < minY) minY = bq.y
                if (bq.z < minZ) minZ = bq.z
                if (bq.x > maxX) maxX = bq.x
                if (bq.y > maxY) maxY = bq.y
                if (bq.z > maxZ) maxZ = bq.z
            }
            maxX++; maxY++; maxZ++
        } else {
            minX = 0; minY = 0; minZ = 0; maxX = 0; maxY = 0; maxZ = 0
        }
        // C2: bake each layer's quads into a static GPU vertex buffer once (absolute recorded coords
        // already baked in, so no per-frame per-block matrix). Fail-open per layer — a GPU-bake throw
        // leaves that layer out of staticByLayer and the renderer falls back to byLayer's vc.quad emit.
        val staticByLayer = try { bakeStatic(byLayer) } catch (_: Throwable) { emptyMap() }
        val cb = ChunkBlocks(rec.chunkX, rec.chunkZ, byLayer, staticByLayer, blockCount, minX, minY, minZ, maxX, maxY, maxZ)
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
     * C2 bake: turn one chunk's per-layer [BlockQuads] into **static GPU vertex buffers** (one per
     * layer). Each layer is emitted ONCE into a `BufferBuilder` (`DrawMode.QUADS`, the layer's own
     * `VertexFormat` — `POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL`) using a bake-time `MatrixStack`
     * that translates each block to its absolute recorded coord, so the baked vertices carry absolute
     * positions and the renderer needs no per-frame per-block matrix (just one model-view uniform per
     * frame). The built `ByteBuffer` is uploaded once via `RenderSystem.getDevice().createBuffer`
     * (`USAGE_VERTEX | USAGE_COPY_DST`), then the CPU buffer is released. This is the same `vc.quad`
     * work the renderer used to do **per frame**, now done **once per chunk** — moving the ~90%
     * per-frame emission cost to a one-time, lazy-budgeted bake. Per-layer fail-open: a throw for one
     * layer leaves it absent from the result and the renderer falls back to that layer's [byLayer].
     */
    private fun bakeStatic(byLayer: Map<RenderLayer, List<BlockQuads>>): Map<RenderLayer, StaticChunkLayer> {
        val out = HashMap<RenderLayer, StaticChunkLayer>()
        if (byLayer.isEmpty()) return out
        val dev = try { RenderSystem.getDevice() } catch (_: Throwable) { null } ?: return out
        val ms = MatrixStack()
        for ((layer, blocks) in byLayer) {
            try {
                if (blocks.isEmpty()) continue
                val alloc = BufferAllocator(STATIC_ALLOC_CAPACITY)
                try {
                    val bb = BufferBuilder(alloc, VertexFormat.DrawMode.QUADS, layer.getVertexFormat())
                    for (bq in blocks) {
                        ms.push()
                        ms.translate(bq.x.toFloat(), bq.y.toFloat(), bq.z.toFloat())
                        val entry = ms.peek()
                        for (q in bq.quads) {
                            try {
                                bb.quad(entry, q, bq.r, bq.g, bq.b, 1f, FULL_LIGHT, OverlayTexture.DEFAULT_UV)
                            } catch (_: Throwable) {
                                // skip one bad quad, keep the rest of the chunk
                            }
                        }
                        ms.pop()
                    }
                    val built = bb.end()
                    try {
                        val bytes = built.getBuffer()
                        val vb = dev.createBuffer(
                            java.util.function.Supplier<String> { "iustitia-chunk-static" },
                            GpuBuffer.USAGE_VERTEX or GpuBuffer.USAGE_COPY_DST,
                            bytes,
                        )
                        val vertexCount = built.getDrawParameters().vertexCount()
                        // DrawMode.QUADS: 4 verts/quad → 6 indices/quad (triangulated at draw by the
                        // shared sequential quad index buffer).
                        out[layer] = StaticChunkLayer(vb, vertexCount, vertexCount / 4 * 6)
                    } finally {
                        try { built.close() } catch (_: Throwable) {}
                    }
                } finally {
                    try { alloc.close() } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {
                // fail-open for this layer: leave it absent, renderer falls back to byLayer emit
            }
        }
        return out
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
     *  Closes the static `GpuBuffer`s owned by the baked chunks BEFORE dropping the cache (reassigning
     *  a fresh map would orphan the GL buffers). Reassigns fresh instances so a concurrent render
     *  thread can't see a half-cleared map. */
    fun free() {
        try {
            closeStaticBaked(baked)
            snapRef = null
            snapIndex = null
            baked = LinkedHashMap()
            stateCache = HashMap(256)
            tintCache = HashMap(256)
            layerCache = HashMap(256)
        } catch (_: Throwable) {}
    }

    /** Close every static `GpuBuffer` in a baked-chunks map (fail-open per buffer). Used by
     *  [ensureSnapshot] + [free] before dropping/replacing the map, so GL buffers aren't orphaned. */
    private fun closeStaticBaked(map: Map<Long, ChunkBlocks>) {
        for (cb in map.values) {
            for (st in cb.staticByLayer.values) {
                try { st.vb.close() } catch (_: Throwable) {}
            }
        }
    }
}
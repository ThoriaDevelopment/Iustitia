package dev.iustitia.render

import dev.iustitia.config.ConfigManager
import dev.iustitia.replay.ReplayState
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.Vec3d

/**
 * Draws the active clip's captured chunk world as **solid, textured blocks** (the real map) each
 * frame, under the same shared origin translate [dev.iustitia.render.ReplayRenderer] applies, so the
 * chunk world + ghosts move as one rigid block to the user. The block list is built **lazily per
 * chunk** by [ChunkMesher] as **per-block, face-culled, precomputed quads** (only the quads vanilla
 * would draw — buried faces are dropped at bake time, and `getQuads(null)` general quads are kept so
 * non-cube blocks don't get holes); this class emits those quads each frame via
 * `VertexConsumer.quad`, one `push/translate/pop` per block — no per-frame model lookup, no
 * `renderBlockAsEntity` dispatch.
 *
 * ## Per-chunk render-distance cull + lazy progressive baking (the perf design)
 *
 * A max-radius capture ([IustitiaConfig.clipChunkRadius], up to 17×17 = 289 chunks) can hold hundreds
 * of thousands of surface blocks. Two things keep a playclip playable:
 *
 * 1. **Render-distance cull** — each frame we only consider chunks within
 *    [IustitiaConfig.clipChunkRenderDistance] (default 6, clamped 4..12) of the camera in **recorded
 *    space** (the camera's recorded-space position = `camPos - relocOffset`). Chunks outside that
 *    chebyshev radius are neither rendered NOR baked.
 * 2. **Lazy progressive baking** — of the in-range chunks, only the ones already baked render; the
 *    nearest UNbaked in-range chunks are baked on demand up to a small **per-frame block budget**
 *    ([LAZY_BAKE_BLOCK_BUDGET], counted in surface blocks). So the clip's world streams in from the
 *    camera outward over the first second instead of appearing in one synchronous bake spike. Far
 *    chunks only bake if you fly toward them; chunks you never visit are never baked. When a new
 *    chunk scrolls into the render ring as you move, only that chunk bakes (within the budget).
 *
 * ## Why precomputed quads (the v1.3 perf rework)
 *
 * The previous path called `BlockRenderManager.renderBlockAsEntity` per surface block per frame,
 * which emits the whole 6-face model every frame (including faces buried against opaque neighbours)
 * and re-resolves the model each frame — at ~30k+ surface blocks that collapsed FPS (~380→40). The
 * mesher now bakes each chunk once into per-`RenderLayer` lists of [ChunkMesher.BlockQuads] using
 * vanilla's own face-cull rule (`Block.shouldDrawSide` per direction + `getQuads(null)` general
 * quads always), so a frame just emits the precomputed quads: ~6× fewer quads (no buried faces) and
 * no per-frame model dispatch. A full GPU static-mesh bake (`GpuBuffer`, one upload / one draw call
 * per chunk) is the documented follow-up if this is still insufficient.
 *
 * ## Gating
 *
 * No-op unless `ReplayState.active && ReplayState.chunks != null` — so a wireframe-terrain clip
 * (`chunks == null`) and a plain `/ius replay` are unaffected; the live world is only hidden by
 * [dev.iustitia.mixin.SectionRenderStateMixin] when `chunks != null` too. [free] drops the mesher cache.
 */
object ChunkWorldRenderer {

    /** Fullbright light coord (LightmapTextureManager.MAX_LIGHT_COORDINATE = 0xF000F0) — replays don't
     *  need real AO/skylight; flat-lit solid blocks read clearly. Real lighting is a follow-up. */
    private const val FULL_LIGHT = 0xF000F0

    /**
     * Per-frame lazy-bake budget in **surface-block count**. Each frame we bake the nearest unbaked
     * in-range chunks until the cumulative surface-block count of newly-baked chunks reaches this, so
     * the bake work spreads evenly across frames. ~3000 blocks/frame ≈ a handful of arena chunks,
     * baking the whole in-range ring (≤169 chunks at radius 6) over ~1 s at 60 fps with no
     * single-frame spike. At least one chunk always bakes per frame (if any unbaked in-range) so the
     * view never stalls entirely.
     */
    private const val LAZY_BAKE_BLOCK_BUDGET = 3000

    /**
     * Draw the clip's chunk world. [matrices] is already translated by the shared origin translate
     * (`-camPos + relocOffset`) in [dev.iustitia.render.ReplayRenderer.drawGhosts]; each block is then
     * translated to its absolute recorded coord, landing at `recorded - camPos + offset` — same frame
     * as the ghosts, so both move together. [camPos] is the live camera world pos used for the shared
     * translate + the recorded-space camera chunk.
     *
     * Each frame: snapshot-key the mesher, compute the recorded-space camera chunk, then walk the
     * in-range chunk coords nearest-first (chebyshev rings outward from the camera chunk). For each
     * coord: render the chunk if it's already baked, else (if the per-frame bake budget remains — or
     * it's the camera's own chunk) bake it now and render it. Unbaked chunks with no budget left are
     * skipped this frame — they stream in over subsequent frames. Fail-open per frame + per chunk.
     */
    fun render(matrices: MatrixStack, vcp: VertexConsumerProvider, camPos: Vec3d, relocOffset: Vec3d) {
        if (!ReplayState.active) return
        val snap = ReplayState.chunks ?: return
        try {
            ChunkMesher.ensureSnapshot(snap)
            // Recorded-space camera = camPos - relocOffset. Cull chunks whose chebyshev distance from
            // this point exceeds the configured render distance.
            val rdx = camPos.x - relocOffset.x
            val rdz = camPos.z - relocOffset.z
            val camChunkX = Math.floor(rdx / 16.0).toInt()
            val camChunkZ = Math.floor(rdz / 16.0).toInt()
            val dist = ConfigManager.config.clipChunkRenderDistance.coerceIn(4, 12)

            // Visit in-range chunk coords NEAREST-FIRST by walking chebyshev rings outward from the
            // camera chunk (d = 0, then 1, … up to the render distance). Each in-range cell is visited
            // exactly once per frame — no per-frame list allocation, no coord truncation.
            var budget = LAZY_BAKE_BLOCK_BUDGET
            for (d in 0..dist) {
                if (d == 0) {
                    budget -= processChunk(camChunkX, camChunkZ, 0, snap, matrices, vcp, budget)
                    continue
                }
                for (cx in (camChunkX - d)..(camChunkX + d)) {
                    budget -= processChunk(cx, camChunkZ - d, d, snap, matrices, vcp, budget)
                    budget -= processChunk(cx, camChunkZ + d, d, snap, matrices, vcp, budget)
                }
                for (cz in (camChunkZ - d + 1)..(camChunkZ + d - 1)) {
                    budget -= processChunk(camChunkX - d, cz, d, snap, matrices, vcp, budget)
                    budget -= processChunk(camChunkX + d, cz, d, snap, matrices, vcp, budget)
                }
            }
        } catch (_: Throwable) {
            // fail-open: chunk world drops this frame, ghosts + live render continue
        }
    }

    /**
     * Render the chunk at (cx,cz) if it's already baked; otherwise bake it on demand (if the per-frame
     * [budget] remains, or it's the camera's own chunk — d == 0 — which always bakes so the view
     * directly around the camera never stalls) and render it. Skips chunks the snapshot has no data
     * for, and chunks that are unbaked with no budget left (they stream in on a later frame).
     *
     * @return the surface-block count of the chunk it just baked (so the caller can decrement its
     *   running budget), or 0 when the chunk was already baked / had no data / was skipped for budget.
     *   Fail-open per chunk (returns 0 on a throw).
     */
    private fun processChunk(
        cx: Int, cz: Int, d: Int,
        snap: dev.iustitia.replay.ChunkSnapshot,
        matrices: MatrixStack, vcp: VertexConsumerProvider,
        budget: Int,
    ): Int = try {
        if (ChunkMesher.chunkRecAt(cx, cz) == null) return 0
        val cb = ChunkMesher.bakedChunk(cx, cz)
        if (cb != null) {
            renderChunkBlocks(cb, matrices, vcp)
            return 0
        }
        if (budget > 0 || d == 0) {
            val newly = ChunkMesher.bakeChunk(snap, cx, cz)
            if (newly != null) {
                renderChunkBlocks(newly, matrices, vcp)
                return newly.blockCount
            }
        }
        // budget spent + not the camera chunk → skip this frame; it bakes on a later frame.
        0
    } catch (_: Throwable) {
        0
    }

    /** Emit every precomputed quad of one baked chunk at its absolute recorded coord, grouped by
     *  RenderLayer (one VertexConsumer fetch per layer). One push/translate/pop per block; the shared
     *  origin translate is already on [matrices]. Fail-open per block + per quad. */
    private fun renderChunkBlocks(
        cb: ChunkMesher.ChunkBlocks,
        matrices: MatrixStack,
        vcp: VertexConsumerProvider,
    ) {
        for ((layer, blocks) in cb.byLayer) {
            val vc = try { vcp.getBuffer(layer) } catch (_: Throwable) { return }
            for (bq in blocks) {
                try {
                    matrices.push()
                    matrices.translate(bq.x.toFloat(), bq.y.toFloat(), bq.z.toFloat())
                    val entry = matrices.peek()
                    for (q in bq.quads) {
                        try {
                            vc.quad(entry, q, bq.r, bq.g, bq.b, 1f, FULL_LIGHT, OverlayTexture.DEFAULT_UV)
                        } catch (_: Throwable) {
                            // skip one bad quad, keep the rest
                        }
                    }
                } catch (_: Throwable) {
                    // skip one bad block, keep the rest
                } finally {
                    try { matrices.pop() } catch (_: Throwable) {}
                }
            }
        }
    }

    /** Drop the mesher cache (called from [dev.iustitia.replay.ReplayState.stop]). Idempotent. */
    fun free() = ChunkMesher.free()
}
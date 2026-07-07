# playclip auto-freecam + per-face-culled chunk render — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/ius playclip` auto-enter FREECAM (reverting on stop) and replace the per-frame `renderBlockAsEntity`-per-block chunk render with a bake-once, per-face-culled, precomputed-quad path so playclip FPS stops collapsing on max-radius clips.

**Architecture:** Two independent changes. (1) One line in the shared playclip entry point (`ClipPlayback.start`) flips the camera mode to FREECAM after a successful start; revert is already handled by `ReplayState.stop()`. (2) `ChunkMesher` bakes each chunk once into per-`RenderLayer` lists of `BlockQuads` (absolute coord + tint + only the quads vanilla would draw — per-direction `getQuads(d)` gated by `Block.shouldDrawSide`, plus `getQuads(null)` general quads always), and `ChunkWorldRenderer` emits those precomputed quads each frame via `VertexConsumer.quad` with one `push/translate/pop` per block — no per-frame model lookup, no buried faces.

**Tech Stack:** Kotlin, Fabric, Minecraft 1.21.11 (yarn mappings), Mixin. Build: `./gradlew.bat compileKotlin`. No test framework — render/mixin paths are **runtime-only-verifiable** per project convention (every mixin KDoc in this repo states this): the build confirms compile + API signatures; a live client run confirms behaviour. Fail-open throughout (project posture).

## Global Constraints

- **MC version:** 1.21.11, yarn `1.21.11+build.6`. Block render model type is `net.minecraft.client.render.model.BlockStateModel` (the old `BakedModel` is gone for block rendering); parts come from `BlockStateModel.getParts(Random)` → `List<BlockModelPart>`; per-face quads from `BlockModelPart.getQuads(Direction)` (and `getQuads(null)` for general quads). All verified by javap against the named jar.
- **No new mixins, no config changes, no codec/format bump.** `clipChunkWorld` / `clipChunkRadius` / `clipChunkRenderDistance` / `LAZY_BAKE_BLOCK_BUDGET` stay.
- **Fail-open:** every new render/bake path is wrapped so a throw degrades (skip block / drop frame) rather than crashing the client.
- **Iris/Sodium coexistence:** keep using the `AFTER_ENTITIES` Immediate `VertexConsumerProvider` (`ctx.consumers()`) — do NOT route through the blaze4d GPU pipeline (`GpuBuffer`/`VertexBufferManager`).
- **`/ius replay` must stay unchanged** — it does not go through `ClipPlayback`, so the auto-freecam only affects `/ius playclip` (+ the clip-manager screen Play button, which shares `ClipPlayback.start`).
- **Commit style:** end commit messages with `Co-Authored-By: Claude <noreply@anthropic.com>`. Commit only the files each task touches (the working tree has unrelated dirty files — do not `git add -A`).
- **Build command:** `./gradlew.bat compileKotlin` (expected: `BUILD SUCCESSFUL`). Use the Bash tool (Git Bash) — `./gradlew.bat` runs correctly there.

---

### Task 1: Auto-enter FREECAM on `/ius playclip` start

**Files:**
- Modify: `src/main/kotlin/dev/iustitia/replay/ClipPlayback.kt` (the `start` function, lines ~30–38)

**Interfaces:**
- Consumes: `ReplayState.start(...)` (unchanged), `ReplayState.setCameraMode(ReplayState.CameraMode)` (existing, public).
- Produces: none — purely behavioural; no signature change. `ClipPlayback.start` still returns `Result`.

- [ ] **Step 1: Add the auto-FREECAM call after a successful start**

Replace the body of `ClipPlayback.start` (the whole `fun start(name: String, speed: Float): Result = try { ... }`) with:

```kotlin
    fun start(name: String, speed: Float): Result = try {
        val clip = ClipStore.load(name)
        if (clip == null || clip.window.frames.isEmpty()) {
            Result.LoadFailed
        } else {
            val started = ReplayState.start(clip.window, clip.focus, speed, ConfigManager.config.replayHideLive, relocate = true)
            if (started) {
                // Auto-enter FREECAM so /ius playclip is immediately a free-spectate through the clip's
                // solid world (or the live world for a no-chunk clip — live terrain isn't suppressed
                // when chunks==null, which is fine). Revert on stop is already handled by
                // ReplayState.stop() -> exitFreecam() + restorePerspective() (next frame's camera mixin
                // FREECAM branch returns false -> vanilla re-derives the live-player view). /ius replay
                // never reaches here (it calls ReplayState.start directly), so it keeps its FREE default.
                // Fail-open: a throw leaves the camera in the FREE default set by start — never a crash.
                try { ReplayState.setCameraMode(ReplayState.CameraMode.FREECAM) } catch (_: Throwable) {}
                Result.Started(clip.window.frames.size, clip.focus)
            } else Result.StartFailed
        }
    } catch (_: Throwable) { Result.StartFailed }
```

- [ ] **Step 2: Build-verify**

Run: `./gradlew.bat compileKotlin`
Expected: `BUILD SUCCESSFUL`. (This is a one-line behavioural change; if it fails, the only likely cause is a typo in `CameraMode.FREECAM` / `setCameraMode` — both already exist in `ReplayState.kt`.)

- [ ] **Step 3: Runtime-verify (live client)**

Launch the client with the built mod. With a saved chunk-bearing clip (e.g. one made via `/ius clip 10 myclip` while `clipChunkWorld` on):
1. Run `/ius playclip myclip` → the camera should **immediately detach** into freecam (WASD flies noclip through the captured solid world; the first-person hand is hidden — that's the existing `GameRendererMixin` gated on `freecamActive`).
2. Let the clip play to its end → the camera should **snap back** to the live player's view and the hand should reappear (this is `ReplayState.stop()` → `exitFreecam()` + `restorePerspective()`, already implemented).
3. Repeat, but this time stop early with `/ius playclip off` → same revert.
4. Sanity: `/ius replay <player> <seconds>` (the live instant-replay) should **not** enter freecam — it keeps your own view (FREE), confirming the change is playclip-scoped.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/iustitia/replay/ClipPlayback.kt
git commit -F - <<'EOF'
feat(playclip): auto-enter FREECAM on /ius playclip start

ClipPlayback.start now flips the camera mode to FREECAM after a successful
start, so a playclip is immediately a free-spectate. Revert on stop (natural
end / /ius playclip off) is already handled by ReplayState.stop() ->
exitFreecam() + restorePerspective(). /ius replay is untouched (it does not
go through ClipPlayback). Fail-open.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
```

---

### Task 2: Per-face-culled, precomputed-quad chunk render

This is the perf rework. `ChunkMesher` and `ChunkWorldRenderer` are tightly coupled (`ChunkWorldRenderer` consumes `ChunkMesher.ChunkBlocks`), so they change together in this task — the build gate is at the end, after both files are edited.

**Files:**
- Modify (full rewrite): `src/main/kotlin/dev/iustitia/render/ChunkMesher.kt`
- Modify (render path rewrite): `src/main/kotlin/dev/iustitia/render/ChunkWorldRenderer.kt` (the `render`, `processChunk`, `renderChunkBlocks` functions; imports)

**Interfaces:**
- Consumes (unchanged from the rest of the codebase): `ReplayState.active`, `ReplayState.chunks` (`ChunkSnapshot`), `ReplayState.relocOffset`, `ConfigManager.config.clipChunkRenderDistance`, `ChunkSnapshot.nameAt(x,y,z)` + `ChunkSnapshot.ChunkRec`/`SectionRec` (palette/data), `ReplayRenderer`'s shared origin translate (already on the `MatrixStack` when `render` is called).
- Produces (new public surface of `ChunkMesher`, consumed only by `ChunkWorldRenderer`):
  - `data class BlockQuads(x: Int, y: Int, z: Int, r: Float, g: Float, b: Float, quads: List<BakedQuad>)`
  - `data class ChunkBlocks(chunkX: Int, chunkZ: Int, byLayer: Map<RenderLayer, List<BlockQuads>>, blockCount: Int)`
  - `fun ensureSnapshot(snap: ChunkSnapshot)`, `fun chunkRecAt(cx, cz): ChunkSnapshot.ChunkRec?`, `fun bakedChunk(cx, cz): ChunkBlocks?`, `fun bakeChunk(snap, cx, cz): ChunkBlocks?`, `fun free()` — same signatures as today (so `ChunkWorldRenderer.render`'s control flow is unchanged; only what it does per chunk changes).
- Removed (only used inside these two files): `ChunkMesher.SurfBlock`, `ChunkBlocks.blocks: List<SurfBlock>`, the `brm`/`BlockRenderManager` parameter threading in `ChunkWorldRenderer` (the renderer no longer calls `renderBlockAsEntity`; baking resolves the model itself).

- [ ] **Step 1: Rewrite `ChunkMesher.kt`**

Replace the **entire contents** of `src/main/kotlin/dev/iustitia/render/ChunkMesher.kt` with:

```kotlin
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
```

- [ ] **Step 2: Rewrite the render path in `ChunkWorldRenderer.kt`**

The control flow of `render` (the nearest-first ring walk + render-distance cull + per-frame lazy-bake budget) is **unchanged** — only `processChunk` and `renderChunkBlocks` change (they no longer take/pass `brm`, and `renderChunkBlocks` emits precomputed quads instead of calling `renderBlockAsEntity`). The KDoc's perf-section paragraph about `renderBlockAsEntity` becomes stale and is updated.

Replace the **whole file** `src/main/kotlin/dev/iustitia/render/ChunkWorldRenderer.kt` with:

```kotlin
package dev.iustitia.render

import dev.iustitia.config.ConfigManager
import dev.iustitia.replay.ReplayState
import net.minecraft.client.MinecraftClient
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
```

- [ ] **Step 3: Build-verify**

Run: `./gradlew.bat compileKotlin`
Expected: `BUILD SUCCESSFUL`.

If it fails, the likely causes (in order of probability):
1. A yarn-name typo. Confirm against the named jar with `javap` (path below) — the key symbols are `BlockRenderManager.getModel(BlockState): BlockStateModel`, `BlockStateModel.getParts(Random): List<BlockModelPart>`, `BlockModelPart.getQuads(Direction): List<BakedQuad>`, `Block.shouldDrawSide(BlockState, BlockState, Direction): boolean` (static), `BlockRenderLayers.getEntityBlockLayer(BlockState): RenderLayer`, `VertexConsumer.quad(MatrixStack.Entry, BakedQuad, float,float,float,float, int,int)`, `MinecraftClient.getBlockColors()`, `BlockColors.getColor(BlockState, BlockRenderView, BlockPos, int): int`, `BlockState.getRenderType(): BlockRenderType`, `Direction.getOffsetX/Y/Z()`.
   - Named jar: `C:\Users\deniz\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged\1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\minecraft-merged-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar`
   - Example: `unzip -p <jar> net/minecraft/block/Block.class > /tmp/blk.class && javap -p /tmp/blk.class | grep shouldDrawSide`
2. `Direction.offsetX` not resolving as a Kotlin property — if so, use `d.getOffsetX()` (etc.) instead. (`d.offsetX` is correct for yarn `getOffsetX()`, but verify.)
3. `RenderLayers.solid()` vs `BlockRenderLayers` — both exist; `RenderLayers.solid()` is the fail-open default and is imported from `net.minecraft.client.render.RenderLayers`. Confirm the import.

- [ ] **Step 4: Runtime-verify (live client)**

Launch the client with the built mod. Make a max-radius chunk clip (in config set `clipChunkRadius` to e.g. 8, `clipChunkWorld` on; `/ius clip 10 bigclip` while standing in a built area with torches / fences / glass / stairs so non-cube coverage is testable), then `/ius playclip bigclip`:
1. **FPS**: fly around with freecam (WASD). FPS should no longer collapse (was ~40 on a max-radius clip; expect a large recovery — roughly back toward the un-clip framerate). The world should stream in from the camera outward over ~1 s (no single load spike).
2. **No holes in non-cube blocks**: torches, fences, walls, plants, stairs render their full model (not just one face). If any non-cube block shows a hole / missing face, the `getQuads(null)` general-quads path is the place to recheck.
3. **No buried-face overdraw**: a solid stone wall should render only the exposed face (visually identical to before, but cheaper). Confirm by FPS, not by eye.
4. **Clean shell at the captured-region edge**: at the boundary of the captured radius, the world should end cleanly (faces draw at the edge — region-edge neighbours resolve to air → `shouldDrawSide` true → drawn). No floating internal faces, no gap ring.
5. **Tint**: grass blocks read green (not white) — confirms `BlockColors.getColor` tint is applied.
6. **No crash on a no-chunk clip**: with `clipChunkWorld` off, save + play a clip — `ReplayState.chunks == null` so `ChunkWorldRenderer.render` returns immediately; the ghosts + live world render as before (Task 1's auto-freecam still applies — you freecam through the live world).
7. **Stop**: `/ius playclip off` → the chunk world disappears, live world re-renders, camera reverts to the live player (no leak of the clip world, no stuck freecam).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/iustitia/render/ChunkMesher.kt src/main/kotlin/dev/iustitia/render/ChunkWorldRenderer.kt
git commit -F - <<'EOF'
perf(playclip): per-face-culled precomputed-quad chunk render

Replace the per-frame BlockRenderManager.renderBlockAsEntity-per-surface-block
path (which emitted every block's full 6-face model every frame, including
faces buried against opaque neighbours, and re-resolved the model each frame
— the ~380->40 FPS collapse on max-radius clips) with a bake-once path:

ChunkMesher now bakes each chunk into per-RenderLayer lists of BlockQuads
using vanilla's own face-cull rule — per-direction part.getQuads(d) gated by
Block.shouldDrawSide (so buried faces are dropped at bake time) plus
part.getQuads(null) general quads always emitted (so non-cube blocks like
torches/fences/plants keep their full model and don't get holes). Tint via
BlockColors.getColor + layer via BlockRenderLayers.getEntityBlockLayer, both
cached per block-name. ChunkWorldRenderer emits the precomputed quads each
frame via VertexConsumer.quad with one push/translate/pop per block.

~6x fewer quads + no per-frame model dispatch. Keeps fullbright lighting, the
AFTER_ENTITIES Immediate vcp (Iris/Sodium-safe), the lazy progressive bake +
render-distance cull + per-frame block budget, and the fail-open posture.
API surface javap-verified against the 1.21.11 named jar. No new mixins,
config, or codec change.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
```

---

## Self-Review (completed)

**Spec coverage:** §1 (auto-freecam) → Task 1. §2.1 bake (getModel/getParts, per-direction shouldDrawSide, getQuads(null), tint, layer, INVISIBLE skip, block-level shell skip, per-name caches) → Task 2 Step 1. §2.2 render (per-layer buffer, per-block push/translate, vc.quad) → Task 2 Step 2. §3 components (ChunkMesher data classes + caches, ChunkWorldRenderer render path, ClipPlayback one-liner) → Tasks 1–2. §4 error handling (fail-open bake/render, raw-snapshot cull) → encoded in both files' try/catch. §4 API verification → Task 2 Step 3 + the javap path. §4 runtime-verify → Step 3 (Task 1) + Step 4 (Task 2). No gaps.

**Placeholder scan:** no TBD/TODO/“add error handling”/“similar to Task N” — every code step contains the full code.

**Type consistency:** `ChunkMesher.BlockQuads(x,y,z,r,g,b,quads)` + `ChunkMesher.ChunkBlocks(chunkX,chunkZ,byLayer,blockCount)` are produced in Task 2 Step 1 and consumed identically in Task 2 Step 2 (`cb.byLayer`, `bq.x/y/z/r/g/b/quads`, `newly.blockCount`). `ensureSnapshot`/`chunkRecAt`/`bakedChunk`/`bakeChunk`/`free` signatures unchanged, so `ChunkWorldRenderer.render`'s control flow is untouched. Task 1's `ReplayState.setCameraMode(ReplayState.CameraMode.FREECAM)` matches the existing `ReplayState` API. No cross-task name drift.
# Design: playclip auto-freecam + per-face-culled chunk render

**Date:** 2026-07-07
**Status:** Approved (brainstorming complete), pending implementation plan
**Scope:** `/ius playclip` behavior + the chunk-world render path used only by chunk-bearing playclips

## Goal

Two changes to `/ius playclip`:

1. **Auto-freecam.** `/ius playclip <name>` instantly puts the user in FREECAM (the detached noclip camera), instead of the plain FREE camera mode. When the clip ends — naturally (playhead reaches the end), via `/ius playclip off`, or any other `ReplayState.stop()` path — the camera automatically reverts to the player's live view. This applies to **every** playclip, including clips with no captured chunk world (those freecam through the *live* world with ghosts overlaid). `/ius replay` (the live instant-replay) is unchanged.

2. **Perf: per-face-culled, precomputed-quad chunk render.** Replace the per-frame `BlockRenderManager.renderBlockAsEntity`-per-surface-block loop (which emits every block's full 6-face model every frame, including faces buried against opaque neighbours) with a bake-once, emit-precomputed-quads path that mirrors vanilla's own face-cull rule. This kills the ~6× buried-face overdraw and the per-frame model resolution that collapses FPS (~380→40) on max-radius clips.

## Background — current state (verified in tree)

- `ClipPlayback.start` (`replay/ClipPlayback.kt`) is the single playclip entry point shared by `/ius playclip` and the clip-manager screen's Play button. It calls `ReplayState.start(window, focus, speed, hideLive, relocate = true)`. `/ius replay` calls `ReplayState.start(..., relocate = false)` directly — it does **not** go through `ClipPlayback`.
- `ReplayState.start` sets `cameraMode = CameraMode.FREE`. FREECAM is only entered via `setCameraMode(FREECAM)` → `enterFreecam()` (seeds pose from the player's eye, 2-block backoff, flips `freecamActive`). `stop()` already calls `exitFreecam()` (flips `freecamActive=false`) + `restorePerspective()`, so the camera reverts to the live-player view the next frame. `tickFreecam` is wired in `Iustitia.kt:136` (onClientTick); `/ius playclip off` and natural end route through `ReplayState.stop`.
- `ChunkMesher.bakeChunk` builds a per-chunk "surface shell" — non-air blocks with ≥1 non-opaque neighbour (block-level cull only; no per-face cull). Output: `List<SurfBlock(state, x, y, z)>`.
- `ChunkWorldRenderer.render` walks in-range chunks nearest-first (chebyshev rings), render-distance-culled (`clipChunkRenderDistance`, 4..12), with a per-frame lazy-bake block budget (`LAZY_BAKE_BLOCK_BUDGET`). For each baked chunk, `renderChunkBlocks` does per surface block: `matrices.push()/translate(x,y,z)` → `brm.renderBlockAsEntity(state, matrices, vcp, FULL_LIGHT, DEFAULT_UV)` → `pop()`. `renderBlockAsEntity` emits the **entire** `BlockStateModel` (all faces) — the overdraw source.
- The live world's terrain is suppressed during a chunk-bearing playclip by `SectionRenderStateMixin` (cancels `renderSection` when `active && chunks != null`). Entities, sky, fog, and the `AFTER_ENTITIES` Fabric hook (where `ReplayRenderer` draws ghosts + the chunk world) keep running.

## 1. Behavior — auto-freecam on `/ius playclip`, auto-revert on end

**Change (one line, playclip-only):** in `ClipPlayback.start`, after `ReplayState.start(...)` returns true, call `ReplayState.setCameraMode(CameraMode.FREECAM)`.

- **Always FREECAM for playclip** — including clips with no chunk world (`clipChunkWorld` off or pre-v6 `.iusclip`). A no-chunk playclip freecams through the *live* world (live terrain is not suppressed when `chunks == null`) with the buffered ghosts overlaid. This is the approved behaviour.
- **`/ius replay` unchanged** — it does not go through `ClipPlayback`, so it keeps its current FREE default. Auto-freecam is genuinely playclip-scoped.
- **Revert already works** — `ReplayState.stop()` (natural end, `/ius playclip off`, `/ius replay off`, world change) already calls `exitFreecam()` + `restorePerspective()`. The next frame's `CameraMixin` FREECAM branch returns false → vanilla re-derives the live-player view. No change required on the revert side.
- **Clip-manager screen Play** uses `ClipPlayback.start`, so it auto-freecams too — consistent with the chat-command path.

No new mixins, no config flags, no codec/format change.

## 2. Perf — per-face cull + precomputed quads

### 2.1 Bake (lazy/on-demand per chunk, in `ChunkMesher`)

Keeps the existing lazy progressive bake + render-distance cull + per-frame block budget. For each non-air block with ≥1 non-opaque neighbour (surface shell — keep the existing block-level skip):

- `model = brm.getModel(state)` → `BlockStateModel`; `parts = model.getParts(rand)` — resolved **once per block-name**, cached.
- `tint = mc.blockColors.getColor(state, null, null, 0)` — cached per block-name (same tint call `renderBlockAsEntity` makes; non-biome default for grass etc.).
- `layer = BlockRenderLayers.getEntityBlockLayer(state)` — cached per block-name (same routing as `renderBlockAsEntity`).
- Skip `BlockRenderType.INVISIBLE` blocks (fluids) — matches current `renderBlockAsEntity` behaviour.
- For each `BlockModelPart`, replicate vanilla `BlockModelRenderer.renderFlat`'s cull iteration **exactly**:
  - For each `Direction d` in the 6 directions: `quads = part.getQuads(d)`; if non-empty, include iff `Block.shouldDrawSide(thisState, neighborState(d), d)` is true. `neighborState(d)` is resolved from the **raw snapshot** via `ChunkSnapshot.nameAt(pos + d)` → `stateFor` (region-edge / air → air state → `shouldDrawSide` returns true → the face draws, closing the shell at the captured-region edge, as today).
  - `generalQuads = part.getQuads(null)`; if non-empty, **always include** (the non-culled quads — torches, fences, plants, and any model detail without a cull face). This is the rule that prevents holes in non-cube blocks.
- Store per block: `BlockQuads(x, y, z, tintR, tintG, tintB, layer, List<BakedQuad>)`. Per chunk, group by `RenderLayer` → `Map<RenderLayer, List<BlockQuads>>` so render fetches each layer's buffer once.

`Block.shouldDrawSide` (vanilla-correct: handles opaque, transparent, and same-block rules — e.g. glass-vs-glass, leaves-vs-leaves) replaces the current crude `isOpaqueAt` approximation **for the per-face decision**. `isOpaqueAt` is retained only for the block-level surface-shell skip (unchanged).

### 2.2 Render (per frame, `ChunkWorldRenderer.render`)

- Shared origin translate (`-camPos + relocOffset`) applied once by `ReplayRenderer.drawGhosts` — unchanged.
- For each in-range baked chunk (render-distance cull + nearest-first ring walk unchanged):
  - For each `(layer, blockQuadsList)` in the chunk's map: `vc = vcp.getBuffer(layer)` once; for each `bq` in the list: `matrices.push()`, `matrices.translate(bq.x, bq.y, bq.z)`, for each `quad` in `bq.quads`: `vc.quad(matrices.peek(), quad, bq.r, bq.g, bq.b, 1f, FULL_LIGHT, OverlayTexture.DEFAULT_UV)`, `matrices.pop()`.

### 2.3 Wins

- **~6× fewer quads** — buried faces never emitted (the root cause: `renderBlockAsEntity` emitted every face).
- **No per-frame model resolution** — `getModel`/`getParts` happen once at bake, not for ~30k blocks every frame.
- **Correct, no holes** — uses vanilla `Block.shouldDrawSide` + the `getQuads(null)` general-quads path, so non-cube blocks render correctly. Strictly better than the current `isOpaqueAt`-per-face approximation would be.
- **Keeps fullbright flat lighting** (as today — no regression; real AO/skylight stays a documented follow-up).
- **Iris/Sodium coexistence unchanged** — still routes through the `AFTER_ENTITIES` Immediate `VertexConsumerProvider` that Iris already intercepts; does not touch the blaze4d GPU pipeline (`GpuBuffer`/`VertexBufferManager`). A full GPU static-mesh bake remains the documented, separately-estimated follow-up if this win is insufficient.

## 3. Components / data flow

- **`ChunkMesher`** (`render/ChunkMesher.kt`):
  - Replace `SurfBlock(state, x, y, z)` with `BlockQuads(x, y, z, r, g, b, layer, List<BakedQuad>)`.
  - `ChunkBlocks` holds `Map<RenderLayer, List<BlockQuads>>` instead of `List<SurfBlock>`.
  - Rework `bakeChunkBlocks` to the `getModel` → `getParts` → per-direction `getQuads(d)` (gated by `Block.shouldDrawSide`) + `getQuads(null)` (always) path. Skip `INVISIBLE` render types.
  - Keep `stateFor` (per-name `BlockState` cache); add per-name caches for tint (Float triple / Int ARGB) and `RenderLayer`.
  - `ensureSnapshot` / `free` / chunk-coord keying / `chunkRecAt` / `bakedChunk` unchanged. A new snapshot still clears all caches.
- **`ChunkWorldRenderer`** (`render/ChunkWorldRenderer.kt`):
  - `renderChunkBlocks` emits precomputed quads via `VertexConsumer.quad` (per-layer buffer fetch, per-block push/translate, per-quad `vc.quad`) instead of `renderBlockAsEntity`.
  - `render`'s ring-walk / render-distance cull / per-frame block budget unchanged. The budget still counts baked surface blocks (a chunk's bake cost still scales with its surface-block count; the per-quad cost is now bounded by exposed faces only).
- **`ClipPlayback`** (`replay/ClipPlayback.kt`): +1 line — `ReplayState.setCameraMode(ReplayState.CameraMode.FREECAM)` after a successful `start`, inside the `try` (fail-open: a throw leaves the camera in FREE).
- **No new mixins, no config changes, no codec/format bump.** `clipChunkWorld` / `clipChunkRadius` / `clipChunkRenderDistance` / `LAZY_BAKE_BLOCK_BUDGET` all unchanged.

## 4. Error handling + testing

- **Fail-open throughout** (project posture): a bake error skips that block (or chunk); a render error drops that frame. Per-face cull reads the raw snapshot via `nameAt`, so culling is correct regardless of which chunks have baked yet (bake order has no effect on correctness — same property as today).
- **API surface already javap-verified** against the 1.21.11 named jar (`minecraft-merged-...-yarn.1_21_11...jar`):
  - `BlockRenderManager.getModel(BlockState): BlockStateModel`
  - `BlockStateModel.getParts(Random): List<BlockModelPart>` (and `addParts`)
  - `BlockModelPart.getQuads(Direction): List<BakedQuad>` — verified callable with `null` (vanilla `renderFlat` calls `getQuads(aconst_null)` for general quads)
  - `Block.shouldDrawSide(BlockState, BlockState, Direction): boolean` (static)
  - `BlockRenderLayers.getEntityBlockLayer(BlockState): RenderLayer`
  - `VertexConsumer.quad(MatrixStack.Entry, BakedQuad, float r, float g, float b, float a, int light, int overlay)` (default method)
  - `BlockColors.getColor(BlockState, BlockRenderView, BlockPos, int): int`
  - Confirmed the old `VertexBuffer`/class_281 is gone; replaced by `GpuBuffer`/`VertexBufferManager` (the deferred GPU-bake follow-up would use these).
- **Runtime-only-verifiable** (per project convention for render-path work): the build confirms compile + the API signatures above; only a live client run confirms (a) playclip auto-enters freecam and reverts on end / `/ius playclip off`, (b) FPS is restored on a max-radius clip, (c) no holes / z-fighting on non-cube blocks (torches, fences, plants) and a clean shell at the captured-region edge.

## 5. Out of scope / documented follow-ups

- Full GPU static-mesh bake (`GpuBuffer` + `VertexBufferManager`, one upload / one draw call per chunk per frame) — the maximal-win follow-up if the per-face-cull + precompute win is insufficient for the largest clips. Real runtime risk via the blaze4d GPU pipeline; separately estimated.
- Real lighting / AO (per-face brightness via a snapshot-backed `BlockRenderView`) — currently fullbright; no regression.
- Block **properties** (recorded facing for stairs/slabs/etc.) and **block entities** (chest contents, sign text, banners) — unchanged from v1 (default state, no block-entity rendering).
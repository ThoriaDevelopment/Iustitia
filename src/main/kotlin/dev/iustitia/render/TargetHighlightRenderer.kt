package dev.iustitia.render

import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.OtherClientPlayerEntity
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.VertexRendering
import net.minecraft.util.hit.EntityHitResult
import net.minecraft.util.shape.VoxelShapes

/**
 * On-world target-highlight overlay (Phase B): draws a tier-colored wireframe box around the OTHER
 * player the local player's crosshair is on. Render-only, fail-open, depth-tested (the lines layer
 * writes depth, so the box is occluded by intervening walls — no wallhack, consistent with the
 * nametag-visibility-respecting philosophy).
 *
 * Registered on `WorldRenderEvents.AFTER_ENTITIES`, which fires on the render thread after entities
 * are drawn — the canonical place for world-space overlays, so the box composites correctly against
 * entities (and is occluded by closer geometry).
 *
 * ## Positioning
 *
 * `WorldRenderContext.matrices()` at AFTER_ENTITIES is the camera-rotation-only view matrix (vanilla
 * applies the camera TRANSLATION per-vertex via the camera-pos offset it passes to its outline
 * draw calls — confirmed by `WorldRenderer.renderTargetBlockOutline` passing `camera.getPos()` to
 * `drawBlockOutline`). So translating the matrix by `-cameraPos` puts world-coord geometry on
 * screen, exactly as entity renderers position themselves via `(x - cameraX, …)`. We then draw the
 * entity's world-coord [net.minecraft.entity.Entity.getBoundingBox] with no extra offset.
 *
 * ## Color
 *
 * `VertexRendering.drawOutline` passes a single `int` to `VertexConsumer.color(int)` (ARGB), so the
 * colors below are full-alpha ARGB (0xFFRRGGBB) — robust whether the helper packs its own alpha or
 * treats the int as already-ARGB. Tier-colored: red/yellow always drawn; green only when the user
 * opted into green cues (`nametagGreenEnabled`), so clean players aren't boxed by default.
 *
 * Runtime-only-verifiable: a build cannot confirm the box lands on the entity (depends on the
 * matrices being rotation-only, which the vanilla precedent establishes but cannot be build-proven).
 * If the box is offset/wrong-tinted, the translate offset or color packing is a one-line fix.
 */
object TargetHighlightRenderer {

    fun register() {
        try {
            WorldRenderEvents.AFTER_ENTITIES.register(WorldRenderEvents.AfterEntities { ctx ->
                try {
                    if (!ConfigManager.config.targetHighlight) return@AfterEntities
                    val mc = MinecraftClient.getInstance()
                    val hit = mc.crosshairTarget
                    if (hit !is EntityHitResult) return@AfterEntities
                    val entity = hit.entity
                    if (entity !is OtherClientPlayerEntity) return@AfterEntities
                    val self = mc.player
                    if (self != null && entity.uuid == self.uuid) return@AfterEntities

                    val tier = FlagHistory.tierFor(entity.uuid)
                    if (tier == FlagHistory.Tier.GREEN && !ConfigManager.config.nametagGreenEnabled) return@AfterEntities
                    val color = when (tier) {
                        FlagHistory.Tier.GREEN -> GREEN
                        FlagHistory.Tier.YELLOW -> YELLOW
                        FlagHistory.Tier.RED -> RED
                    }

                    val camPos = ctx.gameRenderer().camera.getCameraPos()
                    val matrices = ctx.matrices()
                    val lines = ctx.consumers().getBuffer(RenderLayers.lines())
                    matrices.push()
                    // Rotation-only view matrix → translate by -cameraPos to draw world-coord geometry.
                    matrices.translate(-camPos.x, -camPos.y, -camPos.z)
                    VertexRendering.drawOutline(
                        matrices, lines,
                        VoxelShapes.cuboid(entity.getBoundingBox()),
                        0.0, 0.0, 0.0, color, 1.0f,
                    )
                    matrices.pop()
                } catch (_: Throwable) {
                    // Fail-open: a render error never crashes the client; the box just doesn't draw.
                }
            })
        } catch (_: Throwable) {}
    }
}

// Full-alpha ARGB (0xFFRRGGBB) — see class doc on color packing.
private const val RED = 0xFFFF3030.toInt()
private const val YELLOW = 0xFFFFFF30.toInt()
private const val GREEN = 0xFF30FF30.toInt()
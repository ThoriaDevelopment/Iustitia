package dev.iustitia.render

import net.minecraft.block.BlockState
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.model.Model
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.BlockRenderLayers
import net.minecraft.client.render.LightmapTextureManager
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.TexturedRenderLayers
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.block.BlockModelRenderer
import net.minecraft.client.render.block.MovingBlockRenderState
import net.minecraft.client.render.command.ModelCommandRenderer
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.command.RenderCommandQueue
import net.minecraft.client.render.entity.state.EntityRenderState
import net.minecraft.client.render.item.ItemRenderState
import net.minecraft.client.render.item.ItemRenderer
import net.minecraft.client.render.model.BakedQuad
import net.minecraft.client.render.model.BlockStateModel
import net.minecraft.client.render.model.ModelBaker
import net.minecraft.client.render.state.CameraRenderState
import net.minecraft.client.texture.Sprite
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.item.ItemDisplayContext
import net.minecraft.text.OrderedText
import net.minecraft.text.Text
import net.minecraft.util.Colors
import net.minecraft.util.Identifier
import net.minecraft.util.math.Box
import net.minecraft.util.math.ColorHelper
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraft.util.math.random.Random
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * A minimal [OrderedRenderCommandQueue] that emits model/vertex work **immediately** into a plain
 * [VertexConsumerProvider] instead of feeding vanilla's deferred multi-pass entity queue.
 *
 * ## Why this exists
 *
 * The replay/clip ghost renderer draws during `WorldRenderEvents.AFTER_ENTITIES`, where the entity-pass
 * command queue is the batched one — it can't be driven for a fabricated entity that doesn't exist in
 * the world. Player ghosts sidestep this by rendering their model straight onto the overlay's
 * [VertexConsumerProvider] (see [ReplayRenderer.drawModel]). Non-living entity ghosts (boats, minecarts)
 * can't: their `EntityRenderer.render` callback is handed an [OrderedRenderCommandQueue] and only knows
 * how to submit into it. This class bridges the two — it accepts the submissions and writes each one
 * into the [VertexConsumerProvider] buffer for that render layer right away (the same layer the vanilla
 * entity pass would have filled).
 *
 * ## What each submit does
 *
 * Each submission replays the corresponding drain step from vanilla's command renderers
 * (`LabelCommandRenderer`, `FireCommandRenderer`, `LeashCommandRenderer`, …) inline instead of
 * queueing it — so nametags, fire, leashes, item frames, shadows, text and model parts submitted by a
 * non-living ghost's renderer actually draw. The one genuine exception is
 * [submitCustom]([OrderedRenderCommandQueue.LayeredCustom]): that path renders through its own GPU
 * `RenderPass` against the particles framebuffers (`LayeredCustomCommandRenderer`), not through a
 * [VertexConsumerProvider] — it cannot be bridged and no entity renderer submits into it, so it stays
 * a documented no-op.
 *
 * Ordering/layering is therefore best-effort (each submission flushes into its layer's buffer in call
 * order, no batching/pass split), which is fine for a replay overlay drawn on top of the captured world.
 * Everything is fail-open: a submission error is swallowed so one bad entity never kills the frame.
 *
 * Ported from SnapClip's `dev.snapclip.render.ImmediateRenderQueue` (empty submits filled in from
 * the vanilla drain bodies).
 */
class ImmediateRenderQueue(private var vcp: VertexConsumerProvider) : OrderedRenderCommandQueue {

    /** Re-bind the frame's [VertexConsumerProvider] so one instance can be reused across frames
     *  (render thread only) instead of allocating per ghost per frame. */
    fun bind(vcp: VertexConsumerProvider) {
        this.vcp = vcp
    }

    private val client: MinecraftClient? get() = try { MinecraftClient.getInstance() } catch (_: Throwable) { null }

    override fun <S> submitModel(
        model: Model<in S>,
        state: S,
        matrices: MatrixStack,
        layer: RenderLayer,
        light: Int,
        overlay: Int,
        tintedColor: Int,
        sprite: Sprite?,
        outlineColor: Int,
        crumbling: ModelCommandRenderer.CrumblingOverlayCommand?,
    ) {
        try {
            // Vanilla drain (ModelCommandRenderer): setAngles, sprite-aware consumer, tintedColor.
            // Outline color + crumbling are not drawn — a ghost overlay has no outline pass and
            // fabricated entities never mine blocks.
            model.setAngles(state)
            val base = vcp.getBuffer(layer)
            val consumer = sprite?.getTextureSpecificVertexConsumer(base) ?: base
            model.render(matrices, consumer, light, overlay, tintedColor)
        } catch (_: Throwable) {
        }
    }

    override fun submitCustom(
        matrices: MatrixStack,
        layer: RenderLayer,
        custom: OrderedRenderCommandQueue.Custom,
    ) {
        try {
            custom.render(matrices.peek(), vcp.getBuffer(layer))
        } catch (_: Throwable) {
        }
    }

    override fun submitShadowPieces(matrices: MatrixStack, shadowRadius: Float, shadowPieces: List<EntityRenderState.ShadowPiece>) {
        // Vanilla drain (ShadowPiecesCommandRenderer): one flat quad per piece on the shared shadow layer.
        try {
            val consumer = vcp.getBuffer(SHADOW_LAYER)
            val matrix = matrices.peek().positionMatrix
            for (piece in shadowPieces) {
                val box: Box = piece.shapeBelow.boundingBox
                val minX = piece.relativeX + box.minX.toFloat()
                val maxX = piece.relativeX + box.maxX.toFloat()
                val y = piece.relativeY + box.minY.toFloat()
                val minZ = piece.relativeZ + box.minZ.toFloat()
                val maxZ = piece.relativeZ + box.maxZ.toFloat()
                val r = shadowRadius
                val u0 = -minX / 2.0f / r + 0.5f
                val u1 = -maxX / 2.0f / r + 0.5f
                val v0 = -minZ / 2.0f / r + 0.5f
                val v1 = -maxZ / 2.0f / r + 0.5f
                val color = ColorHelper.getWhite(piece.alpha)
                shadowVertex(matrix, consumer, color, minX, y, minZ, u0, v0)
                shadowVertex(matrix, consumer, color, minX, y, maxZ, u0, v1)
                shadowVertex(matrix, consumer, color, maxX, y, maxZ, u1, v1)
                shadowVertex(matrix, consumer, color, maxX, y, minZ, u1, v0)
            }
        } catch (_: Throwable) {
        }
    }

    private fun shadowVertex(matrix: Matrix4f, consumer: VertexConsumer, color: Int, x: Float, y: Float, z: Float, u: Float, v: Float) {
        val pos = matrix.transformPosition(x, y, z, Vector3f())
        consumer.vertex(pos.x, pos.y, pos.z, color, u, v, OverlayTexture.DEFAULT_UV, LightmapTextureManager.MAX_LIGHT_COORDINATE, 0.0f, 1.0f, 0.0f)
    }

    override fun submitLabel(
        matrices: MatrixStack, nameLabelPos: Vec3d?, y: Int, label: Text, notSneaking: Boolean,
        light: Int, squaredDistanceToCamera: Double, cameraState: CameraRenderState,
    ) {
        // Vanilla drain (LabelCommandRenderer.Commands.add + render): transform at submit time, then
        // a SEE_THROUGH pass (optionally) + a NORMAL pass. `squaredDistanceToCamera` only orders the
        // batched see-through pass — irrelevant in call order.
        if (nameLabelPos == null) return
        try {
            val mc = client ?: return
            val tr = mc.textRenderer
            matrices.push()
            matrices.translate(nameLabelPos.x, nameLabelPos.y + 0.5, nameLabelPos.z)
            matrices.multiply(cameraState.orientation)
            matrices.scale(0.025f, -0.025f, 0.025f)
            val x = -tr.getWidth(label) / 2.0f
            val bg = (mc.options.getTextBackgroundOpacity(0.25f) * 255.0f).toInt() shl 24
            val matrix = matrices.peek().positionMatrix
            if (notSneaking) {
                // see-through copy so the nametag is readable through the ghost's own body
                tr.draw(label, x, y.toFloat(), -2130706433, false, matrix, vcp, TextRenderer.TextLayerType.SEE_THROUGH, bg, light)
                tr.draw(label, x, y.toFloat(), -1, false, matrix, vcp, TextRenderer.TextLayerType.NORMAL, 0, LightmapTextureManager.applyEmission(light, 2))
            } else {
                tr.draw(label, x, y.toFloat(), -2130706433, false, matrix, vcp, TextRenderer.TextLayerType.NORMAL, bg, light)
            }
            matrices.pop()
        } catch (_: Throwable) {
        }
    }

    override fun submitText(
        matrices: MatrixStack, x: Float, y: Float, text: OrderedText, dropShadow: Boolean,
        layerType: TextRenderer.TextLayerType, light: Int, color: Int, backgroundColor: Int, outlineColor: Int,
    ) {
        // Vanilla drain (TextCommandRenderer).
        try {
            val tr = client?.textRenderer ?: return
            if (outlineColor == 0) {
                tr.draw(text, x, y, color, dropShadow, matrices.peek().positionMatrix, vcp, layerType, backgroundColor, light)
            } else {
                tr.drawWithOutline(text, x, y, color, outlineColor, matrices.peek().positionMatrix, vcp, light)
            }
        } catch (_: Throwable) {
        }
    }

    override fun submitFire(matrices: MatrixStack, state: EntityRenderState, rotation: Quaternionf) {
        // Vanilla drain (FireCommandRenderer): stacked shrinking billboard slices on the cutout layer.
        // It MUTATES the entry (scale/rotate/translate) — work on a copy, not the caller's live stack.
        try {
            val mc = client ?: return
            val atlas = mc.atlasManager
            val sprite0 = atlas.getSprite(ModelBaker.FIRE_0)
            val sprite1 = atlas.getSprite(ModelBaker.FIRE_1)
            val entry = matrices.peek().copy()
            val f = state.width * 1.4f
            entry.scale(f, f, f)
            var halfWidth = 0.5f
            var yOffset = 0.0f
            var height = state.height / f
            var zOff = 0.0f
            var slice = 0
            entry.rotate(rotation)
            entry.translate(0.0f, 0.0f, 0.3f - height.toInt() * 0.02f)
            val consumer = vcp.getBuffer(TexturedRenderLayers.getEntityCutout())
            while (height > 0.0f) {
                val s = if (slice % 2 == 0) sprite0 else sprite1
                var uMin = s.minU
                val vMin = s.minV
                var uMax = s.maxU
                val vMax = s.maxV
                if (slice / 2 % 2 == 0) {
                    val t = uMax; uMax = uMin; uMin = t
                }
                fireVertex(entry, consumer, -halfWidth, -yOffset, zOff, uMax, vMax)
                fireVertex(entry, consumer, halfWidth, -yOffset, zOff, uMin, vMax)
                fireVertex(entry, consumer, halfWidth, 1.4f - yOffset, zOff, uMin, vMin)
                fireVertex(entry, consumer, -halfWidth, 1.4f - yOffset, zOff, uMax, vMin)
                height -= 0.45f
                yOffset -= 0.45f
                halfWidth *= 0.9f
                zOff -= 0.03f
                slice++
            }
        } catch (_: Throwable) {
        }
    }

    private fun fireVertex(entry: MatrixStack.Entry, consumer: VertexConsumer, x: Float, y: Float, z: Float, u: Float, v: Float) {
        consumer.vertex(entry, x, y, z)
            .color(Colors.WHITE)
            .texture(u, v)
            .overlay(0, 10)
            .light(LightmapTextureManager.MAX_BLOCK_LIGHT_COORDINATE)
            .normal(entry, 0.0f, 1.0f, 0.0f)
    }

    override fun submitLeash(matrices: MatrixStack, leashData: EntityRenderState.LeashData) {
        // Vanilla drain (LeashCommandRenderer): 24-segment two-sided ribbon with a slack bezier.
        try {
            val dx = (leashData.endPos.x - leashData.startPos.x).toFloat()
            val dy = (leashData.endPos.y - leashData.startPos.y).toFloat()
            val dz = (leashData.endPos.z - leashData.startPos.z).toFloat()
            val invSqrt = MathHelper.inverseSqrt(dx * dx + dz * dz) * 0.05f / 2.0f
            val perpX = dz * invSqrt
            val perpZ = dx * invSqrt
            val matrix = Matrix4f(matrices.peek().positionMatrix)
            matrix.translate(leashData.offset.x.toFloat(), leashData.offset.y.toFloat(), leashData.offset.z.toFloat())
            val consumer = vcp.getBuffer(RenderLayers.leash())
            for (i in 0..LEASH_SEGMENTS) leashVertex(consumer, matrix, dx, dy, dz, LEASH_WIDTH, perpX, perpZ, i, false, leashData)
            for (i in LEASH_SEGMENTS downTo 0) leashVertex(consumer, matrix, dx, dy, dz, 0.0f, perpX, perpZ, i, true, leashData)
        } catch (_: Throwable) {
        }
    }

    private fun leashVertex(
        consumer: VertexConsumer, matrix: Matrix4f, dx: Float, dy: Float, dz: Float,
        yOff: Float, sideOff: Float, perpOff: Float, segment: Int, backside: Boolean,
        data: EntityRenderState.LeashData,
    ) {
        val f = segment / LEASH_SEGMENTS.toFloat()
        val blockLight = MathHelper.lerp(f, data.leashedEntityBlockLight.toFloat(), data.leashHolderBlockLight.toFloat()).toInt()
        val skyLight = MathHelper.lerp(f, data.leashedEntitySkyLight.toFloat(), data.leashHolderSkyLight.toFloat()).toInt()
        val light = LightmapTextureManager.pack(blockLight, skyLight)
        val shade = if (segment % 2 == (if (backside) 1 else 0)) 0.7f else 1.0f
        val r = 0.5f * shade
        val g = 0.4f * shade
        val b = 0.3f * shade
        val x = dx * f
        val y = if (data.slack) {
            if (dy > 0.0f) dy * f * f else dy - dy * (1.0f - f) * (1.0f - f)
        } else {
            dy * f
        }
        val z = dz * f
        consumer.vertex(matrix, x - sideOff, y + yOff, z + perpOff).color(r, g, b, 1.0f).light(light)
        consumer.vertex(matrix, x + sideOff, y + 0.05f - yOff, z - perpOff).color(r, g, b, 1.0f).light(light)
    }

    override fun submitModelPart(
        part: ModelPart, matrices: MatrixStack, renderLayer: RenderLayer, light: Int, overlay: Int,
        sprite: Sprite?, sheeted: Boolean, hasGlint: Boolean, tintedColor: Int,
        crumblingOverlay: ModelCommandRenderer.CrumblingOverlayCommand?, i: Int,
    ) {
        // Vanilla drain (ModelPartCommandRenderer): glint/sprite-aware consumer, then part.render.
        // `i` is batch-sort bookkeeping in vanilla and unused at drain time; crumbling is ghost-irrelevant.
        try {
            val base = vcp.getBuffer(renderLayer)
            val consumer = when {
                sprite != null && hasGlint ->
                    sprite.getTextureSpecificVertexConsumer(ItemRenderer.getItemGlintConsumer(vcp, renderLayer, sheeted, true))
                sprite != null -> sprite.getTextureSpecificVertexConsumer(base)
                hasGlint -> ItemRenderer.getItemGlintConsumer(vcp, renderLayer, sheeted, true)
                else -> base
            }
            part.render(matrices, consumer, light, overlay, tintedColor)
        } catch (_: Throwable) {
        }
    }

    override fun submitBlock(matrices: MatrixStack, state: BlockState, light: Int, overlay: Int, outlineColor: Int) {
        // Vanilla drain (FallingBlockCommandRenderer): block-entity model via BlockRenderManager.
        try {
            client?.blockRenderManager?.renderBlockAsEntity(state, matrices, vcp, light, overlay)
        } catch (_: Throwable) {
        }
    }

    override fun submitMovingBlock(matrices: MatrixStack, state: MovingBlockRenderState) {
        // Vanilla drain (FallingBlockCommandRenderer): the moving block's model parts on the moving-block layer.
        try {
            val mc = client ?: return
            val brm = mc.blockRenderManager
            val parts = brm.getModel(state.blockState)
                .getParts(Random.create(state.blockState.getRenderingSeed(state.fallingBlockPos)))
            brm.modelRenderer.render(
                state, parts, state.blockState, state.entityBlockPos, matrices,
                vcp.getBuffer(BlockRenderLayers.getMovingBlockLayer(state.blockState)),
                false, OverlayTexture.DEFAULT_UV,
            )
        } catch (_: Throwable) {
        }
    }

    override fun submitBlockStateModel(
        matrices: MatrixStack, layer: RenderLayer, model: BlockStateModel, r: Float, g: Float, b: Float,
        light: Int, overlay: Int, outlineColor: Int,
    ) {
        // Vanilla drain (FallingBlockCommandRenderer): flat BlockStateModel render.
        try {
            BlockModelRenderer.render(matrices.peek(), vcp.getBuffer(layer), model, r, g, b, light, overlay)
        } catch (_: Throwable) {
        }
    }

    override fun submitItem(
        matrices: MatrixStack, displayContext: ItemDisplayContext, light: Int, overlay: Int,
        outlineColors: Int, tintLayers: IntArray, quads: List<BakedQuad>, renderLayer: RenderLayer,
        glintType: ItemRenderState.Glint,
    ) {
        // Vanilla drain (ItemCommandRenderer): quads straight through ItemRenderer.renderItem. The
        // outline second pass needs an OutlineVertexConsumerProvider the overlay doesn't have — skipped.
        try {
            ItemRenderer.renderItem(displayContext, matrices, vcp, light, overlay, tintLayers, quads, renderLayer, glintType)
        } catch (_: Throwable) {
        }
    }

    override fun submitCustom(custom: OrderedRenderCommandQueue.LayeredCustom) {
        // Deliberate no-op: the LayeredCustom path renders through its own GPU RenderPass against the
        // particles framebuffers (LayeredCustomCommandRenderer), not through a VertexConsumerProvider —
        // it cannot be bridged into this queue, and no entity renderer submits into it (particles only).
    }

    override fun getBatchingQueue(priority: Int): RenderCommandQueue = this

    private companion object {
        const val LEASH_SEGMENTS = 24
        const val LEASH_WIDTH = 0.05f
        val SHADOW_LAYER: RenderLayer =
            RenderLayers.entityShadow(Identifier.ofVanilla("textures/misc/shadow.png"))
    }
}
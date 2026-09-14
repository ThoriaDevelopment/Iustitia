package dev.iustitia.render

import net.minecraft.block.BlockState
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.model.Model
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.block.MovingBlockRenderState
import net.minecraft.client.render.command.ModelCommandRenderer
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.command.RenderCommandQueue
import net.minecraft.client.render.entity.state.EntityRenderState
import net.minecraft.client.render.item.ItemRenderState
import net.minecraft.client.render.model.BakedQuad
import net.minecraft.client.render.model.BlockStateModel
import net.minecraft.client.render.state.CameraRenderState
import net.minecraft.client.texture.Sprite
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.item.ItemDisplayContext
import net.minecraft.text.OrderedText
import net.minecraft.text.Text
import net.minecraft.util.math.Vec3d
import org.joml.Quaternionf

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
 * Ordering/layering is therefore best-effort (each submission flushes into its layer's buffer in call
 * order, no batching/pass split), which is fine for a replay overlay drawn on top of the captured world.
 * Everything is fail-open: a submission error is swallowed so one bad entity never kills the frame.
 *
 * Ported from SnapClip's `dev.snapclip.render.ImmediateRenderQueue`.
 */
class ImmediateRenderQueue(private val vcp: VertexConsumerProvider) : OrderedRenderCommandQueue {

    override fun <S> submitModel(
        model: Model<in S>,
        state: S,
        matrices: MatrixStack,
        layer: RenderLayer,
        light: Int,
        overlay: Int,
        outlineColor: Int,
        sprite: Sprite?,
        flags: Int,
        crumbling: ModelCommandRenderer.CrumblingOverlayCommand?,
    ) {
        try {
            model.render(matrices, vcp.getBuffer(layer), light, overlay)
        } catch (_: Throwable) {
        }
    }

    override fun <S> submitModel(
        model: Model<in S>,
        state: S,
        matrices: MatrixStack,
        layer: RenderLayer,
        light: Int,
        overlay: Int,
        outlineColor: Int,
        crumbling: ModelCommandRenderer.CrumblingOverlayCommand?,
    ) {
        submitModel(model, state, matrices, layer, light, overlay, outlineColor, null, 0, crumbling)
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

    override fun submitShadowPieces(matrices: MatrixStack, shadowRadius: Float, shadowPieces: List<EntityRenderState.ShadowPiece>) {}

    override fun submitLabel(
        matrices: MatrixStack, nameLabelPos: Vec3d?, y: Int, label: Text, notSneaking: Boolean,
        light: Int, squaredDistanceToCamera: Double, cameraState: CameraRenderState,
    ) {}

    override fun submitText(
        matrices: MatrixStack, x: Float, y: Float, text: OrderedText, shadow: Boolean,
        layerType: TextRenderer.TextLayerType, color: Int, backgroundColor: Int, light: Int, z: Int,
    ) {}

    override fun submitFire(matrices: MatrixStack, state: EntityRenderState, rotation: Quaternionf) {}

    override fun submitLeash(matrices: MatrixStack, leashData: EntityRenderState.LeashData) {}

    override fun submitModelPart(
        part: ModelPart, matrices: MatrixStack, renderLayer: RenderLayer, light: Int, overlay: Int,
        sprite: Sprite?, sheeted: Boolean, hasGlint: Boolean, tintedColor: Int,
        crumblingOverlay: ModelCommandRenderer.CrumblingOverlayCommand?, i: Int,
    ) {}

    override fun submitBlock(matrices: MatrixStack, state: BlockState, light: Int, overlay: Int, outlineColor: Int) {}

    override fun submitMovingBlock(matrices: MatrixStack, state: MovingBlockRenderState) {}

    override fun submitBlockStateModel(
        matrices: MatrixStack, layer: RenderLayer, model: BlockStateModel, r: Float, g: Float, b: Float,
        light: Int, overlay: Int, outlineColor: Int,
    ) {}

    override fun submitItem(
        matrices: MatrixStack, displayContext: ItemDisplayContext, light: Int, overlay: Int,
        outlineColors: Int, tintLayers: IntArray, quads: List<BakedQuad>, renderLayer: RenderLayer,
        glintType: ItemRenderState.Glint,
    ) {}

    override fun submitCustom(custom: OrderedRenderCommandQueue.LayeredCustom) {}

    override fun getBatchingQueue(priority: Int): RenderCommandQueue = this
}

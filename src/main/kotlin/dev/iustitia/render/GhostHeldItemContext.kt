package dev.iustitia.render

import net.minecraft.util.HeldItemContext
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World

/**
 * A [HeldItemContext] for a replay/clip ghost — supplies the world + the snap's position/yaw but NO
 * live entity ([getEntity] stays the default null). Used by
 * `ItemModelManager.clearAndUpdate` to populate a held-item `ItemRenderState` for a ghost that has
 * no live `LivingEntity` (the static `ArmedEntityRenderState.updateRenderState` path requires a
 * `LivingEntity`, so it is bypassed). The null entity is fine for almost all items; a few special
 * models (trident / spyglass / custom-model items) may call `getEntity()` and NPE — contained by the
 * per-feature try/catch in [ReplayRenderer.drawModel]. Fail-open by design.
 */
class GhostHeldItemContext(
    private val world: World?,
    private val pos: Vec3d,
    private val bodyYaw: Float,
) : HeldItemContext {
    override fun getEntityWorld(): World? = world
    override fun getEntityPos(): Vec3d = pos
    override fun getBodyYaw(): Float = bodyYaw
    // getEntity() stays the interface default (null) — a ghost has no live entity.
}
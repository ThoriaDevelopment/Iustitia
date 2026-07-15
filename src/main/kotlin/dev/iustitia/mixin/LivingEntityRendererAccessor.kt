package dev.iustitia.mixin

import net.minecraft.client.item.ItemModelManager
import net.minecraft.client.render.entity.LivingEntityRenderer
import net.minecraft.client.render.entity.feature.FeatureRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

/**
 * `@Accessor` mixin for [LivingEntityRenderer]'s two protected final fields that the ghost-equipment
 * render path needs: the `features` list (the per-renderer feature renderers — armor, held-item,
 * cape, elytra, …) and `itemModelManager` (resolves held-item `ItemRenderState`s). Vanilla exposes
 * no public accessor for either. `PlayerEntityRenderer` already constructed its
 * `ArmorFeatureRenderer` + `PlayerHeldItemFeatureRenderer` (with fully-wired deps) at its own
 * construction time — they sit in `features`; we READ the list rather than reconstruct any dep.
 *
 * `@Accessor` does not walk the superclass chain, but both fields are declared on
 * [LivingEntityRenderer] itself; a `PlayerEntityRenderer` instance IS-A `LivingEntityRenderer`, so
 * casting a `PlayerEntityRenderer` to this accessor works — the same pattern as the existing
 * [PlayerEntityRenderStateAccessor] / [EntityRenderStateAccessor] (target the declaring class, cast
 * from the concrete subclass). `defaultRequire = 1` in `iustitia.mixins.json` means a field-name
 * mismatch fails at launch (caught immediately, not at runtime); the field names `features` +
 * `itemModelResolver` are verified from the 1.21.11 named jar.
 */
@Mixin(LivingEntityRenderer::class)
interface LivingEntityRendererAccessor {
    @Accessor("features")
    fun iustitia_getFeatures(): List<FeatureRenderer<*, *>>

    @Accessor("itemModelResolver")
    fun iustitia_getItemModelManager(): ItemModelManager
}
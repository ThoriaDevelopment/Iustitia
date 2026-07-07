package dev.iustitia.mixin

import dev.iustitia.replay.ReplayState
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.Entity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Redirects mouse-look onto the FREECAM pose while a chunk-bearing `/ius playclip` runs in FREECAM
 * mode — the Iustitia port of xolt's Freecam `EntityMixin.turn` redirect (v1.4.1-alpha.1, MC 1.21.11).
 *
 * ## Why
 *
 * Vanilla routes mouse deltas to the local player via `Entity.changeLookDirection(double, double)`
 * (yarn; mojmap `Entity.turn`). With the FREECAM pose overriding the camera (pure camera-override,
 * v1.2.0 rework), the user expects the mouse to turn the **camera**, not the (now-frozen, hidden)
 * player. So when FREECAM is active and this call is on the local player, apply it to the freecam
 * pose via [ReplayState.applyFreecamLook] and cancel the player's own turn — the player's yaw/pitch
 * stays frozen (it's hidden + its walking is already suppressed by [ClientPlayerEntityMixin]), and
 * the freecam pose (which [dev.iustitia.mixin.CameraMixin] writes to the camera) tracks the mouse
 * directly. This is what makes FREECAM feel like a real detached spectator camera rather than the
 * old synthetic drive.
 *
 * ## Re-entrancy
 *
 * The redirect calls `ReplayState.applyFreecamLook`, which only mutates the pose primitives — it does
 * NOT call back into `changeLookDirection`, so there is no re-entrant mixin fire.
 *
 * ## Gate + fail-open
 *
 * Only acts when `ReplayState.active && cameraMode == FREECAM && this === mc.player`; every other call
 * (every entity, every frame) is a no-op. Whole body try/caught (project fail-open posture): a throw
 * leaves the player's turn running (worst case = the player turns a tick during a freecam, never a
 * crash). Camera-only — no packets, no world edits.
 *
 * ## Runtime-only-verifiable
 *
 * The `Entity.changeLookDirection(double, double)` target is verified against the named 1.21.11 jar
 * (`public void changeLookDirection(double, double)` on `net.minecraft.entity.Entity`; mojmap `turn`).
 * `defaultRequire: 1` means a mismatch fails launch — the build can't confirm mixin-apply, only a live
 * client run can.
 */
@Mixin(Entity::class)
class FreecamEntityMixin {

    @Inject(method = ["changeLookDirection"], at = [At("HEAD")], cancellable = true)
    // Mixin merges this class into Entity at runtime, so `this as Entity` (below) is sound at
    // runtime even though Kotlin's static analysis can't see the merge and warns it can never
    // succeed. Suppress that false positive rather than weakening the cast.
    @Suppress("CAST_NEVER_SUCCEEDS")
    private fun iustitia_redirectMouseLook(yawDelta: Double, pitchDelta: Double, ci: CallbackInfo) {
        try {
            if (!ReplayState.active || ReplayState.cameraMode != ReplayState.CameraMode.FREECAM) return
            val self = this as Entity
            if (self !== MinecraftClient.getInstance().player) return
            if (!ReplayState.freecamActive) return
            // Apply the mouse delta to the freecam pose (vanilla-scaled + pitch-clamped inside
            // applyFreecamLook) and cancel the player's own turn — the player's yaw/pitch stays frozen
            // (it's hidden + its walking is suppressed by ClientPlayerEntityMixin), and the freecam
            // pose (which CameraMixin writes to the camera) tracks the mouse directly.
            ReplayState.applyFreecamLook(yawDelta, pitchDelta)
            ci.cancel()
        } catch (_: Throwable) {
            // fail-open: leave the player's turn as-is (worst case = a one-tick player turn, never a crash)
        }
    }
}
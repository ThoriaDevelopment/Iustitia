package dev.iustitia.mixin

import dev.iustitia.replay.ReplayState
import net.minecraft.client.input.Input
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.util.PlayerInput
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Suppresses the local player's walking while ANY replay/playclip is active (every cam mode), so
 * spectating a rewind/clip never moves the live player. Without this, both the camera and the
 * player move on WASD — the camera would just track the player instead of free-flying through the
 * clip's solid world (incl. underground).
 *
 * ## Mechanism
 *
 * `ClientPlayerEntity.tickMovement()` reads `input.playerInput` (the [PlayerInput] record of held
 * movement keys, refreshed each tick by `Input.tick()` at the end of `tickMovement`) and applies it
 * to the player's velocity. Injecting at `HEAD` and zeroing `input.playerInput = PlayerInput.DEFAULT`
 * makes that read see no held keys, so the player does not walk/jump/sneak this tick. The keys
 * themselves (the vanilla `KeyBinding`s) stay pressed — [dev.iustitia.Iustitia.onClientTick] still
 * reads them to advance the camera, and `Input.tick()` at the end re-reads the keys for next tick, so
 * when FREECAM ends the player resumes normally. Verified against the 1.21.11 named jar: `input` is a
 * `public Input input` field on `ClientPlayerEntity`, `PlayerInput` is the movement record with a
 * `public static PlayerInput DEFAULT` zeroed instance, and `tickMovement` is the method that consumes
 * it.
 *
 * ## Why zero [playerInput] and not the whole [Input]
 *
 * We mutate only the record field the movement read consumes; we never replace `input` itself or
 * touch `movementVector`/`Input.tick()`. The next `Input.tick()` rebuilds `playerInput` from the
 * still-held keys, so there is no stuck-state risk and no keybinding side effects.
 *
 * ## Gate + fail-open
 *
 * Cancels nothing — this is an `@Inject` (not cancellable) that overwrites a field. It only acts when
 * `ReplayState.active`; every other tick is a no-op (one volatile read). The whole body is
 * try/caught (project fail-open posture): a throw leaves the player's input untouched
 * (worst case = the player walks a tick during a freecam, never a crash). Camera-only — no packets,
 * no server-side movement.
 *
 * Runtime-only-verifiable (per project convention for input-path mixins): the `tickMovement` target
 * + the `input` field are verified against the named jar; `defaultRequire: 1` means a mismatch fails
 * launch — the build can't confirm mixin-apply, only a live client run can.
 */
@Mixin(ClientPlayerEntity::class)
class ClientPlayerEntityMixin {

    @field:Shadow
    var input: Input? = null

    @Inject(method = ["tickMovement"], at = [At("HEAD")])
    private fun iustitia_suppressReplayMovement(ci: CallbackInfo) {
        try {
            // During any active replay/playclip (every cam mode) the user is spectating a rewind/clip,
            // so their walking inputs must not move the live player. Zeroing playerInput here makes the
            // vanilla movement read see no held keys; the packet-cancel mixin (ClientConnectionMixin)
            // is the belt-and-suspenders for movement + the sole mechanism for break/place/attack/etc.
            if (ReplayState.active) {
                // PlayerInput.DEFAULT is the vanilla all-false instance (forward/backward/left/right/jump/sneak/sprint).
                input?.playerInput = PlayerInput.DEFAULT
            }
        } catch (_: Throwable) {
            // fail-open: leave the player's input as-is (worst case = a one-tick walk, never a crash)
        }
    }
}
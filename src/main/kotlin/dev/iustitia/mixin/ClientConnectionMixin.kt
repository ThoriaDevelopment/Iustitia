package dev.iustitia.mixin

import dev.iustitia.replay.ReplayState
import net.minecraft.network.ClientConnection
import net.minecraft.network.packet.Packet
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Cancels the local player's OWN outgoing **gameplay** packets while a replay/playclip is active, so
 * spectating a rewind/clip never acts on the live server (no move/look/break/place/use/interact/
 * attack/swing). This is the packet-cancel half of the v1.2.0 spectator-like input suppression; the
 * other half is [ClientPlayerEntityMixin] zeroing the movement input.
 *
 * ## Scope (user-authorized exception to the no-outgoing-packets rule)
 *
 * Acts ONLY on the local observer's own packets, ONLY while [ReplayState.active] is true, and ONLY on
 * the closed list of gameplay packet types below. Chat (`ChatMessageC2SPacket`), commands
 * (`ChatCommandC2SPacket`), inventory clicks / button / pick-item, hotbar slot changes, client
 * settings, client status, and abilities packets PASS THROUGH — a true spectator can still chat, run
 * commands, open inventory, and pick a hotbar slot while watching a clip. It never touches incoming
 * S2C packets (the read-only [ClientPlayNetworkHandlerMixin] handles those), other players, or
 * detection.
 *
 * ## Gate + fail-open
 *
 * One `@Volatile` read ([ReplayState.active]) + a runtime type check. Cancels (ci.cancel()) only when
 * active AND the packet is one of the gameplay types; every other call is a no-op. The whole body is
 * try/caught (project fail-open posture): a throw leaves the packet going through (worst case = the
 * player can act during a replay, never a crash). Camera-only side effect — no world edits.
 *
 * ## Runtime-only-verifiable target
 *
 * `ClientConnection.send` was javap-verified against the 1.21.11 named jar and is overloaded with
 * three variants: `send(Packet)`, `send(Packet, ChannelFutureListener)`, and
 * `send(Packet, ChannelFutureListener, boolean)`. With `defaultRequire: 1` the name-only
 * `method = ["send"]` would be ambiguous and fail at game launch, so the descriptor form
 * `method = ["send(Lnet/minecraft/network/packet/Packet;)V"]` is used to target the single-arg
 * public entry point that all gameplay code calls. The `ClientPlayNetworkHandler.sendPacket` fallback
 * was not needed. The build can't confirm mixin-apply; only a live client run can.
 */
@Mixin(ClientConnection::class)
class ClientConnectionMixin {

    @Inject(method = ["send(Lnet/minecraft/network/packet/Packet;)V"], at = [At("HEAD")], cancellable = true)
    private fun iustitia_suppressGameplayPackets(packet: Packet<*>, ci: CallbackInfo) {
        try {
            if (!ReplayState.active) return
            if (isGameplay(packet)) ci.cancel()
        } catch (_: Throwable) {
            // fail-open: a throw leaves the packet going through (worst case = the player can act
            // during a replay, never a crash)
        }
    }

    /** The closed list of outgoing gameplay packets suppressed during a replay. Anything NOT in this
     *  list (chat, commands, inventory, hotbar, settings, abilities, …) passes through. */
    private fun isGameplay(p: Packet<*>): Boolean =
        p is PlayerMoveC2SPacket ||
        p is PlayerActionC2SPacket ||
        p is PlayerInteractBlockC2SPacket ||
        p is PlayerInteractItemC2SPacket ||
        p is PlayerInteractEntityC2SPacket ||
        p is HandSwingC2SPacket ||
        p is PlayerInputC2SPacket
}
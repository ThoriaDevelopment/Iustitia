package dev.iustitia.mixin

import dev.iustitia.Iustitia
import dev.iustitia.event.EffectSignal
import dev.iustitia.event.GameJoinSignal
import dev.iustitia.event.HurtSignal
import dev.iustitia.event.HurtSource
import dev.iustitia.event.SwingSignal
import dev.iustitia.event.VelocitySignal
import dev.iustitia.protocol.ProtocolDetector
import dev.iustitia.tracking.EntityTrackerManager
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayNetworkHandler
import net.minecraft.entity.Entity
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.network.packet.s2c.play.DamageTiltS2CPacket
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket
import net.minecraft.network.packet.s2c.play.EntityStatusEffectS2CPacket
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket
import net.minecraft.network.packet.s2c.play.RemoveEntityStatusEffectS2CPacket
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.util.UUID

/**
 * The one and only mixin class. Every inject is a read-only, HEAD-only capture of an
 * incoming clientbound packet; nothing is cancelled, nothing is sent, no local
 * player/camera state is touched. Each handler resolves the other player's uuid from
 * the live world, drops the local player + unknown entities, and publishes a signal
 * to the [Iustitia] bus. All bodies are fail-open.
 */
@Mixin(ClientPlayNetworkHandler::class)
class ClientPlayNetworkHandlerMixin {

    private fun world() = MinecraftClient.getInstance().world

    private fun otherPlayer(id: Int): UUID? {
        val w = world() ?: return null
        val e: Entity = w.getEntityById(id) ?: return null
        if (e === MinecraftClient.getInstance().player) return null
        return e.uuid
    }

    private fun otherPlayer(entity: Entity?): UUID? {
        if (entity == null) return null
        if (entity === MinecraftClient.getInstance().player) return null
        return entity.uuid
    }

    @Inject(method = ["onGameJoin"], at = [At("HEAD")])
    private fun iustitia_onGameJoin(packet: GameJoinS2CPacket, ci: CallbackInfo) {
        try {
            ProtocolDetector.redetect()
            Iustitia.resetAll()
            Iustitia.bus.publish(GameJoinSignal(Iustitia.tickCounter))
        } catch (_: Throwable) {}
    }

    @Inject(method = ["onEntityAnimation"], at = [At("HEAD")])
    private fun iustitia_onEntityAnimation(packet: EntityAnimationS2CPacket, ci: CallbackInfo) {
        try {
            // Only main/off-hand swings are attack swings; crit/enchanted-hit are hit effects.
            val anim = packet.animationId
            if (anim != EntityAnimationS2CPacket.SWING_MAIN_HAND &&
                anim != EntityAnimationS2CPacket.SWING_OFF_HAND
            ) return
            val attacker = otherPlayer(packet.entityId) ?: return
            Iustitia.bus.publish(SwingSignal(attacker, Iustitia.tickCounter, System.nanoTime(), anim))
        } catch (_: Throwable) {}
    }

    @Inject(method = ["onEntityStatus"], at = [At("HEAD")])
    private fun iustitia_onEntityStatus(packet: EntityStatusS2CPacket, ci: CallbackInfo) {
        try {
            // EntityStatus byte 2 == living-entity "play hurt animation / took damage".
            if (packet.status != 2.toByte()) return
            val w = world() ?: return
            val victim = otherPlayer(packet.getEntity(w)) ?: return
            Iustitia.bus.publish(HurtSignal(victim, Iustitia.tickCounter, -1, HurtSource.STATUS))
        } catch (_: Throwable) {}
    }

    @Inject(method = ["onEntityDamage"], at = [At("HEAD")])
    private fun iustitia_onEntityDamage(packet: EntityDamageS2CPacket, ci: CallbackInfo) {
        try {
            val victim = otherPlayer(packet.entityId) ?: return
            Iustitia.bus.publish(
                HurtSignal(victim, Iustitia.tickCounter, packet.sourceCauseId, HurtSource.ENTITY_DAMAGE)
            )
        } catch (_: Throwable) {}
    }

    @Inject(method = ["onDamageTilt"], at = [At("HEAD")])
    private fun iustitia_onDamageTilt(packet: DamageTiltS2CPacket, ci: CallbackInfo) {
        try {
            val victim = otherPlayer(packet.id) ?: return
            Iustitia.bus.publish(HurtSignal(victim, Iustitia.tickCounter, -1, HurtSource.DAMAGE_TILT))
        } catch (_: Throwable) {}
    }

    @Inject(method = ["onEntityVelocityUpdate"], at = [At("HEAD")])
    private fun iustitia_onEntityVelocityUpdate(packet: EntityVelocityUpdateS2CPacket, ci: CallbackInfo) {
        try {
            val entity = otherPlayer(packet.entityId) ?: return
            val vel = packet.velocity
            EntityTrackerManager.markVelocity(entity, Iustitia.tickCounter, vel)
            Iustitia.bus.publish(VelocitySignal(entity, Iustitia.tickCounter, vel))
        } catch (_: Throwable) {}
    }

    @Inject(method = ["onEntityStatusEffect"], at = [At("HEAD")])
    private fun iustitia_onEntityStatusEffect(packet: EntityStatusEffectS2CPacket, ci: CallbackInfo) {
        try {
            val entity = otherPlayer(packet.getEntityId()) ?: return
            val entry = packet.getEffectId()
            val isSpeed = try { entry === StatusEffects.SPEED } catch (_: Throwable) { false }
            val amp = if (isSpeed) packet.getAmplifier() else -1
            EntityTrackerManager.markEffect(entity, isSpeed, amp, added = true)
            Iustitia.bus.publish(EffectSignal(entity, Iustitia.tickCounter, isSpeed, amp, added = true))
        } catch (_: Throwable) {}
    }

    @Inject(method = ["onRemoveEntityStatusEffect"], at = [At("HEAD")])
    private fun iustitia_onRemoveEntityStatusEffect(packet: RemoveEntityStatusEffectS2CPacket, ci: CallbackInfo) {
        try {
            val entity = otherPlayer(packet.entityId()) ?: return
            val entry = packet.effect()
            val isSpeed = try { entry === StatusEffects.SPEED } catch (_: Throwable) { false }
            if (!isSpeed) return
            EntityTrackerManager.markEffect(entity, isSpeed, -1, added = false)
            Iustitia.bus.publish(EffectSignal(entity, Iustitia.tickCounter, isSpeed, -1, added = false))
        } catch (_: Throwable) {}
    }
}
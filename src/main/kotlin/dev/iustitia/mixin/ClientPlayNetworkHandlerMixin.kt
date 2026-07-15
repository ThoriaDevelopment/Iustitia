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
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket
import net.minecraft.network.packet.s2c.play.DamageTiltS2CPacket
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket
import net.minecraft.network.packet.s2c.play.EntityStatusEffectS2CPacket
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket
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
            // Record the world identity of this session so a later onPlayerRespawn can tell
            // a death-respawn (same world) from a dimension-transfer / Bungee sub-server switch
            // (different world). See iustitia_onPlayerRespawn.
            SpawnState.seed = packet.commonPlayerSpawnInfo().seed()
            Iustitia.bus.publish(GameJoinSignal(Iustitia.tickCounter))
            // Per-server chat history: (re)load the current server's persisted bucket on join. Fail-open.
            try { dev.iustitia.chathist.ChatHistory.onJoin() } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    /**
     * onPlayerRespawn fires on BOTH death-respawn AND dimension transfer (portal) / Bungee
     * sub-server switch. We must reset VL and re-detect protocol ONLY on a genuine world
     * change, never on a same-world death-respawn (that would wipe a cheater's accumulated VL
     * every time they die — a trivial evasion: die, respawn, the slate is clean). Vanilla
     * intentionally keeps the same seed/dimension for a death-respawn, so:
     *  - dimension() != the live (pre-respawn) world's registryKey → portal dimension transfer,
     *  - seed() != the seed recorded at GameJoin (or the last world-change respawn) → Bungee
     *    sub-server switch (same dimension key, freshly generated world).
     * Either => world changed: resetAll + redetect + record the new seed. Both equal => a
     * same-world death-respawn: preserve VL. Fail-open.
     */
    @Inject(method = ["onPlayerRespawn"], at = [At("HEAD")])
    private fun iustitia_onPlayerRespawn(packet: PlayerRespawnS2CPacket, ci: CallbackInfo) {
        try {
            val info = packet.commonPlayerSpawnInfo()
            val newSeed = info.seed()
            val newDim = info.dimension()
            val oldDim = world()?.registryKey
            val dimChanged = oldDim != null && newDim != oldDim
            val seedChanged = newSeed != SpawnState.seed
            if (dimChanged || seedChanged) {
                ProtocolDetector.redetect()
                Iustitia.resetAll()
                SpawnState.seed = newSeed
            }
            // same world (death-respawn): intentionally preserve all VL — see PacketSignals.
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
            val w = world() ?: return
            // EntityStatus byte 2 == living-entity "play hurt animation / took damage".
            if (packet.status == 2.toByte()) {
                val victim = otherPlayer(packet.getEntity(w)) ?: return
                Iustitia.bus.publish(HurtSignal(victim, Iustitia.tickCounter, -1, HurtSource.STATUS))
                return
            }
            // EntityStatus byte 35 == Totem-of-Undying pop (broadcast to every tracking client).
            // Recorded into the replay buffer as a (tick, uuid) event → the `⚡<count>` badge on the
            // ghost's nametag during /ius playclip when clipTotemPopCounter is on. Fail-open.
            if (packet.status == 35.toByte()) {
                val who = otherPlayer(packet.getEntity(w)) ?: return
                dev.iustitia.replay.ReplayBuffer.recordTotemPop(Iustitia.tickCounter, who)
                // Mirror into the manual long-recording buffer (no-op unless `/ius record` is active).
                try { dev.iustitia.replay.RecordManager.recordTotemPop(Iustitia.tickCounter, who) } catch (_: Throwable) {}
            }
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

    /** `onChatMessage` is the only player-chat hook that exposes a raw sender UUID (`packet.sender()`)
     *  + the signed plaintext (`packet.body().content()`) before decoration — `onGameMessage` carries
     *  only a pre-rendered `Text` with no UUID, and `ChatHud.addMessage` bakes the UUID into a
     *  decorated `Text`. Capture OTHER players' messages only (sender is a tracked player AND not the
     *  local player); system/game messages never reach this handler. Fail-open. */
    @Inject(method = ["onChatMessage"], at = [At("HEAD")])
    private fun iustitia_onChatMessage(packet: ChatMessageS2CPacket, ci: CallbackInfo) {
        try {
            val sender = packet.sender() ?: return
            if (sender == MinecraftClient.getInstance().player?.uuid) return
            val tracked = EntityTrackerManager.all().firstOrNull { it.uuid == sender } ?: return
            val text = packet.body()?.content()?.takeIf { it.isNotBlank() }
                ?: packet.unsignedContent()?.string ?: return
            val name = packet.serializedParameters()?.name()?.string
                ?: tracked.username().ifEmpty { sender.toString().take(8) }
            dev.iustitia.chathist.ChatHistory.record(Iustitia.tickCounter, System.currentTimeMillis(), sender, name, text)
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
            val isBlind = try { entry === StatusEffects.BLINDNESS } catch (_: Throwable) { false }
            val amp = if (isSpeed) packet.getAmplifier() else -1
            val blindAmp = if (isBlind) packet.getAmplifier() else -1
            // markEffect is Speed-only (feeds the SpeedEnvelope cap raise); Blindness is tracked
            // per-check (sprintHack subscribes EffectSignal) — no shared TrackedPlayer field.
            EntityTrackerManager.markEffect(entity, isSpeed, amp, added = true)
            Iustitia.bus.publish(EffectSignal(entity, Iustitia.tickCounter, isSpeed, amp, isBlind, blindAmp, added = true))
        } catch (_: Throwable) {}
    }

    @Inject(method = ["onRemoveEntityStatusEffect"], at = [At("HEAD")])
    private fun iustitia_onRemoveEntityStatusEffect(packet: RemoveEntityStatusEffectS2CPacket, ci: CallbackInfo) {
        try {
            val entity = otherPlayer(packet.entityId()) ?: return
            val entry = packet.effect()
            val isSpeed = try { entry === StatusEffects.SPEED } catch (_: Throwable) { false }
            val isBlind = try { entry === StatusEffects.BLINDNESS } catch (_: Throwable) { false }
            // Forward both Speed and Blindness removal; drop other effects (unobserved). The
            // remove packet carries no amplifier, so blindAmplifier = -1 on removal (the
            // sprintHack check only cares that Blindness ended, not the level).
            if (!isSpeed && !isBlind) return
            if (isSpeed) EntityTrackerManager.markEffect(entity, isSpeed, -1, added = false)
            Iustitia.bus.publish(EffectSignal(entity, Iustitia.tickCounter, isSpeed, -1, isBlind, -1, added = false))
        } catch (_: Throwable) {}
    }
}

/**
 * Holds the seed of the world the client is currently in, as reported by the last
 * GameJoin / world-change respawn. Used by [ClientPlayNetworkHandlerMixin.iustitia_onPlayerRespawn]
 * to tell a death-respawn (same seed → preserve VL) from a dimension-transfer / Bungee
 * sub-server switch (different seed → reset VL). Volatile: read by the respawn handler from
 * the network thread, written by the game-join/respawn handlers.
 */
private object SpawnState {
    @Volatile var seed: Long = Long.MIN_VALUE
}
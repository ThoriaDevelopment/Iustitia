package dev.iustitia.event

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import java.util.UUID

/**
 * Raw observable signals emitted by [dev.iustitia.mixin.ClientPlayNetworkHandlerMixin]
 * from read-only HEAD injects on incoming clientbound packets. Iustitia never sees a
 * cheater's outgoing packets, so "an attack happened" is *inferred* downstream by
 * [dev.iustitia.inference.AttackInference] correlating [SwingSignal] with [HurtSignal].
 *
 * All signals carry the *other* player's uuid (resolved from the packet entity id via
 * the live ClientWorld). Signals for the local player or unknown entities are dropped
 * at the mixin boundary and never reach the bus.
 */

/** A swing animation (EntityAnimationS2CPacket, animationId == SWING_MAIN_HAND). */
data class SwingSignal(
    val attacker: UUID,
    val tick: Int,
    val nanoTime: Long,
    val animationId: Int,
)

/**
 * A block-break progress observation (BlockBreakingProgressS2CPacket) for [entity].
 *
 * [progress] >= 0 is a live dig at [pos] (the 0..9 stage the server broadcasts);
 * -1 is the server's abort / stop / destroy and ends it.
 *
 * The server side is `ServerWorld.setBlockBreakingInfo`, which broadcasts to every
 * other player in the same world within 32 blocks (squared distance < 1024.0) and,
 * via `ServerPlayerInteractionManager.continueMining`, only when the integer stage
 * CHANGES. A block whose breaking delta is 0 (bedrock, or obsidian under a too-weak
 * tool) therefore produces exactly one progress-0 packet and then silence for as long
 * as the button is held. That is why consumers keep this as sticky state rather than
 * a time window.
 *
 * What this attests is that the server accepted a dig, not that the player is still
 * holding the button: a client that keeps re-sending START_DESTROY_BLOCK re-arms it.
 * Consumers must not treat the dig state as unforgeable; the guards that use it are
 * bounded elsewhere (see AttackInference's direct-attribution path and the
 * proven-attack clear).
 */
data class DiggingSignal(
    val entity: UUID,
    val tick: Int,
    val progress: Int,
    val pos: BlockPos,
)

/** A hurt event for [victim], from any of the four observable hurt channels. */
data class HurtSignal(
    val victim: UUID,
    val tick: Int,
    /** Best-effort attacker id from EntityDamageS2CPacket.sourceCauseId, or -1 if unknown. */
    val attackerEntityId: Int,
    val source: HurtSource,
)

enum class HurtSource { STATUS, ENTITY_DAMAGE, DAMAGE_TILT, VELOCITY }

/** A velocity update (EntityVelocityUpdateS2CPacket) — opens velocity-exemption windows. */
data class VelocitySignal(
    val entity: UUID,
    val tick: Int,
    val velocity: Vec3d,
)

/** Game join — triggers protocol re-detection and tracker reset. (Respawn is NOT injected
 *  here; per-player state resets via the poll's entity purge/recreate lifecycle, so the live
 *  vl of other players is preserved across a respawn. Adding an onPlayerRespawn inject would
 *  both expand the mixin set and discard that vl on every respawn.) */
data class GameJoinSignal(
    val tick: Int,
)

/**
 * A status effect was added to or removed from [entity]. [speedAmplifier] is the Speed
 * effect's amplifier (0 = Speed I, 1 = Speed II) when [isSpeed]; -1 when not Speed or on
 * removal. Drives the SpeedEnvelope cap raise so Speed-potioned players aren't flagged.
 * [isBlind] / [blindAmplifier] forward the Blindness effect (added in §8 step 12) so the
 * sprintHack check can flag sprint-while-blind (vanilla blocks sprint under Blindness); the
 * amplifier is informational only (any Blindness level cancels sprint) and -1 on removal or
 * when not Blind. Other effects are unobserved (Dolphin/Levitation/SlowFalling mitigated
 * in-check).
 */
data class EffectSignal(
    val entity: UUID,
    val tick: Int,
    val isSpeed: Boolean,
    val speedAmplifier: Int,
    val isBlind: Boolean = false,
    val blindAmplifier: Int = -1,
    val added: Boolean,
)

/**
 * Derived event: the anticheat's inference that [attacker] struck [victim] near [tick],
 * produced by correlating a swing with a hurt within the version-gated tick window.
 * Consumed by ReachCheck and MultiTargetCheck.
 */
data class AttackEvent(
    val attacker: UUID,
    val victim: UUID,
    val tick: Int,
    val nanoTime: Long,
)
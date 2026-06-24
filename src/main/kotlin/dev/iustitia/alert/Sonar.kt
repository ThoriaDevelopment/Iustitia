package dev.iustitia.alert

import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.tracking.EntityTrackerManager
import net.minecraft.client.MinecraftClient
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.Vec3d
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.UUID

/**
 * Sonar alerting (Phase 2): on a flushed alert batch, instead of (or in addition to) a chat line, play
 * a DIRECTIONAL audio cue — a note positioned at the offending player's last-known world position, so
 * the pan tells you the direction and the pitch tells you the distance. Eyes-free alerting: you can
 * keep fighting and just *listen* for cheats. Additive to the existing chat alerts (the chosen mode is
 * "sonar + chat both"), gated by [IustitiaConfig.sonarAlerts].
 *
 * ## Spatial sound on 1.21.11
 *
 * `ClientWorld.playSoundClient(x, y, z, SoundEvent, SoundCategory, volume, pitch, useDistanceFromPlayer)`
 * places a sound at a world position; Minecraft's sound engine pans it left/right from the listener
 * (stereo) and attenuates by distance. We pass `useDistanceFromPlayer = false` so the VOLUME is
 * constant (a faint ping, not loud-when-close) and the pan carries the direction; we then encode
 * DISTANCE in the pitch — closer = higher pitch — so two pings at the same direction but different
 * ranges are audibly distinct without one drowning the other. (Letting natural attenuation do it would
 * make far offenders nearly silent and indistinguishable; encoding distance in pitch keeps every cue
 * audible and ranked.)
 *
 * ## Pitch mapping
 *
 * Horizontal distance `d` (blocks) → pitch in `[0.7, 1.6]` via an inverse mapping: `pitch = 1.6 - d/40`,
 * clamped. A player 2 blocks away pings high (~1.55); one 36+ blocks away pings low (~0.7). The local
 * player's own eye is the listener origin; the offender's last-known position comes from
 * [EntityTrackerManager] (server-space, polled each tick). Vertical offset is folded into the distance
 * (sqrt) so an offender directly above still pings — pan is horizontal-only, which is fine: humans
 * localize elevation poorly and the pitch already encodes "how far".
 *
 * ## Tier → sound
 *
 * GREEN → harp (soft), YELLOW → pling (mid), RED → bass drum (low, ominous) — mirrors the audio-cue
 * tier mapping in [AlertManager.playCue] so a user with both on hears a consistent tier vocabulary.
 *
 * ## Threading + safety
 *
 * Called from [AlertManager.flushBatch] on the client thread (after the chat line). We resolve the
 * offender's position on the client thread (EntityTrackerManager is client-thread) and play on the
 * client thread via `client.execute {}` so the world reference is stable. Fail-open: a missing
 * player / null world / sound error is swallowed — sonar is a bonus cue, never a crash. No packet is
 * sent (client-local sound). Gated by [IustitiaConfig.sonarAlerts] + the same mute/preset rules the
 * chat line already passed, so a muted/preset-dropped alert is silent too.
 */
object Sonar {

    /** Min/max sonar pitch (distance-encoded). */
    private const val PITCH_NEAR = 1.6f
    private const val PITCH_FAR = 0.7f
    /** Distance (blocks) over which pitch sweeps the full range. */
    private const val PITCH_SPAN = 40.0

    /**
     * Play a directional cue for [uuid] (the player whose alert batch just flushed). No-op when sonar
     * is disabled, when the player isn't tracked, or when the world/listener is unavailable.
     */
    fun cue(uuid: UUID) {
        try {
            if (!ConfigManager.config.sonarAlerts) return
            val client = MinecraftClient.getInstance()
            val world = client.world ?: return
            val player = client.player ?: return
            val tp = EntityTrackerManager.get(uuid) ?: return
            val listener = player.getCameraPosVec(1f)
            val offender = tp.pos
            val dx = offender.x - listener.x
            val dz = offender.z - listener.z
            val dy = offender.y - listener.y
            val horiz = sqrt(dx * dx + dz * dz)
            val dist = sqrt(horiz * horiz + dy * dy)
            val pitch = (PITCH_NEAR - (dist / PITCH_SPAN).toFloat()).coerceIn(PITCH_FAR, PITCH_NEAR)

            val tier = FlagHistory.tierFor(uuid)
            val event: SoundEvent = when (tier) {
                FlagHistory.Tier.RED -> SoundEvents.BLOCK_NOTE_BLOCK_BASS.value()
                FlagHistory.Tier.YELLOW -> SoundEvents.BLOCK_NOTE_BLOCK_PLING.value()
                FlagHistory.Tier.GREEN -> SoundEvents.BLOCK_NOTE_BLOCK_HARP.value()
            }
            val vol = ConfigManager.config.sonarVolume.toFloat()
            client.execute {
                try {
                    world.playSoundClient(
                        offender.x, offender.y, offender.z,
                        event, net.minecraft.sound.SoundCategory.RECORDS,
                        vol, pitch, false,
                    )
                } catch (_: Throwable) {
                    // fail-open: positional overload may differ; fall back to non-spatial at player
                    try { player.playSound(event, vol, pitch) } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
            // sonar must never crash the alert path
        }
    }

    // Suppress unused-import lint for Vec3d/atan2/sin/cos — kept for the spatial math documentation
    // and potential future azimuth-pan overrides; the engine handles pan from the position.
    @Suppress("unused") private fun _azimuth(listener: Vec3d, offender: Vec3d): Double {
        val dx = offender.x - listener.x
        val dz = offender.z - listener.z
        return atan2(dx, dz) // radians, 0 = +Z, π/2 = +X
    }
    @Suppress("unused") private fun _dir(az: Double): Vec3d = Vec3d(sin(az), 0.0, cos(az))
}
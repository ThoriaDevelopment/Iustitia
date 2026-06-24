package dev.iustitia.render

import dev.iustitia.Iustitia
import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import net.minecraft.client.MinecraftClient
import net.minecraft.particle.DustParticleEffect
import java.util.UUID

/**
 * Burst-spark (Phase B): on a fresh tier-relevant (yellow/red) alert, spawn a brief tier-colored
 * particle burst at the offender's eye — a visual "flag" cue mid-fight, complementary to the
 * nametag burst-pulse and the audio cue.
 *
 * **Local-only:** uses `World.addParticleClient` (a client-only particle spawn — no packet is sent
 * to the server; the particles exist only in the local client's particle manager). Fail-open: any
 * error is swallowed so alerting/spark rendering never crashes the tick or render path.
 *
 * ## Threading
 *
 * [fire] is called from [dev.iustitia.alert.AlertManager.alert] on the netty packet thread. We
 * hand the actual particle spawn to [MinecraftClient.execute] so it runs on the client thread
 * (particles must be added from the client/tick thread), reading the offender's current position
 * there so it reflects the latest interpolation.
 *
 * ## Color
 *
 * `DustParticleEffect(int color, float scale)` packs RGB in the int (red `0xFF3030`, yellow
 * `0xFFFF30`); spawned via `addParticleClient(dust, x, y, z, vx, vy, vz)` with a small random
 * velocity cone so the burst reads as an outward spray rather than a static clump.
 *
 * Runtime-only-verifiable: a build cannot confirm the particles render at the offender (depends on
 * the offender being in the local particle manager's range / not frustum-culled). All fail-open.
 */
object BurstSparks {

    private const val COUNT = 10
    private const val SCALE = 1.6f
    private const val SPEED = 0.06

    private const val RED_RGB = 0xFF3030
    private const val YELLOW_RGB = 0xFFFF30

    /**
     * Schedule a tier-colored particle burst at [uuid]'s eye. No-op if the player isn't loaded or
     * the feature is disabled. Safe to call from any thread.
     */
    fun fire(uuid: UUID, tier: FlagHistory.Tier) {
        try {
            if (!ConfigManager.config.burstSparks) return
            if (tier == FlagHistory.Tier.GREEN) return
            val color = if (tier == FlagHistory.Tier.RED) RED_RGB else YELLOW_RGB
            val client = MinecraftClient.getInstance()
            client.execute {
                try {
                    val world = client.world ?: return@execute
                    val offender = world.getPlayerByUuid(uuid) ?: return@execute
                    if (offender.isRemoved) return@execute
                    // Eye position via tickDelta 0 (this fires on the tick boundary; close enough for
                    // a particle origin — the spray is coarse by design).
                    val eye = offender.getCameraPosVec(0f)
                    val dust = DustParticleEffect(color, SCALE)
                    // Deterministic pseudo-random spread from the player uuid + current tick so the
                    // burst looks natural but stays off Math.random (unavailable in some contexts).
                    val seed = (uuid.leastSignificantBits xor Iustitia.tickCounter.toLong())
                    for (i in 0 until COUNT) {
                        val r = spread(seed, i, 0)
                        val r2 = spread(seed, i, 1)
                        val r3 = spread(seed, i, 2)
                        world.addParticleClient(
                            dust,
                            eye.x, eye.y, eye.z,
                            (r - 0.5) * SPEED, (r2 - 0.5) * SPEED, (r3 - 0.5) * SPEED,
                        )
                    }
                } catch (_: Throwable) {
                    // fail-open: a particle error never crashes the client thread
                }
            }
        } catch (_: Throwable) {
            // fail-open
        }
    }

    /** Cheap deterministic 0..1 spread from (seed, index, salt) — avoids Math.random. */
    private fun spread(seed: Long, index: Int, salt: Int): Double {
        var h = seed xor (index.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor (salt.toLong() * 0xC2B2AE3D27D4EB4FuL.toLong())
        h = h xor (h ushr 30)
        h *= 0xBF58476D1CE4E5B9uL.toLong()
        h = h xor (h ushr 27)
        h *= 0x94D049BB133111EBuL.toLong()
        h = h xor (h ushr 31)
        return ((h and 0xFFFFFFFFL).toDouble() / 0xFFFFFFFFL.toDouble())
    }
}
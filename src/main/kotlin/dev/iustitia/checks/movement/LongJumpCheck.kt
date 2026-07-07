package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.world.WorldQueries
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import java.util.UUID
import kotlin.math.hypot

/**
 * LongJump detector. A legit sprint-jump's first airborne tick moves ~0.31 horizontally; a
 * LongJump cheat boosts horizontal velocity on launch. We detect the jump impulse
 * (prevDeltaY ≈ 0.42) and flag if any airborne tick within the next 6 has horizontal Δ >
 * [threshold] (0.6/tick ≈ 12 bps, far above sprint-jump launch) with no knockback/velocity/
 * elytra source. The effective cap is raised by the Speed effect (+20%/level) and by
 * ice/slime (×1.3 — frictionless surfaces legitimately carry launch momentum), matching
 * [SpeedEnvelopeCheck] so a legit Speed-potioned or ice-coast launch isn't false-flagged.
 * Speed already catches sustained high bps; this targets the boosted launch specifically.
 * setbackVL 5, decay 0.5/tick.
 */
class LongJumpCheck : Check() {

    override val id: String = "longJump"

    override fun newContext(uuid: UUID): CheckContext = LongJumpContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding || tp.riptide || tp.swimming) return
            if (tick - tp.lastTeleportTick < 5) return
            if (tick - tp.hurtTick < 5) return
            if (tick - tp.velocityTick < 40) return
            // Server-lag exemption: a server-wide hitch / catch-up burst injects a large
            // horizontal Δ that would trip the boosted-launch gate. Skip the sample.
            if (tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW ||
                tick - EntityTrackerManager.lastLagBurstTick <= BURST_WINDOW
            ) return
            val ctx = contextOf(tp.uuid) as LongJumpContext
            // a real jump impulse starts the air phase. Arm on the 2nd airborne tick of ANY
            // jump arc — the prior `abs(prevDeltaY - 0.42) < 0.08` armed only a vanilla 0.42
            // jump, so a Jump Boost jump (impulse 0.52/0.62/…) never armed and LongJump was
            // dead for anyone under Jump Boost. Generalize: prevDeltaY is a plausible jump
            // impulse (0.3..0.9 covers vanilla through high Jump Boost) and Δy is descending
            // (gravity, < prevDeltaY) — the 2nd-tick signature of a real jump, any boost level.
            if (tp.prevDeltaY > 0.3 && tp.prevDeltaY < 0.9 && tp.deltaY < tp.prevDeltaY) {
                ctx.airborneStart = tick
            }
            if (ctx.airborneStart >= 0 && tick - ctx.airborneStart in 1..6 && !tp.groundedProxy) {
                val horiz = hypot(tp.delta.x, tp.delta.z)
                // effective cap mirrors SpeedEnvelope: Speed effect (+20%/level) + ice/slime
                // (×1.3). A null/unloaded foot block is fail-NEGATIVE (skip the flag) — a
                // single-tick check can't afford to strict-flag on an unloaded chunk, per the
                // chunk-unloaded-never-FP rule. The grounded reset below still runs.
                var eff = cfg.threshold * speedFactor(tp)
                val world = MinecraftClient.getInstance().world
                if (world != null) {
                    val state = WorldQueries.blockStateAt(
                        world,
                        Math.floor(tp.pos.x).toInt(),
                        Math.floor(tp.pos.y - 1.0).toInt(),
                        Math.floor(tp.pos.z).toInt()
                    )
                    if (state != null) {
                        if (isSpeedBlock(state)) eff *= 1.3
                        if (horiz > eff) flag(tp, ctx, 1.0, "LongJump", tick)
                    }
                }
            }
            if (tp.groundedProxy) ctx.airborneStart = -1
        } catch (_: Throwable) {}
    }

    private fun speedFactor(tp: TrackedPlayer): Double =
        if (tp.speedAmplifier >= 0) 1.0 + 0.2 * (tp.speedAmplifier + 1) else 1.0

    private fun isSpeedBlock(state: net.minecraft.block.BlockState): Boolean = try {
        state.isOf(Blocks.ICE) || state.isOf(Blocks.BLUE_ICE) ||
            state.isOf(Blocks.PACKED_ICE) || state.isOf(Blocks.FROSTED_ICE) ||
            state.isOf(Blocks.SLIME_BLOCK)
    } catch (_: Throwable) {
        false
    }

    private class LongJumpContext : CheckContext() {
        var airborneStart: Int = -1
    }

    private companion object {
        /** Window (ticks) after a server-wide freeze within which long-jump samples are skipped. */
        private const val LAG_WINDOW = 8
        /** Window (ticks) after a batched catch-up burst within which long-jump samples are skipped. */
        private const val BURST_WINDOW = 3
    }
}
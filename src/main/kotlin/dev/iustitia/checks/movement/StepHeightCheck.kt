package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.protocol.ProtocolDetector
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.world.WorldQueries
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.effect.StatusEffects
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

/**
 * Step detector (StepHeight). The Step cheat raises step height beyond vanilla 0.6 and
 * sends staircase position packets to fake stepping up >0.6 in one tick. A real jump is a
 * 0.42 impulse (below 0.6); slabs/stairs legit-step 0.5 (below 0.6). So a single-tick Δy in
 * (0.6, 2.5] that follows a grounded tick (no airborne lead-up) and is not a 0.42 jump, with
 * a solid block within reach below the new feet, is a step. setbackVL 5, decay 0.5/tick.
 */
class StepHeightCheck : Check() {

    override val id: String = "stepHeight"

    override fun newContext(uuid: UUID): CheckContext = StepContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding || tp.riptide || tp.swimming) return
            if (tick - tp.lastTeleportTick < 5) return
            if (tick - tp.hurtTick < 3) return // vertical knockback can mimic a step
            // Server-lag exemption: a server-wide hitch / catch-up burst injects a large Δy
            // sample that would trip the step gate. Skip the sample (see SpeedEnvelopeCheck).
            if (tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW ||
                tick - EntityTrackerManager.lastLagBurstTick <= BURST_WINDOW
            ) return
            val ctx = contextOf(tp.uuid) as StepContext
            val dy = tp.deltaY
            // Version-aware step limit: 1.8 vanilla step is 0.5, 1.21 is 0.6. Cap the modern
            // default (cfg.threshold, 0.6) down to 0.5 on 1.8 servers; respect a user who lowers
            // the slider below the version's vanilla step.
            val limit = if (ProtocolDetector.is1_8OrLess) min(cfg.threshold, 0.5) else cfg.threshold
            // A real jump's first-tick Δy is the jump impulse (0.42 vanilla, +0.1/level with
            // Jump Boost). A vanilla jump (0.42) is already below `limit` (0.6 on 1.21, 0.5 on
            // 1.8), but Jump Boost I/II push the impulse to 0.52/0.62 — above 0.6 — which the
            // prior `abs(prevDeltaY - 0.42) > 0.1` test did NOT exempt (prevDeltaY is ~0 on the
            // jump tick, so that test was always-true and never excluded anything). Exempt any
            // Δy within the (boost-aware) jump-impulse ceiling + margin; only a Δy beyond a real
            // jump (a step up a full block+, ~1.0+) flags. Slab/stair legit-step (0.5) stays at
            // or below `limit`; a step cheat (≥1.0) clears the ceiling.
            if (dy > limit && dy <= 2.5 && ctx.wasGrounded &&
                dy > jumpImpulseCeiling(tp) + JUMP_MARGIN
            ) {
                val world = MinecraftClient.getInstance().world
                if (world != null && WorldQueries.isSolidBelow(world, tp.pos.x, tp.pos.y, tp.pos.z, 0.7)) {
                    // Slime-block trampoline: a slime bounce can launch a player >0.6 in one
                    // tick from a grounded tick, mimicking a step. Skip the flag (guard, not
                    // early return — the wasGrounded update below must still run). Slime at the
                    // takeoff point (≈0.7 below feet).
                    val below = WorldQueries.blockStateAt(
                        world,
                        Math.floor(tp.pos.x).toInt(),
                        Math.floor(tp.pos.y - 0.7).toInt(),
                        Math.floor(tp.pos.z).toInt()
                    )
                    val slimeBounce = below != null && below.isOf(Blocks.SLIME_BLOCK)
                    if (!slimeBounce) flag(tp, ctx, 1.0, "Step", tick)
                }
            }
            ctx.wasGrounded = tp.groundedProxy
        } catch (_: Throwable) {}
    }

    /** Max first-tick Δy a legit jump can produce for this player: 0.42 + 0.1×(Jump Boost
     *  amplifier+1) (vanilla `LivingEntity.jump` velocity). No effect (amplifier -1) → 0.42.
     *  Reads the live entity's status effect; fail-open to -1 (→ 0.42 ceiling) on any error. */
    private fun jumpImpulseCeiling(tp: TrackedPlayer): Double {
        val amp = try {
            tp.entity?.getStatusEffect(StatusEffects.JUMP_BOOST)?.amplifier ?: -1
        } catch (_: Throwable) { -1 }
        return 0.42 + 0.1 * (amp + 1)
    }

    private companion object {
        /** Margin above the jump-impulse ceiling before a Δy is treated as a step, absorbing
         *  client-side interpolation noise on the takeoff tick. */
        const val JUMP_MARGIN = 0.15
        /** Window (ticks) after a server-wide freeze within which step samples are skipped. */
        private const val LAG_WINDOW = 8
        /** Window (ticks) after a batched catch-up burst within which step samples are skipped. */
        private const val BURST_WINDOW = 3
    }

    private class StepContext : CheckContext() {
        var wasGrounded: Boolean = false
    }
}
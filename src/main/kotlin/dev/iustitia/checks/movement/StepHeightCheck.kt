package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.world.WorldQueries
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import java.util.UUID
import kotlin.math.abs

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
            val ctx = contextOf(tp.uuid) as StepContext
            val dy = tp.deltaY
            if (dy > cfg.threshold && dy <= 2.5 && ctx.wasGrounded &&
                abs(tp.prevDeltaY - 0.42) > 0.1
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

    private class StepContext : CheckContext() {
        var wasGrounded: Boolean = false
    }
}
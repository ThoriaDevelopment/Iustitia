package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.world.WorldQueries
import net.minecraft.client.MinecraftClient
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import dev.iustitia.checks.LagWindows

/**
 * LiquidWalk / Jesus detector (WaterWalk). The cheat rewrites liquid bounding boxes to
 * solid (or micro-Y offsets) so the player stands/walks on the water surface. Observer
 * signature: near-constant Y (|Δy| small) with horizontal motion while the block at the
 * feet is a liquid and NOT a solid (i.e. they're held up by water), and they aren't
 * swimming or in a boat. Sustained ≥3 ticks. Frost-walker (walks on frosted ice, which IS
 * solid) is exempt by the "not solid" gate. setbackVL 5, decay 0.5/tick.
 *
 * Legit-support exemptions (each closes a real water-map FP the bare liquid+!solid form had):
 *  - **Waterlogged solid** — already exempt by the `!isSolidAt` gate (a waterlogged stair/slab/
 *    fence is solid, so the player stands on it legitimately).
 *  - **Lily pad / climbable at the feet** — a lily pad has no collision so isSolidAt is
 *    false, yet a player legitimately stands on it over water; same for a ladder in water.
 *  - **Boat below** — a player standing ON a boat (not riding) has water at the feet but is
 *    supported by the boat. `tp.inVehicle` only catches riding.
 */
class WaterWalkCheck : Check() {

    override val id: String = "waterWalk"

    override fun newContext(uuid: UUID): CheckContext = WaterWalkContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.swimming || tp.gliding) return
            if (tick - tp.lastTeleportTick < 5) return
            // Server-lag exemption: a server-wide hitch / catch-up burst injects a horizontal
            // Δ (and a Δy jitter) that would trip the surface-walk signature. Skip the sample.
            if (tick - EntityTrackerManager.lastServerLagTick <= LagWindows.LAG_WINDOW ||
                tick - EntityTrackerManager.lastLagBurstTick <= LagWindows.BURST_WINDOW
            ) return
            val horiz = hypot(tp.delta.x, tp.delta.z) * 20.0
            val ctx = contextOf(tp.uuid) as WaterWalkContext
            if (horiz < cfg.threshold || abs(tp.deltaY) > 0.1) { ctx.streak = 0; return }
            val world = MinecraftClient.getInstance().world
            if (world == null) { ctx.streak = 0; return }
            val bx = Math.floor(tp.pos.x).toInt()
            val bz = Math.floor(tp.pos.z).toInt()
            val footY = Math.floor(tp.pos.y - 0.1).toInt()
            // held up by a liquid that isn't a solid block (frosted ice is solid → exempt)
            if (WorldQueries.isLiquidAt(world, bx, footY, bz) &&
                !WorldQueries.isSolidAt(world, bx, footY, bz)
            ) {
                // legit-support exemptions: lily pad / climbable at the feet, or a boat below.
                //
                // Plus the **body-in-water** exemption, which is what a vanilla swimmer is. A
                // surface swimmer whose head is above the water still has water at the feet (the
                // feet block is the liquid one) and is not sprint-swimming, so `tp.swimming` stays
                // false for them and the bare "liquid at the feet, not solid, moving, level Y" test
                // describes them exactly -- verified live, it flagged a legit swimmer every tick
                // (streak >= 3, VL 1.0/tick past setbackVL 5). Both limbs of the exemption are
                // about the *feet being inside the liquid* rather than resting on its surface:
                //  - (a) the next block up is also liquid (the player is submerged to the waist),
                //    or
                //  - (b) the feet sit strictly BELOW the liquid block's top face (a body occupying
                //    the water column, i.e. floating), by more than interpolated-surface noise.
                // A WaterWalk/Jesus player stands ON the surface -- feet exactly at the top face,
                // air above -- so neither limb holds and the cheat still flags.
                val liquidTop = (footY + 1).toDouble()
                val feetBelowSurface = tp.pos.y < liquidTop - SURFACE_EPS
                val bodyInLiquid = WorldQueries.isLiquidAt(world, bx, footY + 1, bz)
                val supported = WorldQueries.isLilyPadAt(world, bx, footY, bz) ||
                    WorldQueries.isLilyPadAt(world, bx, footY + 1, bz) ||
                    WorldQueries.isClimbableAt(world, bx, footY, bz) ||
                    WorldQueries.isBoatBelow(world, tp.pos.x, tp.pos.y, tp.pos.z) ||
                    bodyInLiquid || feetBelowSurface
                if (supported) { ctx.streak = 0; return }
                ctx.streak++
                if (ctx.streak >= 3) flag(tp, ctx, 1.0, "WaterWalk", tick)
            } else {
                ctx.streak = 0
            }
        } catch (_: Throwable) {}
    }

    private class WaterWalkContext : CheckContext() {
        var streak: Int = 0
    }

    private companion object {
        /** Margin (blocks) by which the feet must sit below the liquid's top face to count as "the
         *  body is in the water" rather than "standing on it". Well above position-interpolation
         *  noise (~1e-3) and well below the ~0.4 a floating swimmer sits at, so neither a legit
         *  swimmer nor a surface-stander lands ambiguous. */
        private const val SURFACE_EPS = 0.05
    }
}
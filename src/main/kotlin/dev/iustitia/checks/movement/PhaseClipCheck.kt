package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.world.WorldQueries
import net.minecraft.client.MinecraftClient
import java.util.UUID

/**
 * Phase / no-clip detector (PhaseClip). The Phase cheat moves through solid blocks. We
 * sample the world cells containing the player's lower-body (pos.y+0.5) and upper-body
 * (pos.y+1.2) centers; a *real* burial puts BOTH bands inside a FULL-CUBE solid (the player
 * is submerged in a full block / 2-tall wall). We require BOTH (not either): a player standing
 * against a wall, fence, or slab edge — extremely common in PvP — has at most ONE body band
 * in a solid cell (their torso/legs are beside the block, not inside it), so the OR form
 * fired on ~everyone.
 *
 * Two false-positive sources that previously made Phase spam on every lobby, both fixed:
 *
 * 1. **Climbable / occupiable blocks.** `isSolidAt` counts any non-empty collision, which
 *    includes ladders, scaffolding, bamboo, cobwebs and honey — blocks a player is *meant*
 *    to be inside. A legit climber in a 2-tall ladder/scaffold stack put both bands "solid"
 *    and got read as phasing. Now gated on [WorldQueries.isFullCubeSolidAt]: a real phase cheat
 *    goes through full cubes (stone/planks walls), while those blocks have partial collision
 *    shapes and return false. Wall-phase detection is unchanged; only the climber FP is gone.
 *
 * 2. **Server-lag frozen positions.** PhaseClip was the only movement check that did NOT exempt
 *    on the shared [EntityTrackerManager.lastServerLagTick] / [lastLagBurstTick] signal. A lag
 *    spike freezes every player's rebroadcast position; if a player's frozen pos was in a wall
 *    corner (KB-into-wall, rubberband), the streak accumulated past 5 and fired for the whole
 *    lag window. Now we skip evaluation during a lag/burst window (return without touching the
 *    streak — a pre-lag partial streak from a real phaser is preserved, a frozen-in-wall pos
 *    stops accumulating).
 *
 * Server-rebroadcast positions lag the true collided position by up to ~0.1–0.3 (interpolation
 * + client-side inversion), so a player shoved into a corner can momentarily put both bands
 * in a solid cell. A 5-tick sustain gate + decay 0.5 washes those transient penetrations out
 * below setbackVL — a real Phase cheat stays submerged and climbs past it. Knockback can
 * pin a player into a wall, so a recent hurt exempts. Chunk-gated; fail-open. setbackVL 5,
 * decay 0.5/tick.
 */
class PhaseClipCheck : Check() {

    override val id: String = "phaseClip"

    override fun newContext(uuid: UUID): CheckContext = PhaseContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding || tp.swimming) return
            if (tick - tp.lastTeleportTick < 10) return
            if (tick - tp.hurtTick < 3) return // knockback can pin a player into a wall
            // Server-lag exemption: a frozen rebroadcast position in a wall corner must not
            // accumulate. Skip without touching the streak (see class doc). Matches the shared
            // signal every other movement check already consults.
            if (tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW ||
                tick - EntityTrackerManager.lastLagBurstTick <= BURST_WINDOW
            ) return
            val world = MinecraftClient.getInstance().world
            val ctx = contextOf(tp.uuid) as PhaseContext
            if (world == null) { ctx.streak = 0; return }
            val bx = Math.floor(tp.pos.x).toInt()
            val bz = Math.floor(tp.pos.z).toInt()
            val lowerY = Math.floor(tp.pos.y + 0.5).toInt()
            val upperY = Math.floor(tp.pos.y + 1.2).toInt()
            // BOTH bands full-cube-solid = genuinely submerged in a full block. One band (or a
            // partial block) = standing beside a wall/fence/slab or inside a climbable — legit.
            val inside = WorldQueries.isFullCubeSolidAt(world, bx, lowerY, bz) &&
                WorldQueries.isFullCubeSolidAt(world, bx, upperY, bz)
            if (inside) {
                ctx.streak++
                if (ctx.streak >= 5) flag(tp, ctx, 1.0, "Phase", tick)
            } else {
                ctx.streak = 0
            }
        } catch (_: Throwable) {}
    }

    private class PhaseContext : CheckContext() {
        var streak: Int = 0
    }

    private companion object {
        // Mirrors SpeedEnvelope's windows: a lag tick within the last 8 ticks (or a catch-up
        // burst within 3) means positions are unreliable — skip phase evaluation.
        const val LAG_WINDOW = 8
        const val BURST_WINDOW = 3
    }
}
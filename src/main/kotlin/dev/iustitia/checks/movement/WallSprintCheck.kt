package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.history.Evidence
import dev.iustitia.math.Vectors
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.world.WorldQueries
import net.minecraft.client.MinecraftClient
import net.minecraft.client.world.ClientWorld
import java.util.UUID
import kotlin.math.hypot

/**
 * WallSprint detector (Grim `SprintE` / OmniSprint wall-sprint, plan §4 #3 / §8 step 12).
 * Vanilla cancels the sprint state the instant a player collides horizontally with a wall —
 * sprinting is forward motion, and a wall stops forward motion, so the server/client clears
 * the sprint flag. The OmniSprint/wall-sprint cheat forces the sprint metadata to stay set
 * while the player is pressed against a wall, so the broadcast `sprinting` flag remains true
 * across a sustained collision. That sustained sprint-into-wall is the observer-feasible
 * fingerprint — `backwardSprint` covers the yaw (backward) case; this covers the wall case.
 *
 * Detection (all metadata + world, no block-place obs): `sprinting` metadata set AND a solid
 * block directly ahead along the facing yaw within [WALL_REACH] (the player is pressed
 * against it, not merely approaching) AND the forward speed component is near zero (the wall
 * blocks forward motion, so a wall-sprinter advances ~0 along their facing — a player
 * *running at* a wall to jump it still has forward speed and is exempt), sustained ≥
 * [SUSTAIN] ticks. The wall sample uses [WorldQueries.isWallAhead] across the foot/torso/head
 * bands at 0.3/0.6/0.9 ahead; a wall 5 blocks away is outside reach (no flag), a wall within
 * ~0.9 with the player not advancing into it is the cheat. Chunk-unloaded → no flag
 * (isWallAhead fail-opens to false).
 *
 * Exemptions: vehicle/gliding/swimming (can't / shouldn't sprint-wall); a recent hurt
 * (knockback can pin a sprinting player against a wall — vanilla mechanic, reset); a recent
 * teleport (position jump isn't a sustained collision); server-lag pause (a catch-up snap
 * against a wall is not a cheat). setbackVL 5, decay 0.5/tick. Constant [SUSTAIN] tuned in
 * step 14.
 */
class WallSprintCheck : Check() {

    override val id: String = "wallSprint"

    override fun newContext(uuid: UUID): CheckContext = WallSprintContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding || tp.swimming) return
            val ctx = contextOf(tp.uuid) as WallSprintContext
            if (!tp.sprinting) { ctx.streak = 0; return }
            // KB exemption: knockback pins a sprinting player against a wall (vanilla), not a cheat.
            if (tick - tp.hurtTick <= HURT_EXEMPT_TICKS) { ctx.streak = 0; return }
            if (tick - tp.lastTeleportTick < 10) { ctx.streak = 0; return }
            // Server-lag pause: a catch-up snap against a wall is not a sustained wall-sprint.
            // Pause (don't reset — lag doesn't clear the cheat's sprint state).
            if (tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW ||
                tick - EntityTrackerManager.lastLagBurstTick <= BURST_WINDOW
            ) return
            // Distant-player skip: beyond the observation range the wall-sprint signal isn't
            // usefully observable. Reset (like the chunk-unloaded branch) so a stale streak
            // can't flag on re-approach.
            if (BlockLookupBudget.beyondObserveRange(tp)) { ctx.streak = 0; return }
            val world = MinecraftClient.getInstance().world
            val bx = Math.floor(tp.pos.x).toInt()
            val bz = Math.floor(tp.pos.z).toInt()
            if (world == null || !tp.chunkLoadedCached(world, tick, bx, bz)) { ctx.streak = 0; return }
            val look = Vectors.lookVector(tp.yaw.toDouble(), 0.0)
            // A solid wall directly ahead along the facing within reach → pressed against it.
            // Pass the feet Y so isWallAhead sweeps the foot/torso/head bands.
            val wallAhead = wallAheadCached(world, ctx, tp.pos.x, tp.pos.y, tp.pos.z, look.x, look.z, tick)
            if (!wallAhead) { ctx.streak = 0; return }
            // Forward speed along the facing: a player running AT a wall to jump it still
            // advances (~0.2 b/t); a wall-sprinter pressed against the wall advances ~0.
            // Exempt the approach (forward > FORWARD_MAX); flag only the pinned-but-sprinting.
            val forward = tp.delta.x * look.x + tp.delta.z * look.z
            if (forward > FORWARD_MAX) { ctx.streak = 0; return }
            // Also reject a dead-still non-sprinting-into-wall idle: require some intent — either
            // a tiny forward push (forward >= -0.01, i.e. not actively backing away) OR horizontal
            // motion along the wall. A player backing away from a wall while sprint-flagged is a
            // metadata lag edge, not a wall-sprint.
            val horiz = hypot(tp.delta.x, tp.delta.z)
            if (forward < -0.05 && horiz < 0.01) { ctx.streak = 0; return }
            ctx.streak++
            val sustain = cfg.threshold.toInt().coerceAtLeast(1)
            if (ctx.streak >= sustain) {
                flag(tp, ctx, 1.0, "WallSprint", tick, Evidence(
                    subLabel = "sprint-into-wall", measurement = forward,
                    threshold = FORWARD_MAX, pos = tp.pos,
                    extra = "streak=${ctx.streak} horiz=$horiz"))
            }
        } catch (_: Throwable) {}
    }

    /** [WorldQueries.isWallAhead] verdict, recomputed at most every [BlockLookupBudget.RATE_LIMIT_N]
     *  ticks and reused in between (per-player cache in [WallSprintContext]). The per-tick
     *  forward/streak logic runs every tick on this cached verdict; for a wall-sprinter (pressed
     *  stationary against the wall) the verdict is stable, and 1-tick staleness on other motion
     *  is absorbed by the SUSTAIN window + the forward-speed approach exemption. */
    private fun wallAheadCached(
        world: ClientWorld?, ctx: WallSprintContext,
        x: Double, y: Double, z: Double, dx: Double, dz: Double, tick: Int,
    ): Boolean {
        if (ctx.wallAheadTick == -10000 || tick - ctx.wallAheadTick >= BlockLookupBudget.RATE_LIMIT_N) {
            ctx.wallAhead = WorldQueries.isWallAhead(world, x, y, z, dx, dz, WALL_REACH)
            ctx.wallAheadTick = tick
        }
        return ctx.wallAhead
    }

    private class WallSprintContext : CheckContext() {
        var streak: Int = 0
        /** Tick the [wallAhead] verdict was last computed (rate-limit verdict-cache). */
        var wallAheadTick: Int = -10000
        /** Cached `isWallAhead` verdict (rate-limit verdict-cache). */
        var wallAhead: Boolean = false
    }

    private companion object {
        /** Reach (blocks) ahead to test for a wall — 0.9 means the player is pressed against it. */
        private const val WALL_REACH = 0.9
        /** Max forward speed (b/t) along the facing while still "pinned" — above this is an approach. */
        private const val FORWARD_MAX = 0.05
        /** Exempt this many ticks after a hurt — knockback pins a sprinter against a wall. */
        private const val HURT_EXEMPT_TICKS = 5
        /** Window (ticks) after a server-wide freeze within which wall-sprint samples are skipped. */
        private const val LAG_WINDOW = 8
        /** Window (ticks) after a batched catch-up burst within which wall-sprint samples are skipped. */
        private const val BURST_WINDOW = 3
    }
}
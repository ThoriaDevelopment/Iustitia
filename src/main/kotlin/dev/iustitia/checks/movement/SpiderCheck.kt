package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.history.Evidence
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.world.WorldQueries
import net.minecraft.client.MinecraftClient
import net.minecraft.client.world.ClientWorld
import java.util.ArrayDeque
import java.util.UUID

/**
 * Spider / wall-climb detector (AvA `Spider` + NCM `ConstantClimb`, plan §4 #6 / §8 step 12).
 * Two sub-flags share the `spider` VL pool:
 *
 * 1. **Spider** (AvA): the cheat sticks to a wall and ascends it — `Δy > 0` while airborne,
 *    with a solid NON-climbable block beside the player, accumulated over `> [SPIDER_TICKS]`
 *    consecutive ticks. Vanilla wall-adjacent motion can't sustain ascent: a jump beside a
 *    wall ascends for ~6 ticks then falls (Δy goes negative, breaking the streak), and a
 *    ladder/vine/scaffold climb is excluded by the `notClimbable` gate. Only a spider cheat
 *    produces `> 10` consecutive ascending-ticks against a non-climbable wall.
 *
 * 2. **Spider(ConstantClimb)** (NCM): a constant upward YSpeed off-ground against a
 *    non-climbable wall. Vanilla ascent is a decelerating arc (jump: 0.42, 0.39, 0.36, …) —
 *    `Δy` shrinks each tick. A spider cheat sets a fixed upward velocity, so `Δy` is roughly
 *    constant across a window. Flag when the last `[CLIMB_WINDOW]` ascending Δy samples are
 *    all within a `[CLIMB_BAND]` range (constant) and within `[CLIMB_MIN_DY]..[CLIMB_MAX_DY]`
 *    (a real spider band; the `>0.1` floor excludes Levitation's ~0.05/tick constant rise,
 *    and the `<0.6` ceiling excludes a jump's first impulse). The non-climbable-wall gate
 *    + liquid/vehicle/elytra/swim exemptions make this spider-specific (not a fly/levitation
 *    duplicate — `flyEnvelope` owns hover/ascend in the open).
 *
 * Both sub-flags require a solid non-climbable wall beside the player (the 4 face-adjacent
 * columns at feet + torso). Liquid at the feet → exempt (water/swim ascent is legit).
 * Chunk-unloaded → `nearSolidWall` fail-opens to false → no flag (chunk-unloaded-never-FP).
 * Vehicle/gliding/riptide/swimming exempt; teleport exempt; server-lag pause.
 * setbackVL 5, decay 0.5/tick. Constants tuned in step 14.
 */
class SpiderCheck : Check() {

    override val id: String = "spider"

    override fun newContext(uuid: UUID): CheckContext = SpiderContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding || tp.riptide || tp.swimming) return
            val ctx = contextOf(tp.uuid) as SpiderContext
            if (tick - tp.lastTeleportTick < 10) { reset(ctx); return }
            // Server-lag pause: a catch-up snap can inject a few ascending Δy ticks against a
            // wall — not a cheat. Pause both sub-flags (don't reset — lag doesn't clear state).
            if (tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW ||
                tick - EntityTrackerManager.lastLagBurstTick <= BURST_WINDOW
            ) return
            // Distant-player skip: beyond the observation range the fine wall-climb signal isn't
            // usefully observable and the per-tick block lookups are not worth it. Reset (like the
            // chunk-unloaded branch) so a stale streak can't flag on re-approach.
            if (BlockLookupBudget.beyondObserveRange(tp)) { reset(ctx); return }
            val world = MinecraftClient.getInstance().world
            val bx = Math.floor(tp.pos.x).toInt()
            val bz = Math.floor(tp.pos.z).toInt()
            val by = Math.floor(tp.pos.y).toInt()
            if (world == null || !tp.chunkLoadedCached(world, tick, bx, bz)) { reset(ctx); return }
            // Liquid at the feet → legit water/bubble-column ascent; exempt (and reset, since a
            // transition out of water shouldn't inherit a stale streak).
            if (tp.feetLiquidCached(world, tick, bx, by, bz)) { reset(ctx); return }
            val offGround = !tp.onGroundPacket && !tp.groundedProxy
            val dy = tp.deltaY
            val nearWall = nearWallCached(world, ctx, bx, by, bz, tick)

            // ---- Spider (AvA): sustained ascending-ticks against a non-climbable wall ----
            if (offGround && dy > SPIDER_MIN_DY && nearWall) {
                ctx.spiderTicks++
                val need = cfg.threshold.toInt().coerceAtLeast(1)
                if (ctx.spiderTicks > need) {
                    flag(tp, ctx, 1.0, "Spider", tick, Evidence(
                        subLabel = "wall-climb", measurement = dy, threshold = SPIDER_MIN_DY,
                        pos = tp.pos, extra = "ticks=${ctx.spiderTicks}"))
                }
            } else {
                ctx.spiderTicks = 0
            }

            // ---- Spider(ConstantClimb) (NCM): constant upward YSpeed against the wall ----
            if (offGround && nearWall && dy > CLIMB_MIN_DY && dy < CLIMB_MAX_DY) {
                pushFront(ctx.climbRing, dy, CLIMB_WINDOW)
                if (ctx.climbRing.size >= CLIMB_WINDOW) {
                    val mx = ctx.climbRing.maxOrNull() ?: 0.0
                    val mn = ctx.climbRing.minOrNull() ?: 0.0
                    // all ascending + constant within CLIMB_BAND (a decelerating jump arc spans
                    // ~0.15, far wider than CLIMB_BAND, so legit jumps never match).
                    if (mn > 0.0 && (mx - mn) < CLIMB_BAND) {
                        flag(tp, ctx, 1.0, "Spider(ConstantClimb)", tick, Evidence(
                            subLabel = "constant-yspeed", measurement = (mx - mn),
                            threshold = CLIMB_BAND, pos = tp.pos,
                            extra = "min=${"%.4f".format(mn)} max=${"%.4f".format(mx)} n=${ctx.climbRing.size}"))
                    }
                }
            } else {
                ctx.climbRing.clear()
            }
        } catch (_: Throwable) {}
    }

    private fun reset(ctx: SpiderContext) {
        ctx.spiderTicks = 0
        ctx.climbRing.clear()
    }

    /**
     * True iff any of the 4 face-adjacent columns at the player's feet OR torso level is a
     * solid, NON-climbable block (the wall being climbed). Climbable blocks (ladder/vine/
     * scaffolding) return false so a legit ladder climb is exempt. Diagonals are excluded —
     * a wall the player is climbing is at a face, and diagonals add corner-block noise.
     */
    private fun nearSolidNonClimbableWall(world: ClientWorld, bx: Int, by: Int, bz: Int): Boolean {
        for (d in FACE_NEIGHBORS) {
            val x = bx + d[0]
            val z = bz + d[1]
            for (band in intArrayOf(0, 1)) {
                val y = by + band
                if (WorldQueries.isSolidAt(world, x, y, z) && !WorldQueries.isClimbableAt(world, x, y, z)) return true
            }
        }
        return false
    }

    /** [nearSolidNonClimbableWall] verdict, recomputed at most every [BlockLookupBudget.RATE_LIMIT_N]
     *  ticks and reused in between (per-player cache in [SpiderContext]). The per-tick dy/streak
     *  logic below runs every tick on this cached verdict; for a wall-climber (stationary
     *  horizontally) the verdict is stable, and 1-tick staleness on other motion is absorbed by
     *  the SPIDER_TICKS / CLIMB_WINDOW windows. */
    private fun nearWallCached(world: ClientWorld, ctx: SpiderContext, bx: Int, by: Int, bz: Int, tick: Int): Boolean {
        if (ctx.nearWallTick == -10000 || tick - ctx.nearWallTick >= BlockLookupBudget.RATE_LIMIT_N) {
            ctx.nearWall = nearSolidNonClimbableWall(world, bx, by, bz)
            ctx.nearWallTick = tick
        }
        return ctx.nearWall
    }

    private fun <T> pushFront(deque: ArrayDeque<T>, value: T, cap: Int) {
        deque.addFirst(value)
        while (deque.size > cap) deque.removeLast()
    }

    private class SpiderContext : CheckContext() {
        /** Consecutive ascending-ticks against a non-climbable wall (AvA Spider accumulator). */
        var spiderTicks: Int = 0
        /** Ring of recent ascending Δy samples (NCM ConstantClimb constant-band test). */
        val climbRing: ArrayDeque<Double> = ArrayDeque()
        /** Tick the [nearWall] verdict was last computed (rate-limit verdict-cache). */
        var nearWallTick: Int = -10000
        /** Cached `nearSolidNonClimbableWall` verdict (rate-limit verdict-cache). */
        var nearWall: Boolean = false
    }

    companion object {
        /** The 4 face-adjacent offsets (E, W, S, N). */
        private val FACE_NEIGHBORS: Array<IntArray> = arrayOf(
            intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1)
        )
        // -- Spider (AvA) --
        /** Min Δy (b/t) to count as an ascending tick — excludes noise / near-zero hover. */
        const val SPIDER_MIN_DY = 0.05
        // -- Spider(ConstantClimb) (NCM) --
        /** Min Δy (b/t) for the constant-climb band — excludes Levitation (~0.05/tick). */
        const val CLIMB_MIN_DY = 0.1
        /** Max Δy (b/t) for the constant-climb band — excludes a jump's first 0.42 impulse. */
        const val CLIMB_MAX_DY = 0.6
        /** Window size for the constant-Δy ring. */
        const val CLIMB_WINDOW = 8
        /** Max (max-min) Δy spread across the window to be "constant" — a jump arc spans ~0.15. */
        const val CLIMB_BAND = 0.08
        /** Window (ticks) after a server-wide freeze within which spider samples are skipped. */
        const val LAG_WINDOW = 8
        /** Window (ticks) after a batched catch-up burst within which spider samples are skipped. */
        const val BURST_WINDOW = 3
    }
}
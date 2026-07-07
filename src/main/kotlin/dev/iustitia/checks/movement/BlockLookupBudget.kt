package dev.iustitia.checks.movement

import dev.iustitia.tracking.TrackedPlayer
import net.minecraft.client.MinecraftClient

/**
 * Shared perf budget for the per-player block-lookup movement checks (Spider / WallSprint /
 * SprintHack) — the dense-server FPS lever (see plan). Two knobs:
 *
 * 1. [beyondObserveRange] — skip a player beyond [OBSERVE_RANGE] blocks (horizontal) from the
 *    local player. The fine block-interaction these checks detect (wall-climb, water-sprint,
 *    wall-sprint) is not usefully observable at range, and skipping far players removes their
 *    per-tick block-lookup cost. Coverage tradeoff (user-accepted): a cheater beyond the range
 *    is not flagged by these three checks until they approach. Fail-open: a null local player
 *    (loading screen) → return false (don't skip — preserve detection).
 * 2. [RATE_LIMIT_N] — the expensive block-PRESENCE verdicts (Spider's `nearSolidNonClimbableWall`
 *    and WallSprint's `isWallAhead`) are recomputed every Nth tick and reused on the in-between
 *    ticks (a verdict-cache held in each check's own context). Per-tick delta / streak / window
 *    logic STILL runs every tick on the cached verdict, so NO threshold/window rescaling is
 *    needed — only the verdict is up to (N-1) ticks stale. For the target cheats the player is
 *    pressed stationary against the wall, so the verdict is stable; for non-target movement a
 *    1-tick-stale verdict is noise absorbed by the sustained-detection windows. Set `RATE_LIMIT_N
 *    = 1` to disable (recompute every tick = original behavior). SprintHack is NOT verdict-cached
 *    (its `isLiquidAt` lookups are cheap `getBlockState`+`isOf`, not `getCollisionShape`) — it
 *    uses only [beyondObserveRange] + the TrackedPlayer query dedup.
 *
 * Single-threaded client tick — no synchronization needed. Tunable; runtime-only-verifiable.
 */
object BlockLookupBudget {
    /** Horizontal distance (blocks) within which the block-lookup checks observe a player.
     *  64 = 4 chunks (clear observation range). Increase to widen coverage at FPS cost. */
    const val OBSERVE_RANGE: Double = 64.0
    val OBSERVE_RANGE_SQ: Double = OBSERVE_RANGE * OBSERVE_RANGE

    /** Recompute the expensive block-presence verdict every Nth tick (N=2 halves the cost).
     *  1 = every tick (original behavior). */
    const val RATE_LIMIT_N: Int = 2

    /** True iff [tp] is beyond the observation range (skip the block-lookup checks for it).
     *  Fail-open: null/throwing local player → false (don't skip). */
    fun beyondObserveRange(tp: TrackedPlayer): Boolean = try {
        val self = MinecraftClient.getInstance().player ?: return false
        val dx = tp.pos.x - self.x
        val dz = tp.pos.z - self.z
        dx * dx + dz * dz > OBSERVE_RANGE_SQ
    } catch (_: Throwable) { false }
}
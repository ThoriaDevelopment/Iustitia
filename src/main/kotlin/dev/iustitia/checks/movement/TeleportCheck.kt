package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot

/**
 * VClip / SlyPort / InfiniteAura detector (Teleport). A single-tick vertical clip |Δy| >
 * [threshold] (1.5) or horizontal teleport > 2×threshold (3.0), with no jump/knockback/
 * velocity source. The >8-block server-teleport heuristic (in EntityTrackerManager) already
 * marks [TrackedPlayer.lastTeleportTick] this tick, so we skip those (they're known server
 * teleports, not clips) and only flag the 1.5–8 block range.
 *
 * Continuity gate: a clip is a *discontinuity* — the player was level last tick (|prevDeltaY|
 * small) and jumps a large distance this tick. A continued fall (prevDeltaY already
 * substantial) that looks big only because a lag-burst collapsed two fall ticks into one
 * observed tick is NOT a clip — we exempt |prevDeltaY| > 0.5. This cuts the lag-burst-fall
 * false positives (descending stairs/blocks under lag) while keeping a standing VClip
 * (prevDeltaY ≈ 0).
 *
 * One-off legit teleports (ender pearl / chorus fruit landing in this range) produce a
 * single low-level flag that decay (0.5) washes out below setbackVL — a real VClip/SlyPort
 * cheat repeats and climbs past setbackVL. setbackVL 5, decay 0.5/tick.
 */
class TeleportCheck : Check() {

    override val id: String = "teleport"

    override fun newContext(uuid: UUID): CheckContext = TeleportContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding || tp.riptide) return
            if (tp.lastTeleportTick == tick) return // already-classified >8b server teleport
            if (tick - tp.hurtTick < 3) return // knockback can launch >1.5 vertically
            if (tick - tp.velocityTick < 20) return
            // Server lag burst: ≥3 players snapped >2b in the same tick (batched catch-up
            // after a hitch). A single-player clip never sets this. Exempt so a server
            // hitch doesn't VClip/SlyPort-flag every player's catch-up movement.
            if (tick - EntityTrackerManager.lastLagBurstTick <= 1) return
            val ctx = contextOf(tp.uuid) as TeleportContext
            val dy = abs(tp.deltaY)
            val horiz = hypot(tp.delta.x, tp.delta.z)
            val vThresh = cfg.threshold
            val hThresh = cfg.threshold * 2.0
            // continuity: a clip jumps from level (prevDeltaY small). A continued fall
            // (prevDeltaY already large) is a lag-burst collapse, not a clip.
            val discontinuity = abs(tp.prevDeltaY) < 0.5
            if (dy > vThresh && discontinuity) {
                flag(tp, ctx, 1.0, "VClip", tick)
            } else if (horiz > hThresh && discontinuity) {
                flag(tp, ctx, 1.0, "SlyPort", tick)
            }
        } catch (_: Throwable) {}
    }

    private class TeleportContext : CheckContext()
}
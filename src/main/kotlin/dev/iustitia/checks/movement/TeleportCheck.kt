package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import dev.iustitia.checks.LagWindows

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
 * One-off legit teleports (ender pearl / chorus fruit landing in this range) never sustain
 * the clip pattern — the episode gate requires ≥[CLIP_MIN] clips inside [CLIP_WINDOW] ticks
 * and alerts once at setbackVL+1.0, so a repeat VClip/SlyPort is reported while a pearl or a
 * jittery-session catch-up snap is not. setbackVL 5, decay 0.5/tick.
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
            // hitch doesn't VClip/SlyPort-flag every player's catch-up movement. Same
            // LagWindows.BURST_WINDOW (3) the other movement checks use — the old `<= 1` left 2 ticks
            // of post-burst catch-up snaps unexempted.
            if (tick - EntityTrackerManager.lastLagBurstTick <= LagWindows.BURST_WINDOW) return
            val ctx = contextOf(tp.uuid) as TeleportContext
            val dy = abs(tp.deltaY)
            val horiz = hypot(tp.delta.x, tp.delta.z)
            val vThresh = cfg.threshold
            val hThresh = cfg.threshold * 2.0
            // continuity: a clip jumps from level (prevDeltaY small). A continued fall
            // (prevDeltaY already large) is a lag-burst collapse, not a clip.
            val discontinuity = abs(tp.prevDeltaY) < 0.5
            val clipV = dy > vThresh && discontinuity
            val clipH = !clipV && horiz > hThresh && discontinuity
            // Episode-gated: a flat 1.0 per clip against a 0.5/tick decay only ever climbed
            // for an every-other-tick InfiniteAura — a 1-per-second SlyPort (still a blatant
            // cheat) pinned VL at ~1 forever, while jittery-session catch-up snaps could
            // nibble VL onto clean players. ≥[CLIP_MIN] clips within [CLIP_WINDOW] ticks →
            // one episode alert at setbackVL+1.0; a one-off ender-pearl / chorus landing in
            // the 1.5–8b range never sustains.
            val sustainedNow = sustained(ctx, clipV || clipH, CLIP_WINDOW, CLIP_MIN)
            if (sustainedNow) {
                flagEpisode(tp, ctx, if (clipV) "VClip" else "SlyPort", tick)
            } else {
                rearmEpisode(ctx, sustainedNow)
            }
        } catch (_: Throwable) {}
    }

    private class TeleportContext : CheckContext()

    private companion object {
        /** Rolling window (ticks) of clip samples the teleport episode is judged over. */
        private const val CLIP_WINDOW = 20
        /** Clips required inside the window to confirm a VClip/SlyPort episode. */
        private const val CLIP_MIN = 2
    }
}
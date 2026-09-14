package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.math.AimGeometry
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import kotlin.math.abs

/**
 * Max-rotation-rate / snap detector. Flags a single-tick yaw rotation larger than
 * [IustitiaConfig.CheckConfig.threshold] (default 150°) that comes out of a near-still tick
 * (previous wrapped delta < 30°) — a classic aimbot snap from rest. The delta is measured as
 * the **shortest angular distance** ([AimGeometry.wrapDegrees]) so a legitimate turn that
 * crosses the ±180° boundary (179°→-179°, raw Δ -358°, actual 2°) does NOT false-flag: its
 * wrapped delta is ~2°, not 358°. (The old raw-delta form caught "long-way" aimbot snaps, but
 * client-side those are indistinguishable from a legit boundary crossing — both have a tiny
 * real rotation — so that detection was FP-prone and is dropped.) Since the wrapped delta is
 * in [-180, 180), the threshold must stay < 180. setbackVL 5, decay 0.5/tick.
 */
class AimWrapCheck : Check() {

    override val id: String = "aimWrap"

    override fun newContext(uuid: UUID): CheckContext = AimWrapContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            val ctx = contextOf(tp.uuid) as AimWrapContext
            // shortest-path rotation this tick — boundary crossings give ~0, not ±358
            val wrappedDelta = AimGeometry.wrapDegrees(tp.yaw - tp.lastYaw)
            // Exemptions: a server teleport, a server-wide lag burst, or knockback can inject a
            // >threshold wrapped-yaw delta out of a near-still prior tick. Exempt ticks still
            // update lastWrappedDelta (below) so the first post-exempt tick compares against the
            // immediately-prior tick, not a stale pre-teleport value.
            val exempt = tick - tp.lastTeleportTick < 5 ||
                tick - tp.hurtTick < 3 ||
                tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW ||
                tick - EntityTrackerManager.lastLagBurstTick <= BURST_WINDOW
            if (!exempt && abs(ctx.lastWrappedDelta) < 30.0) {
                // A snap opportunity: the previous tick was near-still, so this tick's rotation is
                // judged on its own. Ticks that follow a large rotation are not opportunities --
                // the check is a "snap out of rest" detector, and every large delta would
                // otherwise be counted twice (once on the way out, once on the way back).
                judge(tp, ctx, tick, abs(wrappedDelta) > cfg.threshold)
            }
            ctx.lastWrappedDelta = wrappedDelta.toDouble()
        } catch (_: Throwable) {}
    }

    /**
     * Record one snap opportunity's verdict and alert once when the pattern is sustained.
     *
     * A single snap is flagged at `1.0` against a `0.5`/tick decay, and a snap has to be
     * followed by a near-still tick before the next one can be judged -- so the maximum flag rate
     * is `0.5`/tick, exactly the decay: the VL pinballs around 1.0 and a real aimbot snap never
     * alerts. Requiring the pattern (see [Check.sustained]) and flagging the episode once at a
     * level that clears setbackVL is what turns it into an alert, while a single human flick that
     * happens to be extreme stays a sub-threshold flag.
     */
    private fun judge(tp: TrackedPlayer, ctx: AimWrapContext, tick: Int, snapped: Boolean) {
        val sustainedNow = sustained(ctx, snapped, WINDOW, MIN_VIOLATIONS)
        if (sustainedNow) flagEpisode(tp, ctx, "AimWrap", tick) else rearmEpisode(ctx, sustainedNow)
    }

    private class AimWrapContext : CheckContext() {
        var lastWrappedDelta: Double = 0.0
    }

    private companion object {
        /** Window (ticks) after a server-wide freeze within which rotation snaps are exempt. */
        private const val LAG_WINDOW = 8
        /** Window (ticks) after a batched catch-up burst within which rotation snaps are exempt. */
        private const val BURST_WINDOW = 3
        /** Rolling window of snap opportunities the episode is judged over. */
        private const val WINDOW = 8
        /** Snaps required in the window. The per-event rotation (>=150 deg in one tick) is already
         *  well past human reaction, so 3 of 8 is generous to the cheater and still cannot be
         *  reached by a legitimate flick, which cannot repeat the superhuman delta. */
        private const val MIN_VIOLATIONS = 3
    }
}
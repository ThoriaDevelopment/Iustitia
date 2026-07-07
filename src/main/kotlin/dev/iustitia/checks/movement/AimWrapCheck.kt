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
            if (!exempt && abs(wrappedDelta) > cfg.threshold && abs(ctx.lastWrappedDelta) < 30.0) {
                flag(tp, ctx, 1.0, "AimWrap", tick)
            }
            ctx.lastWrappedDelta = wrappedDelta.toDouble()
        } catch (_: Throwable) {}
    }

    private class AimWrapContext : CheckContext() {
        var lastWrappedDelta: Double = 0.0
    }

    private companion object {
        /** Window (ticks) after a server-wide freeze within which rotation snaps are exempt. */
        private const val LAG_WINDOW = 8
        /** Window (ticks) after a batched catch-up burst within which rotation snaps are exempt. */
        private const val BURST_WINDOW = 3
    }
}
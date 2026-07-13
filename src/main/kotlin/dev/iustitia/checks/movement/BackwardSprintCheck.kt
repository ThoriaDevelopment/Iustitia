package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.math.Vectors
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot

/**
 * BackwardSprint / OmniSprint detector. Vanilla cannot sprint backward — the game blocks
 * it. The OmniSprint module forces sprint while moving backward (or strafe-only). We
 * compute the player's movement direction vs their broadcast yaw and classify the
 * horizontal delta into ONE band per tick:
 *  - **backward** (existing): a negative component along the look direction (moving away
 *    from facing) while sprinting and horizontal speed exceeds [threshold] (1.0 bps).
 *  - **strafe** (added): the delta is near-pure sideways relative to facing (angle from
 *    facing ∈ ~[75°, 91°]) while sprinting. Vanilla requires the W (forward) input to
 *    sprint, so a sprint with a near-zero forward component is impossible legitimately —
 *    exactly the OmniSprint "Sprint OmniSprint" module (Meteor) / LB Sprint strafe mode.
 *    The strafe band stops at 91° (the backward branch's `dot < -0.02·horiz` line); the two
 *    branches are non-overlapping, so a tick accrues to at most one streak. Sprint-swim
 *    (`tp.swimming`) is exempt on the strafe branch only — sprint-swimming sideways is
 *    vanilla, and sprint-in-water is already [SprintHackCheck]'s domain.
 *
 * BLATANT-ONLY by design (user request: only flag really blatant omnisprint, cut the log
 * spam). Two FP sources that previously flooded verbose:
 *  - **Knockback**: a sprinting player taking a hit is pushed backward by KB while their
 *    yaw still faces the attacker → `dot < 0` reads as "backward sprint." That's a vanilla
 *    mechanic, not a cheat. Exempt for [HURT_EXEMPT_TICKS] after a hurt (uses [TrackedPlayer.hurtTick],
 *    the same KB marker the other combat checks exempt on). This was the root cause of the
 *    systemic FP where a whole lobby flagged at once on PvP servers.
 *  - **Brief blips**: single-tick yaw-lag / strafe edges. The old 2-tick gate fired on these.
 *    Raised to [BLATANT_SUSTAIN] = 6 consecutive ticks (0.3s). Vanilla can't sprint backward
 *    at all, so 6 sustained KB-free ticks is unambiguous; a legit player never reaches it.
 *    The strafe branch uses the same 6-tick gate ([STRAFE_SUSTAIN]).
 *
 * setbackVL 5, decay 0.5/tick. A true omnisprint cheater sustains the pattern continuously,
 * so once a streak reaches its gate it flags every tick and VL accrues (+0.5/tick net) to a
 * chat alert in ~10 ticks — exactly what you want for a blatant cheat.
 */
class BackwardSprintCheck : Check() {

    override val id: String = "backwardSprint"

    override fun newContext(uuid: UUID): CheckContext = BackwardSprintContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle) return
            val ctx = contextOf(tp.uuid) as BackwardSprintContext
            if (!tp.sprinting) { ctx.streak = 0; ctx.strafeStreak = 0; return }
            // KB exemption: knockback pushes a sprinting player backward while their yaw faces
            // the attacker — vanilla mechanic, not OmniSprint. Skip for a few ticks after a hit.
            if (tick - tp.hurtTick <= HURT_EXEMPT_TICKS) { ctx.streak = 0; ctx.strafeStreak = 0; return }
            // Server-lag exemption: a server-wide hitch / catch-up burst injects a horizontal
            // impulse that can push a sprinting player backward-of-facing / sideways for a few
            // ticks. Pause the streaks (don't reset — lag doesn't clear a cheater's sprint state).
            if (tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW ||
                tick - EntityTrackerManager.lastLagBurstTick <= BURST_WINDOW
            ) return
            val horiz = hypot(tp.delta.x, tp.delta.z)
            if (horiz * 20.0 < cfg.threshold) { ctx.streak = 0; ctx.strafeStreak = 0; return }
            // look direction (horizontal, unit length): dot = horiz·cos(θ), cross = horiz·sin(θ)
            // where θ is the angle from facing to the movement vector.
            val look = Vectors.lookVector(tp.yaw.toDouble(), 0.0)
            val dot = tp.delta.x * look.x + tp.delta.z * look.z
            if (dot < -0.02 * horiz) {
                // backward (θ > ~91°) — moving away from facing. Unchanged from the original gate.
                ctx.streak++
                ctx.strafeStreak = 0
                if (ctx.streak >= BLATANT_SUSTAIN) flag(tp, ctx, 1.0, "BackwardSprint", tick)
            } else if (!tp.swimming &&
                abs(dot) <= STRAFE_FORWARD_FRAC * horiz &&
                abs(tp.delta.x * look.z - tp.delta.z * look.x) >= STRAFE_SIDEWAYS_FRAC * horiz
            ) {
                // strafe (θ ∈ ~[75°, 91°]) — near-pure sideways while sprint. Vanilla needs W to
                // sprint, so a near-zero forward component is impossible; this is OmniSprint's
                // strafe mode. Exempted from the backward branch by the `dot >= -0.02·horiz` guard
                // above, so the two streaks never both accrue for one tick.
                ctx.strafeStreak++
                ctx.streak = 0
                if (ctx.strafeStreak >= STRAFE_SUSTAIN) flag(tp, ctx, 1.0, "BackwardSprint(strafe)", tick)
            } else {
                ctx.streak = 0
                ctx.strafeStreak = 0
            }
        } catch (_: Throwable) {}
    }

    private class BackwardSprintContext : CheckContext() {
        var streak: Int = 0
        var strafeStreak: Int = 0
    }

    companion object {
        /** Exempt this many ticks after a hurt — KB can push a sprinting player backward. */
        private const val HURT_EXEMPT_TICKS = 5
        /** Consecutive backward-sprint ticks required to flag (blatant-only; vanilla can't do 1). */
        private const val BLATANT_SUSTAIN = 6
        /** Consecutive strafe-sprint ticks required to flag (same gate as backward; vanilla can't strafe-sprint). */
        private const val STRAFE_SUSTAIN = 6
        /** Max forward component of the movement (as a fraction of horiz) still counted as "strafe" —
         *  cos(75°) ≈ 0.259. Movement within 75° of pure-sideways qualifies. */
        private const val STRAFE_FORWARD_FRAC = 0.259
        /** Min sideways component (as a fraction of horiz) for "strafe" — sin(75°) ≈ 0.966. Ensures
         *  the motion is dominated by the sideways axis, not a forward/sideways diagonal sprint. */
        private const val STRAFE_SIDEWAYS_FRAC = 0.966
        /** Window (ticks) after a server-wide freeze within which omnisprint samples are skipped. */
        private const val LAG_WINDOW = 8
        /** Window (ticks) after a batched catch-up burst within which omnisprint samples are skipped. */
        private const val BURST_WINDOW = 3
    }
}
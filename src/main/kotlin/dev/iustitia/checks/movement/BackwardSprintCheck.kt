package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.math.Vectors
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import kotlin.math.hypot

/**
 * BackwardSprint / OmniSprint detector. Vanilla cannot sprint backward — the game blocks
 * it. The OmniSprint module forces sprint while moving backward (or strafe-only). We
 * compute the player's movement direction vs their broadcast yaw: if the horizontal delta
 * has a negative component along the look direction (moving away from facing) while the
 * sprinting metadata is active and horizontal speed exceeds [threshold] (1.0 bps), flag.
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
 *
 * setbackVL 5, decay 0.5/tick. A true omnisprint cheater sustains backward-sprint
 * continuously, so once streak reaches the gate it flags every tick and VL accrues
 * (+0.5/tick net) to a chat alert in ~10 ticks — exactly what you want for a blatant cheat.
 */
class BackwardSprintCheck : Check() {

    override val id: String = "backwardSprint"

    override fun newContext(uuid: UUID): CheckContext = BackwardSprintContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle) return
            val ctx = contextOf(tp.uuid) as BackwardSprintContext
            if (!tp.sprinting) { ctx.streak = 0; return }
            // KB exemption: knockback pushes a sprinting player backward while their yaw faces
            // the attacker — vanilla mechanic, not OmniSprint. Skip for a few ticks after a hit.
            if (tick - tp.hurtTick <= HURT_EXEMPT_TICKS) { ctx.streak = 0; return }
            val horiz = hypot(tp.delta.x, tp.delta.z)
            if (horiz * 20.0 < cfg.threshold) { ctx.streak = 0; return }
            // look direction (horizontal); movement dot look < 0 ⇒ moving backward
            val look = Vectors.lookVector(tp.yaw.toDouble(), 0.0)
            val dot = tp.delta.x * look.x + tp.delta.z * look.z
            if (dot < -0.02 * horiz) {
                ctx.streak++
                if (ctx.streak >= BLATANT_SUSTAIN) flag(tp, ctx, 1.0, "BackwardSprint", tick)
            } else {
                ctx.streak = 0
            }
        } catch (_: Throwable) {}
    }

    private class BackwardSprintContext : CheckContext() {
        var streak: Int = 0
    }

    companion object {
        /** Exempt this many ticks after a hurt — KB can push a sprinting player backward. */
        private const val HURT_EXEMPT_TICKS = 5
        /** Consecutive backward-sprint ticks required to flag (blatant-only; vanilla can't do 1). */
        private const val BLATANT_SUSTAIN = 6
    }
}
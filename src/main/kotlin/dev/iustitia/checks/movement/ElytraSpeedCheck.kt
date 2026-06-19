package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot

/**
 * ElytraFly detector (ElytraSpeed). Vanilla elytra level flight caps ~1.5 blocks/tick (30
 * bps) and a firework boost spikes it briefly (~40–50 bps for 1–2 ticks). ElytraFly cheats
 * sustain a boosted glide well beyond that. We flag when [TrackedPlayer.gliding] is active
 * for ≥3 sustained ticks of either:
 *  - **Blatant**: horizontal bps exceeds [threshold] (40 ≈ 2.0 b/t) — any sustained glide past
 *    40 is a cheat regardless of pitch.
 *  - **Pitch-vs-speed anomaly**: bps exceeds [threshold]×0.85 (~34) while pitch is near level
 *    (< [LEVEL_PITCH]). Vanilla elytra only reaches 34+ bps in a *steep dive* (large |pitch|);
 *    sustaining 34+ at near-level pitch is aerodynamically impossible without a fly cheat, so
 *    this catches the 30–39 bps ElytraFly tier the blatant-only form missed — with no FP,
 *    since a legit 34+ bps glide always has a steep pitch (and thus doesn't trip the anomaly).
 * The 3-tick sustain gate absorbs momentary firework-boost spikes. setbackVL 5, decay 1/tick.
 */
class ElytraSpeedCheck : Check() {

    override val id: String = "elytraSpeed"

    override fun newContext(uuid: UUID): CheckContext = ElytraContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle) return
            val ctx = contextOf(tp.uuid) as ElytraContext
            if (!tp.gliding) { ctx.streak = 0; return }
            if (tick - tp.lastTeleportTick < 5) { ctx.streak = 0; return }
            val bps = hypot(tp.delta.x, tp.delta.z) * 20.0
            val blatant = bps > cfg.threshold
            val levelFast = bps > cfg.threshold * ANOMALY_FACTOR && abs(tp.pitch.toDouble()) < LEVEL_PITCH
            if (blatant || levelFast) {
                ctx.streak++
                if (ctx.streak >= 3) flag(tp, ctx, 1.0, "ElytraSpeed", tick)
            } else {
                ctx.streak = 0
            }
        } catch (_: Throwable) {}
    }

    private class ElytraContext : CheckContext() {
        var streak: Int = 0
    }

    private companion object {
        /** Fraction of [threshold] at which near-level-pitch gliding becomes impossible
         *  in vanilla (the pitch-vs-speed anomaly tier). */
        const val ANOMALY_FACTOR = 0.85
        /** Max |pitch| (deg) considered "near level" for the anomaly. */
        const val LEVEL_PITCH = 8.0
    }
}
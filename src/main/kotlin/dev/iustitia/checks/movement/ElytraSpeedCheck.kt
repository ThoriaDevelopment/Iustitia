package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import kotlin.math.hypot

/**
 * ElytraFly detector (ElytraSpeed). Vanilla elytra level flight caps ~1.5 blocks/tick (30
 * bps) and a firework boost spikes it briefly (~40–50 bps for 1–2 ticks). ElytraFly cheats
 * sustain a boosted glide well beyond that. We flag only when [TrackedPlayer.gliding] is
 * active and horizontal bps exceeds [threshold] (40 ≈ 2.0 b/t) for ≥3 sustained ticks — the
 * sustain gate absorbs momentary firework-boost spikes. setbackVL 5, decay 1/tick.
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
            if (bps > cfg.threshold) {
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
}
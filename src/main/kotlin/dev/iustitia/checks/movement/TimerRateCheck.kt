package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import kotlin.math.hypot

/**
 * Timer / Boost / TickBase detector (TimerRate). The cheat accelerates its local tick rate
 * so it advances position/attacks faster than wall-clock — observable as horizontal bps far
 * above the sprint-jump envelope sustained for several ticks (a 3-tick streak gates out
 * single knockback spikes). The *packet-counting* form of this check (count
 * `EntityPositionS2CPacket` arrivals per client tick) would need a new mixin hook and is
 * confounded by server batching; this is a conservative delta-based surrogate that flags the
 * blatant sustained-overspeed tier (> [threshold], 14 bps) distinct from SpeedEnvelope's
 * cap. setbackVL 5, decay 0.5/tick (so a sustained cheater climbs past setback while a brief
 * spike washes out).
 */
class TimerRateCheck : Check() {

    override val id: String = "timerRate"

    override fun newContext(uuid: UUID): CheckContext = TimerContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding || tp.riptide || tp.swimming) return
            if (tick - tp.lastTeleportTick < 10) return
            if (tick - tp.hurtTick < 5) return
            if (tick - tp.velocityTick < 40) return
            val ctx = contextOf(tp.uuid) as TimerContext
            val bps = hypot(tp.delta.x, tp.delta.z) * 20.0
            if (bps > cfg.threshold) {
                ctx.streak++
                if (ctx.streak >= 3) flag(tp, ctx, 1.0, "Timer", tick)
            } else {
                ctx.streak = 0
            }
        } catch (_: Throwable) {}
    }

    private class TimerContext : CheckContext() {
        var streak: Int = 0
    }
}
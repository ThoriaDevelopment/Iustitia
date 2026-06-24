package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.tracking.EntityTrackerManager
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
 *
 * Server-lag exemption: a server-wide hitch / batched catch-up burst injects huge bps
 * samples into every player at once (the spawn-in burst after a world transition is exactly
 * this). We skip — and clear the streak so a pre-lag partial run can't carry into the post-lag
 * catch-up — within the same [LAG_WINDOW]/[BURST_WINDOW] SpeedEnvelope consults. This was the
 * one overspeed check without the lag gate; without it a post-hitch catch-up mass-flagged
 * Timer across the whole lobby.
 */
class TimerRateCheck : Check() {

    override val id: String = "timerRate"

    override fun newContext(uuid: UUID): CheckContext = TimerContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding || tp.riptide || tp.swimming) return
            val ctx = contextOf(tp.uuid) as TimerContext
            // Teleport exemption: a server teleport (kit-equip, /tpa, respawn) injects a huge bps
            // sample — AND a server-lag catch-up burst registers as a >8-block jump, setting
            // lastTeleportTick too. Skip AND clear the streak so a pre-teleport partial run can't
            // carry into the post-teleport catch-up overspeed and reach 3 (the false flag).
            // Mirrors SpeedEnvelope's clear-on-teleport. Done before the lag-clear below because
            // a catch-up burst sets BOTH signals, and the teleport return would otherwise shadow it.
            if (tick - tp.lastTeleportTick < 10) { ctx.streak = 0; return }
            if (tick - tp.hurtTick < 5) return
            if (tick - tp.velocityTick < 40) return
            // Server-lag exemption: skip AND clear the streak so a pre-lag partial run can't
            // combine with the post-lag catch-up to reach 3. Matches SpeedEnvelope's windows.
            if (tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW ||
                tick - EntityTrackerManager.lastLagBurstTick <= BURST_WINDOW
            ) { ctx.streak = 0; return }
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

    private companion object {
        // Mirrors SpeedEnvelope's windows: a lag tick within the last 8 ticks (or a catch-up
        // burst within 3) means bps samples are unreliable — skip Timer evaluation.
        const val LAG_WINDOW = 8
        const val BURST_WINDOW = 3
    }
}
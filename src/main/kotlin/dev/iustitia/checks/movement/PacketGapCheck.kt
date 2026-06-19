package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import kotlin.math.sqrt

/**
 * Blink / FakeLag / ScaffoldBlink / LagRange detector (PacketGap). The cheat cancels +
 * queues its own outgoing packets, so the server stops rebroadcasting its position — the
 * entity appears frozen for N ticks — then flushes the queue and the entity snaps a large
 * distance in one tick. Observer signature: a long near-zero-Δ silence (≥5 ticks) followed
 * by a single-tick move > [threshold] (2.0 blocks). Documented in Vape ("Blink"/"FakeLag")
 * and Slinky ("Blink"/"Lag Range"); Vape's Blink commonly auto-disables on attack/place/dig,
 * so the snap often lands right at a hit — but we flag the pure freeze-snap shape so
 * non-combat blinks are caught too.
 *
 * **Server-lag exemption:** a genuine server hitch freezes *every* player at once, then
 * releases them in a batched catch-up that looks identical to one player's Blink. We now
 * distinguish them via [EntityTrackerManager.lastServerLagTick] — set when a majority of
 * tracked players froze in the same tick (a single Blinker freezing alone never sets it).
 * Freezes accumulating during a server-lag window don't count, and snaps inside it aren't
 * flagged. This is the per-entity comparison the check previously couldn't do. setbackVL 5,
 * decay 0.5/tick.
 */
class PacketGapCheck : Check() {

    override val id: String = "packetGap"

    override fun newContext(uuid: UUID): CheckContext = PacketGapContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding || tp.riptide) return
            val ctx = contextOf(tp.uuid) as PacketGapContext
            if (tick - tp.lastTeleportTick < 10) { ctx.freeze = 0; return }
            if (tick - tp.hurtTick < 3) { ctx.freeze = 0; return }
            val dx = tp.delta.x
            val dy = tp.delta.y
            val dz = tp.delta.z
            val mag = sqrt(dx * dx + dy * dy + dz * dz)
            // server-wide lag window: majority of players froze recently -> this player's
            // freeze/snap is the server's batched catch-up, not a Blink.
            val serverLag = tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW
            if (mag < 0.01) {
                if (!serverLag) ctx.freeze++
            } else {
                if (ctx.freeze >= 5 && mag > cfg.threshold && !serverLag) {
                    flag(tp, ctx, 1.0, "Blink", tick)
                }
                ctx.freeze = 0
            }
        } catch (_: Throwable) {}
    }

    private class PacketGapContext : CheckContext() {
        var freeze: Int = 0
    }

    companion object {
        /** Ticks after a server-lag tick during which freezes/snaps are exempt. */
        private const val LAG_WINDOW = 8
    }
}
package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import kotlin.math.abs

/**
 * Invalid-pitch detector (BadPacketsD / NCM WrongTurn). The server normally clamps pitch to
 * ±90°, so a broadcast |pitch| > [threshold] (90) is impossible from a legit client — a cheat
 * sent an out-of-range pitch. Trivially portable, low fire-rate (servers clamp), zero FP.
 * decay 0/tick — vl is instead reset to 0 the moment pitch returns in bounds (instant reset),
 * so each violation is a discrete bad packet and a player who stops sending out-of-range pitch
 * recovers instead of sitting above setbackVL forever (which would re-alert every throttle
 * window). A real pitch cheat locks pitch >90 every tick, so it never sends an in-bounds packet
 * and vl still climbs straight to setbackVL — true-positive behaviour unchanged.
 */
class PitchBoundCheck : Check() {

    override val id: String = "pitchBound"

    override fun newContext(uuid: UUID): CheckContext = PitchContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            val ctx = contextOf(tp.uuid)
            if (abs(tp.pitch) > cfg.threshold) {
                flag(tp, ctx, 1.0, "PitchBound", tick)
            } else {
                // in bounds → instant reset (decay is 0, so without this vl was a permanent
                // high-water mark that re-alerted long after the bad packets stopped).
                ctx.vl = 0.0
            }
        } catch (_: Throwable) {}
    }

    private class PitchContext : CheckContext()
}
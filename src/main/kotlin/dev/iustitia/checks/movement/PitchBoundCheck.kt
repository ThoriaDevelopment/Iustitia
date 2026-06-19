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
 * decay 0/tick (instant reset — each violation is a discrete bad packet).
 */
class PitchBoundCheck : Check() {

    override val id: String = "pitchBound"

    override fun newContext(uuid: UUID): CheckContext = PitchContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (abs(tp.pitch) > cfg.threshold) {
                flag(tp, contextOf(tp.uuid), 1.0, "PitchBound", tick)
            }
        } catch (_: Throwable) {}
    }

    private class PitchContext : CheckContext()
}
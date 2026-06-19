package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.event.SwingSignal
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID

/**
 * AutoBlock detector, ported from Rain-Anticheat's 1.8.9 `AutoBlockCheck`. The cheat
 * holds a block/shield up (`usingItem`) while continuously swinging at enemies — on
 * 1.8 that is block-hit autoblock, on 1.21 it is shield-while-attacking. Vanilla cannot
 * swing and use an item at the same time for more than an instant, so sustained overlap
 * is the fingerprint.
 *
 * Rain tests `isSwingInProgress && isUsingItem` for >10 ticks. We don't observe
 * `isSwingInProgress` directly, so we reconstruct the swing-in-progress window from the
 * observed [SwingSignal]: a swing animation lasts ~6 ticks, so `tick - lastSwingTick <
 * SWING_TICKS` is "currently swinging". Combined with `tp.isBlocking` (polled metadata —
 * shield raised / sword blocked), a run of [threshold] (10) overlapping ticks flags.
 * Gating on `isBlocking` (UseAction.BLOCK) instead of the bare `usingItem` excludes bows
 * (BOW useAction while charging — would otherwise FP every swing during a bow draw), food
 * (EAT), and potions (DRINK); vanilla 1.21 can't attack while a shield is raised, so the
 * BLOCK + swing overlap is the cheat signature and never a legit action. setbackVL 5,
 * decay 0.5/tick.
 */
class AutoBlockCheck : Check() {

    override val id: String = "autoBlock"

    init {
        try { Iustitia.bus.subscribe<SwingSignal> { onSwing(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = AutoBlockContext()

    private fun onSwing(sig: SwingSignal) {
        try {
            (contextOf(sig.attacker) as AutoBlockContext).lastSwingTick = sig.tick
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle) return
            val ctx = contextOf(tp.uuid) as AutoBlockContext
            // lastSwingTick starts at Int.MIN_VALUE; `tick - Int.MIN_VALUE` overflows Int and
            // wraps negative, which would make `swinging` true with no swing ever observed —
            // flagging any player holding right-click (food/potion/shield) after SWING_TICKS.
            // Guard the subtraction: no swing recorded yet => not swinging.
            val swinging = ctx.lastSwingTick != Int.MIN_VALUE && tick - ctx.lastSwingTick < SWING_TICKS
            if (swinging && tp.isBlocking) {
                ctx.autoBlockTicks++
                if (ctx.autoBlockTicks > cfg.threshold) {
                    flag(tp, ctx, 1.0, "AutoBlock", tick)
                }
            } else {
                ctx.autoBlockTicks = 0
            }
        } catch (_: Throwable) {}
    }

    private class AutoBlockContext : CheckContext() {
        var lastSwingTick: Int = Int.MIN_VALUE
        var autoBlockTicks: Int = 0
    }

    companion object {
        /** Swing animation duration in ticks (~6 in 1.8; close enough to gate "swinging now"). */
        private const val SWING_TICKS = 6
    }
}
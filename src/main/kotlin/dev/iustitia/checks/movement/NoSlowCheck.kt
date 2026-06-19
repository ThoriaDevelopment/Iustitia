package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import kotlin.math.hypot

/**
 * NoSlow detector. Vanilla slows the player to ~20% while using an item (eating/drinking/
 * blocking). The NoSlow cheat cancels that, so the player sprints at full speed while the
 * using-item metadata flag is set. We flag when a NoSlow-applicable use surface is active
 * and the horizontal bps exceeds [threshold] (4.0 — well above the 0.2× cap of ~1.1 bps) for
 * ≥3 sustained ticks. setbackVL 5, decay 0.5/tick.
 *
 * Gated on [TrackedPlayer.isUsingConsumable] || [TrackedPlayer.isBlocking] — the actual
 * vanilla NoSlow surfaces (eat/drink/block) — NOT the bare `usingItem`. Bows also set
 * `usingItem` while charging, and on 1.21.x the long-standing MC-152728 sprint-while-using
 * bug (fix reverted in 25w04a, so present on our target 1.21.11) lets a legit player keep
 * sprint speed when already sprinting before drawing — so a fast bow-drawer is legit on the
 * target version and must not be flagged. Bow-NoSlow is indistinguishable from that vanilla
 * bug client-side, so excluding bows costs no reliable detection. The ice-coast case (a
 * legit eater/blocker coasting on ice retains momentum above the 0.2× cap) remains a known
 * limitation — it can't be cleanly separated from NoSlow without reading the cheater's input.
 */
class NoSlowCheck : Check() {

    override val id: String = "noSlow"

    override fun newContext(uuid: UUID): CheckContext = NoSlowContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding || tp.swimming) return
            val ctx = contextOf(tp.uuid) as NoSlowContext
            // Only the NoSlow-applicable surfaces (eat/drink/block). Bows (BOW useAction) are
            // excluded — see class doc for the 1.21 sprint-while-drawing rationale.
            if (!(tp.isUsingConsumable || tp.isBlocking)) {
                ctx.streak = 0
                return
            }
            // Hurt exemption: knockback injects a horizontal impulse that can push a legit
            // shield-blocker/eater over the bps cap for a couple ticks. Reset and skip the
            // peak (the velocity-trend/ice-coast case is a separate, deferred problem — a flat
            // cap raise isn't enough there, so this only covers the hurt spike).
            if (tick - tp.hurtTick < 3) {
                ctx.streak = 0
                return
            }
            val bps = hypot(tp.delta.x, tp.delta.z) * 20.0
            if (bps > cfg.threshold) {
                ctx.streak++
                if (ctx.streak >= 3) flag(tp, ctx, 1.0, "NoSlow", tick)
            } else {
                ctx.streak = 0
            }
        } catch (_: Throwable) {}
    }

    private class NoSlowContext : CheckContext() {
        var streak: Int = 0
    }
}
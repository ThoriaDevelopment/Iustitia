package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.history.Evidence
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import kotlin.math.hypot

/**
 * KeepSprint detector (NoSlowOnAttack). Vanilla decelerates the attacker's motion by ~0.6
 * on a hit; KeepSprint cancels that decel so the attacker keeps full speed through the
 * attack and sprint never drops. On [AttackEvent] we snapshot the attacker's pre-attack
 * horizontal speed + sprinting flag; in [process] the next tick we compare. If sprint
 * stayed on and the post-attack speed kept >80% of pre-attack (vanilla would be ~60%),
 * the hit is a violation.
 *
 * The alert is a **sustained episode**, not a per-hit accumulation: the cheapest flag cadence
 * here is exactly one per attack (the check evaluates only on the tick after a hit), which is
 * roughly one per 12 ticks at vanilla cooldown — far below the 0.5/tick decay, so per-event VL
 * could never reach setbackVL no matter how many hits the cheater kept full speed through
 * (measured live: the drive logged a flag per hit and peaked at 1.0 of 5.0). [MIN_VIOLATIONS] of
 * the last [WINDOW] hits must keep full speed with sprint held, and then the episode alerts once
 * at a level that clears setbackVL and re-arms when the pattern breaks.
 */
class KeepSprintCheck : Check() {

    override val id: String = "keepSprint"

    init {
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = KeepSprintContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            val attacker = EntityTrackerManager.get(ev.attacker) ?: return
            val ctx = contextOf(ev.attacker) as KeepSprintContext
            ctx.lastAttackTick = ev.tick
            ctx.preAttackSpeed = hypot(attacker.delta.x, attacker.delta.z)
            ctx.preSprinting = attacker.sprinting
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            val ctx = contextOf(tp.uuid) as KeepSprintContext
            if (tick - ctx.lastAttackTick != 1) return
            if (ctx.preAttackSpeed < 0.2) return // wasn't moving — nothing to decay
            if (!ctx.preSprinting) return // only meaningful if they were sprinting
            // Hurt exemption: knockback the attacker just took can keep sprint on / preserve
            // speed in a way that mimics KeepSprint for the ~2-tick peak. Skip that window.
            if (tick - tp.hurtTick < 2) return
            val speed = hypot(tp.delta.x, tp.delta.z)
            val ratio = if (ctx.preAttackSpeed > 0.0) speed / ctx.preAttackSpeed else 0.0
            // vanilla keeps ~0.6 after a hit; KeepSprint keeps >threshold (default 0.8) and sprint stays on
            val violating = tp.sprinting && ratio > cfg.threshold
            val sustainedNow = sustained(ctx, violating, WINDOW, MIN_VIOLATIONS)
            if (sustainedNow) {
                flagEpisode(tp, ctx, "KeepSprint", tick, Evidence(
                    subLabel = "speed-retained", measurement = ratio, threshold = cfg.threshold,
                    pos = tp.pos,
                    extra = "kept ${"%.0f".format(ratio * 100)}% of pre-hit speed with sprint held " +
                        "(vanilla decelerates to ~60%) on $MIN_VIOLATIONS+ of the last $WINDOW hits"))
            } else {
                rearmEpisode(ctx, sustainedNow)
            }
        } catch (_: Throwable) {}
    }

    private class KeepSprintContext : CheckContext() {
        var lastAttackTick: Int = -10000
        var preAttackSpeed: Double = 0.0
        var preSprinting: Boolean = false
    }

    private companion object {
        /** Rolling window of post-hit verdicts judged for the sustained-episode gate. */
        const val WINDOW = 4
        /** Full-speed hits required in the window (3 of the last 4) to call it sustained. */
        const val MIN_VIOLATIONS = 3
    }
}
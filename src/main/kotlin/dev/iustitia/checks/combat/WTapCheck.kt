package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import kotlin.math.abs

/**
 * WTap / SuperKnockback detector (SprintTogglePerAttack). The cheat toggles sprint state
 * (STOP→START) around each attack tick so the server applies sprint-bonus knockback to the
 * victim even though the cheater isn't really sprinting. We track the attacker's sprint
 * metadata transitions in [process] (comparing this tick's sprinting to the last), then on
 * each [AttackEvent] count transitions within ±1 tick of the attack. ≥ [threshold] (2)
 * transitions around a single attack → flag. setbackVL 5, decay 0.5/tick.
 */
class WTapCheck : Check() {

    override val id: String = "wTap"

    init {
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = WTapContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            val ctx = contextOf(tp.uuid) as WTapContext
            if (tp.sprinting != ctx.prevSprinting) {
                ctx.transitions.add(tick)
                ctx.prevSprinting = tp.sprinting
            }
            ctx.transitions.removeAll { it < tick - 10 }
        } catch (_: Throwable) {}
    }

    private fun onAttack(ev: AttackEvent) {
        try {
            val attacker = EntityTrackerManager.get(ev.attacker) ?: return
            val ctx = contextOf(ev.attacker) as WTapContext
            val need = cfg.threshold.toInt().coerceAtLeast(2)
            val near = ctx.transitions.count { abs(it - ev.tick) <= 1 }
            if (near >= need) {
                flag(attacker, ctx, 1.0, "WTap", ev.tick)
            }
        } catch (_: Throwable) {}
    }

    private class WTapContext : CheckContext() {
        var prevSprinting: Boolean = false
        val transitions = java.util.ArrayDeque<Int>()
    }
}
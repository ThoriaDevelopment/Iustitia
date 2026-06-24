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
 * each [AttackEvent] record whether ≥ [threshold] (2) transitions fell within ±1 tick of
 * that attack. A sustained macro (SuperKnockback) W-taps on nearly every hit; a legit player
 * W-taps only sometimes. We flag only when the pattern is *sustained* — ≥3 of the last 4
 * attacks each had ≥threshold near-transitions (and ≥4 attacks observed) — so a single or
 * occasional legit W-tap can't trip it. setbackVL 5, decay 0.5/tick.
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
            // record whether this attack carried the W-tap transition signature, then judge the
            // rolling window: a sustained macro hits the signature on most attacks, a legit
            // W-tapper only sometimes. Flag at ≥3 of the last 4 (with ≥4 observed).
            ctx.recent.addFirst(near >= need)
            while (ctx.recent.size > WINDOW) ctx.recent.removeLast()
            if (ctx.recent.size >= MIN_SAMPLES && ctx.recent.count { it } >= SUSTAINED_HITS) {
                flag(attacker, ctx, 1.0, "WTap", ev.tick)
            }
        } catch (_: Throwable) {}
    }

    private class WTapContext : CheckContext() {
        var prevSprinting: Boolean = false
        // transitions is mutated in process (client-tick thread: add / removeAll) AND read in
        // onAttack (network thread: count) — AttackEvent is published from the packet handler.
        // A plain ArrayDeque under concurrent modify+iterate can throw CME / corrupt; use a
        // concurrent deque (weakly-consistent iterator, fail-open already covers the rest).
        val transitions = java.util.concurrent.ConcurrentLinkedDeque<Int>()
        /** Per-attack "did this hit carry ≥threshold sprint transitions within ±1 tick".
         *  Mutated only in onAttack (network thread) — single-threaded, ArrayDeque is fine. */
        val recent = java.util.ArrayDeque<Boolean>()
    }

    private companion object {
        /** Rolling window of recent attacks judged for the sustained-macro gate. */
        const val WINDOW = 4
        /** Min attacks observed before flagging (small-sample guard). */
        const val MIN_SAMPLES = 4
        /** Tappy attacks required in the window to call it a sustained macro (3 of 4). */
        const val SUSTAINED_HITS = 3
    }
}
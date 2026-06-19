package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID

/**
 * JumpReset detector (JumpOnHurt). The cheat auto-jumps the instant the cheater is hit to
 * "reset" knockback. Observer signature: the cheater's Δy jumps (~0.4) within 1 tick of
 * being hit, repeatedly, with no obstacle. We record each hit (into the *victim's* context)
 * from [AttackEvent] and watch for a jump in [process] within ±1 tick. After ≥3 hits with a
 * ≥80% jump-coincidence rate → flag. setbackVL 5, decay 0.2/tick.
 */
class JumpOnHurtCheck : Check() {

    override val id: String = "jumpOnHurt"

    init {
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = JumpOnHurtContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            val ctx = contextOf(ev.victim) as JumpOnHurtContext
            ctx.lastHitTick = ev.tick
            ctx.pendingHit = true
            ctx.totalHits++
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding || tp.riptide) return
            if (tick - tp.lastTeleportTick < 5) return
            val ctx = contextOf(tp.uuid) as JumpOnHurtContext
            if (!ctx.pendingHit) return
            val since = tick - ctx.lastHitTick
            if (since in 0..1 && tp.deltaY > 0.3) {
                ctx.coincidentHits++
                ctx.pendingHit = false
                if (ctx.totalHits >= 3 && ctx.coincidentHits.toDouble() / ctx.totalHits >= 0.8) {
                    flag(tp, ctx, 1.0, "JumpReset", tick)
                }
            } else if (since > 1) {
                ctx.pendingHit = false // window closed with no jump
            }
        } catch (_: Throwable) {}
    }

    private class JumpOnHurtContext : CheckContext() {
        var lastHitTick: Int = -10000
        var pendingHit: Boolean = false
        var totalHits: Int = 0
        var coincidentHits: Int = 0
    }
}
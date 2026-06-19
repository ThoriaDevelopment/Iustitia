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
 * from [AttackEvent] and watch for a jump in [process] within ±1 tick. After ≥5 hits with a
 * ≥90% jump-coincidence rate → flag. setbackVL 5, decay 0.2/tick.
 *
 * FP guards over the original 3-hit/80% gate:
 *  - **Higher bar (5 hits / 90%).** Jump-resetting is also a legit anti-KB technique; a
 *    skilled player intentionally hops on damage and can sustain ~80% coincidence over a few
 *    hits. 90% across ≥5 hits is above the legit-technique ceiling while a real JumpReset
 *    cheat (~every hit) still clears it.
 *  - **KB-induced-hop exemption.** A knockback impulse with an upward component (airborne
 *    victim, special KB) produces a small Δy spike that isn't a self-jump. If an
 *    EntityVelocityUpdate arrived within 3 ticks, the Δy is server-induced — skip counting
 *    it as a coincident jump. On servers that don't broadcast other-player velocity packets
 *    this never exempts (we can't distinguish there); the higher bar absorbs that case.
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
                ctx.pendingHit = false
                // KB-induced-hop exemption: a recent velocity update means the Δy is server
                // knockback, not a deliberate jump. Don't count it as a jump-reset coincidence.
                if (tick - tp.velocityTick >= 3) {
                    ctx.coincidentHits++
                    if (ctx.totalHits >= MIN_HITS &&
                        ctx.coincidentHits.toDouble() / ctx.totalHits >= COINCIDENCE
                    ) {
                        flag(tp, ctx, 1.0, "JumpReset", tick)
                    }
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

    private companion object {
        /** Min confirmed hits before judging coincidence (raises the bar above legit jump-resetting). */
        const val MIN_HITS = 5
        /** Coincidence rate required to flag (above the legit anti-KB-hop ceiling). */
        const val COINCIDENCE = 0.9
    }
}
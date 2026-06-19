package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.event.AttackEvent
import dev.iustitia.math.AimGeometry
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID

/**
 * HitFlick / KnockbackDisplace detector. The cheat flicks the attacker's yaw OFF the
 * victim's hitbox at the exact attack tick — to a side angle (Vape "HitFlick": 90/180/270°;
 * Slinky "Knockback Displace": a configurable angle relative to look) — to redirect the
 * knockback sideways, then snaps back onto the target within a tick or two. Vanilla never
 * looks away from a target at the instant of hitting it and immediately back; a genuine
 * turn keeps rotating instead of returning.
 *
 * On each inferred [AttackEvent] we measure the attacker's yaw error to the victim's
 * hitbox span ([AimGeometry.minInsideError]). If the yaw is off the hitbox by more than
 * [threshold] (30°) at the hit, we watch the next [RETURN_TICKS] (2) ticks for a return
 * inside the hitbox span (≤ [RETURN_DEV] = 5°) — the flick-and-return signature. A real
 * hit-flicker repeats every hit; setbackVL 5, decay 0.5/tick. Exempt while riding (vehicle
 * rotations unreliable). Fail-open throughout.
 */
class HitFlickCheck : Check() {

    override val id: String = "hitFlick"

    init {
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = HitFlickContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            val attacker = EntityTrackerManager.get(ev.attacker) ?: return
            val victim = EntityTrackerManager.get(ev.victim) ?: return
            if (attacker.inVehicle) return
            // yaw error to the victim's hitbox span at the attack tick
            val dev = AimGeometry.minInsideError(attacker, victim.pos.x, victim.pos.z, attacker.yaw)
            if (dev > cfg.threshold) {
                val ctx = contextOf(ev.attacker) as HitFlickContext
                ctx.flickTick = ev.tick
                ctx.flickVictim = ev.victim
            }
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            val ctx = contextOf(tp.uuid) as HitFlickContext
            val flickTick = ctx.flickTick
            if (flickTick == Int.MIN_VALUE) return
            // watch the short return window after the flick
            if (tick - flickTick > RETURN_TICKS) { ctx.flickTick = Int.MIN_VALUE; return }
            val victim = ctx.flickVictim?.let { EntityTrackerManager.get(it) } ?: return
            // return: yaw back inside the victim's hitbox span
            val dev = AimGeometry.minInsideError(tp, victim.pos.x, victim.pos.z, tp.yaw)
            if (dev <= RETURN_DEV) {
                flag(tp, ctx, 1.0, "HitFlick", tick)
                ctx.flickTick = Int.MIN_VALUE
            }
        } catch (_: Throwable) {}
    }

    private class HitFlickContext : CheckContext() {
        var flickTick: Int = Int.MIN_VALUE
        var flickVictim: UUID? = null
    }

    companion object {
        private const val RETURN_TICKS = 2
        private const val RETURN_DEV = 5.0f
    }
}
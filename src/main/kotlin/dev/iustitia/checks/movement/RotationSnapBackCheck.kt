package dev.iustitia.checks.movement

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.math.Vectors
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Snap-back aura detector (RotationSnapBack). A tell-tale KillAura pattern: yaw snaps to the
 * victim on the attack tick, then snaps back to the travel direction within 1–3 ticks
 * (target acquired → attack → reset). On [AttackEvent] we check whether the attacker was
 * facing the victim at the attack tick (yaw within 15° of the eye→victim bearing) and prime
 * a watch; in [process] we flag if the yaw has since moved > [threshold] (30°) away within
 * 1–3 ticks. Requires correlation with a damage event, so legit flicks (rare, slower) don't
 * trip it. setbackVL 5, decay 0.5/tick.
 */
class RotationSnapBackCheck : Check() {

    override val id: String = "rotationSnapBack"

    init {
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = SnapContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            val attacker = EntityTrackerManager.get(ev.attacker) ?: return
            val victim = EntityTrackerManager.get(ev.victim) ?: return
            val eye = attacker.pos.add(0.0, attacker.eyeHeight(), 0.0)
            val dx = victim.pos.x - eye.x
            val dz = victim.pos.z - eye.z
            if (dx * dx + dz * dz < 0.09) return
            val expYaw = Math.toDegrees(atan2(-dx, dz))
            if (abs(Vectors.angleDiff(attacker.yaw.toDouble(), expYaw)) < 15.0) {
                val ctx = contextOf(ev.attacker) as SnapContext
                ctx.prime = true
                ctx.snapTick = ev.tick
                ctx.attackYaw = attacker.yaw.toDouble()
            }
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            val ctx = contextOf(tp.uuid) as SnapContext
            if (!ctx.prime) return
            val since = tick - ctx.snapTick
            if (since in 1..3 && abs(Vectors.angleDiff(tp.yaw.toDouble(), ctx.attackYaw)) > cfg.threshold) {
                flag(tp, ctx, 1.0, "SnapBack", tick)
                ctx.prime = false
            } else if (since > 3) {
                ctx.prime = false
            }
        } catch (_: Throwable) {}
    }

    private class SnapContext : CheckContext() {
        var prime: Boolean = false
        var snapTick: Int = -10000
        var attackYaw: Double = 0.0
    }
}
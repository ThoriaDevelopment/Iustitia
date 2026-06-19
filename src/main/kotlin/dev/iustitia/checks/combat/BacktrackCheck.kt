package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.math.AABB
import dev.iustitia.tracking.EntityTrackerManager
import java.util.UUID
import kotlin.math.sqrt

/**
 * Backtrack detector (StalePositionReach), weak / low-VL. The cheat delays incoming packets
 * about the victim by ~80–150ms so it can hit the victim at an older (closer) position. We
 * can't see the cheater's outgoing attack, but we *can* see the victim's position history: if
 * the attacker's eye is OUT of reach of the victim's current position yet IN reach of the
 * victim's position ~3–4 ticks ago, the hit landed on a stale position.
 *
 * Uses the victim's [PositionRingBuffer]. Indirect signal — setbackVL is deliberately high
 * (10) so this rarely alerts on its own; it corroborates a Reach/aura flag. decay 0.25/tick.
 */
class BacktrackCheck : Check() {

    override val id: String = "backtrack"

    init {
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = BacktrackContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            val attacker = EntityTrackerManager.get(ev.attacker) ?: return
            val victim = EntityTrackerManager.get(ev.victim) ?: return
            if (attacker.inVehicle) return
            val reach = cfg.threshold
            val eye = attacker.pos.add(0.0, attacker.eyeHeight(), 0.0)
            val margin = 0.5
            // current position out of reach?
            val curBox = AABB.around(victim.pos.x, victim.pos.y, victim.pos.z, 0.6, 1.8).expand(0.0005)
            val curDist = sqrt(curBox.closestPointSqDistance(eye.x, eye.y, eye.z))
            if (curDist <= reach + margin) return // current pos already in reach — not backtrack
            // any recent position in reach?
            for (p in victim.ring.getPositions(4, ev.tick)) {
                val oldBox = AABB.around(p.x, p.y, p.z, 0.6, 1.8).expand(0.0005)
                val oldDist = sqrt(oldBox.closestPointSqDistance(eye.x, eye.y, eye.z))
                if (oldDist <= reach + margin) {
                    flag(attacker, contextOf(attacker.uuid), 1.0, "Backtrack", ev.tick)
                    return
                }
            }
        } catch (_: Throwable) {}
    }

    private class BacktrackContext : CheckContext()
}
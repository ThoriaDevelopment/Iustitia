package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.history.Evidence
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.world.WorldQueries
import net.minecraft.client.MinecraftClient
import java.util.UUID

/**
 * Through-walls / no-LOS aura detector. On each inferred [AttackEvent], raycasts the
 * attacker's eye to the victim's body center through the client's own world. A solid
 * block on that segment means the attacker landed a hit through a wall — KillAura with
 * no line-of-sight filter.
 *
 * Chunk-gated: a "blocked" verdict is only trusted when both endpoint chunks are loaded
 * ([WorldQueries.hasLineOfSight] returns false on unloaded chunks, which would otherwise
 * false-positive). setbackVL 5, decay 0.5/tick.
 */
class ThroughWallsCheck : Check() {

    override val id: String = "throughWalls"

    init {
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = ThroughWallsContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            val attacker = EntityTrackerManager.get(ev.attacker) ?: return
            val victim = EntityTrackerManager.get(ev.victim) ?: return
            if (attacker.inVehicle) return
            // A passenger's eye sits inside the vehicle frame; LOS through the frame is legit,
            // so a victim riding a boat/minecart is exempt (only the attacker side was before).
            if (victim.inVehicle) return
            val world = MinecraftClient.getInstance().world ?: return
            // Multi-point victim tolerance. A single eye→eye ray flags ANY cover between the
            // two players regardless of attacker look direction — fences, slabs and low walls
            // between meleeing players all occluded eye→eye and fired 21k FPs with 0 Polar
            // corroboration. Sample three victim body points (eye / mid-torso / feet) and flag
            // only when the attacker's eye cannot see ANY of them — i.e. the victim's whole
            // body is occluded, which no legit melee hit is. Most "cover between players" leaves
            // the feet or torso visible, so this narrows to genuine full-wall through-hits.
            val eye = attacker.pos.add(0.0, attacker.eyeHeight(), 0.0)
            val targets = listOf(
                victim.pos.add(0.0, victim.eyeHeight(), 0.0), // ~1.62 victim eye
                victim.pos.add(0.0, 0.9, 0.0),                // mid-torso
                victim.pos.add(0.0, 0.1, 0.0)                 // feet
            )
            // only trust a "blocked" verdict when both endpoint chunks are loaded (all three
            // targets share the victim column, so one chunk check covers them).
            if (!WorldQueries.isChunkLoaded(world, eye.x.toInt(), eye.z.toInt())) return
            if (!WorldQueries.isChunkLoaded(world, victim.pos.x.toInt(), victim.pos.z.toInt())) return
            if (targets.all { !WorldQueries.hasLineOfSight(world, eye, it) }) {
                flag(attacker, contextOf(attacker.uuid), 1.0, "ThroughWalls", ev.tick, Evidence(
                    subLabel = "all-occluded", pos = eye, victim = victim.uuid,
                    measurement = 3.0, threshold = 3.0, extra = "eye→{eye,mid,feet} all blocked"))
            }
        } catch (_: Throwable) {}
    }

    private class ThroughWallsContext : CheckContext()
}
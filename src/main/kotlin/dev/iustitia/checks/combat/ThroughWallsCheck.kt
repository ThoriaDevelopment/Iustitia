package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
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
            val world = MinecraftClient.getInstance().world ?: return
            // Raycast eye→eye (both ~1.62). A legit melee reaches OVER low obstacles
            // (fences 1.5, slabs 0.5/1.0, walls 1.0) since player hitboxes reach 3.0+ at
            // torso/eye height — a horizontal eye-level ray clears them. Only a full
            // block at eye height (a real wall, ≥2 tall or a 1.0-tall block under a
            // ceiling) occludes eye→eye → that is a genuine through-walls hit. Targeting
            // the victim's torso center (0.9) instead made the ray slope downward and
            // clip every fence/slab between two meleeing players (FP on every close hit).
            val eye = attacker.pos.add(0.0, attacker.eyeHeight(), 0.0)
            val target = victim.pos.add(0.0, victim.eyeHeight(), 0.0)
            // only trust a "blocked" verdict when both endpoint chunks are loaded
            if (!WorldQueries.isChunkLoaded(world, eye.x.toInt(), eye.z.toInt())) return
            if (!WorldQueries.isChunkLoaded(world, target.x.toInt(), target.z.toInt())) return
            if (!WorldQueries.hasLineOfSight(world, eye, target)) {
                flag(attacker, contextOf(attacker.uuid), 1.0, "ThroughWalls", ev.tick)
            }
        } catch (_: Throwable) {}
    }

    private class ThroughWallsContext : CheckContext()
}
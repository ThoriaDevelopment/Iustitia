package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.math.Vectors
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.world.WorldQueries
import net.minecraft.client.MinecraftClient
import java.util.UUID
import kotlin.math.hypot

/**
 * Anti-knockback / Velocity detector (Nemesis VelocityA/B proxy). When a sprinting
 * attacker lands a confirmed hit on the cheater, vanilla applies ~0.4 horizontal
 * knockback along the attacker's facing. A Velocity cheat cancels/scales that, so the
 * victim barely moves. We can't see the sent velocity packet (and on many servers it isn't
 * even broadcast for other players), so we use the vanilla expected magnitude and measure
 * the victim's actual ΔXZ on the knockback tick.
 *
 * On [AttackEvent] we record (into the *victim's* context) the hit tick + attacker facing +
 * "attacker was sprinting". In [process], the tick after the hit, if the victim moved less
 * than 0.1/tick (≈2 bps) despite a sprint-hit AND there is no wall ahead in the knockback
 * direction (which would legitimately stop them) → flag. Gated on attacker sprinting (only
 * sprint-KB is strong enough to require movement). setbackVL 5, decay 1/tick.
 */
class NoKnockbackCheck : Check() {

    override val id: String = "noKnockback"

    init {
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = NoKnockbackContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            val attacker = EntityTrackerManager.get(ev.attacker) ?: return
            EntityTrackerManager.get(ev.victim) ?: return
            if (!attacker.sprinting) return // only sprint-KB is strong enough to require
            val ctx = contextOf(ev.victim) as NoKnockbackContext
            ctx.lastHitTick = ev.tick
            ctx.kbYaw = attacker.yaw.toDouble()
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            val ctx = contextOf(tp.uuid) as NoKnockbackContext
            if (tick - ctx.lastHitTick != 1) return // the knockback lands one tick after the hit
            if (tp.inVehicle || tp.gliding || tp.riptide) return
            if (tick - tp.lastTeleportTick < 5) return
            if (tick - tp.hurtTick < 1) return // hurt recorded this tick (the hit itself)
            // Server-lag exemption: a server-wide freeze makes the victim legitimately not
            // move on the knockback tick (everyone frozen), looking exactly like a Velocity
            // cancel. Bail within the shared lag window so a hitch doesn't false-flag NoKB.
            if (tick - EntityTrackerManager.lastServerLagTick <= 4 ||
                tick - EntityTrackerManager.lastLagBurstTick <= 2
            ) return
            val horiz = hypot(tp.delta.x, tp.delta.z)
            if (horiz > 0.1) return // they moved — took the knockback, fine
            val world = MinecraftClient.getInstance().world ?: return
            // exempt if a wall sits ahead in the knockback direction (legit stop)
            val look = Vectors.lookVector(ctx.kbYaw, 0.0)
            val ahead = tp.pos.add(look.multiply(0.6))
            val footY = Math.floor(tp.pos.y).toInt()
            if (WorldQueries.isSolidAt(world, ahead.x.toInt(), footY, ahead.z.toInt()) ||
                WorldQueries.isSolidAt(world, ahead.x.toInt(), footY + 1, ahead.z.toInt())
            ) return
            flag(tp, ctx, 1.0, "NoKB", tick)
        } catch (_: Throwable) {}
    }

    private class NoKnockbackContext : CheckContext() {
        var lastHitTick: Int = -10000
        var kbYaw: Double = 0.0
    }
}
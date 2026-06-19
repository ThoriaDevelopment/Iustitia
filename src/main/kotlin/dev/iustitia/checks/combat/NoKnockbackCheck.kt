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
 * the victim's actual ΔXZ over the knockback window.
 *
 * On [AttackEvent] we record (into the *victim's* context) the hit tick + attacker facing +
 * "attacker was sprinting". In [process] we accumulate the victim's horizontal displacement
 * over a [WINDOW] (3)-tick post-hit window (KB can land 1–2 ticks late and the impulse
 * spreads across ticks, so a single-tick gate misread both directions), then flag if the
 * summed movement is below [MIN_MOVE] despite a sprint-hit. Gated on attacker sprinting
 * (only sprint-KB is strong enough to require movement). setbackVL 5, decay 1/tick.
 *
 * Exemptions (each closes a real FP class the single-tick/single-point form had):
 *  - **Dampening surface** at the feet — liquid / cobweb / soul sand / honey legitimately
 *    suppress horizontal KB below the threshold (vanilla KB in water is tiny). Tested via
 *    [WorldQueries.isKbDampeningAt].
 *  - **Wall in the KB direction** — sampled across several offsets and three body bands
 *    (not just one point straight ahead), so a wall beside/around the victim that stops them
 *    legitimately is recognized. Tested via [WorldQueries.isWallAhead].
 *  - **Server lag** — a server-wide freeze makes the victim legitimately not move; bail
 *    within the shared lag window.
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
            ctx.windowDisp = 0.0
            ctx.windowTicks = 0
            ctx.evaluated = false
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            val ctx = contextOf(tp.uuid) as NoKnockbackContext
            val since = tick - ctx.lastHitTick
            if (since < 1) return // before the KB window
            // accumulate horizontal displacement over the post-hit window (KB spreads across
            // ticks; summing avoids the single-tick misread both ways).
            if (since in 1..WINDOW) {
                ctx.windowDisp += hypot(tp.delta.x, tp.delta.z)
                ctx.windowTicks++
            }
            // evaluate once when the window completes
            if (since >= WINDOW && !ctx.evaluated) {
                ctx.evaluated = true
                evaluate(tp, ctx, tick)
            }
        } catch (_: Throwable) {}
    }

    private fun evaluate(tp: TrackedPlayer, ctx: NoKnockbackContext, tick: Int) {
        if (tp.inVehicle || tp.gliding || tp.riptide) return
        if (tick - tp.lastTeleportTick < 5) return
        // Server-lag exemption: a server-wide freeze makes the victim legitimately not move,
        // looking exactly like a Velocity cancel. Bail within the shared lag window.
        if (tick - EntityTrackerManager.lastServerLagTick <= 4 ||
            tick - EntityTrackerManager.lastLagBurstTick <= 2
        ) return
        // they moved enough across the window — took the knockback, fine
        if (ctx.windowDisp > MIN_MOVE) return
        val world = MinecraftClient.getInstance().world ?: return
        val bx = Math.floor(tp.pos.x).toInt()
        val bz = Math.floor(tp.pos.z).toInt()
        val footY = Math.floor(tp.pos.y).toInt()
        // dampening surface at the feet legitimately suppresses KB
        if (WorldQueries.isKbDampeningAt(world, bx, footY, bz) ||
            WorldQueries.isKbDampeningAt(world, bx, footY - 1, bz)
        ) return
        // a wall in the KB direction (swept, multi-band) legitimately stops them
        val look = Vectors.lookVector(ctx.kbYaw, 0.0)
        if (WorldQueries.isWallAhead(world, tp.pos.x, tp.pos.y, tp.pos.z, look.x, look.z, 1.0)) return
        flag(tp, ctx, 1.0, "NoKB", tick)
    }

    private class NoKnockbackContext : CheckContext() {
        var lastHitTick: Int = -10000
        var kbYaw: Double = 0.0
        /** Summed horizontal displacement over the post-hit window. */
        var windowDisp: Double = 0.0
        /** Ticks counted in the window (sanity — a teleport mid-window inflates disp, safe). */
        var windowTicks: Int = 0
        /** True once the window has been evaluated (reset by the next attack). */
        var evaluated: Boolean = false
    }

    private companion object {
        /** Post-hit window (ticks) over which KB displacement is summed. */
        const val WINDOW = 3
        /** Max summed horizontal displacement (blocks) over the window that still flags. KB
         *  is ~0.4/tick sprint-hit; over 3 ticks a taker moves >0.4 total even with friction. */
        const val MIN_MOVE = 0.1
    }
}
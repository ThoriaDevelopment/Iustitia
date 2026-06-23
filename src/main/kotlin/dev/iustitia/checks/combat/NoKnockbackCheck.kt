package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.event.VelocitySignal
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
 * spreads across ticks, so a single-tick gate misread both directions), then flag (Nemesis
 * VelocityC) when that displacement is under cfg.threshold (0.61) of the EXPECTED KB
 * displacement — impulse × friction-integral, captured from [VelocitySignal] on
 * broadcast-velocity servers or assumed vanilla sprint-KB (~0.4) where it isn't broadcast.
 * Gated on attacker sprinting (only sprint-KB is strong enough to require movement).
 * setbackVL 5, decay 1/tick.
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
        try {
            Iustitia.bus.subscribe<AttackEvent> { onAttack(it) }
            // Capture the KB impulse the server applies to each victim. tp.velocity is clobbered
            // each poll by EntityTrackerManager.updateSnapshot, so the impulse must come from the
            // bus (only broadcast-velocity servers emit EntityVelocityUpdate for other players;
            // on servers that don't, onVelocity never fires and evaluate() falls back to the
            // full-cancel path). This revives the check to catch partial anti-KB (Nemesis
            // VelocityC ratio), not just the full cancels the old MIN_MOVE floor caught.
            Iustitia.bus.subscribe<VelocitySignal> { onVelocity(it) }
        } catch (_: Throwable) {}
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

    private fun onVelocity(ev: VelocitySignal) {
        try {
            // Store the horizontal KB magnitude the server applied to this victim, so evaluate()
            // can compare the victim's actual post-hit displacement as a ratio of the expected KB
            // (Nemesis VelocityC). Ignore sub-0.1 impulses (zero-velocity resets / trivial noise).
            val h = hypot(ev.velocity.x, ev.velocity.z)
            if (h <= 0.1) return
            val ctx = contextOf(ev.entity) as NoKnockbackContext
            ctx.kbImpulseH = h
            ctx.kbTick = ev.tick
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
        // Detection (Nemesis VelocityC ratio). windowDisp is the victim's SUMMED horizontal
        // displacement over the 3-tick post-hit window; a full-KB taker moves ~impulse × friction-
        // integral (~1.0 for a sprint hit). The expected displacement is impulse × KB_FRICTION_INTEGRAL,
        // using the captured VelocitySignal impulse on broadcast-velocity servers, or the vanilla
        // ASSUMED_SPRINT_KB impulse (~0.4) on servers that don't broadcast other-player velocity
        // (this server — onVelocity never fires). Flag when the victim took under cfg.threshold
        // (0.61) of expected → partial anti-KB / Velocity. (The old form divided windowDisp by the
        // per-tick impulse directly — wrong units, ratio ~2.5 for full KB, so the <0.61 test only
        // caught ~24% cancels; combined with the 0.1 full-cancel fallback it fired on nobody.)
        val impulse = if (tick - ctx.kbTick <= 3 && ctx.kbImpulseH > 0.1) ctx.kbImpulseH else ASSUMED_SPRINT_KB
        val expectedDisp = impulse * KB_FRICTION_INTEGRAL
        if (ctx.windowDisp < cfg.threshold * expectedDisp) {
            flag(tp, ctx, 1.0, "NoKB", tick)
        }
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
        /** Horizontal magnitude of the last captured KB impulse (from VelocitySignal); 0 if the
         *  server doesn't broadcast other-player velocity. */
        var kbImpulseH: Double = 0.0
        /** Tick of the last captured KB impulse (−10000 ⇒ none captured / stale). */
        var kbTick: Int = -10000
    }

    private companion object {
        /** Post-hit window (ticks) over which KB displacement is summed. */
        const val WINDOW = 3
        /** Vanilla horizontal sprint-KB impulse (blocks/tick) assumed when the server doesn't
         *  broadcast other-player EntityVelocityUpdate (so onVelocity never captures one). */
        const val ASSUMED_SPRINT_KB = 0.4
        /** Sum of the per-tick ground-friction multipliers over the WINDOW (1 + 0.91 + 0.91² ≈
         *  2.73); the expected 3-tick KB displacement is impulse × this. Ground (0.91) is used
         *  rather than air (0.98) since most sprint-hits land on grounded victims. */
        const val KB_FRICTION_INTEGRAL = 2.7
    }
}
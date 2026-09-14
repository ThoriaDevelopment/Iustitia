package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.event.AttackEvent
import dev.iustitia.history.Evidence
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
 * hit-flicker does it on every hit, which is exactly the cadence that made the old per-hit
 * level-1.0 flag unusable: one flag per two ticks against a 0.5/tick decay is break-even, so the
 * VL oscillated at ~1.0 and never approached setbackVL (measured live). The verdict is now judged
 * over a rolling window of **attacks**: a clean hit pushes `false`, a flick that returns inside the
 * span pushes `true`, and a flick that never returns pushes `false`. [MIN_VIOLATIONS] of the last
 * [WINDOW] attacks must flick-and-return, and then the episode alerts once at a level that clears
 * setbackVL (see [Check.flagEpisode]); a single flick -- a legitimate off-target hit that happens
 * to snap back -- cannot. Exempt while riding (vehicle rotations unreliable). Fail-open throughout.
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
            val ctx = contextOf(ev.attacker) as HitFlickContext
            // yaw error to the victim's hitbox span at the attack tick
            val dev = AimGeometry.minInsideError(attacker, victim.pos.x, victim.pos.z, attacker.yaw)
            if (dev > cfg.threshold) {
                ctx.flickTick = ev.tick
                ctx.flickVictim = ev.victim
            } else {
                // a clean hit: the attacker was facing the victim at the hit tick, which is the
                // counter-example that keeps the window's rate honest.
                judge(ctx, attacker, ev.tick, flicked = false)
            }
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            val ctx = contextOf(tp.uuid) as HitFlickContext
            val flickTick = ctx.flickTick
            if (flickTick == Int.MIN_VALUE) return
            // watch the short return window after the flick
            if (tick - flickTick > RETURN_TICKS) {
                ctx.flickTick = Int.MIN_VALUE
                judge(ctx, tp, tick, flicked = false) // flicked off-bore and stayed away
                return
            }
            val victim = ctx.flickVictim?.let { EntityTrackerManager.get(it) } ?: return
            // return: yaw back inside the victim's hitbox span
            val dev = AimGeometry.minInsideError(tp, victim.pos.x, victim.pos.z, tp.yaw)
            if (dev <= RETURN_DEV) {
                ctx.flickTick = Int.MIN_VALUE
                judge(ctx, tp, tick, flicked = true)
            }
        } catch (_: Throwable) {}
    }

    /** Record one attack's flick verdict and alert once when the pattern is sustained. */
    private fun judge(ctx: HitFlickContext, tp: TrackedPlayer, tick: Int, flicked: Boolean) {
        val sustainedNow = sustained(ctx, flicked, WINDOW, MIN_VIOLATIONS)
        if (sustainedNow) {
            flagEpisode(tp, ctx, "HitFlick", tick, Evidence(
                subLabel = "flick-and-return", measurement = MIN_VIOLATIONS.toDouble(),
                threshold = WINDOW.toDouble(), pos = tp.pos,
                extra = "snapped off the hitbox at the hit and back inside it within $RETURN_TICKS " +
                    "ticks on ≥$MIN_VIOLATIONS of the last $WINDOW attacks"))
        } else {
            rearmEpisode(ctx, sustainedNow)
        }
    }

    private class HitFlickContext : CheckContext() {
        var flickTick: Int = Int.MIN_VALUE
        var flickVictim: UUID? = null
    }

    companion object {
        private const val RETURN_TICKS = 2
        private const val RETURN_DEV = 5.0f
        /** Rolling window of attacks the flick verdict is judged over. */
        const val WINDOW = 5
        /** Flick-and-returns required in the window (3 of the last 5 attacks). */
        const val MIN_VIOLATIONS = 3
    }
}
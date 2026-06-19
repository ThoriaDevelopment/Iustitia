package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Criticals detector (CritOnGround), observable-only. A Criticals cheat spoofs "airborne /
 * falling" at the attack tick so the server awards a crit while the player is actually on
 * the ground. The spoof injects a tiny Y hop (~+0.11 Vanilla / NCP triple) around each damage
 * event — far below a real jump impulse (0.42).
 *
 * Primary signal: a small upward Δy (0.05–0.3) while moving horizontally, occurring within
 * ~3 ticks of the attacker's own inferred attack, AND preceded by a near-level tick
 * (|prevDeltaY| < 0.08 — the player was grounded/level, not mid-jump). A legit hit struck
 * while rising in a real jump has prevDeltaY ~0.2–0.33 (already ascending) and is exempt; a
 * real crit comes from a fall (Δy negative). Only a grounded crit spoof hops from level
 * (prevDeltaY ≈ 0) to a tiny 0.11 dy at the attack tick. The attack tick is recorded from
 * [AttackEvent]; the hop is observed in [process]. setbackVL 5, decay 0.1/tick.
 */
class CriticalsCheck : Check() {

    override val id: String = "criticals"

    init {
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = CriticalsContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            (contextOf(ev.attacker) as CriticalsContext).lastAttackTick = ev.tick
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding || tp.riptide || tp.swimming) return
            if (tick - tp.lastTeleportTick < 5) return
            // Hurt exemption: the attacker's own knockback hop (a small upward dy from a level
            // prevDeltaY) can mimic the crit-spoof pattern in a mutual-hit exchange. The dy +
            // prevDeltaY gates likely already filter it, but this is fail-negative insurance.
            if (tick - tp.hurtTick < 3) return
            val ctx = contextOf(tp.uuid) as CriticalsContext
            if (tick - ctx.lastAttackTick > 3) return
            val dy = tp.deltaY
            val horiz = hypot(tp.delta.x, tp.delta.z)
            // crit hop: small upward Y (below real jump 0.42) while moving horizontally,
            // rising from a near-level previous tick (grounded spoof). A legit mid-jump
            // hit has prevDeltaY already 0.2+ (ascending) → exempt. The window is cfg.threshold
            // (low) .. cfg.threshold + 0.25 (high); default threshold 0.05 reproduces the
            // original 0.05..0.30 window, now YACL-tunable instead of a hardcoded no-op.
            val lo = cfg.threshold
            if (dy in lo..(lo + 0.25) && horiz > 0.1 && abs(tp.prevDeltaY) < 0.08) {
                flag(tp, ctx, 1.0, "Crits", tick)
            }
        } catch (_: Throwable) {}
    }

    private class CriticalsContext : CheckContext() {
        var lastAttackTick: Int = -10000
    }
}
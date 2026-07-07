package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.event.HurtSignal
import dev.iustitia.event.HurtSource
import dev.iustitia.event.SwingSignal
import dev.iustitia.history.Evidence
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID

/**
 * HitsWithoutSwing — Slinky "Hit Select / Fake swing" + Grim `PacketOrderB` (plan §4 #7 /
 * §8 step 12). The cheat deals damage with no swing animation in the window (a disabler
 * suppresses the swing packet, or a hit-select cheat attacks without the arm animation).
 * Observable kernel: a hurt on a victim with NO attacker swing within [SWING_WINDOW] ticks
 * — every vanilla melee attack carries a swing, so a no-swing hurt is a cheat OR an unseen
 * attacker. This is a weak / noisy signal (the caveat in the plan: "some disablers
 * legitimately skip swing — low VL"), so it sits in the [dev.iustitia.history.FlagHistory.CORROBORATOR]
 * tier: it corroborates a killaura/silent-aim case but is not red-capable alone.
 *
 * Attacker inference: if the hurt channel carries `attackerEntityId` (ENTITY_DAMAGE), resolve
 * that tracked player directly. Otherwise (STATUS / DAMAGE_TILT carry -1) attribute the hit
 * to the nearest tracked player within melee range ([MELEE_RANGE]) of the victim — and
 * FAIL-OPEN if no tracked player is that close (the hurt likely came from an unseen/out-of-
 * view attacker, fall, fire, etc. — never a confirmed no-swing attack we can attribute).
 *
 * FP mitigation: a single no-swing hurt is noisy (unseen attacker, packet ordering, a swing
 * just outside the window). Require ≥ [threshold] (default 3) no-swing hurts from the SAME
 * inferred attacker within [EPISODE] ticks, transition-gated one flag per episode — a cheater
 * suppressing every swing sustains it; an unseen attacker or a one-off doesn't accumulate.
 * VELOCITY hurts are excluded (knockback follow-up, not a melee). setbackVL 5, decay 0.5/tick.
 *
 * This check is event-driven (HurtSignal + SwingSignal); it does not override [process].
 * Per-attacker `lastSwingTick` lives in the attacker's own context (same pattern as
 * AutoBlockCheck). Constants tuned in step 14.
 */
class HitsWithoutSwingCheck : Check() {

    override val id: String = "hitsWithoutSwing"

    init {
        try { Iustitia.bus.subscribe<SwingSignal> { onSwing(it) } } catch (_: Throwable) {}
        try { Iustitia.bus.subscribe<HurtSignal> { onHurt(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = HitsWithoutSwingContext()

    private fun onSwing(sig: SwingSignal) {
        try {
            (contextOf(sig.attacker) as HitsWithoutSwingContext).lastSwingTick = sig.tick
        } catch (_: Throwable) {}
    }

    private fun onHurt(sig: HurtSignal) {
        try {
            // Only melee hurt channels. VELOCITY is a knockback follow-up, not a swing-attack.
            if (sig.source == HurtSource.VELOCITY) return
            val victim = EntityTrackerManager.get(sig.victim) ?: return
            val attacker = inferAttacker(sig, victim) ?: return
            val actx = contextOf(attacker.uuid) as HitsWithoutSwingContext
            val tick = sig.tick
            // Was there an attacker swing in the window around the hurt? A vanilla melee swing
            // lands at or just before the hurt (AttackInference correlates within ~1-2 ticks);
            // allow a small either-side window for packet ordering.
            val swung = actx.lastSwingTick != Int.MIN_VALUE &&
                tick - actx.lastSwingTick in -SWING_WINDOW..SWING_WINDOW
            if (!swung) {
                actx.noSwingCount++
                actx.lastNoSwingTick = tick
                val need = cfg.threshold.toInt().coerceAtLeast(1)
                if (actx.noSwingCount >= need && !actx.active) {
                    actx.active = true
                    flag(attacker, actx, VL_NO_SWING, "HitsWithoutSwing", tick, Evidence(
                        subLabel = "no-swing-attack", measurement = actx.noSwingCount.toDouble(),
                        threshold = need.toDouble(), pos = attacker.pos, victim = sig.victim,
                        extra = "src=${sig.source}"))
                }
            }
            // Re-arm the transition gate + reset the episode after a quiet period.
            if (actx.active && tick - actx.lastNoSwingTick > EPISODE) {
                actx.active = false
                actx.noSwingCount = 0
            }
        } catch (_: Throwable) {}
    }

    /**
     * Resolve the attacker: the tracked player matching [HurtSignal.attackerEntityId] when
     * the channel provides one, else the nearest tracked player within [MELEE_RANGE] of
     * the victim. Returns null (fail-open) when no tracked player is close enough — the
     * hurt is then attributable to an unseen attacker / environment, not a no-swing cheat.
     */
    private fun inferAttacker(sig: HurtSignal, victim: TrackedPlayer): TrackedPlayer? {
        if (sig.attackerEntityId >= 0) {
            val direct = EntityTrackerManager.all().firstOrNull { it.entityId == sig.attackerEntityId }
            if (direct != null) return direct
        }
        var nearest: TrackedPlayer? = null
        var nearestSq = MELEE_RANGE_SQ
        for (cand in EntityTrackerManager.all()) {
            if (cand.uuid == victim.uuid) continue
            val dsq = cand.pos.squaredDistanceTo(victim.pos)
            if (dsq < nearestSq) { nearestSq = dsq; nearest = cand }
        }
        return nearest
    }

    private class HitsWithoutSwingContext : CheckContext() {
        /** Tick of the attacker's last observed swing (Int.MIN_VALUE sentinel: never swung). */
        var lastSwingTick: Int = Int.MIN_VALUE
        /** No-swing hurts attributed to this attacker in the current episode. */
        var noSwingCount: Int = 0
        /** Tick of the last no-swing hurt (transition-gate re-arm + episode reset). */
        var lastNoSwingTick: Int = -10000
        /** Transition gate: true once [threshold] no-swing hurts fired this episode (one flag/episode). */
        var active: Boolean = false
    }

    companion object {
        /** Max ticks (either side of the hurt) within which a swing counts as "accompanied". */
        const val SWING_WINDOW = 2
        /** Max squared distance (blocks²) for the proximity-attacker fallback — melee reach ~4. */
        const val MELEE_RANGE_SQ = 16.0
        /** Episode window (ticks) for the no-swing count + the transition-gate re-arm. */
        const val EPISODE = 60
        /** Per-flag VL level — low (this is a CORROBORATOR-tier signal). */
        const val VL_NO_SWING = 1.0
    }
}
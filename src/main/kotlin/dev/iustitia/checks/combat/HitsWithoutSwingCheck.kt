package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.event.HurtSignal
import dev.iustitia.event.HurtSource
import dev.iustitia.event.SwingSignal
import dev.iustitia.history.Evidence
import dev.iustitia.protocol.ProtocolDetector
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
 * Attacker inference: the hurt is attributed to the player the server named
 * (`attackerEntityId`, the ENTITY_DAMAGE channel), and to nobody else. A name that resolves to no
 * tracked player is a mob, an arrow or a TNT block, and a hurt that names nobody at all is
 * environmental: both fail open. Only where the protocol cannot name anyone (legacy 1.8-1.19.3,
 * where [ProtocolDetector.namesDamageCauses] never becomes true) does the check fall back to
 * proximity, and there it requires a candidate within melee range ([MELEE_RANGE_SQ]) of the victim
 * with a recent swing ([SWING_RECENCY]) — and still fails open when nobody qualifies.
 *
 * FP mitigation: a single no-swing hurt is noisy (unseen attacker, packet ordering, a swing
 * just outside the window). Require ≥ [threshold] (default 3) no-swing hurts from the SAME
 * inferred attacker within [EPISODE] ticks, transition-gated one flag per episode — a cheater
 * suppressing every swing sustains it; an unseen attacker or a one-off doesn't accumulate.
 * VELOCITY hurts are excluded (knockback follow-up, not a melee).
 *
 * That episode flag clears setbackVL in a single flag — it has to, because the gate allows at most
 * one flag per [EPISODE] (60) ticks, i.e. ~1/60/tick against a 0.5/tick decay, so the old level-1.0
 * form could never alert at all (measured live: a swing-suppressing drive recorded in `/ius hist`
 * and stayed at 0.0 of the 5.0 needed). setbackVL 5.
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
            // Learn the protocol's damage-cause capability from the signal this check reads: an
            // ENTITY_DAMAGE hurt is the server naming a cause, and from then on an unnamed hurt is
            // environmental by construction. Set here as well as in AttackInference so this check's
            // gate does not depend on that class being registered. Idempotent.
            if (sig.source == HurtSource.ENTITY_DAMAGE) ProtocolDetector.noteDamagePacket()
            val victim = EntityTrackerManager.get(sig.victim) ?: return
            val attacker = inferAttacker(sig, victim) ?: return
            val actx = contextOf(attacker.uuid) as HitsWithoutSwingContext
            val tick = sig.tick
            // Was there an attacker swing in the window around the hurt? A vanilla melee swing
            // lands at or just before the hurt (AttackInference correlates within ~1-2 ticks);
            // allow a small either-side window for packet ordering.
            val swung = actx.lastSwingTick != Int.MIN_VALUE &&
                tick - actx.lastSwingTick in -SWING_WINDOW..SWING_WINDOW
            // A gap longer than EPISODE ends the episode and re-arms the latch. This MUST run
            // first, and it is the only place the quiet period is observable: the check is
            // event-driven off HurtSignal, so it never sees a quiet tick -- the re-arm has to
            // happen on the next hurt.
            //
            // The previous form tested `tick - actx.lastNoSwingTick` AFTER writing
            // `actx.lastNoSwingTick = tick` on the no-swing path, so on a no-swing hurt it
            // evaluated `tick - tick > EPISODE` -- false, always. It was reachable only on a hurt
            // that HAD a swing in the window (which skips the write). Combined with the old
            // caller-side `active` flag never being cleared on that same path, the episode latch
            // was permanent: one alert per session, and the first hit of every later episode
            // swallowed.
            if (tick - actx.lastNoSwingTick > EPISODE) {
                actx.noSwingCount = 0
                rearmEpisode(actx, false)
            }
            if (!swung) {
                actx.noSwingCount++
                actx.lastNoSwingTick = tick
            }
            val need = cfg.threshold.toInt().coerceAtLeast(1)
            val sustainedNow = actx.noSwingCount >= need
            if (sustainedNow) {
                // flagEpisode owns the one-per-episode latch (ctx.episodeActive) and sets it
                // itself; setting any caller-side flag first would consume the episode with no
                // flag -- see [Check.flagEpisode]'s KDoc.
                flagEpisode(attacker, actx, "HitsWithoutSwing", tick, Evidence(
                    subLabel = "no-swing-attack", measurement = actx.noSwingCount.toDouble(),
                    threshold = need.toDouble(), pos = attacker.pos, victim = sig.victim,
                    extra = "src=${sig.source}"))
            }
            rearmEpisode(actx, sustainedNow)
        } catch (_: Throwable) {}
    }

    /**
     * Resolve the attacker: the tracked player the hurt names, and nobody else.
     *
     * A named cause that resolves to no tracked player is a mob, an arrow or a TNT block, and the
     * server has told us the hurt is not a player's — so this returns null (fail-open) instead of
     * falling through to the proximity loop. It used to fall through, which was a live false
     * positive: a mob hit on a teammate charged whoever had swung nearby in the last 60 ticks.
     *
     * The proximity fallback survives for the channels that name nobody (legacy STATUS /
     * DAMAGE_TILT, which carry -1) and only where the protocol cannot name causes at all — see
     * [ProtocolDetector.namesDamageCauses]. There it still requires recent swing activity
     * ([SWING_RECENCY]): a bystander never swung because they never attacked, and without that gate
     * environmental damage next to them charged their no-swing episode. A fully swing-suppressing
     * disabler is caught through the direct channel and other checks.
     */
    private fun inferAttacker(sig: HurtSignal, victim: TrackedPlayer): TrackedPlayer? {
        if (sig.attackerEntityId >= 0) return EntityTrackerManager.byEntityId(sig.attackerEntityId)
        if (ProtocolDetector.namesDamageCauses) return null
        var nearest: TrackedPlayer? = null
        var nearestSq = MELEE_RANGE_SQ
        for (cand in EntityTrackerManager.all()) {
            if (cand.uuid == victim.uuid) continue
            val cctx = contextOf(cand.uuid) as HitsWithoutSwingContext
            if (cctx.lastSwingTick == Int.MIN_VALUE || sig.tick - cctx.lastSwingTick > SWING_RECENCY) continue
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
        /** Tick of the last no-swing hurt (episode reset + re-arm, see [onHurt]). */
        var lastNoSwingTick: Int = -10000
    }

    companion object {
        /** Max ticks (either side of the hurt) within which a swing counts as "accompanied". */
        const val SWING_WINDOW = 2
        /** Max squared distance (blocks²) for the proximity-attacker fallback — melee reach ~4. */
        const val MELEE_RANGE_SQ = 16.0
        /** Max tick age of a candidate's last swing for the -1-attacker proximity fallback. */
        const val SWING_RECENCY = 60
        /** Episode window (ticks) for the no-swing count + the transition-gate re-arm. */
        const val EPISODE = 60
    }
}
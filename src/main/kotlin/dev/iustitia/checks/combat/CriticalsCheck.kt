package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.history.Evidence
import dev.iustitia.math.MathUtil
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.LagCombatCorrelator
import dev.iustitia.tracking.TrackedPlayer
import net.minecraft.item.MaceItem
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Criticals detector (CritOnGround), observable-only. A Criticals cheat spoofs "airborne /
 * falling" at the attack tick so the server awards a crit while the player is actually on
 * the ground. The spoof injects a tiny Y hop (~+0.11 Vanilla / NCP triple) around each damage
 * event — far below a real jump impulse (0.42).
 *
 * Three independent sub-signals, all sharing `criticals`'s VL pool (no new check id), all
 * observed in [process] around the attacker's own inferred attack ([AttackEvent] records
 * `lastAttackTick`):
 *
 *  - **Crits (grounded-hop)**: a small upward Δy (0.05–0.3) while moving horizontally, within
 *    ~3 ticks of the attack, AND preceded by a near-level tick (`|prevDeltaY| < 0.08` — the
 *    player was grounded/level, not mid-jump). A legit hit struck while rising in a real jump
 *    has prevDeltaY ~0.2–0.33 (already ascending) and is exempt; a real crit comes from a fall
 *    (Δy negative). Only a grounded crit spoof hops from level (prevDeltaY ≈ 0) to a tiny
 *    0.11 dy at the attack tick. (Original primary signal.)
 *  - **Crits(MicroY)** (Meteor, plan §3/§8 step 7): Packet/UpdatedNCP/OldNCP modes send a
 *    micro-Y jiggle (`0` / `0.0625` / `0.11…`) with `onGround=false` + a NoFall-skip tag, so
 *    the server awards a crit from a sub-jump perturbation where vanilla needs ≥1.5 block
 *    fall. Observable: `|Δy| < 0.15` AND `onGroundPacket == false` (spoofed airborne) AND
 *    level lead-in (`|prevDeltaY| < 0.08`, excluding a real fall / a jump apex) AND not
 *    sprinting (sprint cancels crit — a sprint-jump-apex attack has the same Y shape but is
 *    not a crit, so sprinting is an FP guard here) AND near the attack. Catches the near-zero
 *    "0" mode the grounded-hop (Δy ≥ 0.05 upward) misses.
 *  - **Crits(Timing)** (Axis B, Slinky Criticals Packet mode, plan §3/§8 step 7): the cheat
 *    lag-times a REAL fall and crits at a chosen phase of the arc — the Y-arc is real, the
 *    *timing of the attack within the fall arc* is the bias. On each descending attack
 *    (Δy < 0) the fall-arc phase (Δy at the attack) is recorded; flag when ≥ [MIN_PHASE]
 *    fall-attacks cluster with stDev < [PHASE_TIGHT] — a fixed fall-arc phase repeated is a
 *    hand never sustains. Transition-gated one-shot (one alert per clustered episode).
 *
 * **NCM `Fight.Criticals` 4-gate verification (plan §3/§8 step 7):** the legit-crit gates are
 * `fall_distance>1`, `NOT sprinting`, no blindness, not on ground — AND mace/spear exempt
 * (1.21 mace changes crit math). Since we detect the Y-spoof (not the crit damage — the
 * multiplier isn't broadcast), the gates map to exemptions, not detection:
 *  - **fall>1**: excluded by the level-lead-in gate (`|prevDeltaY| < 0.08`) — a real >1 fall
 *    has strongly-negative prevDeltaY, not level (no `fallAccum` dependency on another check).
 *  - **NOT sprinting**: required by `Crits(MicroY)` (FP guard, above). The grounded-hop keeps
 *    its proven no-sprint-gate form.
 *  - **no blindness**: other players' blindness is not broadcast → fail-open (a blind
 *    Criticals cheater is rare; the other gates still catch the spoof). Documented.
 *  - **not on ground**: the spoof IS `onGround=false` while grounded; `Crits(MicroY)` keys on
 *    exactly that; the grounded-hop uses the level-lead-in grounded proxy.
 *  - **mace/spear exempt**: snapshot `attackMaceHeld` (`mainHandStack.item is MaceItem`) at the
 *    attack — the mace's smash-attack has its own fall-damage scaling, not the sword crit
 *    multiplier, so a mace-attack's Y wiggle is not a sword-crit spoof. `MaceItem` exists only
 *    post-1.21, so this is auto-fail-open on older protocols. (Spear = trident-riptide, already
 *    exempt via the `riptide` guard; a held trident's melee crit follows the normal sword gates,
 *    so no separate trident exempt.)
 *
 * setbackVL 5, decay 0.1/tick. All paths fail-open.
 */
class CriticalsCheck : Check() {

    override val id: String = "criticals"

    init {
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = CriticalsContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            val tp = EntityTrackerManager.get(ev.attacker) ?: return
            val ctx = contextOf(ev.attacker) as CriticalsContext
            ctx.lastAttackTick = ev.tick
            // Snapshot mace-held at the attack tick (the smash requires the mace at the hit; a
            // hotbar swap by the deferred process tick would misread it). Exempts all three crit
            // sub-signals for this attack. isMaceHeld is auto-fail-open pre-1.21 (no MaceItem).
            ctx.attackMaceHeld = isMaceHeld(tp)
            // Crits(Timing) — record the fall-arc phase (Δy at the attack) of each DESCENDING
            // attack. A fixed-phase critter crits at the same Δy every time; a hand crits at
            // varying phases. Mace-exempt (a mace's fall-smash is legit and naturally clusters).
            if (!ctx.attackMaceHeld && tp.deltaY < 0.0) {
                pushFront(ctx.fallPhases, tp.deltaY, PHASE_WINDOW)
                evaluatePhaseCluster(tp, ctx, ev.tick)
            }
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
            // Mace exempt (NCM 4-gate, §3/§8 step 7): a mace smash-attack is its own fall-damage
            // mechanic, not a sword crit — its Y wiggle near an attack is not a crit spoof.
            if (ctx.attackMaceHeld) return
            val dy = tp.deltaY
            val horiz = hypot(tp.delta.x, tp.delta.z)
            // -- Crits (grounded-hop) — small upward Y from a level lead-in. --
            val lo = cfg.threshold
            if (dy in lo..(lo + 0.25) && horiz > 0.1 && abs(tp.prevDeltaY) < 0.08) {
                flag(tp, ctx, 1.0, "Crits", tick, Evidence(
                    subLabel = "grounded-hop", measurement = dy, threshold = lo, pos = tp.pos,
                    extra = "horiz=$horiz prevΔy=${tp.prevDeltaY}"))
                // Axis B amplifier (plan §2.2/§6): a Criticals Packet-mode cheat lag-times a REAL
                // fall and crits at a chosen phase of the arc — the attacker's own position stream
                // shows a self-induced freeze around the lag-timed attack. Correlating the crit
                // flag with an attacker self-freeze around the attack tick amplifies the lag-timed
                // variant. Distinct label, shares `criticals`'s VL pool (no new check id). The
                // combat tick is the attack (ctx.lastAttackTick), not the process tick.
                try {
                    val lag = LagCombatCorrelator.combatCorrelatedLag(tp.uuid, ctx.lastAttackTick, LAG_CORR_WINDOW)
                    if (lag >= MIN_LAG_FREEZE) {
                        flag(tp, ctx, VL_LAG_CORR, "Crits(LagCorr)", tick, Evidence(
                            subLabel = "lag-correlated", measurement = lag.toDouble(),
                            threshold = MIN_LAG_FREEZE.toDouble(), pos = tp.pos,
                            extra = "attacker self-freeze around crit attack"))
                    }
                } catch (_: Throwable) {}
            }
            // -- Crits(MicroY) (Meteor, §3/§8 step 7): a sub-jump Y jiggle (<0.15) with the
            // spoofed airborne bit (onGroundPacket=false), from a level lead-in, not sprinting,
            // near the attack. Catches the "0"/0.0625 modes the grounded-hop (Δy ≥ 0.05 upward)
            // misses. The level lead-in (|prevDeltaY|<0.08) is the NCM fall>1 gate (a real fall
            // has strongly-negative prevDeltaY) and the jump-apex FP guard (apex has prevDeltaY>0).
            if (abs(dy) < MICRO_Y && !tp.onGroundPacket && abs(tp.prevDeltaY) < 0.08 &&
                !tp.sprinting
            ) {
                flag(tp, ctx, VL_MICRO_Y, "Crits(MicroY)", tick, Evidence(
                    subLabel = "micro-y", measurement = dy, threshold = MICRO_Y, pos = tp.pos,
                    extra = "onGround=${tp.onGroundPacket} prevΔy=${tp.prevDeltaY} sprint=${tp.sprinting}"))
            }
        } catch (_: Throwable) {}
    }

    /**
     * Crits(Timing) cluster evaluator (Axis B, §3/§8 step 7). Called from [onAttack] after each
     * descending attack's phase is pushed. Flags (one-shot, transition-gated) when the last
     * [PHASE_WINDOW] descending-attack Δys have ≥ [MIN_PHASE] samples with stDev < [PHASE_TIGHT]
     * — a fixed fall-arc phase repeated, which a hand never sustains. Re-arms when the stDev
     * rises back above [PHASE_RECOVER] (the cluster broke). High confidence, low frequency:
     * one alert per clustered episode, level clears setbackVL in a single flag.
     */
    private fun evaluatePhaseCluster(tp: TrackedPlayer, ctx: CriticalsContext, tick: Int) {
        if (ctx.fallPhases.size < MIN_PHASE) return
        val sample = newestDoubles(ctx.fallPhases, minOf(ctx.fallPhases.size, PHASE_WINDOW))
        val sd = MathUtil.populationStDev(sample)
        if (sd < PHASE_TIGHT) {
            if (!ctx.phaseActive) {
                ctx.phaseActive = true
                flag(tp, ctx, setbackVL + 1.0, "Crits(Timing)", tick, Evidence(
                    subLabel = "fall-arc-phase", measurement = sd, threshold = PHASE_TIGHT,
                    pos = tp.pos, extra = "n=${sample.size} stDev=${"%.4f".format(sd)}"))
            }
        } else if (sd >= PHASE_RECOVER) {
            // cluster broke (attacks no longer at a fixed phase) → re-arm for a fresh episode.
            ctx.phaseActive = false
        }
    }

    /** True if the attacker's main hand holds a mace (1.21+); auto-fail-open pre-1.21. */
    private fun isMaceHeld(tp: TrackedPlayer): Boolean = try {
        tp.entity?.mainHandStack?.item is MaceItem
    } catch (_: Throwable) {
        false
    }

    /** Snapshot the newest [n] phase samples into a DoubleArray, newest-first (matches the
     *  ArrayDeque iterator order — head→tail = newest→oldest via [pushFront]). */
    private fun newestDoubles(deque: ArrayDeque<Double>, n: Int): DoubleArray {
        val arr = DoubleArray(n)
        var i = 0
        val iter = deque.iterator()
        while (i < n && iter.hasNext()) { arr[i++] = iter.next() }
        return arr
    }

    private fun <T> pushFront(deque: ArrayDeque<T>, value: T, cap: Int) {
        deque.addFirst(value)
        while (deque.size > cap) deque.removeLast()
    }

    private class CriticalsContext : CheckContext() {
        var lastAttackTick: Int = -10000
        /** Mace held at the last attack tick (exempts all crit sub-signals for that attack). */
        var attackMaceHeld: Boolean = false
        /** Rolling window of descending-attack Δys (fall-arc phases) for the Crits(Timing) cluster. */
        val fallPhases = ArrayDeque<Double>()
        /** Transition gate for Crits(Timing): true while the fixed-phase cluster holds, so we flag
         *  only the false→true edge (one flag per clustered episode), not every descending attack. */
        var phaseActive: Boolean = false
    }

    private companion object {
        // -- Lag-correlation amplifier (Axis B, plan §3/§8 step 6) --
        /** Window (ticks) around the attack within which an attacker self-freeze corroborates a
         *  lag-timed crit. Crit timing is tight, so a tighter window than reach/backtrack. Tuned
         *  in step 14. */
        const val LAG_CORR_WINDOW = 4
        /** Min local-freeze ticks (coincident with combat) to amplify. */
        const val MIN_LAG_FREEZE = 2
        /** Amplifier sub-flag level — "weight up" on top of the crit flag. Tuned in step 14. */
        const val VL_LAG_CORR = 1.0

        // -- Meteor micro-Y sub-signal (plan §3/§8 step 7) --
        /** Max |Δy| for the micro-Y jiggle (Meteor Packet/UpdatedNCP/OldNCP modes: 0 / 0.0625 /
         *  0.11…). Vanilla crits need ≥1.5 block fall; a sub-jump perturbation is the spoof. */
        const val MICRO_Y = 0.15
        /** Micro-Y sub-flag level — a confirmed spoofed-airborne bit is high-confidence. Tuned in step 14. */
        const val VL_MICRO_Y = 1.0

        // -- Crit-timing-in-fall-arc sub-signal (Axis B, plan §3/§8 step 7) --
        /** Window of recent descending-attack Δys (fall-arc phases) scanned for a fixed-phase cluster. */
        const val PHASE_WINDOW = 30
        /** Min fall-attacks required before clustering is evaluated — a hand never sustains ≥8
         *  attacks at a near-identical fall-arc phase. Tuned in step 14. */
        const val MIN_PHASE = 8
        /** stDev (blocks) of the fall-attack Δys below which the cluster counts as a fixed phase.
         *  0.05 = the attack-Δy varies by <0.05 across ≥8 fall-attacks — far tighter than a hand. */
        const val PHASE_TIGHT = 0.05
        /** Recovery bar: stDev rising back to ≥ this re-arms the transition gate (cluster broke). */
        const val PHASE_RECOVER = 0.15
    }
}
package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.event.AttackEvent
import dev.iustitia.history.Evidence
import dev.iustitia.protocol.ProtocolDetector
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import net.minecraft.item.MaceItem
import net.minecraft.util.math.Vec3d
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs

/**
 * MaceSmash detector (LiquidBounce `ModuleMaceKill` family), observable-only. A mace's
 * smash damage scales with fall distance, so the cheat fakes a tall fall around each
 * attack by warping Y via position-only packets (NOT a teleport packet) — visible as a
 * Y spike `|Δy| > 1.5` within ±2 ticks of the attack, while the player is actually on
 * level ground. A genuine mace smash from a real high fall has a *sustained negative-Δy
 * arc leading into* the attack; the cheat is a warp-up-then-back with no such lead-in.
 *
 * Plan §7.1: flag a mace-holder's attack with `|Δy| > 1.5` within ±2 ticks, exempt a
 * genuine fall. **§8.3 binding constraint: the `>8b` teleport heuristic must NOT exempt
 * this check.** `EntityTrackerManager` sets `tp.lastTeleportTick` whenever a single-tick
 * jump exceeds 8 blocks, and a big Y warp trips it; `CriticalsCheck` gates on that flag
 * and would exempt exactly this spike. `MaceSmashCheck` therefore reads `tp.deltaY` /
 * `tp.prevDeltaY` **directly and never reads `lastTeleportTick`** — the Y spike IS the
 * signal, not something to gate out. (The `>8b` heuristic does not touch `deltaY`/
 * `prevDeltaY`, so they still carry the warp.) The standard lag gate IS kept — a batched
 * catch-up snap is a legit large Δy with no fall lead-in, exactly the false signature.
 *
 * Evaluation is deferred to `attack+2` so the full ±2 window (and the 2-tick lead-in
 * before the attack) is available in a small Δy ring. `setbackVL` 5, decay 0.05/tick
 * (slow — a per-attack burst signal; sustained every-hit MaceKill climbs, a single
 * residual rubberband doesn't alert). Version-gated to 1.21+ by the held-mace check
 * itself (`MaceItem` exists only post-1.21); `is1_8OrLess` is a cheap 1.8-era skip.
 */
class MaceSmashCheck : Check() {

    override val id: String = "maceSmash"

    init {
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = MaceContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            // 1.8 era has no mace (and item-slot remap via ViaFabricPlus is messy) — skip.
            if (ProtocolDetector.is1_8OrLess) return
            val tp = EntityTrackerManager.get(ev.attacker) ?: return
            val ctx = contextOf(ev.attacker) as MaceContext
            // Snapshot the smash preconditions AT the hit: the smash requires the mace in
            // hand at the attack tick (the deferred eval tick may follow a hotbar swap),
            // and the alert position is the attack-tick pos, not the eval-tick pos.
            ctx.pendingAttack = ev.tick
            ctx.attackMaceHeld = try { tp.entity?.mainHandStack?.item is MaceItem } catch (_: Throwable) { false }
            ctx.attackPos = tp.pos
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding || tp.riptide || tp.swimming) return
            if (ProtocolDetector.is1_8OrLess) return
            val ctx = contextOf(tp.uuid) as MaceContext
            // accumulate the Δy ring every tick (covers the ±2 window + the 2-tick lead-in)
            ctx.ring.addFirst(tick to tp.deltaY)
            while (ctx.ring.size > RING_SIZE) ctx.ring.removeLast()
            if (ctx.pendingAttack == Int.MIN_VALUE) return
            val at = ctx.pendingAttack
            // wait for the full forward window; clear + fail-open if we overshot (gap/despawn)
            if (tick < at + EVAL_DELAY) return
            ctx.pendingAttack = Int.MIN_VALUE
            if (tick > at + EVAL_DELAY) return
            evaluate(tp, ctx, tick, at)
        } catch (_: Throwable) {}
    }

    private fun evaluate(tp: TrackedPlayer, ctx: MaceContext, tick: Int, at: Int) {
        // not a mace smash (no mace in hand at the attack) → no flag
        if (!ctx.attackMaceHeld) return
        // Lag gate (standard §8 step-0 posture): a batched catch-up snap is a large Δy
        // with no fall lead-in — precisely the false signature. This is NOT the >8b
        // teleport exemption (we deliberately never read lastTeleportTick — see class doc).
        if (tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW ||
            tick - EntityTrackerManager.lastLagBurstTick <= BURST_WINDOW
        ) return
        if (ctx.lastFlaggedAttack == at) return  // dedup: one flag per attack
        // ring ≈ at-3..at+2 (6 entries pushed each tick)
        val dy = HashMap<Int, Double>()
        for (e in ctx.ring) dy[e.first] = e.second
        // Exempt A — genuine fall: sustained negative-Δy arc LEADING INTO the attack
        //   (the 2 ticks before the attack both descending ≤ -0.5). A real fast-fall
        //   smash (≥12b fall to reach |Δy|>1.5) looks exactly like this → exempt.
        val leadA = dy[at - 2]
        val leadB = dy[at - 1]
        if (leadA != null && leadB != null && leadA <= LEAD_IN_DESCEND && leadB <= LEAD_IN_DESCEND) return
        // Scan the ±2 window for a warp spike not covered by the per-spike bounce exemption.
        for (t in (at - 2)..(at + 2)) {
            val v = dy[t] ?: continue
            if (abs(v) <= cfg.threshold) continue  // threshold = 1.5, the |Δy| floor (§7.1)
            // Exempt B — bounce: an UPWARD spike preceded by a descent (slime/wind-charge
            //   landing) is legit. The cheat warp-up from level ground has prev ≈ 0, NOT ≤ -0.5.
            if (v > 0 && (dy[t - 1] ?: 0.0) <= LEAD_IN_DESCEND) continue
            // warp (up-from-level, or down-from-level) → flag once per attack
            flag(tp, ctx, VL_MACE, "MaceSmash", at, Evidence(
                subLabel = "y-warp", measurement = abs(v), threshold = cfg.threshold,
                pos = ctx.attackPos, extra = "Δy=$v dt=${t - at}"))
            ctx.lastFlaggedAttack = at
            return
        }
    }

    private class MaceContext : CheckContext() {
        /** (tick, Δy) ring — last 6 ticks, covers the attack±2 window + the 2-tick lead-in. */
        val ring = ArrayDeque<Pair<Int, Double>>()
        /** Attack tick awaiting deferred evaluation; Int.MIN_VALUE = none pending. */
        var pendingAttack: Int = Int.MIN_VALUE
        /** Whether the attacker held a mace at the attack tick (snapshot in onAttack). */
        var attackMaceHeld: Boolean = false
        /** Attacker position at the attack tick (alert evidence pos). */
        var attackPos: Vec3d? = null
        /** Last attack tick we already flagged — dedup so one smash = one flag. */
        var lastFlaggedAttack: Int = Int.MIN_VALUE
    }

    private companion object {
        /** Flag level into the maceSmash pool (tuned in step 14). */
        const val VL_MACE = 1.5
        /** Δy ring depth (attack±2 window + 2-tick lead-in + 1 boundary). */
        const val RING_SIZE = 6
        /** Deferred-evaluation delay: decide at attack+2 so the full ±2 window is in the ring. */
        const val EVAL_DELAY = 2
        /** Genuine-fall lead-in floor: a descent is Δy ≤ this (≤ -0.5/tick). */
        const val LEAD_IN_DESCEND = -0.5
        /** Standard lag gate (§8 step-0 posture). */
        const val LAG_WINDOW = 8
        const val BURST_WINDOW = 3
    }
}
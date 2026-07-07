package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.event.AttackEvent
import dev.iustitia.event.SwingSignal
import dev.iustitia.history.Evidence
import dev.iustitia.protocol.ProtocolDetector
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID

/**
 * AutoBlock detector, ported from Rain-Anticheat's 1.8.9 `AutoBlockCheck`, folded with the
 * observer-feasible kernel of Grim `MultiActionsA`/`MultiActionsE` (plan §7.1 / §8 step 12).
 * The cheat family uses an item while attacking — vanilla cannot swing/attack and use an
 * item at once for more than an instant, so sustained overlap is the fingerprint. Three
 * sub-flags share `autoBlock`'s VL pool (no new check id):
 *
 * 1. **AutoBlock** (Rain port): `isBlocking` (shield raised / sword blocked) + a reconstructed
 *    swing-in-progress window. Rain tests `isSwingInProgress && isUsingItem` for >10 ticks;
 *    we don't observe `isSwingInProgress` directly, so we rebuild it from [SwingSignal] (a
 *    swing animation lasts ~6 ticks → `tick - lastSwingTick < SWING_TICKS` is "currently
 *    swinging"). Gating on `isBlocking` (UseAction.BLOCK) — NOT bare `usingItem` — excludes
 *    bows (BOW useAction while charging would FP every swing during a bow draw), food (EAT),
 *    and potions (DRINK). Vanilla 1.21 can't attack while a shield is raised, so the BLOCK +
 *    swing overlap is the cheat signature and never a legit action. On 1.8 sword-blocking
 *    (UseAction.BLOCK) is a normal PvP technique — a longer overlap run is required there
 *    ([LEGACY_1_8_BONUS]) so legit block-hitters don't flag.
 *
 * 2. **AutoBlock(SwingUse)** (Grim `MultiActionsE` fold, §8 step 12): a swing while the
 *    player is actively eating/drinking (`isUsingConsumable`). Vanilla cancels the use when
 *    the player left-clicks (attacks) — a swing animation sustained while the consume
 *    metadata stays set means the cheat keeps using through the swing. Bows are excluded
 *    (bow-draw is `usingItem` but not `isUsingConsumable`), matching the existing AutoBlock
 *    bow-exclusion rationale. Same sustained-`[threshold]`-overlap gate as the block path.
 *
 * 3. **AutoBlock(AttackUse)** (Grim `MultiActionsA` fold, §8 step 12): an actual attack
 *    (inferred [AttackEvent]) while `isBlocking || isUsingConsumable`. Vanilla attacking
 *    interrupts using; an attack that lands while the use metadata is still set is the
 *    damage-confirmed variant of the swing-use kernel — a single event is already the cheat
 *    (the use survived the attack), so it flags once per attack at a low level rather than
 *    on a sustained overlap. Subscribes [AttackEvent]; resolves the attacker via
 *    [EntityTrackerManager].
 *
 * setbackVL 5, decay 0.5/tick. All paths fail-open. Constants tuned in step 14.
 */
class AutoBlockCheck : Check() {

    override val id: String = "autoBlock"

    init {
        try { Iustitia.bus.subscribe<SwingSignal> { onSwing(it) } } catch (_: Throwable) {}
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = AutoBlockContext()

    private fun onSwing(sig: SwingSignal) {
        try {
            (contextOf(sig.attacker) as AutoBlockContext).lastSwingTick = sig.tick
        } catch (_: Throwable) {}
    }

    private fun onAttack(ev: AttackEvent) {
        try {
            val tp = EntityTrackerManager.get(ev.attacker) ?: return
            if (tp.inVehicle) return
            // AutoBlock(AttackUse) — Grim MultiActionsA: an attack lands while the use-item
            // metadata is still set. Vanilla attacking interrupts using, so the use surviving
            // the attack is the damage-confirmed variant of the swing-use kernel.
            if (tp.isBlocking || tp.isUsingConsumable) {
                val ctx = contextOf(tp.uuid) as AutoBlockContext
                flag(tp, ctx, VL_ATTACK_USE, "AutoBlock(AttackUse)", ev.tick, Evidence(
                    subLabel = "attack-while-using", measurement = VL_ATTACK_USE,
                    threshold = VL_ATTACK_USE, pos = tp.pos, victim = ev.victim,
                    extra = if (tp.isBlocking) "block" else "consume"))
            }
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle) return
            val ctx = contextOf(tp.uuid) as AutoBlockContext
            // lastSwingTick starts at Int.MIN_VALUE; `tick - Int.MIN_VALUE` overflows Int and
            // wraps negative, which would make `swinging` true with no swing ever observed —
            // flagging any player holding right-click (food/potion/shield) after SWING_TICKS.
            // Guard the subtraction: no swing recorded yet => not swinging.
            val swinging = ctx.lastSwingTick != Int.MIN_VALUE && tick - ctx.lastSwingTick < SWING_TICKS

            // ---- AutoBlock (Rain): block + swing overlap ----
            if (swinging && tp.isBlocking) {
                ctx.autoBlockTicks++
                // On 1.8, sword-blocking (UseAction.BLOCK) is a normal PvP technique — a skilled
                // player block-hits (swings while the sword is raised) and sustains overlap far
                // past the modern threshold. On 1.21 a shield can't be raised while attacking, so
                // the same overlap is the cheat. Require a much longer run on 1.8 so legit
                // block-hitters don't flag while blatant 1.8 autoblock (sustained across a whole
                // fight) still climbs past it. Fail-open: ProtocolDetector falls back to modern,
                // so a misdetection only loosens the 1.8 gate.
                val need = if (ProtocolDetector.is1_8OrLess) cfg.threshold + LEGACY_1_8_BONUS else cfg.threshold
                if (ctx.autoBlockTicks > need) {
                    flag(tp, ctx, 1.0, "AutoBlock", tick)
                }
            } else {
                ctx.autoBlockTicks = 0
            }

            // ---- AutoBlock(SwingUse) (Grim MultiActionsE fold): swing while consuming ----
            // Vanilla left-click interrupts eating/drinking; a sustained swing with the consume
            // metadata still set is the cheat. Bows excluded (bow-draw is usingItem but not
            // isUsingConsumable) — same exclusion as the block path. Modern threshold only: a
            // legit eater who taps left-click once resets long before the gate.
            if (swinging && tp.isUsingConsumable) {
                ctx.useSwingTicks++
                if (ctx.useSwingTicks > cfg.threshold) {
                    flag(tp, ctx, 1.0, "AutoBlock(SwingUse)", tick, Evidence(
                        subLabel = "swing-while-using", measurement = ctx.useSwingTicks.toDouble(),
                        threshold = cfg.threshold, pos = tp.pos, extra = "consume"))
                }
            } else {
                ctx.useSwingTicks = 0
            }
        } catch (_: Throwable) {}
    }

    private class AutoBlockContext : CheckContext() {
        var lastSwingTick: Int = Int.MIN_VALUE
        var autoBlockTicks: Int = 0
        /** Sustained swing+consume overlap ticks (MultiActionsE). */
        var useSwingTicks: Int = 0
    }

    companion object {
        /** Swing animation duration in ticks (~6 in 1.8; close enough to gate "swinging now"). */
        private const val SWING_TICKS = 6
        /** Extra overlap ticks required on 1.8 before flagging — legit block-hitting sustains a
         *  long overlap, so the modern 10-tick gate would flag skilled 1.8 players. */
        private const val LEGACY_1_8_BONUS = 30.0
        /** Per-attack VL for the AutoBlock(AttackUse) sub-flag — low (one event is the cheat). */
        const val VL_ATTACK_USE = 1.0
    }
}
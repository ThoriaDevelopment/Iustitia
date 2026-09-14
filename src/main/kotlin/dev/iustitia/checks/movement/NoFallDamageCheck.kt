package dev.iustitia.checks.movement

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.event.HurtSignal
import dev.iustitia.history.Evidence
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.world.WorldQueries
import net.minecraft.block.Blocks
import net.minecraft.item.MaceItem
import net.minecraft.client.MinecraftClient
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * NoFall detector, observable-only. Accumulates downward distance while airborne and
 * flags when a player lands from a damaging fall without the corresponding hurt signal
 * (the server would normally play the hurt animation / send EntityDamage on impact).
 *
 * Signals:
 *  - **Landed-no-hurt**: a **touchdown** with fallAccum > [threshold] (8.0 — relaxed for
 *    unobservable feather-falling), no hurt in the last 2 ticks, and the landing block not
 *    soft (water/slime/hay/cobweb). A touchdown is [TrackedPlayer.groundedProxy] *or* the
 *    transition groundedProxy cannot see: the tick the feet reach a surface while the player
 *    still carries downward velocity, with the server reporting onGround and a solid block
 *    within [TOUCHDOWN_TOL]. That second form is not decoration — a bunny-hopper re-launches
 *    on the very tick it lands, so |Δy| is never < 0.01 and keying the reset on groundedProxy
 *    alone summed ~1.1 per hop across a chain of individually harmless hops until an ordinary
 *    chain crossed the threshold (verified live: `legit-bunnyhop` flagged a "12.5-block fall").
 *    The touchdown is judged *before* anything is cleared, so a spoofed onGround with no
 *    support under the feet is not a touchdown and the fall keeps accumulating to be judged
 *    where it lands.
 *  - **No-damage-burst re-basing**: a wind charge / TNT-cannon impulse deals no damage, and
 *    vanilla negates fall damage for every block above the point the burst was armed at
 *    (MC-268383 / MC-272821) — so a legal launch-and-land-same-surface flight legitimately
 *    arrives with **no hurt signal** and is indistinguishable from the cheat by hurt alone.
 *    The tracker already detects the impulse (`TrackedPlayer.burstTick`); this check uses it to
 *    re-base the accumulator at the impulse's own start (`pos.y - Δy`), keeping the *highest*
 *    burst point of the flight. Only the descent *below* that floor counts, which is exactly
 *    the part vanilla still damages — so a landing at or above the launch height no longer
 *    false-flags, while an impulse fired on the way down cannot lower the floor (it is set
 *    above the falling player) and cannot clear the accumulator, so the evasion attempt still
 *    flags (covered by `cheat-nofall-burst-spoof-meteor`).
 *  - **Ground-spoof-over-air**: the server-reported onGround is true but no solid block
 *    is below the player and they have fallAccum > [threshold] (floating-onGround spoof
 *    after a real fall). Gated to the threshold — a low 1.0 gate flags every chunk-edge
 *    / half-block stand during normal KitPvP knockback-falls.
 *  - **Stair-step** (Verus): repeated ≈-2.0 then ≈0 Δy cycles (≥3) — packet-level fall reset.
 *
 *  - **Mace-smash re-base** (1.21): a successful mace smash attack negates ALL fall damage
 *    accumulated prior to the attack (vanilla "resets the player's fall height"; damage resumes
 *    only for descent AFTER the smash). The observable form of "a smash landed" is the attack
 *    correlation itself — [AttackEvent] (swing + victim hurt, via AttackInference) while the
 *    attacker is DESCENDING with a mace in hand on a real fall (fallAccum > 1.5, the smash's own
 *    minimum). At that event the accumulator re-bases to 0, exactly like the vanilla fall-height
 *    counter: without this, every aerial mace smash on a Spear-Mace-style FFA lands with no hurt
 *    signal for the *faller* (the victim is the one hurt) and flags `landed-no-hurt` (live-log
 *    audit: NoFall was 34% of all events on 43 players, 43% of them on players the server-side
 *    anticheat never flagged). Evasion bounds: the confirmation is the attack correlation — a
 *    swing with no victim hurt produces no event and clears nothing (`cheat-nofall-mace-evade`);
 *    a missed smash takes the fall damage normally, so the hurt channel resets the accumulator
 *    the usual way. The descent + real-fall gates keep a grounded mace attack from ever arming it.
 *    (Same precedent as CriticalsCheck's mace exemption; isMaceHeld is auto-fail-open pre-1.21.)
 *
 * A hurt signal (any channel) resets that player's fallAccum and records the tick,
 * exempting the next landing. Chunk not loaded → skip. setbackVL 4, decay 1/tick.
 */
class NoFallDamageCheck : Check() {

    override val id: String = "noFallDamage"

    init {
        try {
            Iustitia.bus.subscribe<HurtSignal> { onHurt(it) }
            Iustitia.bus.subscribe<AttackEvent> { onAttack(it) }
        } catch (_: Throwable) {}
    }

    /**
     * Mace-smash re-base: vanilla negates the fall a successful smash attacks with, so the
     * accumulator re-bases at the confirmed smash instead of being judged at the (legitimately
     * hurtless) landing. Gates before any clearing — see the class doc for the evasion bounds.
     */
    private fun onAttack(ev: AttackEvent) {
        try {
            val tp = EntityTrackerManager.get(ev.attacker) ?: return
            if (tp.deltaY >= 0.0 || tp.fallAccum <= SMASH_MIN_FALL) return // not a falling smash
            if (!isMaceHeld(tp)) return
            tp.fallAccum = 0.0
            (contextOf(ev.attacker) as NoFallContext).burstFloorY = null
        } catch (_: Throwable) {}
    }

    /** True if the tracked player's main hand holds a mace (1.21+); auto-fail-open pre-1.21. */
    private fun isMaceHeld(tp: TrackedPlayer): Boolean = try {
        tp.entity?.mainHandStack?.item is MaceItem
    } catch (_: Throwable) {
        false
    }

    override fun newContext(uuid: UUID): CheckContext = NoFallContext()

    private fun onHurt(h: HurtSignal) {
        try {
            val tp = EntityTrackerManager.get(h.victim) ?: return
            tp.fallAccum = 0.0
            (contextOf(h.victim) as NoFallContext).lastHurtTick = h.tick
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding) return
            if (tick - tp.lastTeleportTick < 5) return
            // Server-lag exemption: a server-wide hitch / catch-up burst injects a large Δy
            // sample that would inflate fallAccum (the stair-step spoof also keys on a big
            // negative Δy). Skip the sample so lag never poisons the fall accumulator.
            if (tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW ||
                tick - EntityTrackerManager.lastLagBurstTick <= BURST_WINDOW
            ) return
            val world = MinecraftClient.getInstance().world ?: return
            val ctx = contextOf(tp.uuid) as NoFallContext
            val dy = tp.deltaY
            // Math.floor, not toInt(): Double.toInt() truncates toward zero, picking the wrong
            // block column at any negative coordinate (e.g. -0.4 → 0 instead of -1).
            val bx = Math.floor(tp.pos.x).toInt()
            val bz = Math.floor(tp.pos.z).toInt()

            // A touchdown groundedProxy cannot see: the feet have reached a surface (solid within
            // TOUCHDOWN_TOL) while downward velocity remains, and the server reports onGround.
            // That is the vanilla landing tick, and for a *continuous* hop chain it is the only
            // support event that ever occurs (the hop re-launches on it, so |Δy| is never < 0.01).
            // Both halves are required, which is what keeps it from being an evasion: a cheater
            // spoofing onGround in mid-air has no solid block at their feet, so it is not a
            // touchdown -- their fall keeps accumulating and is judged when it lands.
            val touchdown = !tp.groundedProxy && tp.onGroundPacket &&
                WorldQueries.isSolidBelow(world, tp.pos.x, tp.pos.y, tp.pos.z, TOUCHDOWN_TOL)

            if (tp.groundedProxy || touchdown) {
                // airborne → grounded transition. Judged here, before anything is cleared: the
                // accumulated fall is the evidence, and clearing first would retire the cheat.
                if (ctx.wasAirborne && tp.fallAccum > cfg.threshold) {
                    val landY = Math.floor(tp.pos.y - 0.5).toInt()
                    val state = WorldQueries.blockStateAt(world, bx, landY, bz)
                    val soft = state != null && isSoftLand(state)
                    if (!soft && tick - ctx.lastHurtTick > 2) {
                        val level = max(1.0, ceil((tp.fallAccum - 3.0) * 2.0))
                        flag(tp, ctx, level, "NoFall", tick, Evidence(
                            subLabel = "landed-no-hurt", measurement = tp.fallAccum, threshold = cfg.threshold,
                            extra = "landed a ${"%.1f".format(tp.fallAccum)}-block fall with no hurt signal"))
                    }
                }
                tp.fallAccum = 0.0
                ctx.wasAirborne = false
                // a new airborne period re-bases cleanly: a stale burst floor must never exempt a
                // later, unrelated fall.
                ctx.burstFloorY = null
                ctx.stairPhase = 0
                ctx.stairCycle = 0
            } else {
                ctx.wasAirborne = true

                // No-damage burst (wind charge / TNT-cannon): the tracker arms `burstTick` at the
                // impulse onset from the motion itself. Vanilla negates fall damage above the
                // point the burst was armed at (MC-268383 / MC-272821), so re-base the fall on the
                // impulse's *own start* -- `pos.y - Δy`, i.e. the position before the impulse,
                // since the position this tick already includes it. Kept at the highest burst point
                // of the flight: an impulse fired on the way down is set *above* the falling player
                // (Δy < 0), so it can never lower the floor or reduce what is accumulated, and the
                // accumulator is deliberately never cleared here -- a fall already accrued still
                // counts. Only the descent below the floor is the part vanilla damages.
                if (tick - tp.burstTick in 0..BURST_ONSET_TICKS) {
                    val launchY = tp.pos.y - dy
                    ctx.burstFloorY = ctx.burstFloorY?.let { max(it, launchY) } ?: launchY
                }

                if (dy < 0.0) {
                    val floor = ctx.burstFloorY
                    if (floor == null || tp.pos.y < floor) tp.fallAccum += -dy
                }

                // stair-step spoof: big negative step followed by a near-zero step
                if (ctx.stairPhase == 0 && dy < -1.5) {
                    ctx.stairPhase = 1
                } else if (ctx.stairPhase == 1 && abs(dy) < 0.05) {
                    ctx.stairCycle++
                    ctx.stairPhase = 0
                    if (ctx.stairCycle >= 3) {
                        flag(tp, ctx, 1.0, "NoFall(Stair)", tick, Evidence(
                            subLabel = "stair-step", measurement = ctx.stairCycle.toDouble(), threshold = 3.0,
                            extra = "packet-level fall reset (${ctx.stairCycle} ≈-2.0/0 Δy cycles)"))
                        ctx.stairCycle = 0
                    }
                }
            }

            // ground-spoof-over-air: onGround claimed but nothing solid below + a real fall
            // accumulated. Gated to a fixed 4.0 (not cfg.threshold 8.0): this branch only fires
            // when onGroundPacket is true AND there is provably no solid below, so the strict
            // floor check already prevents FPs — the gate is pure sensitivity, and 4.0 catches
            // the shorter ground-spooofs Polar flagged (Ground Spoof) that 8.0 missed. The
            // landed-no-hurt branch above keeps cfg.threshold (8.0) to protect against
            // unobservable feather-falling.
            if (tp.onGroundPacket && tp.fallAccum > 4.0 &&
                !WorldQueries.isSolidBelow(world, tp.pos.x, tp.pos.y, tp.pos.z, 0.5)
            ) {
                flag(tp, ctx, 1.0, "NoFall(Spoof)", tick, Evidence(
                    subLabel = "ground-spoof-over-air", measurement = tp.fallAccum, threshold = 4.0,
                    extra = "onGround spoofed over air — Δy ${"%.3f".format(dy)}, fell ${"%.1f".format(tp.fallAccum)} blocks"))
            }
        } catch (_: Throwable) {}
    }

    private fun isSoftLand(state: net.minecraft.block.BlockState): Boolean = try {
        state.isOf(Blocks.WATER) || state.isOf(Blocks.SLIME_BLOCK) ||
            state.isOf(Blocks.HAY_BLOCK) || state.isOf(Blocks.COBWEB)
    } catch (_: Throwable) {
        false
    }

    private class NoFallContext : CheckContext() {
        var wasAirborne = false
        var lastHurtTick = -10000
        var stairPhase = 0
        var stairCycle = 0
        /**
         * Y (blocks) of the highest no-damage-burst start seen in the current airborne period, or
         * null when the flight had no burst. Only the descent below it is damage-relevant
         * (vanilla negates everything above), so it re-bases the accumulator instead of clearing
         * it. Cleared on touchdown.
         */
        var burstFloorY: Double? = null
    }

    private companion object {
        /**
         * Min accumulated fall (blocks) a confirmed mace attack may re-base. The smash's own
         * vanilla minimum is a >1.5-block fall; this keeps a grounded or hop-apex mace attack
         * from ever arming the re-base.
         */
        private const val SMASH_MIN_FALL = 1.5
        /** Window (ticks) after a server-wide freeze within which fall samples are skipped. */
        private const val LAG_WINDOW = 8
        /** Window (ticks) after a batched catch-up burst within which fall samples are skipped. */
        private const val BURST_WINDOW = 3
        /**
         * Ticks after a no-damage-burst onset within which the launch point is captured. Wider
         * than 1 only so the capture does not depend on the tracker arming `burstTick` on the
         * same tick this runs; `pos.y - Δy` stays the pre-impulse position either way.
         */
        private const val BURST_ONSET_TICKS = 1
        /**
         * Support tolerance (blocks) for recognising a touchdown during residual downward
         * velocity. Deliberately tight (the onGround proxy's own 0.05): this must mean "the feet
         * are on the surface", not "the ground is somewhere below" -- a loose tolerance would let
         * a cheater hovering just over a surface claim a touchdown and have the fall cleared.
         */
        private const val TOUCHDOWN_TOL = 0.05
    }
}
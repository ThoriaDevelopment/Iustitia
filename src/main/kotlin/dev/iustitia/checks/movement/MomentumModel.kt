package dev.iustitia.checks.movement

import kotlin.math.max
import kotlin.math.min

/**
 * Pure (Minecraft-free) kernel of [SpeedEnvelopeCheck]'s Speed(Momentum) sub-signal, split out
 * so the friction model and the advantage accumulator can be unit-tested against a vanilla
 * movement trace (docs/ai-assisted-development.md: pure tests where the logic separates).
 *
 * Issue #36: the sub-signal used to rest at VL ~1.0 on legitimate players. Two causes:
 *
 *  1. **Unmodelled sprint-jump.** On the takeoff tick vanilla still applies *ground* input
 *     (the player was on the ground when the tick started) and adds a 0.2 b/t sprint-jump
 *     impulse, but `groundedProxy` is already false (Δy = 0.42), so the model allowed only
 *     the 0.026 air input. Every legit sprint-jump left a ~0.3 excess — an immediate flag at
 *     ~1/sec, and +0.3 into the advantage pool.
 *  2. **Advantage latch.** The pool decayed at Grim's ×0.999/tick, which takes ~70 s to fall
 *     from the cap back under the flag line, and the advantage branch flagged on *every*
 *     moving tick once over it — even ticks with zero excess. A few residues (jumps, network
 *     jitter) latched it above [ADVANTAGE_FLAG] and the check flagged 1.0 each tick against a
 *     1.0/tick VL decay: VL pinned at ~1.0. Grim's decay works for Grim because a full
 *     simulation leaves ~1e-4 legit residue; a portable friction model leaves more.
 *
 * The fix models the takeoff tick and turns the pool into a leaky bucket that only flags on a
 * tick that itself carries excess.
 */
internal object MomentumModel {

    /** Vanilla generic.movement_speed attribute default (walkSpeed). The speedup ratio
     *  self-calibrates to 1.0 at legit speed, so the exact attribute is not load-bearing. */
    const val BASE_ATTRIBUTE = 0.1
    /** Sprint input boost (ground only). */
    const val SPRINT_BOOST = 1.3
    /** Nemesis LAND_MOVEMENT_FACTOR (per-tick input-accel constant). */
    const val LAND_MOVEMENT_FACTOR = 0.16277136
    /** Nemesis air movementSpeed (per-tick air-control input; ground uses the friction³ form). */
    const val AIR_MOVEMENT_SPEED = 0.026
    /** Per-tick horizontal air drag (the friction applied in air / the ground retention base). */
    const val AIR_FRICTION = 0.91
    /** Vanilla sprint-jump horizontal impulse (LivingEntity.jump: +0.2 along the facing).
     *  Allowed on every takeoff tick, not only when the tracker reports sprinting — the
     *  observed sprint flag can lag a tick, and one tick of 0.2 leniency per jump is far
     *  below what the 3-of-6 bps cap and the air-phase model still constrain. */
    const val JUMP_BOOST = 0.2
    /** Min rise (blocks) for a tick to count as a jump takeoff. A vanilla jump rises 0.42; a
     *  head-blocked jump in a 2-high tunnel ~0.2. Tiny micro-hops below this get no impulse. */
    const val TAKEOFF_MIN_DY = 0.1
    /** Grim: a single tick's excess >= this flags immediately (a blatant 1-tick accel spike). */
    const val IMMEDIATE_OFFSET = 0.1
    /** Accumulated advantage >= this flags (sustained low-grade overspeed). */
    const val ADVANTAGE_FLAG = 1.0
    /** Grim maxAdvantage: advantage accumulation cap. */
    const val MAX_ADVANTAGE = 4.0
    /** Per-tick leak from the advantage pool (blocks/tick of unexplained input). Residue below
     *  this — quantisation, interpolation jitter — drains to 0 instead of accumulating. A
     *  sustained +x% sprint overspeed carries ≈0.13·x excess a tick, so +10% (0.013) still
     *  fills the pool in ~125 ticks and +20% in ~48; overspeed under ~+4% no longer accumulates. */
    const val ADVANTAGE_LEAK = 0.005

    /** Max new horizontal input a tick on the ground: the Nemesis `/ friction³` form. */
    fun groundMovementSpeed(friction: Double, speedFactor: Double, sprinting: Boolean): Double {
        val sprintBoost = if (sprinting) SPRINT_BOOST else 1.0
        return BASE_ATTRIBUTE * speedFactor * sprintBoost * LAND_MOVEMENT_FACTOR /
            (friction * friction * friction)
    }

    /** Max new horizontal input a tick in the air. */
    fun airMovementSpeed(speedFactor: Double): Double = AIR_MOVEMENT_SPEED * speedFactor

    /**
     * The tick a jump starts: rising now, and either grounded last tick or falling last tick.
     * The second case is a held-jump bunny-hop — vanilla re-jumps on the tick after landing, and
     * the landing tick itself still reads airborne (Δy < 0), so no grounded tick is ever seen.
     */
    fun isTakeoff(deltaY: Double, grounded: Boolean, wasGrounded: Boolean, lastDeltaY: Double): Boolean =
        !grounded && deltaY >= TAKEOFF_MIN_DY && (wasGrounded || lastDeltaY < 0.0)

    /**
     * Max new horizontal input allowed this tick.
     *
     * [takeoff] is the tick the player leaves the ground (grounded last tick, rising now): vanilla
     * runs that tick with ground input against the previous tick's ground friction
     * ([groundFriction]) plus the sprint-jump impulse, so allow both.
     */
    fun allowance(
        grounded: Boolean, takeoff: Boolean, friction: Double, groundFriction: Double,
        speedFactor: Double, sprinting: Boolean,
    ): Double = when {
        takeoff -> groundMovementSpeed(groundFriction, speedFactor, sprinting) + JUMP_BOOST
        grounded -> groundMovementSpeed(friction, speedFactor, sprinting)
        else -> airMovementSpeed(speedFactor)
    }

    /** Excess of this tick's move over (momentum carry + max input). 0 when explained. */
    fun excess(offsetH: Double, carried: Double, allowance: Double): Double =
        max(0.0, offsetH - carried - allowance)

    /** Leaky-bucket advantage: add this tick's excess, leak [ADVANTAGE_LEAK], clamp to [0, MAX]. */
    fun accumulate(advantage: Double, offset: Double): Double =
        min(MAX_ADVANTAGE, max(0.0, advantage + offset - ADVANTAGE_LEAK))

    /**
     * Whether this tick flags: a single large excess, or a full pool — but only on a tick whose
     * own excess is above the leak, so a pool filled earlier cannot flag clean movement.
     */
    fun shouldFlag(offset: Double, advantage: Double): Boolean =
        offset >= IMMEDIATE_OFFSET || (offset > ADVANTAGE_LEAK && advantage >= ADVANTAGE_FLAG)
}

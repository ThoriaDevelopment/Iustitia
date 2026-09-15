package dev.iustitia.checks.movement

import net.minecraft.block.BlockState
import net.minecraft.block.Blocks

/**
 * Speed-cap math shared by [LongJumpCheck] and [SpeedEnvelopeCheck] so the two caps can never
 * drift apart (both were byte-identical private copies before extraction).
 */
internal object SpeedMath {
    /** Cap multiplier for a Speed status effect: +20% per amplifier level above the base effect. */
    fun speedFactor(amplifier: Int): Double =
        if (amplifier >= 0) 1.0 + 0.2 * (amplifier + 1) else 1.0

    /** Frictionless / momentum-carrying surfaces that justify the 1.3× cap raise. Soul sand is
     *  intentionally excluded — it does not make a player faster (a cap raise there is
     *  wrong-intent leniency). */
    fun isSpeedBlock(state: BlockState): Boolean = try {
        state.isOf(Blocks.ICE) || state.isOf(Blocks.BLUE_ICE) ||
            state.isOf(Blocks.PACKED_ICE) || state.isOf(Blocks.FROSTED_ICE) ||
            state.isOf(Blocks.SLIME_BLOCK)
    } catch (_: Throwable) {
        false
    }
}
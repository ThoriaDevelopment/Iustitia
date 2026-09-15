package dev.iustitia.checks

/**
 * Shared lag-exemption windows. Every check that reads
 * [dev.iustitia.tracking.EntityTrackerManager.lastServerLagTick] /
 * [dev.iustitia.tracking.EntityTrackerManager.lastLagBurstTick] skips flagging while within
 * this many ticks of the signal (a server-wide freeze or a batched catch-up burst injects
 * samples that are not the player's own movement/combat). The values were previously
 * duplicated as private constants in ~20 check companions (movement + MaceSmash +
 * LagCombatCorrelator) and could silently drift apart; they are one semantic here.
 *
 * Deliberately NOT folded in: KillAuraCheck's GCD_/DRIFT_ windows keep their own private
 * per-component constants, and ReachCheck's REACH_LAG_WINDOW (3) is a different window —
 * the span of reach history maxed over, not a lag posture.
 */
internal object LagWindows {
    /** Window (ticks) after a server-wide freeze within which samples are skipped. */
    const val LAG_WINDOW = 8
    /** Window (ticks) after a batched catch-up burst within which samples are skipped. */
    const val BURST_WINDOW = 3
}
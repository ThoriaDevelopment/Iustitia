package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.history.Evidence
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.LagCombatCorrelator
import dev.iustitia.tracking.TrackedPlayer
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.sqrt

/**
 * Blink / FakeLag / ScaffoldBlink / LagRange detector (PacketGap). The cheat cancels +
 * queues its own outgoing packets, so the server stops rebroadcasting its position — the
 * entity appears frozen for N ticks — then flushes the queue and the entity snaps a large
 * distance in one tick. Observer signature: a long near-zero-Δ silence (≥5 ticks) followed
 * by a single-tick move > [threshold] (2.0 blocks). Documented in Vape ("Blink"/"FakeLag")
 * and Slinky ("Blink"/"Lag Range"); Vape's Blink commonly auto-disables on attack/place/dig,
 * so the snap often lands right at a hit — but we flag the pure freeze-snap shape so
 * non-combat blinks are caught too.
 *
 * **Server-lag exemption:** a genuine server hitch freezes *every* player at once, then
 * releases them in a batched catch-up that looks identical to one player's Blink. We now
 * distinguish them via [EntityTrackerManager.lastServerLagTick] (a majority froze) and
 * [EntityTrackerManager.lastLagBurstTick] (a batched catch-up snapped) — a single Blinker
 * freezing alone never sets either. During a lag window we *reset* the freeze counter so a
 * pre-lag idle run (freeze already ≥5) can't carry into the first post-lag movement tick and
 * fire a false "Blink", and snaps inside the window aren't flagged. This is the per-entity
 * comparison the check previously couldn't do. setbackVL 5, decay 0.5/tick.
 *
 * **Pulse-cadence amplifier:** some FakeLag modes (LB BlinkManager, Slinky FakeLag Dynamic)
 * pulse on a fixed timer — the freeze-snap recurs at a near-constant interval. Each pulse
 * already flags as `Blink` above; this amplifier adds a distinct `Blink(Pulse)` sub-flag when
 * the last few Blink intervals are tightly clustered (low variance), escalating a sustained
 * pulser above the per-snap accrual. It only ever runs on a genuine above-threshold Blink
 * flag (never on sub-threshold jitter), so the lag/teleport/hurt exemptions already gate it.
 * Shares `packetGap`'s VL pool (no new check id).
 */
class PacketGapCheck : Check() {

    override val id: String = "packetGap"

    override fun newContext(uuid: UUID): CheckContext = PacketGapContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding || tp.riptide) return
            val ctx = contextOf(tp.uuid) as PacketGapContext
            if (tick - tp.lastTeleportTick < 10) { ctx.freeze = 0; return }
            if (tick - tp.hurtTick < 3) { ctx.freeze = 0; return }
            val dx = tp.delta.x
            val dy = tp.delta.y
            val dz = tp.delta.z
            val mag = sqrt(dx * dx + dy * dy + dz * dz)
            // server-wide lag window: majority froze (lastServerLagTick) OR a batched catch-up
            // snapped (lastLagBurstTick) recently -> this player's freeze/snap is the server's
            // catch-up, not a Blink. Reset the freeze so a pre-lag idle run can't fire on the
            // first post-lag move; skip the snap. (Consulting lastLagBurstTick matches the
            // sibling SpeedEnvelope/PhaseClip checks, which a lag manifested as a burst only
            // would otherwise evade.)
            val serverLag = tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW ||
                tick - EntityTrackerManager.lastLagBurstTick <= BURST_WINDOW
            if (serverLag) {
                ctx.freeze = 0
                return
            }
            if (mag < 0.01) {
                ctx.freeze++
            } else {
                if (ctx.freeze >= 5 && mag > cfg.threshold) {
                    flag(tp, ctx, 1.0, "Blink", tick)
                    // Pulse-cadence amplifier: a fixed-timer FakeLag recurs at a near-constant
                    // interval. Record this snap's tick and, once enough intervals are tight,
                    // flag the sustained-pulser pattern. Runs only on a genuine Blink flag, so
                    // the lag/teleport/hurt exemptions above already gate it. Distinct label,
                    // shares `packetGap`'s VL pool (no new check id).
                    try {
                        if (isPulsing(ctx, tick)) {
                            flag(tp, ctx, VL_PULSE, "Blink(Pulse)", tick, Evidence(
                                subLabel = "fixed-cadence", measurement = ctx.meanInterval(),
                                threshold = CADENCE_STDDEV.toDouble(), pos = tp.pos,
                                extra = "intervals=${ctx.intervalList()} stddev=${"%.2f".format(ctx.intervalStddev())}"))
                        }
                    } catch (_: Throwable) {}
                    // Axis B amplifier (plan §2.2/§6): FakeLag Dynamic flushes its packet queue *on*
                    // a combat event — the freeze-snap lands right at a combat hurt on the frozen
                    // entity. A non-combat blink (Vape auto-disables on place/dig; a generic lag
                    // switch) has no such coincidence. A combat hurt on this entity within the
                    // tight flush window amplifies the Blink flag toward FakeLag Dynamic. The
                    // freeze-snap itself is already detected above; this is the combat-coincidence
                    // gate. Distinct label, shares `packetGap`'s VL pool (no new check id).
                    try {
                        if (LagCombatCorrelator.combatHurtNear(tp.uuid, tick, LAG_CORR_WINDOW)) {
                            flag(tp, ctx, VL_LAG_CORR, "Blink(LagCorr)", tick, Evidence(
                                subLabel = "combat-correlated", measurement = ctx.freeze.toDouble(),
                                threshold = 5.0, pos = tp.pos,
                                extra = "freeze=${ctx.freeze} snap-mag=$mag combat hurt nearby"))
                        }
                    } catch (_: Throwable) {}
                }
                ctx.freeze = 0
            }
        } catch (_: Throwable) {}
    }

    /** True once the recent Blink-flag intervals are tightly clustered — i.e. the snaps recur
     *  on a near-fixed cadence. Requires ≥[CADENCE_MIN_INTERVALS] intervals (≥[CADENCE_FLAGS]
     *  flags), a low interval std-dev (≤[CADENCE_STDDEV] ticks), and a mean interval ≥
     *  [CADENCE_MIN_PERIOD] (so a single combat exchange's back-to-back flags can't read as a
     *  "cadence"). [ctx] is updated with the cadence stats as a side effect. */
    private fun isPulsing(ctx: PacketGapContext, tick: Int): Boolean {
        ctx.recordFlag(tick)
        val intervalCount = ctx.flagCount - 1
        if (intervalCount < CADENCE_MIN_INTERVALS) return false
        if (ctx.meanInterval() < CADENCE_MIN_PERIOD) return false
        return ctx.intervalStddev() <= CADENCE_STDDEV
    }

    private class PacketGapContext : CheckContext() {
        var freeze: Int = 0

        /** Recent Blink-flag ticks (newest at the tail) for the pulse-cadence fit. Bounded to
         *  [CADENCE_FLAGS] so a long-ago burst can't keep a stale cadence alive. */
        private val flagTicks: ArrayDeque<Int> = ArrayDeque(CADENCE_FLAGS)
        /** Mean / std-dev of the consecutive flag-intervals, recomputed once per recorded flag.
         *  0.0 when fewer than 2 flags are recorded. */
        private var mean: Double = 0.0
        private var stddev: Double = 0.0

        /** Count of Blink flags recorded (== [flagTicks].size). */
        val flagCount: Int get() = flagTicks.size

        /** Record a Blink flag's tick and recompute the interval stats over the retained flags. */
        fun recordFlag(tick: Int) {
            if (flagTicks.size >= CADENCE_FLAGS) flagTicks.pollFirst()
            flagTicks.addLast(tick)
            recompute()
        }

        /** Mean of the consecutive flag-intervals; 0.0 when fewer than 2 flags are recorded. */
        fun meanInterval(): Double = mean

        /** Std-dev of the consecutive flag-intervals; 0.0 when fewer than 2 flags are recorded. */
        fun intervalStddev(): Double = stddev

        /** Debug-only human form of the retained intervals (used in the Evidence `extra` line). */
        fun intervalList(): String =
            flagTicks.zipWithNext { a, b -> (b - a) }.joinToString(",")

        private fun recompute() {
            val n = flagTicks.size - 1
            if (n < 1) { mean = 0.0; stddev = 0.0; return }
            val iv = IntArray(n) { i -> flagTicks.elementAt(i + 1) - flagTicks.elementAt(i) }
            val m = iv.average()
            mean = m
            val varSum = iv.fold(0.0) { acc, v -> acc + (v - m) * (v - m) }
            stddev = sqrt(varSum / n)
        }
    }

    companion object {
        /** Ticks after a server-wide freeze during which freezes/snaps are exempt. */
        private const val LAG_WINDOW = 8
        /** Ticks after a batched catch-up burst during which freezes/snaps are exempt. */
        private const val BURST_WINDOW = 3
        // -- Combat-correlation amplifier (Axis B, plan §3/§8 step 6) --
        /** Window (ticks) around the snap within which a combat hurt on the frozen entity makes
         *  the blink a FakeLag-Dynamic flush. The flush is tight (~80–150ms ≈ 4–7 ticks). Tuned
         *  in step 14. */
        const val LAG_CORR_WINDOW = 4
        /** Amplifier sub-flag level — "weight up" on top of the Blink flag. Tuned in step 14. */
        const val VL_LAG_CORR = 1.0
        // -- Pulse-cadence amplifier --
        /** Blink flags retained for the cadence fit (= intervals + 1). 5 → 4 intervals, enough
         *  to spot a near-fixed period without letting an old burst dominate. */
        private const val CADENCE_FLAGS = 5
        /** Minimum intervals (= CADENCE_FLAGS - 1) before a cadence verdict is trusted. */
        private const val CADENCE_MIN_INTERVALS = CADENCE_FLAGS - 1
        /** Max interval std-dev (ticks) for "tightly clustered" — a pulser's period jitters <4
         *  ticks; a legit stop-start-stop pattern (AFK↔move, irregular) varies far more. */
        private const val CADENCE_STDDEV = 4
        /** Min mean interval (ticks) for a cadence — filters a single combat exchange's rapid
         *  back-to-back flags, which are not a *periodic* pulse. */
        private const val CADENCE_MIN_PERIOD = 5
        /** Amplifier sub-flag level for a sustained pulser — "weight up" on top of each Blink
         *  flag once the cadence is established. Matches the LagCorr amplifier weight. */
        const val VL_PULSE = 1.0
    }
}
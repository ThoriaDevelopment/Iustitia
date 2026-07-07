package dev.iustitia.checks.movement

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.history.Evidence
import dev.iustitia.math.MathUtil.gcd
import dev.iustitia.math.Vectors
import dev.iustitia.protocol.ProtocolDetector
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Silent-aim / AimAssist detector (RotationTracking). The cheat silently rotates to face a
 * target; with MovementCorrection the velocity-yaw looks vanilla, but the player's look
 * *consistently* points at one nearby entity's body center — a real player micro-adjusts and
 * looks around. GCD-quantization doesn't hide "always faces the target."
 *
 * Each tick we find the nearest other player within 6 blocks, compute the expected yaw/pitch
 * from the player's eye to that target's center, and mark a "match" if the broadcast
 * yaw/pitch land within a tolerance. Over a rolling window we flag when the match rate
 * exceeds [threshold] (0.92) with ≥60 samples — a sustained near-perfect lock. setbackVL 5,
 * decay 0.05/tick (slow — tracking is a sustained behavior, one clean tick shouldn't reset).
 *
 * FP guards over the original 50-sample / ±8°±10° / always-on form:
 *  - **Distance floor with tighter tolerance.** Inside ~2 blocks the target hitbox spans a
 *    huge angular range, so "within ±8°" is meaningless and a legit close-range tracker can
 *    sustain it. Inside 2 blocks we require a *much* tighter ±3°/±4° match; outside, the
 *    normal ±8°/±10° applies.
 *  - **Combat-window gate.** Samples only accumulate while the attacker has been in combat
 *    recently (an attack within [COMBAT_WINDOW]); idle "looking at a teammate" never feeds
 *    the window. AimAssist activates to land hits, so combat-gating targets exactly the cheat
 *    window while removing the idle-stare FP.
 *  - **Higher sample bar (60).** Raises the bar above short lucky stretches.
 *
 * Sub-flag **gcd** (Nemesis `AimAssistD`, Axis A plan §8 step 2): flags a pitch-delta
 * GCD below 0.009 (the double Euclidean base-0.001 GCD) under a yaw/pitch-accel gate —
 * the "too-clean pitch step while locking" aim-assist fingerprint. Gated on
 * `ProtocolDetector.fullFloatLook && tp.sensitivity.valid` (fail-open pre-1.21.2 /
 * before the substrate converges) and the same combat window. Shares this check's VL
 * pool; flag level 1.0 (matches `AimTrack`).
 */
class RotationTrackingCheck : Check() {

    override val id: String = "rotationTracking"

    init {
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = TrackContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            (contextOf(ev.attacker) as TrackContext).lastAttackTick = ev.tick
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle) return
            // Distant-player skip: the 6-block aim-match target search isn't usefully observable
            // beyond the observation range. Skip the O(N) per-attacker neighborhood scan for far
            // players (it runs only past the combat gate below anyway). Same tradeoff as the §8
            // block-lookup checks (far cheaters not flagged until they approach).
            if (BlockLookupBudget.beyondObserveRange(tp)) return
            val ctx = contextOf(tp.uuid) as TrackContext
            // combat gate: only accumulate while in/around a fight; idle looking never feeds VL.
            if (tick - ctx.lastAttackTick > COMBAT_WINDOW) return
            // Sub-flag gcd (Nemesis AimAssistD, Axis A §8 step 2): too-clean pitch GCD while
            // in combat. Runs before the geometric target search — aim-assist can lock onto a
            // target just outside the 6-block match window, so the GCD signal is combat-gated,
            // not target-gated.
            gcdComponent(tp, ctx, tick)
            // find nearest other player within 6 blocks
            var best: TrackedPlayer? = null
            var bestD = 6.0
            for (other in EntityTrackerManager.all()) {
                if (other.uuid == tp.uuid) continue
                if (other.inVehicle) continue
                val d = tp.pos.distanceTo(other.pos)
                if (d < bestD) { bestD = d; best = other }
            }
            if (best == null) { push(ctx, false); return }
            val target = best!!
            val eye = tp.pos.add(0.0, tp.eyeHeight(), 0.0)
            val cx = target.pos.x - eye.x
            // Pose-aware target body center: 0.9 standing (1.8 hitbox) / 0.75 sneaking (1.5
            // hitbox). The attacker eye is already pose-aware (eyeHeight), so a pose-blind
            // target center let aimbots locking onto sneak-bridgers fall outside the ±10°
            // pitch tolerance and escape. (Gliding/swimming target centers remain ~0.4 —
            // out of scope for melee-PvP tracking; a future follow-up can extend tcenter.)
            val tcenter = if (target.sneaking) 0.75 else 0.9
            val cy = (target.pos.y + tcenter) - eye.y
            val cz = target.pos.z - eye.z
            val horiz = sqrt(cx * cx + cz * cz)
            if (horiz < 0.3) { push(ctx, false); return }
            val expYaw = Math.toDegrees(atan2(-cx, cz))
            val expPitch = Math.toDegrees(atan2(-cy, horiz))
            val yDiff = abs(Vectors.angleDiff(tp.yaw.toDouble(), expYaw))
            val pDiff = abs(tp.pitch.toDouble() - expPitch)
            // distance floor: inside 2 blocks the hitbox spans a wide angle, so ±8° is
            // trivially held by a legit close-range tracker — require a tight ±3°/±4° there.
            val match = if (horiz < 2.0) yDiff < 3.0 && pDiff < 4.0
                        else yDiff < 8.0 && pDiff < 10.0
            push(ctx, match)

            // evaluate over the rolling window
            val samples = ctx.window.size
            if (samples >= MIN_SAMPLES) {
                val rate = ctx.window.count { it }.toDouble() / samples
                if (rate > cfg.threshold) flag(tp, ctx, 1.0, "AimTrack", tick)
            }
        } catch (_: Throwable) {}
    }

    private fun push(ctx: TrackContext, v: Boolean) {
        ctx.window.addFirst(v)
        while (ctx.window.size > WINDOW) ctx.window.removeLast()
    }

    /**
     * Nemesis `AimAssistD` (Axis A, plan §8 step 2). Flags a pitch-delta GCD below
     * [PITCH_GCD_MAX] under a yaw/pitch-accel gate — the "too-clean pitch step while
     * aim-assist is locking" fingerprint. Gated on the full-float-look substrate;
     * fail-open (clears predecessors) when cold / lagging / teleported. Faithful to
     * Nemesis: flags on the 2nd consecutive hit, decays 0.005 when the GCD is clean,
     * 0.3 when the accel gate fails.
     */
    private fun gcdComponent(tp: TrackedPlayer, ctx: TrackContext, tick: Int) {
        // version + readiness gate; clear predecessor when cold
        if (!ProtocolDetector.fullFloatLook || !tp.sensitivity.valid) {
            ctx.hasChange = false; ctx.pitchGcdVl = 0.0; return
        }
        // lag gate: catch-up snaps distort deltas → don't pair a stale predecessor.
        if (tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW ||
            tick - EntityTrackerManager.lastLagBurstTick <= BURST_WINDOW
        ) {
            ctx.hasChange = false; return
        }
        // teleport guard: a >8b jump (the tracker teleport threshold) snaps rotation.
        if (tp.delta.lengthSquared() > 64.0) {
            ctx.hasChange = false; ctx.hasRotation = false; return
        }
        if (!ctx.hasRotation) {
            ctx.lastYaw = tp.yaw; ctx.lastPitch = tp.pitch; ctx.hasRotation = true; return
        }
        val yawChange = abs(tp.yaw - ctx.lastYaw)
        val pitchChange = abs(tp.pitch - ctx.lastPitch)
        ctx.lastYaw = tp.yaw; ctx.lastPitch = tp.pitch
        if (!ctx.hasChange) {
            ctx.lastYawChange = yawChange; ctx.lastPitchChange = pitchChange; ctx.hasChange = true; return
        }
        val yawAccel = abs(ctx.lastYawChange - yawChange)
        val pitchAccel = abs(ctx.lastPitchChange - pitchChange)
        val gate = yawChange > YAW_CHANGE_MIN && pitchChange < PITCH_CHANGE_MAX &&
            yawAccel > ACCEL_MIN && pitchAccel > ACCEL_MIN && pitchChange < yawChange
        if (gate) {
            val pitchGcd = gcd(pitchChange.toDouble(), ctx.lastPitchChange.toDouble())
            if (pitchGcd < PITCH_GCD_MAX) {
                ctx.pitchGcdVl += 1.0
                if (ctx.pitchGcdVl > 1.0) {  // Nemesis: flag on the 2nd hit
                    flag(tp, ctx, VL_GCD, "gcd", tick, Evidence(
                        pos = tp.pos, measurement = pitchGcd, threshold = PITCH_GCD_MAX,
                        extra = "vl=${ctx.pitchGcdVl} sens=${tp.sensitivity.sensitivity}"))
                }
            } else {
                ctx.pitchGcdVl = maxOf(0.0, ctx.pitchGcdVl - 0.005)  // Nemesis decreaseVl(0.005)
            }
        } else {
            ctx.pitchGcdVl = maxOf(0.0, ctx.pitchGcdVl - 0.3)  // Nemesis decreaseVl(0.3)
        }
        ctx.lastYawChange = yawChange; ctx.lastPitchChange = pitchChange
    }

    private class TrackContext : CheckContext() {
        val window = ArrayDeque<Boolean>()
        var lastAttackTick: Int = -10000
        // sub-flag gcd (Nemesis AimAssistD, Axis A §8 step 2)
        var lastYaw: Float = 0f
        var lastPitch: Float = 0f
        var hasRotation: Boolean = false
        var lastYawChange: Float = 0f
        var lastPitchChange: Float = 0f
        var hasChange: Boolean = false
        var pitchGcdVl: Double = 0.0
    }

    private companion object {
        /** Rolling sample window (ticks). */
        const val WINDOW = 60
        /** Min samples before judging the match rate. */
        const val MIN_SAMPLES = 60
        /** Ticks after an attack during which samples accumulate (combat window). */
        const val COMBAT_WINDOW = 60

        // -- sub-flag gcd: Nemesis AimAssistD (Axis A §8 step 2) --
        /** Flag level for the aim-assist GCD sub-flag (matches the AimTrack flag level). */
        const val VL_GCD = 1.0
        /** Nemesis floor: pitch GCD below this (double Euclidean, base 0.001) is sub-mouse-grid. */
        const val PITCH_GCD_MAX = 0.009
        /** Nemesis accel gate: a real flick has yaw motion + both-axis accel; pitch < yaw. */
        const val YAW_CHANGE_MIN = 3.0f
        const val PITCH_CHANGE_MAX = 10.0f
        const val ACCEL_MIN = 2.0f
        /** Standard lag gate (§1.8 posture + step-1 sensitivity feed). */
        const val LAG_WINDOW = 8
        const val BURST_WINDOW = 3
    }
}
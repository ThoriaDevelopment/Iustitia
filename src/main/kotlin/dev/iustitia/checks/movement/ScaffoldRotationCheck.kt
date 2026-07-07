package dev.iustitia.checks.movement

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.ConfigManager
import dev.iustitia.event.SwingSignal
import dev.iustitia.history.Evidence
import dev.iustitia.math.Vectors
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.world.WorldQueries
import net.minecraft.client.MinecraftClient
import net.minecraft.item.BlockItem
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round

/**
 * Scaffold / BridgeAssist detector with two complementary paths sharing one VL pool:
 *
 * 1. **Scaffold (rotation-snap / godbridge)**: the cheat snaps pitch to ~[threshold] (78°)
 *    (±3) and yaw to a cardinal/block-face direction (nearest 45° multiple) at a regular
 *    cadence while bridging — and, distinctively, does so while *airborne* (places blocks
 *    mid-air as it moves over the gap), where a legit bridger mostly stands on the bridge
 *    they've built. Flag when pitch locked near 78°, yaw near-cardinal, moving horizontally
 *    AND not grounded (over air), sustained ≥8 ticks. No block-update mixin needed.
 *
 * 2. **LegitScaffold (sneak-tap bridge)** — ported from Rain-Anticheat's 1.8.9
 *    `LegitScaffoldCheck`. The "legit scaffold" cheat taps sneak for 1–2 ticks right as it
 *    places each block (to edge-place without falling), swinging at the crouch-end, looking
 *    down (pitch ≥ 60°) while holding a block on the ground. The fingerprint is the
 *    *consistently short* crouch (≤2 ticks) + swing timed to crouch-end, repeated. Rain's
 *    exact values: pitch ≥ 60, crouchDuration ∈ 1..2, swing ∈ [crouchEnd, crouchEnd+1],
 *    last 3 crouch durations all ≤ 2. Rain's per-flag 60-tick cooldown was DROPPED: it was
 *    incompatible with Iustitia's added per-tick VL decay (decay 0.5 × 60-tick cooldown
 *    drained 30× the 1.0 flag before the next flag was even allowed, so vl oscillated
 *    0→1.0→0 and the path could never reach setbackVL 5.0 — it was dead). AlertManager's
 *    40-tick per-(player,check) chat throttle already prevents spam; verbose fires per-flag
 *    by design. Decay was lowered 0.5→0.05 to match rotationTracking so a real run of flags
 *    can climb to setback. Optional strict gates (motion + cadence + 5-short-crouches) sit
 *    behind `config.legitScaffoldStrictGates` (default off) for A/B-testing a hypothetical
 *    stationary-builder FP once one is actually observed.
 *
 * 3. **Scaffold(Clutch)** (Slinky Clutch snap-back round-trip, plan §3/§8 step 10): the cheat
 *    silently rotates to the place target then snaps back to the travel heading so the local
 *    camera stays steady — but the server-broadcast yaw shows the round-trip the player never
 *    initiated. Observable WITHOUT block-place ground truth: a single-tick yaw excursion
 *    (≥ [CLUTCH_SNAP]) followed within [CLUTCH_WINDOW] ticks by a return to < [CLUTCH_RETURN]
 *    of the pre-excursion heading, with a swing (the place) in the window. The discriminator
 *    is the **return-identity**, not the speed — a hand cannot return a ≥50° excursion to
 *    <3° of where it started within 4 ticks; a mechanical snap-back to the stored heading
 *    does. Repeated ≥ [CLUTCH_REPEAT] round-trips in [CLUTCH_EPISODE] ticks (transition-gated
 *    one-shot per episode). This separates an automated clutch from a manual one (a manual
 *    clutch returns to ~5–10°, not <3°, and isn't repeated 3× in 40 ticks while falling).
 *
 * **Deferred sub-signals (plan §3/§8.10 — NOT observer-feasible without expanding the mixin
 * surface; documented per the §1.8 "observer-feasible" gate):**
 *  - **RotationPlace ray-vs-block-box** (Grim): "does the look ray actually hit the block face
 *    that was placed?" requires the *placed-block position*. Iustitia has no block-place /
 *    block-update observation — `ClientPlayNetworkHandlerMixin` is the single read-only mixin
 *    (swing/hurt/velocity/gamejoin/effect only); a block-update (BlockUpdateS2CPacket) inject
 *    + a placer-inference layer would be needed. Deferred pending that mixin expansion.
 *  - **DuplicateRotPlace** pitch-delta-identity (`xDiff<0.0001, deltaX>2`): needs placement-event
 *    timing to be meaningful, and overlaps this check's existing pitch~78+cardinal lock — deferred
 *    with RotationPlace (the placement-timing dependency is the blocker, not the geometry).
 *  - **Tower motionY 0.4195** (Raven `tower motionY = 0.42 - 0.000454352838557992`): the
 *    constant differs from the vanilla 0.42 jump impulse by 0.00045 — *below client-side
 *    interpolation noise* (per-sample noise > the gap; a mean-convergence test needs ~123–500
 *    jump-impulses, far too slow for a responsive check). The tower's ~7-tick jump-land cycle
 *    is identical to legit pillar-jumping, so the cadence is not a discriminator either. Only
 *    the server (exact Y, no interpolation) can read this fingerprint. Not implemented.
 *  - **Bridge Assist Randomize edge-offset** (Slinky): the *distribution shape* of the placed-
 *    block edge-offset is the tell — requires the placed-block position. Same blocker as
 *    RotationPlace. Deferred pending a block-update mixin.
 *
 * **legitScaffold strict-gate validation (plan §8.10):** the strict gates remain default-off
 * behind `config.legitScaffoldStrictGates` pending validation against a real cheater — a
 * manual task that cannot run in this environment. The baseline Rain path runs unguarded;
 * flip the strict gates on once a real LegitScaffold cheater is confirmed and an FP is
 * actually observed.
 *
 * setbackVL 5, decay 0.05/tick.
 */
class ScaffoldRotationCheck : Check() {

    override val id: String = "scaffoldRotation"

    init {
        try { Iustitia.bus.subscribe<SwingSignal> { onSwing(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = ScaffoldContext()

    private fun onSwing(sig: SwingSignal) {
        try {
            (contextOf(sig.attacker) as ScaffoldContext).lastSwingTick = sig.tick
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding || tp.swimming) return
            if (tick - tp.lastTeleportTick < 10) return
            // Server-lag exemption: a server-wide hitch / catch-up burst injects a position/
            // rotation jump that would trip the rotation-snap and legit-scaffold signatures.
            // Skip both sub-paths for the window so lag never poisons either streak/history.
            if (tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW ||
                tick - EntityTrackerManager.lastLagBurstTick <= BURST_WINDOW
            ) return
            val ctx = contextOf(tp.uuid) as ScaffoldContext

            rotationPath(tp, ctx, tick)
            legitScaffoldPath(tp, ctx, tick)
            clutchPath(tp, ctx, tick)
        } catch (_: Throwable) {}
    }

    /** Rotation-snap godbridge: pitch~78 + cardinal yaw + airborne, sustained ≥8 ticks. */
    private fun rotationPath(tp: TrackedPlayer, ctx: ScaffoldContext, tick: Int) {
        val horiz = hypot(tp.delta.x, tp.delta.z)
        if (horiz < 0.05) { ctx.streak = 0; return }
        val pitchOk = abs(tp.pitch - cfg.threshold) < 3.0f
        // cardinal yaw: within 4° of the nearest 45° multiple
        val nearest = round(tp.yaw / 45.0) * 45.0
        val yawOk = abs(Vectors.angleDiff(tp.yaw.toDouble(), nearest)) < 4.0
        if (!pitchOk || !yawOk) { ctx.streak = 0; return }
        // airborne over air: not grounded and no solid block just below. Chunk not loaded →
        // bail (fail-negative): an unloaded "below" must not produce an airborne flag at
        // chunk borders, per the chunk-unloaded-never-FP rule. isSolidBelow returns false on
        // unloaded chunks, so without this guard a missing-in-unloaded-chunk floor would
        // false-flag a godbridge over the edge of loaded terrain.
        val world = MinecraftClient.getInstance().world
        val bx = Math.floor(tp.pos.x).toInt()
        val bz = Math.floor(tp.pos.z).toInt()
        if (world == null || !WorldQueries.isChunkLoaded(world, bx, bz)) { ctx.streak = 0; return }
        val airborne = !tp.groundedProxy &&
            !WorldQueries.isSolidBelow(world, tp.pos.x, tp.pos.y, tp.pos.z, 0.3)
        if (airborne) {
            ctx.streak++
            if (ctx.streak >= 8) flag(tp, ctx, 1.0, "Scaffold", tick)
        } else {
            ctx.streak = 0
        }
    }

    /** LegitScaffold (Rain port): short crouch + swing-at-crouch-end + looking down holding a block. */
    private fun legitScaffoldPath(tp: TrackedPlayer, ctx: ScaffoldContext, tick: Int) {
        // track crouch transitions for the duration history
        val currSneak = tp.sneaking
        if (currSneak && !ctx.wasSneaking) {
            ctx.lastCrouchStart = tick
        } else if (!currSneak && ctx.wasSneaking) {
            ctx.lastCrouchEnd = tick
            val duration = tick - ctx.lastCrouchStart
            ctx.crouchDurations.addFirst(duration)
            if (ctx.crouchDurations.size > 5) ctx.crouchDurations.removeLast()
            ctx.crouchEndTicks.addFirst(tick)
            if (ctx.crouchEndTicks.size > 5) ctx.crouchEndTicks.removeLast()
        }
        ctx.wasSneaking = currSneak

        // Rain gates on pitch >= 60, holding an ItemBlock, and onGround.
        if (tp.pitch < LEGIT_PITCH_MIN) return
        if (!tp.onGroundPacket && !tp.groundedProxy) return
        // holding a block in EITHER hand: 1.9+ standard bridge layout keeps blocks off-hand
        // (pickaxe/eat main). Rain's getHeldItem() predates the off-hand slot, so the port
        // must also accept an off-hand BlockItem — otherwise off-hand bridgers fully bypass
        // the path. Both reads independently try/catch-wrapped to preserve fail-open granularity.
        val mainBlock = try { tp.entity?.mainHandStack?.item is BlockItem } catch (_: Throwable) { false }
        val offBlock = try { tp.entity?.offHandStack?.item is BlockItem } catch (_: Throwable) { false }
        if (!mainBlock && !offBlock) return

        val end = ctx.lastCrouchEnd
        val swing = ctx.lastSwingTick
        val crouchDuration = end - ctx.lastCrouchStart
        val quickCrouch = crouchDuration in 1..2
        // lastSwingTick is Int.MIN_VALUE until the first swing; this is a range-containment
        // test (never subtracted), so the sentinel is safe and yields swingTiming=false pre-swing.
        val swingTiming = swing in end..end + 1
        val consistent = ctx.crouchDurations.size >= 3 &&
            ctx.crouchDurations[0] <= 2 && ctx.crouchDurations[1] <= 2 && ctx.crouchDurations[2] <= 2
        if (quickCrouch && swingTiming && consistent) {
            // Optional strict gates (config flag, default off). The baseline Rain path fires
            // on a stationary sneak-tapper looking down holding a block — a real legit builder
            // edge-placing at a ceiling/wall. Bridging always moves and has a regular cadence,
            // so three sub-gates separate sustained in-motion bridging from incidental taps.
            // Kept behind a flag because the FP is hypothetical (not seen in any validation
            // run) and the path isn't yet validated against real cheaters — flip on once an FP
            // actually surfaces, off otherwise so the true-positive path runs unguarded.
            if (ConfigManager.config.legitScaffoldStrictGates) {
                // (1) horizontal motion at the crouch-end tick — bridging always moves.
                if (hypot(tp.delta.x, tp.delta.z) < STRICT_MOTION_MIN) return
                // (2) cadence: last 3 crouch-ends within 40 ticks — sustained bridging places a
                //     block every few ticks; incidental sneaks are spread out.
                if (ctx.crouchEndTicks.size < 3 ||
                    ctx.crouchEndTicks[0] - ctx.crouchEndTicks[2] > STRICT_CADENCE_SPAN
                ) return
                // (3) last 5 crouches all short (<=2) — real LegitScaffold produces a long run of
                //     <=2-tick taps; this excludes incidental 3-tap bursts.
                if (ctx.crouchDurations.size < 5 ||
                    ctx.crouchDurations[0] > 2 || ctx.crouchDurations[1] > 2 ||
                    ctx.crouchDurations[2] > 2 || ctx.crouchDurations[3] > 2 ||
                    ctx.crouchDurations[4] > 2
                ) return
            }
            flag(tp, ctx, 1.0, "LegitScaffold", tick)
        }
    }

    /**
     * Scaffold(Clutch) — Slinky Clutch snap-back round-trip (plan §3/§8 step 10). The cheat
     * silently rotates to the place target then snaps back to the travel heading; the
     * server-broadcast yaw shows a round-trip the player never initiated. Observable without
     * block-place ground truth — the discriminator is the **return-identity**: a single-tick
     * yaw excursion (≥ [CLUTCH_SNAP]) followed within [CLUTCH_WINDOW] ticks by an opposite-sign
     * snap that returns the yaw to < [CLUTCH_RETURN] of the pre-excursion heading (a hand
     * cannot return a ≥50° excursion to <3°; a mechanical snap-back to the stored heading
     * does), with a swing (the place) in the window. Repeated ≥ [CLUTCH_REPEAT] round-trips
     * in [CLUTCH_EPISODE] ticks; transition-gated one flag per episode.
     */
    private fun clutchPath(tp: TrackedPlayer, ctx: ScaffoldContext, tick: Int) {
        val dYaw = Vectors.angleDiff(tp.yaw.toDouble(), tp.lastYaw.toDouble())
        val ad = abs(dYaw)
        if (ad >= CLUTCH_SNAP) {
            val sign = if (dYaw > 0.0) 1 else -1
            // Pair an opposite-sign snap within the window whose yaw returns to the pre-out
            // heading — that's the round-trip. A same-sign or out-of-window snap becomes a new
            // pending snap-out (its pre-yaw is tp.lastYaw, the heading before this tick's jump).
            if (ctx.clutchSnapSign != 0 && sign != ctx.clutchSnapSign &&
                tick - ctx.clutchSnapTick in 1..CLUTCH_WINDOW &&
                abs(Vectors.angleDiff(tp.yaw.toDouble(), ctx.clutchPreYaw.toDouble())) < CLUTCH_RETURN
            ) {
                // require the place swing inside the excursion window
                if (ctx.lastSwingTick in ctx.clutchSnapTick..tick) {
                    ctx.clutchCount++
                    ctx.lastClutchTripTick = tick
                    if (ctx.clutchCount >= CLUTCH_REPEAT && !ctx.clutchActive) {
                        ctx.clutchActive = true
                        val ret = Vectors.angleDiff(tp.yaw.toDouble(), ctx.clutchPreYaw.toDouble())
                        flag(tp, ctx, 1.0, "Scaffold(Clutch)", tick, Evidence(
                            subLabel = "snap-back-round-trip", measurement = ad,
                            threshold = CLUTCH_SNAP, pos = tp.pos,
                            extra = "returnΔ=${"%.3f".format(ret)} count=${ctx.clutchCount}"))
                    }
                }
                // round-trip consumed — clear the pending snap so the next out is fresh.
                ctx.clutchSnapSign = 0
            } else {
                ctx.clutchPreYaw = tp.lastYaw
                ctx.clutchSnapTick = tick
                ctx.clutchSnapSign = sign
            }
        } else if (ctx.clutchSnapSign != 0 && tick - ctx.clutchSnapTick > CLUTCH_WINDOW) {
            // pending snap-out expired without a return — drop it (a stale out must not pair
            // with an unrelated later snap).
            ctx.clutchSnapSign = 0
        }
        // Re-arm the transition gate + reset the episode after a quiet period (no round-trip
        // for CLUTCH_EPISODE ticks). clutchActive starts false so the initial Int.MIN_VALUE
        // lastClutchTripTick never reaches this branch (no overflow).
        if (ctx.clutchActive && tick - ctx.lastClutchTripTick > CLUTCH_EPISODE) {
            ctx.clutchActive = false
            ctx.clutchCount = 0
        }
    }

    private class ScaffoldContext : CheckContext() {
        // rotation path
        var streak: Int = 0
        // legit-scaffold path (Rain port)
        var wasSneaking: Boolean = false
        var lastCrouchStart: Int = 0
        var lastCrouchEnd: Int = 0
        var lastSwingTick: Int = Int.MIN_VALUE
        val crouchDurations: ArrayDeque<Int> = ArrayDeque()
        // crouch-end tick history (newest first) for the strict cadence gate.
        val crouchEndTicks: ArrayDeque<Int> = ArrayDeque()
        // clutch (yaw round-trip) path
        /** Heading (yaw) before the pending snap-out — the return target for the snap-back. */
        var clutchPreYaw: Float = 0f
        /** Tick of the pending snap-out (Int.MIN_VALUE sentinel safe: only read when clutchSnapSign != 0). */
        var clutchSnapTick: Int = Int.MIN_VALUE
        /** Sign of the pending snap-out (±1); 0 = no pending snap. */
        var clutchSnapSign: Int = 0
        /** Completed round-trips in the current episode. */
        var clutchCount: Int = 0
        /** Tick of the last completed round-trip (transition-gate re-arm + episode reset). */
        var lastClutchTripTick: Int = -10000
        /** Transition gate: true once [CLUTCH_REPEAT] round-trips fired this episode (one flag/episode). */
        var clutchActive: Boolean = false
    }

    companion object {
        // Rain LegitScaffold values (ported verbatim).
        private const val LEGIT_PITCH_MIN = 60.0f
        // Strict-gate constants (only applied when config.legitScaffoldStrictGates is on).
        private const val STRICT_MOTION_MIN = 0.03
        private const val STRICT_CADENCE_SPAN = 40
        /** Window (ticks) after a server-wide freeze within which scaffold samples are skipped. */
        private const val LAG_WINDOW = 8
        /** Window (ticks) after a batched catch-up burst within which scaffold samples are skipped. */
        private const val BURST_WINDOW = 3

        // -- Scaffold(Clutch) snap-back round-trip (Slinky, plan §3/§8 step 10) --
        /** Min single-tick yaw excursion (°) to start a clutch round-trip — a meaningful rotation
         *  to a place target, not a tiny aim adjustment. Tuned in step 14. */
        const val CLUTCH_SNAP = 50.0
        /** The snap-back must return the yaw to within this (°) of the pre-excursion heading — the
         *  return-identity tell. A hand returns a ≥50° excursion to ~5–10°, not <3°. Tuned in step 14. */
        const val CLUTCH_RETURN = 3.0
        /** Max ticks between the snap-out and the snap-back for a clean round-trip (automated speed). */
        const val CLUTCH_WINDOW = 4
        /** Min completed round-trips in [CLUTCH_EPISODE] ticks to flag — repeated mechanical clutching. */
        const val CLUTCH_REPEAT = 3
        /** Episode window (ticks) for the round-trip count + the transition-gate re-arm. */
        const val CLUTCH_EPISODE = 40
    }
}
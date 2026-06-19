package dev.iustitia.checks.movement

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.ConfigManager
import dev.iustitia.event.SwingSignal
import dev.iustitia.math.Vectors
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
            val ctx = contextOf(tp.uuid) as ScaffoldContext

            rotationPath(tp, ctx, tick)
            legitScaffoldPath(tp, ctx, tick)
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
    }

    companion object {
        // Rain LegitScaffold values (ported verbatim).
        private const val LEGIT_PITCH_MIN = 60.0f
        // Strict-gate constants (only applied when config.legitScaffoldStrictGates is on).
        private const val STRICT_MOTION_MIN = 0.03
        private const val STRICT_CADENCE_SPAN = 40
    }
}
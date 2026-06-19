package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.event.AttackEvent
import dev.iustitia.event.SwingSignal
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.world.WorldQueries
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Unified killaura / silent-aim detector, ported from Rain-Anticheat's 1.8.9
 * `KillauraCheck` (the flagship, ~34 KB). Rain is *also* a client-sided observer
 * of other players — the same architecture as Iustitia — so its detection logic
 * ports directly, unlike MX's server-side ML. Seven sub-components share one VL
 * pool and one alert id (`killAura`), each flagged with a distinct label so the
 * chat alert shows which signal fired.
 *
 * **Silent-aim background** (Rain's comment, verbatim intent): the cheat leaves
 * the cheater's own camera untouched and only swaps yaw/pitch inside outgoing
 * look packets. The server believes those packets and rebroadcasts them in entity
 * look packets, so from our observer position the cheater's head visibly *snaps*
 * onto enemies even though their screen never moved. That rebroadcast rotation
 * stream is what we sample here. On 1.8 it is byte-quantized to 1.40625°
 * (360/256) and interpolated over up to 3 ticks; on 1.21.11 it is full-precision
 * floats — silent-aim snaps resolve even *more* cleanly, so the same thresholds
 * (expressed in degrees) apply. `QUANTUM` is kept as the angular tolerance unit.
 *
 * Components (all feeding this check's VL pool; levels scaled to our 0–10 setback
 * economy from Rain's 0–400 shared pool):
 *  - **heuristic(aim|constant|sync)**: windowed "robotized" rotation counts —
 *    yaw changes simultaneously large and nearly identical to the window's first
 *    change (MX AimBasicCheck port). Tolerances rescaled to quantization steps.
 *  - **pattern(snap)**: both-direction big-jump oscillation streak (multi-target
 *    switching / snap-and-return spam).
 *  - **silent(snap)**: a yaw burst (1–7 ticks, same direction, ≥20° total) that
 *    *started* >20° off every nearby player and *settled* inside someone's hitbox
 *    bearing within ~1 quantum. Promoted to alert on the 3rd qualifying hit
 *    (Rain: field-confirmed accurate) — `level` 2.5 so 3 hits clear setbackVL 5.
 *  - **silent(return)**: the mirror burst off the target right after a snap hit
 *    — the attack-tick-only silent-aim signature.
 *  - **silent(track)**: yaw stays inside a target's hitbox bearing on ≥85% of
 *    ticks where the line of sight to that target is rotating fast (strafing
 *    fights). Humans drift off; lock-on auras don't.
 *  - **movement(fix|lock|sprint)**: the body/head desync "movement fix" silent
 *    aim introduces — the body keeps moving along the real screen yaw while the
 *    packet yaw is snapped to a target. Vanilla ground movement bears on a
 *    multiple of 45° off the broadcast yaw; a movement fix drifts arbitrarily,
 *    so the window-MEAN bucket residual separates them. Plus residual while the
 *    head is pinned inside a target's span, and sprint speed beyond the ±45°
 *    sprintable cone.
 *  - **consume**: swinging at entities while using an item (Rain's old Killaura
 *    B) — driven by the inferred [AttackEvent] + `tp.usingItem` sustained.
 *
 * Not ported from MX (needs exact packet floats / known sensitivity, destroyed
 * by quantization + interpolation): GCD/sensitivity, exact 0.1/0.01 deltas,
 * 1e-4 accel patterns, Shannon entropy, jolt duplicate statistics, ML modules.
 *
 * setbackVL 5, decay 0.05/tick (slow — these are sustained/corroborating
 * behaviors; a single sub-flag shouldn't reset the pool).
 */
class KillAuraCheck : Check() {

    override val id: String = "killAura"

    init {
        try { Iustitia.bus.subscribe<SwingSignal> { onSwing(it) } } catch (_: Throwable) {}
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = KillAuraContext()

    // ---- shared geometry / burst / track constants (port verbatim from Rain) ----
    /** Sentinel for "never" tick fields (Int.MIN_VALUE; tick counters are small positives). */
    private val NO_TICK = Int.MIN_VALUE
    /** MX gates aim analysis to 3500ms after an attack; 70 ticks ~ 3.5s. */
    private val COMBAT_WINDOW_TICKS = 70
    /** Out of combat this long -> per-fight counters reset. */
    private val SESSION_RESET_TICKS = 140
    /** AimBasicCheck analyzes windows of 10 non-zero rotations. */
    private val WINDOW_SIZE = 10
    /** Quantization step of observed remote rotations (360 / 256), used as angular tolerance. */
    private val QUANTUM = 1.40625f

    // silent(snap) burst machine
    private val BURST_STEP_MIN = 7.0f
    private val BURST_QUIET = 2.5f
    private val BURST_MAX_TICKS = 7
    private val BURST_SUM_MIN = 20.0f
    private val SNAP_PRE_ERROR_MIN = 20.0f
    private val SNAP_MIN_HITS = 3
    private val RETURN_PAIR_TICKS = 8

    // silent(track)
    private val TRACK_WINDOW = 24
    private val TRACK_RATIO = 0.85f
    private val TRACK_LOS_MIN = 2.5f
    private val TRACK_LOS_MAX = 45.0f
    private val TRACK_MIN_DIST = 2.2
    private val TRACK_INSIDE_TOL = QUANTUM * 0.5f

    // movement(fix) desync
    private val MOVE_MIN_SPEED = 0.15
    private val MOVE_MAX_SPEED = 0.45
    private val MOVE_FLAT_DY = 0.001
    private val MOVE_SMOOTH_ACCEL = 0.022
    private val MOVE_WINDOW = 12
    private val MOVE_MEAN_LIMIT = 7.5f
    private val MOVE_DESYNC_RESIDUAL = 8.0f
    private val LOCK_RESIDUAL = 13.0f
    private val LOCK_HITS = 3
    private val LOCK_TOL = QUANTUM
    private val SPRINT_ACCEL = 0.08
    private val SPRINT_MIN_SPEED = 0.25
    private val SPRINT_OFFSET = 62.0f
    private val SPRINT_HITS = 4
    private val MOVE_DECAY_TICKS = 40

    /** Aura target search radius around the attacker (blocks, squared). */
    private val TARGET_RANGE_SQ = 36.0
    /** Half hitbox width (0.3) plus margin, for bearing-span tests. */
    private val HITBOX_HALF_WIDTH = 0.4
    private val TRAIL_LEN = 5

    // consume component
    private val EAT_TIMEOUT = 33
    private val MIN_USE_TIME = 6

    // ---- VL weights scaled to our 0–10 setback economy (Rain 0–400 pool / ~10) ----
    private val VL_AIM = 2.0
    private val VL_CONSTANT = 1.3
    private val VL_SYNC = 1.0
    private val VL_PATTERN_SNAP = 1.1
    private val VL_SILENT_SNAP = 2.5
    private val VL_SILENT_RETURN = 1.5
    private val VL_SILENT_TRACK = 1.6
    private val VL_MOVE_FIX = 1.4
    private val VL_MOVE_LOCK = 1.7
    private val VL_MOVE_SPRINT = 1.7
    private val VL_CONSUME = 1.0

    /** Per-player position history (newest at 0), shared across all attackers as target trails. */
    private val trails = ConcurrentHashMap<UUID, Trail>()

    private fun onSwing(sig: SwingSignal) {
        try {
            (contextOf(sig.attacker) as KillAuraContext).lastSwingTick = sig.tick
        } catch (_: Throwable) {}
    }

    private fun onAttack(ev: AttackEvent) {
        try {
            val attacker = EntityTrackerManager.get(ev.attacker) ?: return
            val ctx = contextOf(ev.attacker) as KillAuraContext
            ctx.lastAttackTick = ev.tick
            // consume: an inferred attack while a consumable (eat/drink) has been held for
            // >MIN_USE_TIME. Gated on isUsingConsumable, not bare usingItem — shields (BLOCK)
            // and bows (BOW) also set usingItem but are not the killaura-while-eating signal,
            // and bare usingItem double-flags a legit shield-blocker with AutoBlock. The
            // lastEatTick gate is a post-eat cooldown; guard the subtraction because
            // lastEatTick starts at NO_TICK (Int.MIN_VALUE) — `ev.tick - Int.MIN_VALUE`
            // overflows Int and wraps negative, which would make this always true (flagging
            // before any real eat). The NO_TICK case = still in the first eat, where
            // attacking-while-eating IS the consume signal, so we flag there too.
            if (attacker.isUsingConsumable && ctx.useItemTicks > MIN_USE_TIME &&
                (ctx.lastEatTick == NO_TICK || ev.tick - ctx.lastEatTick < EAT_TIMEOUT)
            ) {
                flag(attacker, ctx, VL_CONSUME, "Consume", ev.tick)
            }
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle) return // vehicle rotations/consume timing are unreliable
            val ctx = contextOf(tp.uuid) as KillAuraContext

            // Keep position history fresh for this player AND the local observer — both
            // are bearing candidates when someone else is the attacker (Rain pushes both).
            trail(tp.uuid).push(tp.pos.x, tp.pos.z, tick)
            pushObserverTrail(tick)

            // consume timing: count sustained consumable (eat/drink) ticks. isUsingConsumable
            // excludes shields/bows so a legit blocker doesn't accumulate an eat window.
            if (tp.isUsingConsumable) {
                ctx.useItemTicks++
            } else {
                if (ctx.useItemTicks > 0) ctx.lastEatTick = tick
                ctx.useItemTicks = 0
            }

            // rotation stream
            val yaw = tp.yaw
            val pitch = tp.pitch
            if (!ctx.hasRotation) {
                ctx.lastYaw = yaw; ctx.lastPitch = pitch; ctx.hasRotation = true
                return
            }
            val prevYaw = ctx.lastYaw
            val yawChange = wrapDegrees(yaw - ctx.lastYaw)
            val pitchChange = wrapDegrees(pitch - ctx.lastPitch)
            ctx.lastYaw = yaw
            ctx.lastPitch = pitch

            // Teleport/lag guard: a large position step also snaps observed rotation,
            // which would poison every rotation component with a false "snap".
            val moveX = tp.delta.x
            val moveY = tp.deltaY
            val moveZ = tp.delta.z
            if (moveX * moveX + moveZ * moveZ > 25.0) {
                ctx.yawChangeWindow.clear()
                resetBurst(ctx)
                ctx.lastBearing = Float.NaN
                ctx.lastTargetId = null
                ctx.hasVel = false
                ctx.moveSamples = 0
                ctx.moveDesyncTicks = 0
                ctx.residualSum = 0.0f
                return
            }

            // AimHeuristicCheck.event(): rotations outside the attack window are ignored.
            if (ctx.lastSwingTick == NO_TICK ||
                tick < ctx.lastSwingTick ||
                tick - ctx.lastSwingTick > COMBAT_WINDOW_TICKS
            ) {
                if (ctx.lastSwingTick != NO_TICK &&
                    tick - ctx.lastSwingTick > SESSION_RESET_TICKS
                ) {
                    resetSession(ctx)
                }
                return
            }

            // component: windowed rotation heuristics (AimBasicCheck port)
            val absYawChange = abs(yawChange)
            val absPitchChange = abs(pitchChange)
            if (absYawChange != 0.0f || absPitchChange != 0.0f) {
                ctx.yawChangeWindow.add(absYawChange)
                if (ctx.yawChangeWindow.size >= WINDOW_SIZE) {
                    analyzeWindow(tp, ctx, ctx.yawChangeWindow, tick)
                    ctx.yawChangeWindow.clear()
                }
            }

            // geometry components share one candidate scan per tick
            val targets = targetsNear(tp, tick)
            burstMachine(tp, ctx, tick, yawChange, prevYaw, targets)
            trackComponent(tp, ctx, yaw, targets, tick)
            movementComponent(tp, ctx, moveX, moveY, moveZ, yaw, targets, tick)

            // prune stale trails so despawned players don't accumulate
            if (trails.size > 80) {
                val it = trails.entries.iterator()
                while (it.hasNext()) { if (it.next().value.lastTick < tick - 200) it.remove() }
            }
        } catch (_: Throwable) {
            // fail-open: never crash the tick
        }
    }

    /** Port of AimBasicCheck.checkDefaultAim(): robotized rotation + snap pattern. */
    private fun analyzeWindow(tp: TrackedPlayer, ctx: KillAuraContext, window: List<Float>, tick: Int) {
        val yawChangeFirst = window[0]
        var oldYawChange = yawChangeFirst
        var machineKnownMovement = 0
        var constantRotations = 0
        var robotizedAmount = 0
        var bigSwingUp = 0
        var bigSwingDown = 0

        for (yawChange in window) {
            val robotized = abs(yawChange - yawChangeFirst)
            val diffBetweenYawChanges = yawChange - oldYawChange
            if (robotized < QUANTUM * 1.5f && yawChange > QUANTUM * 2.0f) ++robotizedAmount
            if (robotized < QUANTUM && yawChange > QUANTUM * 3.0f) ++machineKnownMovement
            if (robotized < QUANTUM * 0.5f && yawChange > QUANTUM * 2.5f) ++constantRotations
            // AimBasicCheck +-2 on raw floats; observed data needs real snaps -> +-12.
            if (diffBetweenYawChanges > 12.0f) ++bigSwingUp
            if (diffBetweenYawChanges < -12.0f) ++bigSwingDown
            oldYawChange = yawChange
        }

        if (machineKnownMovement > 8) flag(tp, ctx, VL_AIM, "heuristic(aim)", tick)
        if (constantRotations > 6) flag(tp, ctx, VL_CONSTANT, "heuristic(constant)", tick)
        if (robotizedAmount > 8) flag(tp, ctx, VL_SYNC, "heuristic(sync)", tick)

        // pattern(snap): both-direction big jumps, persistent >2 windows
        if (bigSwingUp > 1 && bigSwingDown > 1 && bigSwingUp + bigSwingDown > 4) {
            ++ctx.snapStreak
            if (ctx.snapStreak > 2) flag(tp, ctx, VL_PATTERN_SNAP, "pattern(snap)", tick)
        } else {
            ctx.snapStreak = 0
        }
    }

    /** silent(snap)/silent(return): detect a yaw burst and judge where it settled. */
    private fun burstMachine(tp: TrackedPlayer, ctx: KillAuraContext, tick: Int,
                             yawChange: Float, prevYaw: Float, targets: List<TrackedPlayer>) {
        val absYaw = abs(yawChange)

        if (ctx.burstTicks > 0) {
            val sameDir = yawChange * ctx.burstDir >= 0.0f
            if (absYaw < BURST_QUIET) {
                if (ctx.burstSum >= BURST_SUM_MIN) evaluateBurst(tp, ctx, tick, targets, prevYaw)
                resetBurst(ctx)
                ctx.quietTicks = 1
            } else if (sameDir) {
                ++ctx.burstTicks
                ctx.burstSum += absYaw
                if (ctx.burstTicks > BURST_MAX_TICKS) ctx.burstTicks = -1 // sustained turn (swipe)
            } else if (absYaw > BURST_STEP_MIN) { // hard direction flip: new burst
                ctx.burstTicks = 1
                ctx.burstSum = absYaw
                ctx.burstDir = yawChange
                ctx.preBurstYaw = prevYaw
                ctx.quietTicks = 0
            } else {
                resetBurst(ctx); ctx.quietTicks = 0
            }
        } else if (ctx.burstTicks == -1) {
            if (absYaw < BURST_QUIET) { resetBurst(ctx); ctx.quietTicks = 1 }
        } else {
            if (absYaw > BURST_STEP_MIN && ctx.quietTicks >= 2) {
                ctx.burstTicks = 1
                ctx.burstSum = absYaw
                ctx.burstDir = yawChange
                ctx.preBurstYaw = prevYaw
                ctx.quietTicks = 0
            } else if (absYaw < BURST_QUIET) {
                ++ctx.quietTicks
            } else {
                ctx.quietTicks = 0
            }
        }
    }

    private fun evaluateBurst(tp: TrackedPlayer, ctx: KillAuraContext, tick: Int,
                              targets: List<TrackedPlayer>, prevYaw: Float) {
        if (targets.isEmpty()) return // nobody near — flick is meaningless either way
        var bestErr = Float.MAX_VALUE
        var bestPre = 0.0f
        var bestPreInside = Float.MAX_VALUE
        for (target in targets) {
            val trail = trail(target.uuid)
            val err = minInsideError(tp, trail, ctx.lastYaw)
            if (err < bestErr) {
                bestErr = err
                val bearingNow = bearingTo(tp, trail.x[0], trail.z[0])
                bestPre = abs(wrapDegrees(ctx.preBurstYaw - bearingNow))
            }
            bestPreInside = minOf(bestPreInside, minInsideError(tp, trail, ctx.preBurstYaw))
        }

        if (bestErr <= QUANTUM && bestPre > SNAP_PRE_ERROR_MIN) {
            ++ctx.snapHits
            ctx.lastSnapHitTick = tick
            if (ctx.snapHits >= SNAP_MIN_HITS && ctx.snapHits > ctx.snapMisses) {
                // silent(snap): repeated packet-precision landings = confirmed silent aim.
                flag(tp, ctx, VL_SILENT_SNAP, "silent(snap)", tick)
            }
        } else if (bestPreInside <= QUANTUM && bestErr > SNAP_PRE_ERROR_MIN * 0.75f) {
            // burst started on a target and left it — the return leg of a snap-attack-return
            if (ctx.lastSnapHitTick != NO_TICK &&
                tick - ctx.lastSnapHitTick <= RETURN_PAIR_TICKS
            ) {
                flag(tp, ctx, VL_SILENT_RETURN, "silent(return)", tick)
            }
        } else if (bestPre > SNAP_PRE_ERROR_MIN && bestErr > QUANTUM * 2.0f) {
            ++ctx.snapMisses // genuine flick that landed past/short of everyone
        }
    }

    /** silent(track): while the bearing to the same target rotates fast, count inside-span holds. */
    private fun trackComponent(tp: TrackedPlayer, ctx: KillAuraContext, yaw: Float,
                               targets: List<TrackedPlayer>, tick: Int) {
        var target: TrackedPlayer? = null
        var bestDistSq = Double.MAX_VALUE
        for (candidate in targets) {
            val dx = candidate.pos.x - tp.pos.x
            val dy = candidate.pos.y - tp.pos.y
            val dz = candidate.pos.z - tp.pos.z
            val distSq = dx * dx + dy * dy + dz * dz
            if (distSq < bestDistSq) { bestDistSq = distSq; target = candidate }
        }
        if (target == null) { ctx.lastTargetId = null; ctx.lastBearing = Float.NaN; return }
        val t = target
        val targetId = t.uuid
        val trail = trail(targetId)
        val bearingNow = bearingTo(tp, trail.x[0], trail.z[0])
        if (targetId == ctx.lastTargetId && !ctx.lastBearing.isNaN()) {
            val losDelta = abs(wrapDegrees(bearingNow - ctx.lastBearing))
            val dx = t.pos.x - tp.pos.x
            val dz = t.pos.z - tp.pos.z
            val horizDist = sqrt(dx * dx + dz * dz)
            // close range makes the hitbox span huge — inside-span is only meaningful from ~2.2 out
            if (losDelta > TRACK_LOS_MIN && losDelta < TRACK_LOS_MAX && horizDist >= TRACK_MIN_DIST) {
                ++ctx.trackSamples
                if (minInsideError(tp, trail, yaw) <= TRACK_INSIDE_TOL) ++ctx.trackTicks
                if (ctx.trackSamples >= TRACK_WINDOW) {
                    if (ctx.trackTicks.toFloat() >= TRACK_RATIO * ctx.trackSamples.toFloat()) {
                        flag(tp, ctx, VL_SILENT_TRACK, "silent(track)", tick)
                    }
                    ctx.trackSamples = 0
                    ctx.trackTicks = 0
                }
            }
        }
        ctx.lastTargetId = targetId
        ctx.lastBearing = bearingNow
    }

    /** movement(fix|lock|sprint): the body/head desync movement-fix silent aim introduces. */
    private fun movementComponent(tp: TrackedPlayer, ctx: KillAuraContext,
                                  moveX: Double, moveY: Double, moveZ: Double,
                                  yaw: Float, targets: List<TrackedPlayer>, tick: Int) {
        if (++ctx.moveTickCounter >= MOVE_DECAY_TICKS) {
            ctx.moveTickCounter = 0
            ctx.lockDesync = maxOf(0, ctx.lockDesync - 1)
            ctx.sprintDesync = maxOf(0, ctx.sprintDesync - 1)
        }

        val flat = ctx.hasVel && abs(moveY) < MOVE_FLAT_DY && abs(ctx.lastMoveY) < MOVE_FLAT_DY
        val haveAccel = ctx.hasVel
        val ax = moveX - ctx.lastVelX
        val az = moveZ - ctx.lastVelZ
        ctx.lastVelX = moveX
        ctx.lastVelZ = moveZ
        ctx.lastMoveY = moveY
        ctx.hasVel = true
        if (!haveAccel) return
        val accel = sqrt(ax * ax + az * az)
        val speed = sqrt(moveX * moveX + moveZ * moveZ)

        // Only flat-ground running is informative: air/knockback carry momentum off-yaw.
        // Hurt window substitutes for the (dead) velocity window — recent knockback fakes residual.
        if (!flat || tick - tp.hurtTick < 10 || speed < MOVE_MIN_SPEED || speed > MOVE_MAX_SPEED) return
        // Ice keeps momentum pointing off-yaw for many ticks even for legit players — skip it.
        val world = MinecraftClient.getInstance().world
        val ground = WorldQueries.blockStateAt(
            world, tp.pos.x.toInt(), Math.floor(tp.pos.y - 0.5).toInt(), tp.pos.z.toInt()
        )
        if (ground == null) return // chunk unloaded — fail-negative, never FP
        if (ground.isOf(Blocks.ICE) || ground.isOf(Blocks.PACKED_ICE) || ground.isOf(Blocks.BLUE_ICE)) return

        // Yaw you'd hold to walk this velocity forward, vs the yaw actually broadcast;
        // residual = distance to the nearest legal 45-deg strafe offset.
        val moveBearing = Math.toDegrees(atan2(-moveX, moveZ)).toFloat()
        val offset = wrapDegrees(moveBearing - yaw)
        val residual = bucketResidual(offset)

        // sprint-direction leak (tolerant accel gate: orbiting curves gently)
        if (tp.sprinting && speed > SPRINT_MIN_SPEED && accel < SPRINT_ACCEL && abs(offset) > SPRINT_OFFSET) {
            ++ctx.sprintDesync
            if (ctx.sprintDesync >= SPRINT_HITS) {
                flag(tp, ctx, VL_MOVE_SPRINT, "movement(sprint)", tick)
                ctx.sprintDesync -= SPRINT_HITS
            }
        }

        if (accel > MOVE_SMOOTH_ACCEL) return // turning momentum lags yaw ~1.5 ticks; residual unreliable

        // head pinned inside someone's hitbox while the body walks its own line
        if (residual > LOCK_RESIDUAL) {
            for (target in targets) {
                if (minInsideError(tp, trail(target.uuid), yaw) <= LOCK_TOL) {
                    ++ctx.lockDesync
                    if (ctx.lockDesync >= LOCK_HITS) {
                        flag(tp, ctx, VL_MOVE_LOCK, "movement(lock)", tick)
                        ctx.lockDesync -= LOCK_HITS
                    }
                    break
                }
            }
        }

        ++ctx.moveSamples
        ctx.residualSum += residual
        if (residual > MOVE_DESYNC_RESIDUAL) ++ctx.moveDesyncTicks
        if (ctx.moveSamples >= MOVE_WINDOW) {
            val mean = ctx.residualSum / ctx.moveSamples.toFloat()
            if (mean > MOVE_MEAN_LIMIT) flag(tp, ctx, VL_MOVE_FIX, "movement(fix)", tick)
            ctx.moveSamples = 0
            ctx.moveDesyncTicks = 0
            ctx.residualSum = 0.0f
        }
    }

    /** Distance (deg) from an angle to the nearest multiple of 45 — the legal strafe offsets. */
    private fun bucketResidual(offset: Float): Float {
        val nearest = 45.0f * round(offset / 45.0f)
        return abs(wrapDegrees(offset - nearest))
    }

    /** Players within aura range of the attacker that could be aim targets. */
    private fun targetsNear(attacker: TrackedPlayer, tick: Int): List<TrackedPlayer> {
        val out = ArrayList<TrackedPlayer>()
        for (p in EntityTrackerManager.all()) {
            if (p.uuid == attacker.uuid) continue
            val dx = p.pos.x - attacker.pos.x
            val dy = p.pos.y - attacker.pos.y
            val dz = p.pos.z - attacker.pos.z
            if (dx * dx + dy * dy + dz * dz > TARGET_RANGE_SQ) continue
            trail(p.uuid).push(p.pos.x, p.pos.z, tick) // freshen target trail
            out.add(p)
        }
        // the local observer is also a candidate target (cheater may snap onto us)
        pushObserverTrail(tick)
        return out
    }

    /** Push the local player's pos into a trail so a cheater snapping onto us is detectable. */
    private fun pushObserverTrail(tick: Int) {
        try {
            val p = MinecraftClient.getInstance().player ?: return
            trail(p.uuid).push(p.getX(), p.getZ(), tick)
        } catch (_: Throwable) {}
    }

    /**
     * Angular distance (deg) from [yaw] to the OUTSIDE of the target's horizontal hitbox
     * span — 0 means the yaw points inside the hitbox. Minimized over the target's recent
     * positions so network latency and the ~1-tick rotation interpolation lag can't
     * manufacture error. Ported verbatim from Rain.
     */
    private fun minInsideError(attacker: TrackedPlayer, trail: Trail, yaw: Float): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until trail.size) {
            val dx = trail.x[i] - attacker.pos.x
            val dz = trail.z[i] - attacker.pos.z
            val horizDist = sqrt(dx * dx + dz * dz)
            if (horizDist < 0.5) continue // overlapping — bearing is meaningless
            val bearing = (Math.toDegrees(atan2(dz, dx)).toFloat()) - 90.0f
            val err = abs(wrapDegrees(yaw - bearing))
            val halfWidth = Math.toDegrees(atan2(HITBOX_HALF_WIDTH, horizDist)).toFloat()
            best = minOf(best, maxOf(0.0f, err - halfWidth))
        }
        return best
    }

    /** Yaw bearing from the attacker to a world position (vanilla faceEntity formula). */
    private fun bearingTo(attacker: TrackedPlayer, x: Double, z: Double): Float {
        val dx = x - attacker.pos.x
        val dz = z - attacker.pos.z
        return (Math.toDegrees(atan2(dz, dx)).toFloat()) - 90.0f
    }

    private fun trail(uuid: UUID): Trail = trails.computeIfAbsent(uuid) { Trail() }

    private fun resetBurst(ctx: KillAuraContext) {
        ctx.burstTicks = 0; ctx.burstSum = 0.0f; ctx.burstDir = 0.0f
    }

    private fun resetSession(ctx: KillAuraContext) {
        resetBurst(ctx)
        ctx.quietTicks = 0
        ctx.snapHits = 0
        ctx.snapMisses = 0
        ctx.lastSnapHitTick = NO_TICK
        ctx.trackSamples = 0
        ctx.trackTicks = 0
        ctx.lastTargetId = null
        ctx.lastBearing = Float.NaN
        ctx.hasVel = false
        ctx.moveSamples = 0
        ctx.moveDesyncTicks = 0
        ctx.residualSum = 0.0f
        ctx.lockDesync = 0
        ctx.sprintDesync = 0
        ctx.moveTickCounter = 0
    }

    private fun wrapDegrees(angle: Float): Float {
        var a = angle % 360.0f
        if (a >= 180.0f) a -= 360.0f
        if (a < -180.0f) a += 360.0f
        return a
    }

    /** Short per-player position history, newest entry at index 0. */
    private inner class Trail {
        val x = DoubleArray(TRAIL_LEN)
        val z = DoubleArray(TRAIL_LEN)
        var lastTick: Int = Int.MIN_VALUE
        var size: Int = 0

        fun push(px: Double, pz: Double, tick: Int) {
            if (tick == lastTick && size > 0) return
            System.arraycopy(x, 0, x, 1, TRAIL_LEN - 1)
            System.arraycopy(z, 0, z, 1, TRAIL_LEN - 1)
            x[0] = px; z[0] = pz
            lastTick = tick
            if (size < TRAIL_LEN) ++size
        }
    }

    private inner class KillAuraContext : CheckContext() {
        // rotation stream
        var lastYaw: Float = 0f
        var lastPitch: Float = 0f
        var hasRotation: Boolean = false
        // combat gate
        var lastSwingTick: Int = NO_TICK
        var lastAttackTick: Int = NO_TICK
        // heuristic window
        val yawChangeWindow: ArrayList<Float> = ArrayList()
        var snapStreak: Int = 0
        // silent(snap) burst machine
        var burstTicks: Int = 0   // 0 = idle, -1 = invalidated (sustained turn), >0 = in burst
        var burstSum: Float = 0f
        var burstDir: Float = 0f
        var preBurstYaw: Float = 0f
        var quietTicks: Int = 0
        var snapHits: Int = 0
        var snapMisses: Int = 0
        var lastSnapHitTick: Int = NO_TICK
        // silent(track)
        var lastTargetId: UUID? = null
        var lastBearing: Float = Float.NaN
        var trackSamples: Int = 0
        var trackTicks: Int = 0
        // movement(fix) desync
        var lastVelX: Double = 0.0
        var lastVelZ: Double = 0.0
        var lastMoveY: Double = 0.0
        var hasVel: Boolean = false
        var moveSamples: Int = 0
        var moveDesyncTicks: Int = 0
        var residualSum: Float = 0f
        var lockDesync: Int = 0
        var sprintDesync: Int = 0
        var moveTickCounter: Int = 0
        // consume component
        var useItemTicks: Int = 0
        var lastEatTick: Int = NO_TICK
    }
}
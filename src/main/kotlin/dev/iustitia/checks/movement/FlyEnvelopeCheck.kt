package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.history.Evidence
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.world.WorldQueries
import net.minecraft.client.MinecraftClient
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Flight envelope, ported from AvA `checkFlight` + Nemesis `FlyB`. Three airborne
 * signals (only checked while the onGround proxy is false):
 *
 *  - **Physics breach**: Δy exceeds the gravity-predicted `(prevDeltaY-0.08)*0.98` by
 *    more than [threshold] (0.1) *and* the player has barely descended in the last 5
 *    ticks (recentDescend < 0.5) — i.e. they are gaining altitude while hovering.
 *  - **Hover / AirWalk**: |Δy| < 0.01 with horizontal motion > 0.1 for ≥3 ticks.
 *  - **Ascend-no-jump**: Δy > 0.2 with no observed jump impulse (prevDeltaY ≈ 0.42)
 *    within the last 2 ticks.
 *
 * Exemptions: vehicle, gliding, riptide, in-water, recent velocity (<30 ticks — a real
 * knockback/velocity impulse decays in ~1s; the old 200-tick window left a 10s fly loophole
 * after a single pulse). A solid block within 2 above the head suppresses the ascend flag
 * (ceiling collisions jitter Δy). Levitation/SlowFalling are unobservable; the recentDescend
 * gate on the physics breach absorbs most of those FPs. setbackVL 5, decay 1/tick.
 */
class FlyEnvelopeCheck : Check() {

    override val id: String = "flyEnvelope"

    override fun newContext(uuid: UUID): CheckContext = FlyContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding || tp.riptide || tp.swimming) return
            if (tick - tp.velocityTick < 30) return
            // Teleport exemption: a server teleport injects a huge Δy that would trip the
            // physics-breach and ascend flags. Skip and clear the descend window so the
            // post-teleport ticks start clean.
            val ctx = contextOf(tp.uuid) as FlyContext
            if (tick - tp.lastTeleportTick < 10) {
                ctx.descendWindow.clear()
                ctx.hoverTicks = 0
                return
            }
            // Hurt/knockback exemption: a recent hit injects a vertical knockback impulse
            // that trips the ascend flag (Δy>0.2 with no 0.42 jump impulse) and the physics
            // breach. Skip the ~6-tick peak + residual upward drift — substitutes for the
            // velocity window, which is dead on servers that don't broadcast other-player
            // EntityVelocityUpdate packets (the residual KB upward drift after the old 3-tick
            // window expired was the dominant Fly-breach FP: a player knocked up still has
            // Δy>0 and recentDescend<0.5 several ticks after the hurt peak).
            if (tick - tp.hurtTick < 6) return
            if (tp.groundedProxy) {
                ctx.hoverTicks = 0
                return
            }

            val dy = tp.deltaY

            // recent downward distance (last 5 ticks) — gates the physics breach
            pushFront(ctx.descendWindow, if (dy < 0) -dy else 0.0, 5)
            val recentDescend = ctx.descendWindow.sum()

            // Sustained-levitation guard: a player airborne for many ticks with a small,
            // steady upward drift (0 < dy < LEV_RISE) and no jump/velocity/teleport/hurt
            // impulse is under a Levitation effect (Shulker bullet / potion), not flying —
            // vanilla Levitation I–III drifts up ~0.045–0.135/tick. Slow Falling (dy<0) is
            // already excluded by the dy>-0.01 gate below; this closes the Levitation leak on
            // the physics-breach and ascend signals. Once sustained, stop accumulating VL.
            // (We can't observe the effect without expanding the mixin, so we infer shape.)
            val smallRise = dy > 0.0 && dy < LEV_RISE
            val noImpulse = tick - ctx.lastJumpTick > 6
            ctx.levTicks = if (smallRise && noImpulse) ctx.levTicks + 1 else 0
            val levitating = ctx.levTicks >= LEV_GUARD_TICKS
            if (levitating) { ctx.breachTicks = 0; ctx.ascendTicks = 0 }

            // 1) physics breach — sustained ≥2 ticks. A single lag-catch-up tick (server
            //    didn't move last tick → prevDeltaY~0 → this tick a collapsed 2-tick jump
            //    Δy) trips the prediction once; requiring 2 consecutive breaches filters
            //    that inversion noise. A real hover/fly sustains.
            val expectedY = (tp.prevDeltaY - 0.08) * 0.98
            // dy > -0.01: Slow Falling (unobservable, dy<0) can exceed expectedY+threshold while
            // recentDescend<0.5 (a slow-faller barely descends) and trip a false Fly. A real
            // hover/fly gains altitude (dy>0), so requiring dy > -0.01 excludes slow-fallers
            // without loosening the breach for ascenders.
            if (!levitating && dy > expectedY + cfg.threshold && recentDescend < 0.5 && dy > -0.01) {
                ctx.breachTicks++
                if (ctx.breachTicks >= 2) flag(tp, ctx, 1.0, "Fly", tick, Evidence(
                    measurement = dy, threshold = expectedY + cfg.threshold, pos = tp.pos,
                    extra = "recentDescend=$recentDescend prevΔy=${tp.prevDeltaY}"))
            } else if (!levitating) {
                ctx.breachTicks = 0
            }

            // 2) hover / airwalk
            val horizontal = hypot(tp.delta.x, tp.delta.z)
            if (abs(dy) < 0.01 && horizontal > 0.1) {
                ctx.hoverTicks++
                if (ctx.hoverTicks >= 3) flag(tp, ctx, 1.0, "Fly(Hover)", tick, Evidence(
                    measurement = dy, threshold = 0.01, pos = tp.pos, extra = "horiz=$horizontal"))
            } else {
                ctx.hoverTicks = 0
            }

            // 3) ascend without a jump impulse — sustained ≥6 ticks (same lag-catch-up filter
            //    as the physics breach, but longer: a KitPvP stairs/slab ramp is ≤4–5 ticks of
            //    dy≈0.5 that the jump recognizer never arms — sustain 6 + decay 0.5 keeps those
            //    brief legit climbs below setbackVL while a real fly sustains past it).
            if (dy > 0.2) {
                // record a legitimate jump impulse for a grace window. The old
                // `abs(prevDeltaY - 0.42) < 0.08` only matched a vanilla jump (0.42); Jump Boost
                // II/III/... launches at 0.62/0.72/.../0.92 (Grim: 0.42 + (amp+1)*0.1), so a
                // JB-potioned player's ascend never matched and false-flagged Fly(Ascend).
                // Broader + debounced recognition: a level-or-descending prev tick (prevDeltaY
                // < 0.15) rising sharply (0.3 < dy < 1.0) is a jump impulse of any tier; the
                // >6-tick debounce stops an oscillating fly from re-arming lastJumpTick each
                // cycle. (dy < 1.0 excludes teleports/bursts, which the teleport gate already
                // clears separately.)
                if (tp.prevDeltaY < 0.15 && dy > 0.3 && dy < 1.0 &&
                    tick - ctx.lastJumpTick > 6
                ) ctx.lastJumpTick = tick
                // Climbable exemption: ladders / vines / scaffolding sustain a legit upward
                // drift (dy≈0.2–0.35) that the jump recognizer never arms (prevDeltaY stays
                // high), so without this the ascend branch flags the entire climb — the
                // dominant Fly(Ascend) FP source. A real fly over a ladder column is already
                // caught by the physics-breach sub. (Ascend is the last sub, so returning here
                // skips nothing else.)
                if (isOnClimbable(tp)) { ctx.ascendTicks = 0; return }
                val blockedAbove = hasBlockAbove(tp)
                if (!levitating && !blockedAbove && tick - ctx.lastJumpTick > 2) {
                    ctx.ascendTicks++
                    if (ctx.ascendTicks >= 6) flag(tp, ctx, 1.0, "Fly(Ascend)", tick, Evidence(
                        measurement = dy, threshold = 0.2, pos = tp.pos, extra = "ascendTicks=${ctx.ascendTicks}"))
                } else {
                    ctx.ascendTicks = 0
                }
            } else {
                ctx.ascendTicks = 0
            }
        } catch (_: Throwable) {}
    }

    private fun isOnClimbable(tp: TrackedPlayer): Boolean = try {
        val world = MinecraftClient.getInstance().world ?: return false
        val bx = Math.floor(tp.pos.x).toInt()
        val bz = Math.floor(tp.pos.z).toInt()
        val by = Math.floor(tp.pos.y).toInt()
        // A player climbing a ladder/vine/scaffolding column intersects the climbable block at
        // foot level or the block below it; sample both so a freshly-grabbed ladder (feet still
        // in air) still exempts. Fail-negative (return false) on unload/error → no exemption,
        // same conservative posture as hasBlockAbove.
        WorldQueries.isClimbableAt(world, bx, by, bz) ||
            WorldQueries.isClimbableAt(world, bx, by - 1, bz)
    } catch (_: Throwable) {
        false
    }

    private fun hasBlockAbove(tp: TrackedPlayer): Boolean = try {
        val world = MinecraftClient.getInstance().world ?: return false
        // Math.floor, not toInt(): truncation toward zero picks the wrong column at negative
        // coords (a ceiling above a player at x=-0.4 would be sampled at column 0, not -1).
        val bx = Math.floor(tp.pos.x).toInt()
        val bz = Math.floor(tp.pos.z).toInt()
        val baseY = Math.floor(tp.pos.y + 2.0).toInt()
        // Chunk not loaded → fail NEGATIVE (treat as blocked): an unloaded "above" must not
        // produce an ascend flag at chunk borders. isSolidAt returns false on unloaded chunks,
        // so without this guard a missing-in-unloaded-chunk ceiling would false-flag.
        if (!WorldQueries.isChunkLoaded(world, bx, bz)) return true
        WorldQueries.isSolidAt(world, bx, baseY, bz) ||
            WorldQueries.isSolidAt(world, bx, baseY + 1, bz)
    } catch (_: Throwable) {
        // on error, assume blocked (fail-negative — don't flag ascend)
        true
    }

    private fun <T> pushFront(deque: ArrayDeque<T>, value: T, cap: Int) {
        deque.addFirst(value)
        while (deque.size > cap) deque.removeLast()
    }

    private class FlyContext : CheckContext() {
        var hoverTicks = 0
        var breachTicks = 0
        var ascendTicks = 0
        var lastJumpTick = -10000
        /** Consecutive airborne small-upward-drift ticks — sustained => Levitation guard. */
        var levTicks = 0
        val descendWindow = ArrayDeque<Double>()
    }

    private companion object {
        /** Max upward Δy (blocks/tick) still treated as a Levitation drift (not a fly ascend). */
        const val LEV_RISE = 0.2
        /** Airborne small-drift ticks required to arm the sustained-levitation guard (1s). */
        const val LEV_GUARD_TICKS = 20
    }
}
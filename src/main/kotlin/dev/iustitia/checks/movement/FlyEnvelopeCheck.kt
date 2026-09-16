package dev.iustitia.checks.movement

import dev.iustitia.NumFmt
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.history.Evidence
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.world.WorldQueries
import net.minecraft.client.MinecraftClient
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import dev.iustitia.checks.LagWindows

/**
 * Flight envelope, ported from AvA `checkFlight` + Nemesis `FlyB`. Seven airborne
 * sub-signals (only checked while the onGround proxy is false) — three original + four
 * §8 step 8 sub-signals (see below):
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
 *
 * **A grounded tick breaks every sustained streak** (audit FP — the slab/stairs ramp). The three
 * streak counters ([FlyContext.breachTicks]/[FlyContext.flyBTicks]/[FlyContext.ascendTicks]) plus
 * `hoverTicks` are reset in the `groundedProxy` branch, so "consecutive" means consecutive
 * *evaluated* ticks. Without that reset the ramp walked by a tracked player fires `Fly` from its
 * second step: [TrackedPlayer.deltaY] is a **raw** position delta (the tracker's snapshot is
 * `Vec3d(e.getX(), e.getY(), e.getZ())` — entity fields, *not* render interpolation), so a 0.5
 * step-up arrives as a **single-tick** Δy spike followed by three flat grounded ticks. That spike
 * clears `expectedY + 0.1` by a wide margin (`prevDeltaY ≈ 0` ⇒ `expectedY = (0 − 0.08)·0.98 ≈
 * −0.078`, and `recentDescend` is 0 because Δy is never negative), while the flat ticks return
 * early at the grounded branch — so with only `hoverTicks` cleared, `breachTicks` stood across the
 * level ground and every step after the first was the *second* consecutive breach. Two steps, two
 * breaches, `Fly`: **17** flags over a 20-step ramp (measured; the pre-fix estimate was closer to
 * 19 -- one per step after the first), and no `expectQuiet` assertion can see them (1.0
 * per flag against a 0.5/tick decay, at most one flag per step, so the VL never approaches
 * setbackVL 5 — the scenario's flag count is the only observable). The streak reset is what makes
 * each of these sub-signals a statement about a *continuous* rise, which a ramp is not — every step
 * is followed by level ground.
 *
 * `Fly(Ascend)` is **not** reachable by that shape, and the reason is worth recording because the
 * audit pointed at the ascend block rather than the breach: on a fixed step cadence
 * `ascendTicks` can never exceed **1**. The jump recognizer (`prevDeltaY < 0.15 && 0.3 < dy < 1.0`)
 * fires on a spike but re-arms only after 6 quiet ticks, so at a 4-tick cadence it re-arms on every
 * *other* step — and on a re-arm tick `lastJumpTick` is set to that very tick, so the ascend gate's
 * own `tick - lastJumpTick > 2` fails (`0 > 2`) and zeroes the counter; on the steps where it does
 * increment, the intervening level ticks zero it again (their `dy` is 0). Six consecutive ascending
 * ticks are unreachable, so the ascend streak length is not what separated a ramp from a fly —
 * the grounded reset is.
 *
 * **§8 step 8 sub-signals (plan §3 — Meteor Flight anti-kick + Strafe-hop/blink + Nemesis
 * FlyB 0.005 vertical-friction kernel).** Four more signals, all sharing `flyEnvelope`'s VL
 * pool (distinct labels, no new check id):
 *  - **Fly(FlyB)**: the Nemesis vertical air-friction kernel `(prevDeltaY-0.08)*0.98` at a
 *    tighter tolerance — owns the [0.05, cfg.threshold] band the 0.1 physics breach misses (a
 *    slow 0.05–0.1 sustained upward drift = hover/float), sustained ≥4 ticks. Nemesis
 *    server-side uses 0.005 (ground truth); relaxed to 0.05 (no ground truth). Same guards.
 *  - **Fly(Blink)**: a pure midair full-freeze (|Δ|²<0.0001) ≥ the config window
 *    ([dev.iustitia.config.IustitiaConfig.blinkFreezeTicks], default 30 — a PURE freeze is
 *    1v1-unobservable from a player-specific lag stall, hence the conservative window) —
 *    the Blink / fly-hold the existing horiz>0.1 hover misses; packetGap flags the eventual
 *    snap, not the hold.
 *  - **Fly(AntiKick)**: Y held within ±0.05 of a hover point ≥40 ticks with periodic ~20t
 *    downward dips (≤0.04) — Meteor AntiKick dips 0.0313 every delay=20 to reset the 80-tick
 *    floating counter; the micro-dip cadence while hovering is the fingerprint.
 *  - **Fly(StrafeHop)**: a repeated first-tick-jump Δy in a tight band around 0.40123128
 *    (Meteor Speed strafe-hop; vanilla 0.42 / JB 0.62+ sit outside the band) ≥5 times.
 */
class FlyEnvelopeCheck : Check() {

    override val id: String = "flyEnvelope"

    override fun newContext(uuid: UUID): CheckContext = FlyContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding || tp.riptide || tp.swimming) return
            // velocity-exemption window; + the no-damage knockback burst window (wind charge /
            // TNT-cannon), which produces neither a velocity nor a hurt signal on the target
            // server — an upward wind-charge launch (Δy ≥ ~1.0) would otherwise false-flag
            // Fly(Ascend). 20 ticks ≈ the ~1s decay of a single impulse (matches the 30-tick
            // velocity reasoning).
            if (tick - tp.velocityTick < 30 || tick - tp.burstTick < 20) return
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
            // Server-lag exemption: a server-wide hitch / catch-up burst injects a large Δy
            // sample that trips the physics-breach and ascend flags. Skip the sample (the
            // descend window is not pushed this tick, so lag never poisons the ≥2-of-5 gate).
            if (tick - EntityTrackerManager.lastServerLagTick <= LagWindows.LAG_WINDOW ||
                tick - EntityTrackerManager.lastLagBurstTick <= LagWindows.BURST_WINDOW
            ) return
            // Grounded: every sub-signal here is a claim about a *consecutive* tick run, so a
            // grounded tick ends the run. Clearing only hoverTicks (as this once did) left the
            // three sustained counters standing across the flat gaps of a slab/stairs ramp — the
            // audit's false positive. See the class doc: `deltaY` is a raw position delta, so a
            // step-up is ONE spike tick and the counters used to span the level ground between two
            // steps, making a "consecutive" gate that two *blocks of hill* satisfied.
            //
            // Fail-open by construction: a flyer that is genuinely groundedProxy for a tick is not
            // rising that tick, so nothing is lost that the next tick cannot re-establish. Like
            // every exemption here this is a *concession* on a cheater who can hold Δy≈0 within
            // 0.05 of a solid block (which reads as grounded) — the same posture [Fly(Blink)]
            // documents for a pure midair freeze.
            if (tp.groundedProxy) {
                ctx.breachTicks = 0
                ctx.flyBTicks = 0
                ctx.ascendTicks = 0
                ctx.hoverTicks = 0
                return
            }
            // Climbable exemption, applied to **every** vertical sub-flag rather than only to the
            // ascend branch. A ladder/vine/scaffolding column sustains a legit upward drift
            // (dy≈0.2-0.35/tick) that satisfies the FlyB friction band and the physics-breach
            // prediction within the first ticks of a climb -- verified live on a 0.2 b/t ladder
            // climb, which fired FlyB every tick to peakVL 24.5 (past setbackVL 5) for ~20 ticks
            // before the sustained-Levitation guard took over. Exempting only the ascend path left
            // the other two consumers of the same physics unguarded, which is what made this the
            // suite's loudest false positive. The counts are cleared so a climb leaves no partial
            // streak for the tick the player steps off.
            if (isOnClimbable(tp)) {
                ctx.breachTicks = 0
                ctx.flyBTicks = 0
                ctx.hoverTicks = 0
                ctx.ascendTicks = 0
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
            //
            //    The 2-consecutive requirement is only meaningful because a grounded tick resets
            //    the counter (see the `groundedProxy` branch). A step-up spike has prevDeltaY≈0, so
            //    expectedY≈-0.078 and the spike clears expectedY+threshold easily; with the counter
            //    surviving the level ground between steps, two consecutive *steps* — two blocks of
            //    hill, two slabs, two stairs — satisfied a gate that says "consecutive ticks".
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

            // 1b) Fly(FlyB) — Nemesis vertical air-friction kernel (plan §3/§8 step 8): the same
            //   (prevDeltaY-0.08)*0.98 prediction as the physics breach, at a TIGHTER tolerance.
            //   The breach flags past +cfg.threshold (0.1); FlyB owns the [FLYB_TOL, cfg.threshold]
            //   band — the slow 0.05–0.1 upward drift a sustained hover/float makes that the 0.1
            //   breach misses. Nemesis server-side uses 0.005 (ground truth); we relax to 0.05
            //   (no ground truth, interpolation noise) and keep the breach's Slow-Fall (dy>-0.01) +
            //   real-fall (recentDescend<0.5) + Levitation (!levitating) guards. Sustained ≥4 ticks
            //   (vs the breach's 2) — a slow drift persists; a lag-catch-up inversion does not.
            if (!levitating && dy > expectedY + FLYB_TOL && dy <= expectedY + cfg.threshold &&
                recentDescend < 0.5 && dy > -0.01
            ) {
                ctx.flyBTicks++
                if (ctx.flyBTicks >= FLYB_SUSTAIN) flag(tp, ctx, 1.0, "Fly(FlyB)", tick, Evidence(
                    subLabel = "friction", measurement = dy - expectedY, threshold = FLYB_TOL,
                    pos = tp.pos, extra = "expected=$expectedY recentDescend=$recentDescend prevΔy=${tp.prevDeltaY}"))
            } else {
                ctx.flyBTicks = 0
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

            // 2b) Fly(Blink) — Meteor Blink hold (plan §3/§8 step 8): a PURE midair freeze
            //   (|Δ|² < BLINK_FREEZE_MAG2 — no horiz, no vert) sustained ≥ [freezeWindow] ticks
            //   while airborne. The existing hover needs horiz>0.1, so a perfectly-still midair
            //   hold (the Blink / fly-hold) is missed; packetGap flags the eventual snap (after
            //   freeze≥5), not the hold itself. CONCESSION: a PURE freeze is 1v1-unobservable
            //   from a player-specific lag stall (the server just stops sending that player's
            //   movement) — the server-wide burst exemption can't catch a single-player stall —
            //   so the default window (30 = 1.5s, config-exposed as
            //   [dev.iustitia.config.IustitiaConfig.blinkFreezeTicks], clamped 5..200) is
            //   deliberately longer than the old hardcoded 20 and we do NOT alert on a single
            //   brief freeze. A legit jump apex freezes ~1 tick; an AFK player is grounded →
            //   groundedProxy early-return. Transition-gated: one flag per hold.
            val freezeWindow = try {
                dev.iustitia.config.ConfigManager.config.blinkFreezeTicks.coerceIn(5, 200)
            } catch (_: Throwable) {
                BLINK_FREEZE // fail-open to the built-in default (headless tests / early boot)
            }
            val mag2 = tp.delta.x * tp.delta.x + dy * dy + tp.delta.z * tp.delta.z
            if (mag2 < BLINK_FREEZE_MAG2) {
                ctx.blinkFreezeTicks++
                if (ctx.blinkFreezeTicks >= freezeWindow && !ctx.blinkActive) {
                    ctx.blinkActive = true
                    // Level = setbackVL + 1.0: the flat 1.0 the old code used broke even against
                    // this check's 0.5/tick decay (a single-hold blink could never alert — a
                    // dead detector). blinkActive is already the one-flag-per-hold latch; the
                    // episode-level flag clears setbackVL in the one flag this gate allows.
                    flag(tp, ctx, setbackVL + 1.0, "Fly(Blink)", tick, Evidence(
                        subLabel = "freeze-hold", measurement = ctx.blinkFreezeTicks.toDouble(),
                        threshold = freezeWindow.toDouble(), pos = tp.pos,
                        extra = "mag²=$mag2 airborne full-freeze hold"))
                }
            } else {
                ctx.blinkFreezeTicks = 0
                ctx.blinkActive = false
            }

            // 2c) Fly(AntiKick) — Meteor Flight AntiKick cadence (plan §3/§8 step 8): Y held within
            //   ±ANTIKICK_BAND of a hover point for ≥ ANTIKICK_MIN_DURATION ticks, with periodic
            //   small DOWNWARD dips (dy in [ANTIKICK_BLIP_MIN, ANTIKICK_BLIP_MAX]) every ~20 ticks.
            //   Meteor's AntiKick dips Y 0.0313 every delay=20 to reset the server's 80-tick
            //   floating counter — a periodic micro-dip cadence while otherwise hovering is the
            //   anti-kick fingerprint. recentDescend < ANTIKICK_DESCEND gates out a real descent
            //   (a slow-faller accumulates ~0.1 over 5 ticks; Slow Falling is unobservable).
            //   Transition-gated: one flag per anti-kick episode.
            if (recentDescend < ANTIKICK_DESCEND && abs(dy) < ANTIKICK_BAND) {
                ctx.hoverBandTicks++
                if (dy in ANTIKICK_BLIP_MIN..ANTIKICK_BLIP_MAX) {
                    if (ctx.lastBlipTick > -10000) {
                        val interval = tick - ctx.lastBlipTick
                        // only count dips at the ~20t anti-kick cadence; a non-periodic dip restarts.
                        ctx.blipCount = if (interval in ANTIKICK_PERIOD_MIN..ANTIKICK_PERIOD_MAX) ctx.blipCount + 1 else 1
                    } else {
                        ctx.blipCount = 1
                    }
                    ctx.lastBlipTick = tick
                }
                if (ctx.hoverBandTicks >= ANTIKICK_MIN_DURATION && ctx.blipCount >= ANTIKICK_CYCLES &&
                    !ctx.antiKickActive
                ) {
                    ctx.antiKickActive = true
                    flag(tp, ctx, 1.0, "Fly(AntiKick)", tick, Evidence(
                        subLabel = "anti-kick-cadence", measurement = ctx.blipCount.toDouble(),
                        threshold = ANTIKICK_CYCLES.toDouble(), pos = tp.pos,
                        extra = "hoverBand=${ctx.hoverBandTicks} blips=${ctx.blipCount} sinceBlip=${tick - ctx.lastBlipTick}"))
                }
            } else {
                ctx.hoverBandTicks = 0
                ctx.blipCount = 0
                ctx.lastBlipTick = -10000
                ctx.antiKickActive = false
            }

            // 3) ascend without a jump impulse — sustained ≥6 ticks (same lag-catch-up filter as
            //    the physics breach, but longer: a single step-up is 1–2 ticks of rising Δy that
            //    the jump recognizer never arms, so 1–2 accumulating ticks stay far below the 6-tick
            //    streak while a real fly sustains past it).
            //
            //    A *ramp* of steps used to reach 6 anyway, and the streak length is not what fixes
            //    that — no length separates a long ramp from a fly, because at a fixed cadence the
            //    ramp's rise is numerically the same signal. What separates them is that a ramp is
            //    made of steps: each rise is followed by level ground, and the grounded reset at the
            //    top of `process` ends the streak there. A fly has no grounded ticks to end it.
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
                // 3a) Fly(StrafeHop) — Meteor Speed strafe-hop 0.40123128 (plan §3/§8 step 8): a
                //   spoofed hop impulse at the exact 0.40123128 Y (slightly UNDER vanilla 0.42,
                //   server-enforced) to optimize strafe momentum. Vanilla / JB jumps (0.42 / 0.62+)
                //   sit outside the STRAFE_HOP_BAND, so a repeated hop in a tight band around
                //   0.40123128 is the cheat. Recorded on EVERY first-tick-jump (prevDeltaY<0.15,
                //   0.3<dy<1.0), not the debounced jump recognizer — the debounce is for the ascend
                //   grace window, not the strafe-hop detector. A non-band jump breaks the cluster
                //   (re-arm). Transition-gated: one flag per consistent strafe-hop episode.
                if (tp.prevDeltaY < 0.15 && dy > 0.3 && dy < 1.0) {
                    if (abs(dy - STRAFE_HOP_Y) <= STRAFE_HOP_BAND) {
                        pushFront(ctx.strafeHops, dy, STRAFE_HOP_WINDOW)
                        if (ctx.strafeHops.size >= STRAFE_HOP_MIN && !ctx.strafeHopActive) {
                            ctx.strafeHopActive = true
                            flag(tp, ctx, 1.0, "Fly(StrafeHop)", tick, Evidence(
                                subLabel = "strafe-hop-impulse", measurement = dy, threshold = STRAFE_HOP_Y,
                                pos = tp.pos, extra = "bandΔ=${NumFmt.d(digits = 5, v = abs(dy - STRAFE_HOP_Y))} hops=${ctx.strafeHops.size}"))
                        }
                    } else {
                        // a vanilla 0.42 / JB hop breaks the strafe-hop cluster → re-arm for a fresh episode.
                        ctx.strafeHops.clear()
                        ctx.strafeHopActive = false
                    }
                }
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
        // A climbing player occupies the block *beside* the ladder: the ladder is attached to a
        // wall in a neighbouring column and the climber's own column is air (that is what
        // `can climb` means), so sampling only the player's own column never matches anything.
        // Verified live: a ladder column one block away at z+1 left `flyEnvelope`'s exemption dead
        // and the drive kept false-flagging. Sample the player's column plus the four horizontal
        // neighbours, at foot level and one below (a freshly-grabbed ladder has the feet still in
        // air). Fail-negative (return false) on unload/error → no exemption, same conservative
        // posture as hasBlockAbove.
        for (y in intArrayOf(by, by - 1)) {
            if (WorldQueries.isClimbableAt(world, bx, y, bz)) return true
            if (WorldQueries.isClimbableAt(world, bx + 1, y, bz)) return true
            if (WorldQueries.isClimbableAt(world, bx - 1, y, bz)) return true
            if (WorldQueries.isClimbableAt(world, bx, y, bz + 1)) return true
            if (WorldQueries.isClimbableAt(world, bx, y, bz - 1)) return true
        }
        false
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

        // -- §8 step 8 sub-signal state (all share `flyEnvelope`'s VL pool; no new check id) --
        /** Fly(FlyB): sustained tight-breach counter (Nemesis vertical-friction kernel). */
        var flyBTicks = 0
        /** Fly(AntiKick): consecutive airborne ticks with |dy| < ANTIKICK_BAND (the hover band). */
        var hoverBandTicks = 0
        /** Fly(AntiKick): tick of the last downward blip (the anti-kick dip). */
        var lastBlipTick = -10000
        /** Fly(AntiKick): count of periodically-spaced (~20t) downward blips. */
        var blipCount = 0
        /** Fly(AntiKick): transition gate (one flag per anti-kick episode). */
        var antiKickActive = false
        /** Fly(StrafeHop): rolling window of first-tick-jump Δys in the 0.40123128 band. */
        val strafeHops = ArrayDeque<Double>()
        /** Fly(StrafeHop): transition gate (one flag per strafe-hop cluster). */
        var strafeHopActive = false
        /** Fly(Blink): consecutive airborne full-freeze (|Δ|² < 0.0001) ticks. */
        var blinkFreezeTicks = 0
        /** Fly(Blink): transition gate (one flag per hold). */
        var blinkActive = false
    }

    private companion object {
        /** Max upward Δy (blocks/tick) still treated as a Levitation drift (not a fly ascend). */
        const val LEV_RISE = 0.2
        /** Airborne small-drift ticks required to arm the sustained-levitation guard (1s). */
        const val LEV_GUARD_TICKS = 20

        // -- §8 step 8 sub-signals (plan §3, "Meteor Flight anti-kick + Strafe-hop/blink +
        // Nemesis FlyB 0.005 vertical-friction kernel"). All share `flyEnvelope`'s VL pool;
        // distinct labels, no new check id. Constants are initial values — tuning pass is step 14. --

        /** Fly(FlyB) tolerance (blocks above the gravity-predicted Δy). Nemesis server-side uses
         *  0.005 (ground truth); relaxed to 0.05 client-side (no ground truth, interpolation noise).
         *  Owns the [FLYB_TOL, cfg.threshold] band the 0.1 physics breach misses. Tuned in step 14. */
        const val FLYB_TOL = 0.05
        /** Fly(FlyB) sustained-breach ticks — a slow hover/float drifts persistently; a lag-catch-up
         *  one-tick inversion does not. Tuned in step 14. */
        const val FLYB_SUSTAIN = 4
        /** Fly(AntiKick): |dy| hover band (blocks). Meteor AntiKick dips 0.0313 every delay=20 to
         *  reset the 80-tick floating counter; the Y stays within ±0.05 of the hover point. */
        const val ANTIKICK_BAND = 0.05
        /** Fly(AntiKick): min airborne hover-band ticks before the cadence is evaluated (2s). */
        const val ANTIKICK_MIN_DURATION = 40
        /** Fly(AntiKick): downward-blip Δy lower bound (the anti-kick dip; 0.0313 → -0.04 floor). */
        const val ANTIKICK_BLIP_MIN = -0.04
        /** Fly(AntiKick): downward-blip Δy upper bound. */
        const val ANTIKICK_BLIP_MAX = -0.02
        /** Fly(AntiKick): acceptable inter-blip interval (ticks) for a periodic cadence (~20). */
        const val ANTIKICK_PERIOD_MIN = 15
        /** Fly(AntiKick): acceptable inter-blip interval upper bound. */
        const val ANTIKICK_PERIOD_MAX = 25
        /** Fly(AntiKick): periodic blips required to flag (≥2 dips ~20t apart while hovering). */
        const val ANTIKICK_CYCLES = 2
        /** Fly(AntiKick): max recentDescend (blocks/5t) — a real hover doesn't descend; a slow-faller
         *  accumulates ~0.1 and is exempt (Slow Falling is unobservable). */
        const val ANTIKICK_DESCEND = 0.05
        /** Fly(StrafeHop): the Meteor Speed strafe-hop spoofed Y impulse (vanilla jump is 0.42). */
        const val STRAFE_HOP_Y = 0.40123128
        /** Fly(StrafeHop): band around STRAFE_HOP_Y — excludes vanilla 0.42 (Δ=0.019) and JB 0.62+. */
        const val STRAFE_HOP_BAND = 0.006
        /** Fly(StrafeHop): rolling window of recent first-tick-jump Δys. */
        const val STRAFE_HOP_WINDOW = 12
        /** Fly(StrafeHop): in-band hops required to flag — a consistent strafe-hopper; a hand mixing
         *  in a vanilla jump resets the cluster. */
        const val STRAFE_HOP_MIN = 5
        /** Fly(Blink): built-in fallback for the freeze window when the config value is
         *  unreadable (headless tests / early boot). The live window is
         *  [dev.iustitia.config.IustitiaConfig.blinkFreezeTicks] (default 30), clamped 5..200. */
        const val BLINK_FREEZE = 30
        /** Fly(Blink): |Δ|² below which a tick counts as a full freeze (|Δ| < 0.01 in each axis). */
        const val BLINK_FREEZE_MAG2 = 0.0001
    }
}
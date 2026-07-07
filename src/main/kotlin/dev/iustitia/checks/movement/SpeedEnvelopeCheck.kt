package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.history.Evidence
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.world.WorldQueries
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Horizontal-speed envelope, ported from AvA `checkSpeed` + hardened with the Nemesis
 * `Speed` momentum/friction model and the Grim `Simulation` offset-accumulation-with-decay
 * pattern (plan §3/§8 step 9). Two sub-signals share `speedEnvelope`'s VL pool (no new
 * check id):
 *
 *  - **Speed (3-of-6 bps blatancy)**: when ≥3 of the last 6 ticks exceeded the cap
 *    ([threshold], default 10.0 bps). The flat cap is RETAINED as a blatancy floor — it
 *    catches >10 bps speed hacks regardless of the momentum model's baseline. A single
 *    knockback/lag spike (1 over-cap tick) cannot reach 3, so it never flags. On KitPvP
 *    servers with dash/teleport items the cap is left conservative (10.0) by design — it
 *    mostly flags nothing there; the Speed(Momentum) sub-flag catches the sub-cap overspeed
 *    a flat cap structurally cannot.
 *  - **Speed(Momentum)** (Nemesis + Grim, §3/§8 step 9): the per-tick horizontal move is
 *    decomposed into momentum carry + new input. `lastOffsetH = offsetH_prev * friction`
 *    (carry), `movementSpeed = BASE_ATTRIBUTE * speedFactor * sprintBoost *
 *    LAND_MOVEMENT_FACTOR / friction³` (ground) or `0.026 * speedFactor` (air) is the max
 *    new input accel a tick, and `offset = max(0, offsetH - lastOffsetH - movementSpeed)`
 *    is the excess (a speed hack adds input beyond the physical ceiling). At a legit
 *    steady state the carry exactly absorbs the friction loss and the input exactly
 *    fills it, so `offset = 0` (the `speedup` ratio is 1.0); a speed hack pushes `offset`
 *    persistently positive. Accumulate `advantage = min(advantage + offset, MAX_ADVANTAGE)`
 *    (Grim) and flag when `offsetH > MIN_MOVE && (offset >= IMMEDIATE_OFFSET ||
 *    advantage >= ADVANTAGE_FLAG)`. Decay `advantage *= SETBACK_DECAY` when `> DECAY_THRESHOLD`
 *    so a single blip can't accumulate to a flag, but a sustained low-grade overspeed does.
 *    This catches the +10–50% sub-cap speed hacks the flat 10 bps cap misses, while the
 *    3rd-gen NP-complete FP problem is avoided (no per-block simulation — a portable
 *    friction model from the block under the player + a default attribute).
 *
 * Friction model (the portable insight): `friction = 0.91 * slip` (ground; slip = block
 * slipperiness, 0.6 default / 0.98 ice / 0.8 slime) or `0.91` (air). The `0.91 * 0.6 =
 * 0.546` ground retention yields a steady state of `movementSpeed/(1-friction) ≈ 0.286
 * blocks/tick = 5.7 bps` for a vanilla sprint, matching the real 5.6 bps — so the `speedup`
 * ratio self-calibrates to 1.0 at legit speed regardless of the (unobservable) exact
 * movement attribute (plan: "use a conservative default attribute"). Speed II raises
 * `speedFactor` → movementSpeed → steady-state 8.0 bps (matches vanilla 7.8). Ice's high
 * slip lowers friction → momentum carries further (the legit ice-speedup).
 *
 * Exemption order (fail-closed → stricter on unknowns): recent velocity (<20 ticks — a real
 * gliding/riptide, vehicle, in-water (dolphin's grace unobservable), a 10-tick teleport
 * exemption (server teleports otherwise inject 50k+ bps samples), then a 3-tick
 * hurt/knockback exemption (substitutes for the velocity window on servers that don't
 * broadcast other-player EntityVelocityUpdate packets — knockback follows a hit, so a
 * recent hurt marks the knockback peak we must not flag). Soft exemptions (velocity/hurt/
 * lag) SKIP the flag but still carry the momentum baseline (`lastOffsetH`) through them —
 * a knockback/velocity impulse IS momentum, so carrying it keeps the model continuous
 * across the exempt window instead of a stale baseline FP on the first post-exempt tick.
 * Teleport HARD-RESETS the baseline (a position jump is not momentum). The Speed status
 * effect raises the cap by +20% per level (Speed I→×1.2, II→×1.4). A 1.3× cap raise
 * applies on ice / slime (frictionless surfaces that legitimately carry momentum). Soul
 * sand is NOT included — it does not make a player faster. Chunk not loaded → no cap
 * raise (strict — may slightly over-flag at borders, never under-flags).
 *
 * Server-lag exemption: a server-wide hitch (everyone frozen) or a batched catch-up burst
 * (everyone snapping >2b at once) injects huge bps samples into every player
 * simultaneously — the spawn-in burst after a world transition ("sent to KitPVP") is
 * exactly this, and would otherwise mass-flag Speed across the whole lobby.
 * [EntityTrackerManager.lastServerLagTick] / [EntityTrackerManager.lastLagBurstTick] are
 * the single source of truth for those signals; we skip the flag (and skip pushing the
 * bps sample, so lag never poisons the ≥3-of-6 window) when within a short window of
 * either — but the momentum baseline still carries (frozen → offsetH≈0 → lastOffsetH→0
 * naturally). PacketGapCheck and TeleportCheck already do the same lag posture.
 *
 * setbackVL 5, decay 1/tick. All paths fail-open. Constants (friction factors / movement
 * speeds / offset thresholds / decay) are initial values; tuned in step 14.
 */
class SpeedEnvelopeCheck : Check() {

    override val id: String = "speedEnvelope"

    override fun newContext(uuid: UUID): CheckContext = SpeedContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            // hard exemptions — movement is not player-driven
            if (tp.inVehicle || tp.gliding || tp.riptide || tp.swimming) return

            val ctx = contextOf(tp.uuid) as SpeedContext

            // Teleport exemption: a server teleport (kit-equip, /tpa, respawn, "jumped to")
            // injects a 50k+ bps sample AND breaks the momentum baseline (a position jump is
            // not momentum). Hard-reset: clear the bps window + the momentum/advantage state
            // so post-teleport ticks start clean.
            if (tick - tp.lastTeleportTick < 10) {
                ctx.bpsWindow.clear()
                ctx.lastOffsetH = 0.0
                ctx.advantage = 0.0
                return
            }

            // Soft exemptions: a recent velocity impulse (<20t), knockback peak (<3t), or a
            // server-wide lag window. These SKIP the flag (an impulse/lag sample is not a
            // cheat), but — unlike the teleport reset — the momentum baseline carries
            // THROUGH them: a knockback/velocity impulse IS momentum, so lastOffsetH
            // tracks it and the model stays continuous across the exempt window (a reset
            // here would leave a stale baseline that false-flags the first post-exempt
            // tick). The bps window is not pushed (so lag/impulse samples don't poison the
            // ≥3-of-6 gate) but is not cleared either (pre-exempt samples age out naturally).
            val skipFlag = tick - tp.velocityTick < 20 ||
                tick - tp.hurtTick < 3 ||
                tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW ||
                tick - EntityTrackerManager.lastLagBurstTick <= BURST_WINDOW

            val offsetH = hypot(tp.delta.x, tp.delta.z)

            // ---- Speed (3-of-6 bps blatancy) — only when not soft-exempt ----
            val cap = cfg.threshold
            val speedFactor = if (tp.speedAmplifier >= 0) 1.0 + 0.2 * (tp.speedAmplifier + 1) else 1.0
            if (!skipFlag) {
                pushFront(ctx.bpsWindow, offsetH * 20.0, 6)
                // cap raise on ice/slime/soul-sand (chunk-gated via WorldQueries)
                var effectiveCap = cap * speedFactor
                val world = MinecraftClient.getInstance().world
                if (world != null) {
                    // Math.floor, not toInt(): truncation toward zero picks the wrong column at
                    // negative coords (a -0.4 foot block would be sampled at y/x/z 0, not -1).
                    val bx = Math.floor(tp.pos.x).toInt()
                    val by = Math.floor(tp.pos.y - 1.0).toInt()
                    val bz = Math.floor(tp.pos.z).toInt()
                    val state = WorldQueries.blockStateAt(world, bx, by, bz)
                    if (state != null && isSpeedBlock(state)) effectiveCap *= 1.3
                }
                // Sustained/alternating overspeed: ≥3 of the last 6 ticks over the cap. A
                // single knockback/lag spike (1 over-cap tick) cannot reach 3, so it never
                // flags — this is what stops the flat vl~6 baseline on normal players. A
                // blatant speed hack (all 6 over) or alternating BHop (3 over / 3 under)
                // both reach 3 and flag.
                val overCount = ctx.bpsWindow.count { it > effectiveCap }
                if (overCount >= 3) {
                    val windowedMax = ctx.bpsWindow.maxOrNull() ?: 0.0
                    // Bounded level: overshoot/3 capped at 10 so a blatant hacker (20+ bps)
                    // clears setbackVL fast (level ~4/tick) but VL doesn't explode to 5 digits.
                    val overshoot = windowedMax - effectiveCap
                    val level = max(1.0, min(10.0, ceil(overshoot / 3.0)))
                    flag(tp, ctx, level, "Speed", tick)
                }
            }

            // ---- Speed(Momentum) (Nemesis + Grim, §3/§8 step 9) — always runs (carries
            //   the momentum baseline through soft-exempt ticks; only FLAGS when !skipFlag).
            //   The friction model is the portable kernel: friction = 0.91*slip (ground) or
            //   0.91 (air); movementSpeed = the max new input accel a tick; offset = the
            //   excess beyond momentum-carry + input. A legit steady state has offset=0
            //   (speedup=1.0); a speed hack pushes it persistently positive. ----
            val friction = if (tp.groundedProxy) {
                val slip = slipperiness(worldBlockUnder(tp))
                AIR_FRICTION * slip
            } else {
                AIR_FRICTION
            }
            // Speed effect raises the attribute (speedFactor); sprint adds a 1.3× boost
            // (ground only — sprint input isn't applied in air). The default attribute
            // (BASE_ATTRIBUTE=0.1) is the conservative vanilla walkSpeed; the speedup ratio
            // self-calibrates to 1.0 at legit speed, so the exact attribute is not load-bearing.
            val sprintBoost = if (tp.sprinting && tp.groundedProxy) SPRINT_BOOST else 1.0
            val movementSpeed = if (tp.groundedProxy) {
                BASE_ATTRIBUTE * speedFactor * sprintBoost * LAND_MOVEMENT_FACTOR /
                    (friction * friction * friction)
            } else {
                AIR_MOVEMENT_SPEED * speedFactor
            }
            // excess = how far this tick's move exceeds (momentum carry + max input accel).
            val offset = max(0.0, offsetH - ctx.lastOffsetH - movementSpeed)
            if (!skipFlag) {
                ctx.advantage = min(ctx.advantage + offset, MAX_ADVANTAGE)
                // Flag (Grim): immediate on a single large excess, or sustained on accumulated
                //   advantage; gated by offsetH > MIN_MOVE so the momentum model isn't applied
                //   to near-stationary noise (Nemesis offsetH>0.2 guard).
                if (offsetH > MIN_MOVE && (offset >= IMMEDIATE_OFFSET || ctx.advantage >= ADVANTAGE_FLAG)) {
                    flag(tp, ctx, 1.0, "Speed(Momentum)", tick, Evidence(
                        subLabel = "momentum-friction", measurement = offset, threshold = IMMEDIATE_OFFSET,
                        pos = tp.pos, extra = "offsetH=$offsetH mvSpeed=$movementSpeed friction=$friction adv=${ctx.advantage}"))
                }
            }
            // Grim decay: advantage *= 0.999 each tick when > 0.05 (a single blip can't
            //   accumulate to a flag; a sustained overspeed outpaces the decay and does).
            if (ctx.advantage > DECAY_THRESHOLD) ctx.advantage *= SETBACK_DECAY
            // momentum carry for the next tick (always — keeps the baseline continuous).
            ctx.lastOffsetH = offsetH * friction
        } catch (_: Throwable) {}
    }

    /** The block state under the player's feet (for slipperiness); null on unload/error. */
    private fun worldBlockUnder(tp: TrackedPlayer): BlockState? = try {
        val world = MinecraftClient.getInstance().world ?: return null
        val bx = Math.floor(tp.pos.x).toInt()
        val by = Math.floor(tp.pos.y - 1.0).toInt()
        val bz = Math.floor(tp.pos.z).toInt()
        WorldQueries.blockStateAt(world, bx, by, bz)
    } catch (_: Throwable) {
        null
    }

    /** Slipperiness of the block under the player (default 0.6; ice 0.98; slime 0.8). */
    private fun slipperiness(state: BlockState?): Double = when {
        state == null -> DEFAULT_SLIP
        state.isOf(Blocks.ICE) || state.isOf(Blocks.BLUE_ICE) ||
            state.isOf(Blocks.PACKED_ICE) || state.isOf(Blocks.FROSTED_ICE) -> ICE_SLIP
        state.isOf(Blocks.SLIME_BLOCK) -> SLIME_SLIP
        else -> DEFAULT_SLIP
    }

    private fun isSpeedBlock(state: BlockState): Boolean = try {
        // Frictionless / momentum-carrying surfaces. Soul sand is intentionally excluded —
        // it does not make a player faster (a cap raise there is wrong-intent leniency).
        state.isOf(Blocks.ICE) || state.isOf(Blocks.BLUE_ICE) ||
            state.isOf(Blocks.PACKED_ICE) || state.isOf(Blocks.FROSTED_ICE) ||
            state.isOf(Blocks.SLIME_BLOCK)
    } catch (_: Throwable) {
        false
    }

    private fun <T> pushFront(deque: ArrayDeque<T>, value: T, cap: Int) {
        deque.addFirst(value)
        while (deque.size > cap) deque.removeLast()
    }

    companion object {
        /** Window (ticks) after a server-wide freeze within which speed samples are skipped. */
        private const val LAG_WINDOW = 8
        /** Window (ticks) after a batched catch-up burst within which speed samples are skipped. */
        private const val BURST_WINDOW = 3

        // -- Nemesis momentum/friction model (plan §3/§8 step 9) --
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
        /** Default block slipperiness (most blocks). */
        const val DEFAULT_SLIP = 0.6
        /** Ice-family slipperiness (carries momentum further → legit ice speedup). */
        const val ICE_SLIP = 0.98
        /** Slime-block slipperiness. */
        const val SLIME_SLIP = 0.8
        /** Min offsetH (blocks/tick) to apply the momentum model — below ~4 bps the model is
         *  noise (Nemesis offsetH>0.2 guard). Tuned in step 14. */
        const val MIN_MOVE = 0.2
        /** Grim: a single tick's excess >= this flags immediately (a blatant 1-tick accel spike). */
        const val IMMEDIATE_OFFSET = 0.1
        /** Grim: accumulated advantage >= this flags (sustained low-grade overspeed). */
        const val ADVANTAGE_FLAG = 1.0
        /** Grim maxAdvantage: advantage accumulation cap. */
        const val MAX_ADVANTAGE = 4.0
        /** Grim: decay advantage only when above this (don't decay a zero baseline toward NaN). */
        const val DECAY_THRESHOLD = 0.05
        /** Grim setbackDecay: advantage *= this each tick when > DECAY_THRESHOLD. */
        const val SETBACK_DECAY = 0.999
    }

    private class SpeedContext : CheckContext() {
        /** 6-tick bps window for the 3-of-6 blatancy flag. */
        val bpsWindow = ArrayDeque<Double>()
        /** Previous tick's offsetH × friction — the momentum carried into this tick. */
        var lastOffsetH = 0.0
        /** Accumulated excess (Grim offset-accumulation), decayed at SETBACK_DECAY each tick. */
        var advantage = 0.0
    }
}
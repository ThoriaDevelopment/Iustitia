package dev.iustitia.checks.movement

import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.world.WorldQueries
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Horizontal-speed envelope, ported from AvA `checkSpeed`. Flags sustained/alternating
 * overspeed: when ≥3 of the last 6 ticks exceeded the cap ([threshold], default 10.0 —
 * absorbs vanilla sprint-jump ~6.2, Speed II sprint ~7.8). A single knockback/lag-spike
 * sample (one over-cap tick) is NOT enough — the previous 10-tick windowed-MAX form let one
 * uncovered spike poison the window for 10 ticks and pin vl~6 on normal players. The
 * ≥3-of-6 gate catches both sustained speed hacks (all 6 over) and alternating BHop
 * (3 over, 3 under) while rejecting 1–2 tick spikes.
 *
 * Exemption order (fail-closed → stricter on unknowns): recent velocity (<40 ticks),
 * gliding/riptide, vehicle, in-water (dolphin's grace unobservable), a 10-tick teleport
 * exemption (server teleports otherwise inject 50k+ bps samples), then a 3-tick
 * hurt/knockback exemption (substitutes for the velocity window on servers that don't
 * broadcast other-player EntityVelocityUpdate packets — knockback follows a hit, so a
 * recent hurt marks the knockback peak we must not flag). The Speed status effect raises
 * the cap by +20% per level (Speed I→×1.2, II→×1.4) so legit Speed-potioned players aren't
 * flagged. A 1.3× cap raise applies on ice / slime (frictionless surfaces that legitimately
 * carry momentum). Soul sand is NOT included — it does not make a player faster. The level per
 * flag is bounded (overshoot/3, cap 10) so blatant cheaters still alert promptly but VL doesn't
 * explode to five digits. Chunk not loaded → no cap raise (strict — may slightly over-flag
 * at borders, never under-flags).
 *
 * Server-lag exemption: a server-wide hitch (everyone frozen) or a batched catch-up burst
 * (everyone snapping >2b at once) injects huge bps samples into every player simultaneously
 * — the spawn-in burst after a world transition ("sent to KitPVP") is exactly this, and would
 * otherwise mass-flag Speed across the whole lobby. [EntityTrackerManager.lastServerLagTick]
 * / [EntityTrackerManager.lastLagBurstTick] are the single source of truth for those signals;
 * we skip the flag (and skip pushing the sample, so lag never poisons the ≥3-of-6 window) when
 * within a short window of either. PacketGapCheck and TeleportCheck already do the same.
 */
class SpeedEnvelopeCheck : Check() {

    override val id: String = "speedEnvelope"

    override fun newContext(uuid: UUID): CheckContext = SpeedContext()

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            // exemptions
            if (tp.inVehicle || tp.gliding || tp.riptide || tp.swimming) return
            if (tick - tp.velocityTick < 40) return

            // Teleport exemption: a server teleport (kit-equip, /tpa, respawn, "jumped to")
            // injects a 50k+ bps sample that would otherwise rocket VL and poison the window.
            // Skip and clear the window so post-teleport ticks start clean.
            val ctx = contextOf(tp.uuid) as SpeedContext
            if (tick - tp.lastTeleportTick < 10) {
                ctx.bpsWindow.clear()
                return
            }

            // Hurt/knockback exemption: a recent hit injects a knockback impulse whose
            // peak ticks would otherwise be over-cap. Skip the ~3-tick peak WITHOUT
            // clearing the window — residual knockback stays under the cap, so only the
            // peak sample is dropped. Substitutes for the velocity-exemption window, which
            // is dead on servers that don't broadcast other-player EntityVelocityUpdate
            // packets (arch.mc).
            if (tick - tp.hurtTick < 3) return

            // Server-lag exemption: a server-wide hitch / catch-up burst injects huge bps
            // samples into every player at once (e.g. the spawn-in burst after "sent to
            // KitPVP"). Skip AND don't push the sample, so lag never poisons the ≥3-of-6
            // window. See the shared signals in EntityTrackerManager.
            if (tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW ||
                tick - EntityTrackerManager.lastLagBurstTick <= BURST_WINDOW
            ) return

            val cap = cfg.threshold
            val dxz = hypot(tp.delta.x, tp.delta.z)
            val bps = dxz * 20.0

            pushFront(ctx.bpsWindow, bps, 6)

            // Speed effect raises the cap: +20% per amplifier level (Speed I→×1.2, II→×1.4).
            // Without this, Speed II sprint (~7.8 bps) trips the cap. Dolphin/Levitation are
            // unobservable; the water exemption absorbs dolphin's grace.
            val speedFactor = if (tp.speedAmplifier >= 0) 1.0 + 0.2 * (tp.speedAmplifier + 1) else 1.0

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

            // Sustained/alternating overspeed: ≥3 of the last 6 ticks over the cap. A single
            // knockback/lag spike (1 over-cap tick) cannot reach 3, so it never flags — this
            // is what stops the flat vl~6 baseline on normal players. A blatant speed hack
            // (all 6 over) or alternating BHop (3 over / 3 under) both reach 3 and flag.
            val overCount = ctx.bpsWindow.count { it > effectiveCap }
            if (overCount >= 3) {
                val windowedMax = ctx.bpsWindow.maxOrNull() ?: 0.0
                // Bounded level: overshoot/3 capped at 10 so a blatant hacker (20+ bps) clears
                // setbackVL fast (level ~4/tick) but VL doesn't explode to five digits.
                val overshoot = windowedMax - effectiveCap
                val level = max(1.0, min(10.0, ceil(overshoot / 3.0)))
                flag(tp, ctx, level, "Speed", tick)
            }
        } catch (_: Throwable) {}
    }

    private fun isSpeedBlock(state: net.minecraft.block.BlockState): Boolean = try {
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
    }

    private class SpeedContext : CheckContext() {
        val bpsWindow = ArrayDeque<Double>()
    }
}
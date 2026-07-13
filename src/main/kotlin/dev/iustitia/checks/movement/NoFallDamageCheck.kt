package dev.iustitia.checks.movement

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.HurtSignal
import dev.iustitia.history.Evidence
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.world.WorldQueries
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * NoFall detector, observable-only. Accumulates downward distance while airborne and
 * flags when a player lands from a damaging fall without the corresponding hurt signal
 * (the server would normally play the hurt animation / send EntityDamage on impact).
 *
 * Signals:
 *  - **Landed-no-hurt**: airborne→grounded transition with fallAccum > [threshold]
 *    (8.0 — relaxed for unobservable feather-falling), no hurt in the last 2 ticks, and
 *    the landing block is not soft (water/slime/hay/cobweb).
 *  - **Ground-spoof-over-air**: the server-reported onGround is true but no solid block
 *    is below the player and they have fallAccum > [threshold] (floating-onGround spoof
 *    after a real fall). Gated to the threshold — a low 1.0 gate flags every chunk-edge
 *    / half-block stand during normal KitPvP knockback-falls.
 *  - **Stair-step** (Verus): repeated ≈-2.0 then ≈0 Δy cycles (≥3) — packet-level fall reset.
 *
 * A hurt signal (any channel) resets that player's fallAccum and records the tick,
 * exempting the next landing. Chunk not loaded → skip. setbackVL 4, decay 1/tick.
 */
class NoFallDamageCheck : Check() {

    override val id: String = "noFallDamage"

    init {
        try {
            Iustitia.bus.subscribe<HurtSignal> { onHurt(it) }
        } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = NoFallContext()

    private fun onHurt(h: HurtSignal) {
        try {
            val tp = EntityTrackerManager.get(h.victim) ?: return
            tp.fallAccum = 0.0
            (contextOf(h.victim) as NoFallContext).lastHurtTick = h.tick
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding) return
            if (tick - tp.lastTeleportTick < 5) return
            // Server-lag exemption: a server-wide hitch / catch-up burst injects a large Δy
            // sample that would inflate fallAccum (the stair-step spoof also keys on a big
            // negative Δy). Skip the sample so lag never poisons the fall accumulator.
            if (tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW ||
                tick - EntityTrackerManager.lastLagBurstTick <= BURST_WINDOW
            ) return
            val world = MinecraftClient.getInstance().world ?: return
            val ctx = contextOf(tp.uuid) as NoFallContext
            val dy = tp.deltaY
            // Math.floor, not toInt(): Double.toInt() truncates toward zero, picking the wrong
            // block column at any negative coordinate (e.g. -0.4 → 0 instead of -1).
            val bx = Math.floor(tp.pos.x).toInt()
            val bz = Math.floor(tp.pos.z).toInt()

            if (tp.groundedProxy) {
                // airborne → grounded transition
                if (ctx.wasAirborne && tp.fallAccum > cfg.threshold) {
                    val landY = Math.floor(tp.pos.y - 0.5).toInt()
                    val state = WorldQueries.blockStateAt(world, bx, landY, bz)
                    val soft = state != null && isSoftLand(state)
                    if (!soft && tick - ctx.lastHurtTick > 2) {
                        val level = max(1.0, ceil((tp.fallAccum - 3.0) * 2.0))
                        flag(tp, ctx, level, "NoFall", tick, Evidence(
                            subLabel = "landed-no-hurt", measurement = tp.fallAccum, threshold = cfg.threshold,
                            extra = "landed a ${"%.1f".format(tp.fallAccum)}-block fall with no hurt signal"))
                    }
                }
                tp.fallAccum = 0.0
                ctx.wasAirborne = false
                ctx.stairPhase = 0
                ctx.stairCycle = 0
            } else {
                ctx.wasAirborne = true
                if (dy < 0.0) tp.fallAccum += -dy

                // stair-step spoof: big negative step followed by a near-zero step
                if (ctx.stairPhase == 0 && dy < -1.5) {
                    ctx.stairPhase = 1
                } else if (ctx.stairPhase == 1 && abs(dy) < 0.05) {
                    ctx.stairCycle++
                    ctx.stairPhase = 0
                    if (ctx.stairCycle >= 3) {
                        flag(tp, ctx, 1.0, "NoFall(Stair)", tick, Evidence(
                            subLabel = "stair-step", measurement = ctx.stairCycle.toDouble(), threshold = 3.0,
                            extra = "packet-level fall reset (${ctx.stairCycle} ≈-2.0/0 Δy cycles)"))
                        ctx.stairCycle = 0
                    }
                }
            }

            // ground-spoof-over-air: onGround claimed but nothing solid below + a real fall
            // accumulated. Gated to a fixed 4.0 (not cfg.threshold 8.0): this branch only fires
            // when onGroundPacket is true AND there is provably no solid below, so the strict
            // floor check already prevents FPs — the gate is pure sensitivity, and 4.0 catches
            // the shorter ground-spooofs Polar flagged (Ground Spoof) that 8.0 missed. The
            // landed-no-hurt branch above keeps cfg.threshold (8.0) to protect against
            // unobservable feather-falling.
            if (tp.onGroundPacket && tp.fallAccum > 4.0 &&
                !WorldQueries.isSolidBelow(world, tp.pos.x, tp.pos.y, tp.pos.z, 0.5)
            ) {
                flag(tp, ctx, 1.0, "NoFall(Spoof)", tick, Evidence(
                    subLabel = "ground-spoof-over-air", measurement = tp.fallAccum, threshold = 4.0,
                    extra = "onGround spoofed over air — Δy ${"%.3f".format(dy)}, fell ${"%.1f".format(tp.fallAccum)} blocks"))
            }
        } catch (_: Throwable) {}
    }

    private fun isSoftLand(state: net.minecraft.block.BlockState): Boolean = try {
        state.isOf(Blocks.WATER) || state.isOf(Blocks.SLIME_BLOCK) ||
            state.isOf(Blocks.HAY_BLOCK) || state.isOf(Blocks.COBWEB)
    } catch (_: Throwable) {
        false
    }

    private class NoFallContext : CheckContext() {
        var wasAirborne = false
        var lastHurtTick = -10000
        var stairPhase = 0
        var stairCycle = 0
    }

    private companion object {
        /** Window (ticks) after a server-wide freeze within which fall samples are skipped. */
        private const val LAG_WINDOW = 8
        /** Window (ticks) after a batched catch-up burst within which fall samples are skipped. */
        private const val BURST_WINDOW = 3
    }
}
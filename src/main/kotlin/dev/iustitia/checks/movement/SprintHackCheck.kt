package dev.iustitia.checks.movement

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.event.EffectSignal
import dev.iustitia.history.Evidence
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.world.WorldQueries
import net.minecraft.client.MinecraftClient
import java.util.UUID

/**
 * SprintHack — three sprint-metadata-should-be-canceled sub-flags that share the
 * `sprintHack` VL pool (Grim `SprintG`/`SprintB`/`SprintD`, plan §4 #4-5 / §8 step 12):
 *
 * 1. **Sprint(Water)** (Grim `SprintG`): feet in water, head above water, not swimming,
 *    sprinting. Vanilla cancels sprint on water entry (you can't sprint in surface water);
 *    the `!swimming` gate excludes the legit sprint-swim pose, and head-above-water excludes
 *    the fully-submerged case. Detected via [WorldQueries.isLiquidAt] at the feet and head
 *    (eye-height) block — no `inWater` field needed. Chunk-unloaded → `isLiquidAt` fail-opens
 *    to false → no flag (chunk-unloaded-never-FP).
 * 2. **Sprint(Sneak)** (Grim `SprintB`): sprinting while sneaking. Vanilla blocks sprint
 *    while sneaking — both metadata flags set simultaneously is a cheat. Both fields are
 *    polled directly on [TrackedPlayer], trivial.
 * 3. **Sprint(Blind)** (Grim `SprintD`): sprinting while Blind. Vanilla blocks sprint under
 *    Blindness. Blindness on other players IS broadcast (EntityStatusEffectS2CPacket) — the
 *    mixin forwards it via [EffectSignal] (extended in this step to carry `isBlind`). This
 *    check subscribes [EffectSignal] and tracks `blind` per-player in its context (no
 *    TrackedPlayer field; the Speed-specific `markEffect` is untouched).
 *
 * All three are mutually exclusive with sprint in vanilla, so they are clean high-signal
 * fingerprints; a single sustain gate (≥ [threshold], default 3 ticks) absorbs 1-2 tick
 * metadata-lag edges. The label reflects the specific sub-flag firing this tick. Vehicle/
 * gliding exempt (can't sprint meaningfully); hurt exempt (KB holds the sprint flag briefly);
 * teleport exempt; server-lag pause. setbackVL 5, decay 0.5/tick.
 */
class SprintHackCheck : Check() {

    override val id: String = "sprintHack"

    init {
        try { Iustitia.bus.subscribe<EffectSignal> { onEffect(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = SprintHackContext()

    private fun onEffect(sig: EffectSignal) {
        try {
            if (!sig.isBlind) return
            (contextOf(sig.entity) as SprintHackContext).blind = sig.added
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding) return
            val ctx = contextOf(tp.uuid) as SprintHackContext
            if (!tp.sprinting) { ctx.streak = 0; return }
            if (tick - tp.hurtTick < 3) { ctx.streak = 0; return }
            if (tick - tp.lastTeleportTick < 10) { ctx.streak = 0; return }
            // Server-lag pause: a catch-up snap can hold the sprint flag through a state the
            // server already canceled. Pause (don't reset — lag doesn't clear the cheat state).
            if (tick - EntityTrackerManager.lastServerLagTick <= LAG_WINDOW ||
                tick - EntityTrackerManager.lastLagBurstTick <= BURST_WINDOW
            ) return
            // Distant-player skip: beyond the observation range the water-sprint signal isn't
            // usefully observable. Reset (like the other no-signal branches) so a stale streak
            // can't flag on re-approach. No verdict-cache here — waterSprint's isLiquidAt lookups
            // are cheap getBlockState+isOf (no getCollisionShape), so rate-limiting them is pointless.
            if (BlockLookupBudget.beyondObserveRange(tp)) { ctx.streak = 0; return }

            val waterSprint = waterSprint(tp, tick)
            val sneakSprint = tp.sneaking
            val blindSprint = ctx.blind

            if (waterSprint || sneakSprint || blindSprint) {
                // Prefer the most-specific / strongest signal for the label; Blind first (rarest,
                // clearest vanilla-cancel), then Water, then Sneak.
                val (label, sub) = when {
                    blindSprint -> "Sprint(Blind)" to "blind"
                    waterSprint -> "Sprint(Water)" to "water"
                    else -> "Sprint(Sneak)" to "sneak"
                }
                ctx.streak++
                val sustain = cfg.threshold.toInt().coerceAtLeast(1)
                if (ctx.streak >= sustain) {
                    flag(tp, ctx, 1.0, label, tick, Evidence(
                        subLabel = sub, measurement = ctx.streak.toDouble(),
                        threshold = sustain.toDouble(), pos = tp.pos))
                }
            } else {
                ctx.streak = 0
            }
        } catch (_: Throwable) {}
    }

    /** Sprint(Water): feet block is liquid, head (eye-height) block is NOT liquid, not swimming. */
    private fun waterSprint(tp: TrackedPlayer, tick: Int): Boolean {
        val world = MinecraftClient.getInstance().world ?: return false
        val bx = Math.floor(tp.pos.x).toInt()
        val bz = Math.floor(tp.pos.z).toInt()
        val footY = Math.floor(tp.pos.y).toInt()
        val headY = Math.floor(tp.pos.y + tp.eyeHeight()).toInt()
        if (!tp.chunkLoadedCached(world, tick, bx, bz)) return false
        val feetInWater = tp.feetLiquidCached(world, tick, bx, footY, bz)
        val headInWater = WorldQueries.isLiquidAt(world, bx, headY, bz)
        return feetInWater && !headInWater && !tp.swimming
    }

    private class SprintHackContext : CheckContext() {
        var streak: Int = 0
        /** Blindness currently on this player (tracked from EffectSignal; default false — a
         *  player already Blind at join isn't re-sent the add, so fail-open until observed). */
        var blind: Boolean = false
    }

    private companion object {
        /** Window (ticks) after a server-wide freeze within which sprint-hack samples are skipped. */
        private const val LAG_WINDOW = 8
        /** Window (ticks) after a batched catch-up burst within which sprint-hack samples are skipped. */
        private const val BURST_WINDOW = 3
    }
}
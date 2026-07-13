package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.history.Evidence
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.world.WorldQueries
import net.minecraft.client.MinecraftClient
import java.util.ArrayDeque
import java.util.UUID

/**
 * Through-walls / no-LOS aura detector (NCM-style rolling match-rate). On each inferred
 * [AttackEvent], raycasts the attacker's eye to three victim body points (eye / mid-torso /
 * feet) through the client's own world and records whether the victim's whole body was
 * occluded (all three blocked). A solid block on every segment means the attacker landed a
 * hit through a wall — KillAura with no line-of-sight filter.
 *
 * **Match-rate gate (replaces the old per-hit flag):** a single occluded hit is fragile — a
 * chunk-edge LOS miss, a fast corner-peek, or one laggy sample can all produce one all-occluded
 * verdict on a legit player. So instead of flagging on the first occluded hit, we keep a
 * rolling window of the last [WINDOW_N] occlusion verdicts per attacker and flag only when the
 * occluded fraction crosses [IustitiaConfig.CheckConfig.threshold] (default 0.5 — half of the
 * attacker's recent hits landed through a wall) once at least [MIN_SAMPLE] hits have accumulated.
 * A legit player's occasional occluded hit reads as a low rate and never flags; a through-walls
 * aura's repeated full-body-occluded hits read as a high rate and flag after a few hits (<1s of
 * combat). This is strictly better FP-wise than the per-hit flag it replaces.
 *
 * Chunk-gated throughout: a "blocked" verdict is only trusted when both endpoint chunks are
 * loaded ([WorldQueries.hasLineOfSight] returns false on unloaded chunks, which would otherwise
 * false-positive), and a hit whose chunks aren't loaded is skipped entirely (not counted in the
 * window) so an unloaded-chunk gap can't deflate the rate. setbackVL 8, decay 0.5/tick.
 */
class ThroughWallsCheck : Check() {

    override val id: String = "throughWalls"

    init {
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = ThroughWallsContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            val attacker = EntityTrackerManager.get(ev.attacker) ?: return
            val victim = EntityTrackerManager.get(ev.victim) ?: return
            if (attacker.inVehicle) return
            // A passenger's eye sits inside the vehicle frame; LOS through the frame is legit,
            // so a victim riding a boat/minecart is exempt (only the attacker side was before).
            if (victim.inVehicle) return
            val world = MinecraftClient.getInstance().world ?: return
            // Multi-point victim tolerance. A single eye→eye ray flags ANY cover between the
            // two players regardless of attacker look direction — fences, slabs and low walls
            // between meleeing players all occluded eye→eye and fired 21k FPs with 0 Polar
            // corroboration. Sample three victim body points (eye / mid-torso / feet) and treat
            // the hit as occluded only when the attacker's eye cannot see ANY of them — i.e. the
            // victim's whole body is occluded, which no legit melee hit is. Most "cover between
            // players" leaves the feet or torso visible, so this narrows to genuine full-wall hits.
            val eye = attacker.pos.add(0.0, attacker.eyeHeight(), 0.0)
            val targets = listOf(
                victim.pos.add(0.0, victim.eyeHeight(), 0.0), // ~1.62 victim eye
                victim.pos.add(0.0, 0.9, 0.0),                // mid-torso
                victim.pos.add(0.0, 0.1, 0.0)                 // feet
            )
            // only trust a verdict when both endpoint chunks are loaded (all three targets share
            // the victim column, so one chunk check covers them). Floor (not toInt) so negative
            // X/Z select the correct chunk — Double.toInt() truncates toward zero and would gate
            // the wrong chunk at negative coordinates. An unloaded-chunk hit is skipped entirely
            // (not pushed into the window) so it can't deflate the rate toward a false "clear".
            if (!WorldQueries.isChunkLoaded(world, Math.floor(eye.x).toInt(), Math.floor(eye.z).toInt())) return
            if (!WorldQueries.isChunkLoaded(world, Math.floor(victim.pos.x).toInt(), Math.floor(victim.pos.z).toInt())) return
            val occluded = targets.all { !WorldQueries.hasLineOfSight(world, eye, it) }

            // Rolling match-rate: push the verdict, trim to the window, flag when the occluded
            // fraction crosses the configured ratio with enough samples to trust it.
            val ctx = contextOf(attacker.uuid) as ThroughWallsContext
            ctx.push(occluded)
            val size = ctx.size
            if (size >= MIN_SAMPLE) {
                val rate = ctx.occludedCount.toDouble() / size
                if (rate >= cfg.threshold) {
                    flag(attacker, ctx, 1.0, "ThroughWalls", ev.tick, Evidence(
                        subLabel = "match-rate",
                        pos = eye,
                        victim = victim.uuid,
                        measurement = ctx.occludedCount.toDouble(),
                        threshold = size.toDouble(),
                        extra = "occluded ${ctx.occludedCount}/$size (rate ${"%.2f".format(rate)} ≥ ${"%.2f".format(cfg.threshold)})"))
                }
            }
        } catch (_: Throwable) {}
    }

    private class ThroughWallsContext : CheckContext() {
        /** Rolling window of recent occlusion verdicts (true = the hit was all-3-points-blocked).
         *  Newest at the tail; oldest evicted from the head beyond [WINDOW_N]. */
        private val window: ArrayDeque<Boolean> = ArrayDeque(WINDOW_N)
        /** Cached count of `true` in [window] so the rate is O(1) per hit, not an O(N) rescan. */
        private var occluded: Int = 0

        val size: Int get() = window.size
        val occludedCount: Int get() = occluded

        fun push(value: Boolean) {
            if (window.size >= WINDOW_N) {
                val evicted = window.pollFirst()
                if (evicted == true) occluded--
            }
            window.addLast(value)
            if (value) occluded++
        }
    }

    private companion object {
        /** Rolling window length (hits) for the occlusion match-rate. ~10–15 per the NCM design;
         *  12 balances responsiveness (flags after a few hits) against noise-smoothing. */
        private const val WINDOW_N = 12
        /** Minimum hits in the window before the rate is trusted. Without this, the first
         *  occluded hit reads 1/1 = 100% and flags instantly — defeating the FP-smoothing point
         *  of the rate gate. 3 = a blatant all-occluded opener flags within ~3 hits (<1s). */
        private const val MIN_SAMPLE = 3
    }
}
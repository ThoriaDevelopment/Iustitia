package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.event.AttackEvent
import dev.iustitia.history.Evidence
import dev.iustitia.math.AABB
import dev.iustitia.math.HitboxSizes
import dev.iustitia.math.RayAABB
import dev.iustitia.math.Vectors
import dev.iustitia.protocol.ProtocolDetector
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Triggerbot detector — deliberately LAX: only flags really blatant, sub-reaction auto-attackers.
 *
 * A triggerbot auto-attacks the instant the crosshair reaches a victim's hitbox; the player
 * aims manually and the cheat clicks for them. Unlike KillAura (silent-aim — attacks *without*
 * facing the victim) and HitFlick (snaps away at the hit tick), a triggerbot DOES face the target,
 * so those checks miss it by design. The only client-side-observable, inhuman signal is *timing*:
 * a triggerbot lands its hit within a tick or two of the crosshair FIRST reaching the hitbox
 * (a rising edge), every single hit. A real player has ~5–12 ticks of visual reaction and only
 * occasionally sweeps onto a target — never consistently on every hit.
 *
 * Mechanism:
 *  - `process` runs every tick per attacker and raycasts the attacker's eye→look against each
 *    nearby victim's hitbox (within vanilla melee reach). It records the tick the crosshair FIRST
 *    reaches each victim's hitbox (the rising edge, [TriggerbotContext.engagementStart]).
 *  - `onAttack` (an inferred attack A→V) reads V's engagement-start tick and classes the hit as
 *    "fast" when it lands within [MAX_REACTION_TICKS] of that rising edge.
 *  - A rolling window of recent hits (cap [WINDOW]) feeds a consistency gate: flag only when at
 *    least [cfg.threshold] (default 4) fast hits have occurred, across at least [MIN_SAMPLES]
 *    (5) observed hits, at a ratio ≥ [RATIO] (0.75). This is the "really blatant" bar — a legit
 *    player may produce one or two fast-looking hits by luck, but not 4 of 5.
 *
 * Timing fuzz is deliberately absorbed: the hurt that confirms an attack arrives up to
 * [ProtocolDetector.hurtLookback] ticks after the swing, and `process` runs a tick behind the
 * attack packet — so a 0-tick-reaction triggerbot reads as `ev.tick − risingTick ≈ 2` (modern) /
 * `≈ 3` (1.8). [MAX_REACTION_TICKS] = 3 catches 0–2-tick-reaction triggerbots on both protocols;
 * legit 5+ tick reaction lands at 7+ and never qualifies. The consistency gate absorbs the
 * 3–4-tick ambiguous zone (a single sub-3-tick reaction is superhuman but not impossible; four
 * of five is not).
 *
 * Chunk-unloaded ⇒ never-FP preserved: the look→hitbox test uses only the server-rebroadcast
 * yaw/pos (no world query), and an unloaded victim chunk yields a near-origin pos that is never
 * within melee reach → skipped. Exempt while riding (vehicle rotations unreliable). setbackVL 5,
 * decay 0.05/tick. Fail-open throughout.
 */
class TriggerbotCheck : Check() {

    override val id: String = "triggerbot"

    init {
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = TriggerbotContext()

    /** A hit is "fast" (inhuman reaction) when it lands within this many ticks of the rising edge. */
    private val maxReactionTicks: Int get() = MAX_REACTION_TICKS

    /** Per-tick: update each attacker's crosshair-on-hitbox state and record rising edges. */
    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle) return
            val ctx = contextOf(tp.uuid) as TriggerbotContext
            val eye = tp.pos.add(0.0, tp.eyeHeight(), 0.0)
            val look = Vectors.lookVector(tp.yaw.toDouble(), tp.pitch.toDouble())
            val end = eye.add(look.multiply(LOOK_REACH + 1.0))
            val margin = 0.0005 + if (ProtocolDetector.is1_8OrLess) 0.1 else 0.0

            val nearby = HashSet<UUID>()
            for (victim in EntityTrackerManager.all()) {
                if (victim.uuid == tp.uuid) continue
                // cheap distance pre-check before the raycast (skip far victims)
                val dx = tp.pos.x - victim.pos.x
                val dy = tp.pos.y - victim.pos.y
                val dz = tp.pos.z - victim.pos.z
                if (dx * dx + dy * dy + dz * dz > COMBAT_RANGE_SQ) continue
                val vEntity = victim.entity
                if (vEntity != null && !vEntity.isAlive) continue
                nearby.add(victim.uuid)

                // victim hitbox is pose-aware (sneak → 0.6×1.5, glide/swim/riptide → 0.6×0.6).
                // A standing-sized box on a crouched victim registered the crosshair "on"
                // while still above the real (shorter) hitbox → rising edge early → false
                // sub-reaction reading on a legit player sneaking under the crosshair.
                val vh = HitboxSizes.forPose(victim)
                val box = AABB.around(victim.pos.x, victim.pos.y, victim.pos.z, vh.width, vh.height).expand(margin)
                val onNow = RayAABB.calculateIntercept(box, eye, end) != null
                val prev = ctx.onHitbox[victim.uuid] ?: false
                if (onNow && !prev) {
                    // rising edge: crosshair FIRST reached this victim's hitbox this tick
                    ctx.engagementStart[victim.uuid] = tick
                } else if (!onNow) {
                    // disengaged: clear so the next on-target is a fresh rising edge
                    ctx.engagementStart.remove(victim.uuid)
                }
                ctx.onHitbox[victim.uuid] = onNow
            }
            // prune victims no longer nearby so the maps can't grow unbounded
            val hitIt = ctx.onHitbox.keys.iterator()
            while (hitIt.hasNext()) { if (hitIt.next() !in nearby) hitIt.remove() }
            val engIt = ctx.engagementStart.keys.iterator()
            while (engIt.hasNext()) { if (engIt.next() !in nearby) engIt.remove() }
        } catch (_: Throwable) {
            // fail-open
        }
    }

    private fun onAttack(ev: AttackEvent) {
        try {
            val attacker = EntityTrackerManager.get(ev.attacker) ?: return
            if (attacker.inVehicle) return
            // victim must be tracked for the rising edge to be meaningful
            EntityTrackerManager.get(ev.victim) ?: return
            val ctx = contextOf(ev.attacker) as TriggerbotContext

            val startTick = ctx.engagementStart[ev.victim]
            val fast = startTick != null && (ev.tick - startTick) in 0..maxReactionTicks

            // push into the rolling window (cap WINDOW, evict oldest)
            ctx.samples.addFirst(HitSample(ev.tick, fast))
            while (ctx.samples.size > WINDOW) ctx.samples.removeLast()

            val total = ctx.samples.size
            if (total < MIN_SAMPLES) return
            val fastCount = ctx.samples.count { it.fast }
            val minFast = cfg.threshold.toInt().coerceAtLeast(1)
            if (fastCount < minFast) return
            val ratio = fastCount.toDouble() / total.toDouble()
            if (ratio < RATIO) return

            // blatant, consistent sub-reaction auto-attacker: flag once per qualifying fast hit.
            // The consistency gate (minFast + ratio) means this only fires once the pattern is
            // established, so each subsequent fast hit climbs VL toward setbackVL (5).
            val reaction = startTick?.let { ev.tick - it }
            flag(attacker, ctx, 1.0, "Triggerbot", ev.tick, Evidence(
                measurement = ratio, threshold = RATIO.toDouble(), pos = attacker.pos,
                victim = ev.victim, extra = "fast=$fastCount/$total reaction=$reaction"))
        } catch (_: Throwable) {
            // fail-open
        }
    }

    private class TriggerbotContext : CheckContext() {
        // onHitbox + engagementStart are mutated in process (client-tick thread: put / remove /
        // key-iterator.remove) AND read in onAttack (network thread: get) — AttackEvent is
        // published from the packet handler. A plain HashMap under concurrent put+get can
        // resize-mid-get and infinite-loop (NOT caught by fail-open, which only catches
        // throwables); ConcurrentHashMap is the drop-in safe replacement.
        /** victim uuid → was the attacker's crosshair on this victim's hitbox last tick. */
        val onHitbox: ConcurrentHashMap<UUID, Boolean> = ConcurrentHashMap()
        /** victim uuid → tick the current on-hitbox engagement began (rising edge). Absent = disengaged. */
        val engagementStart: ConcurrentHashMap<UUID, Int> = ConcurrentHashMap()
        /** rolling window of recent hits (most-recent first); bounded by [WINDOW].
         *  Mutated only in onAttack (network thread) — single-threaded, ArrayDeque is fine. */
        val samples: ArrayDeque<HitSample> = ArrayDeque()
    }

    private data class HitSample(val tick: Int, val fast: Boolean)

    companion object {
        /** Look-ray length for the "crosshair on hitbox" test = vanilla melee reach (3.0). A target
         *  beyond reach is not hittable, so its rising edge only matters once it enters reach — this
         *  avoids the FP where a legit player walks the last fraction of a block into range with the
         *  crosshair already held on the target (which would otherwise read as a 2-3 tick "reaction"). */
        private const val LOOK_REACH = 3.0
        /** Only consider victims within this distance (cheap pre-check before raycast). */
        private const val COMBAT_RANGE = 5.0
        private const val COMBAT_RANGE_SQ = COMBAT_RANGE * COMBAT_RANGE
        /** Fast-hit window (ticks from rising edge). See class doc for the hurtLookback-fuzz reasoning. */
        private const val MAX_REACTION_TICKS = 3
        /** Min observed hits before judging consistency (avoids small-sample flukes). */
        private const val MIN_SAMPLES = 5
        /** Rolling-window size (recent hits judged for the ratio). */
        private const val WINDOW = 24
        /** Min fast-hit ratio required to flag (4 of 5 ≈ 0.8 clears it; 3 of 5 = 0.6 does not). */
        private const val RATIO = 0.75
    }
}
package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.math.AABB
import dev.iustitia.math.HitboxSizes
import dev.iustitia.math.RayAABB
import dev.iustitia.math.Vectors
import dev.iustitia.protocol.ProtocolDetector
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max

/**
 * Lag-compensated reach, ported from Nemesis `RangeA` + Grim `Reach`/`ReachUtils`.
 *
 * On each inferred [AttackEvent] (attacker A → victim V), measures the minimum
 * attacker-eye → victim-hitbox ray intercept over candidate looks (current + last
 * yaw/pitch, Grim's idle-packet trick) × candidate victim positions (current +
 * last ~3 ticks from V's ring buffer, Nemesis lag comp). The held-item attack
 * range is never broadcast, so we assume vanilla 3.0 and flag past 3.5.
 *
 * Exemptions: attacker in vehicle, victim dead, attacker just teleported this tick,
 * non-player victim (filtered upstream). 1.8 protocol widens the hitbox margin by 0.1.
 */
class ReachCheck : Check() {

    override val id: String = "reach"

    init {
        try {
            Iustitia.bus.subscribe<AttackEvent> { onAttack(it) }
        } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = ReachContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            val attacker = EntityTrackerManager.get(ev.attacker) ?: return
            val victim = EntityTrackerManager.get(ev.victim) ?: return
            if (attacker.inVehicle) return
            if (attacker.lastTeleportTick == ev.tick) return
            val vEntity = victim.entity
            if (vEntity != null && !vEntity.isAlive) return

            val eye = attacker.pos.add(0.0, attacker.eyeHeight(), 0.0)
            val margin = 0.0005 + if (ProtocolDetector.is1_8OrLess) 0.1 else 0.0
            val maxReach = cfg.threshold

            val looks = listOf(
                Vectors.lookVector(attacker.yaw.toDouble(), attacker.pitch.toDouble()),
                Vectors.lookVector(attacker.lastYaw.toDouble(), attacker.pitch.toDouble()),
                Vectors.lookVector(attacker.lastYaw.toDouble(), attacker.lastPitch.toDouble()),
            )

            // victim hitbox is pose-aware (sneak → 0.6×1.5, glide/swim/riptide → 0.6×0.6).
            // A standing-sized box on a crouched/elytra victim biased the ray intercept
            // (taller box → shorter measured distance → missed real reach against them).
            val vh = HitboxSizes.forPose(victim)

            // candidate victim positions: current + recent ring samples (lag comp)
            val positions = ArrayList<Vec3d>(8)
            positions.add(victim.pos)
            for (p in victim.ring.getPositions(3, ev.tick)) positions.add(p)

            var minDist = Double.MAX_VALUE
            var anyHit = false
            for (vp in positions) {
                val box = AABB.around(vp.x, vp.y, vp.z, vh.width, vh.height).expand(margin)
                for (look in looks) {
                    val end = eye.add(look.multiply(maxReach + 3.0))
                    val hit = RayAABB.calculateIntercept(box, eye, end) ?: continue
                    anyHit = true
                    val d = eye.distanceTo(hit)
                    if (d < minDist) minDist = d
                }
            }

            val ctx = contextOf(attacker.uuid)
            if (!anyHit) {
                // None of the 3 candidate looks × ring positions caught the hitbox. This is routine
                // interpolation noise, NOT an anomaly: the actual attack look falls between our 3
                // sampled looks (current / lastYaw+pitch / lastYaw+lastPitch) and/or the victim
                // moved between the few ring samples, so a legit hit's ray routinely misses the box
                // — validation showed this fired 100+ times per legit player (122 on one), flagging
                // 37 players where the server's own anticheat (Polar) flagged only 3. Flagging every
                // miss was pure FP. Only treat a miss as a reach violation when the victim's hitbox
                // is genuinely beyond reach (nearest face > maxReach + 0.5), which no legit melee
                // hit can be. Close-range misses are inconclusive and dropped (chunk-unloaded ⇒
                // never-FP is preserved: an unloaded victim chunk yields a near origin/pos, which
                // is always within reach and therefore never flags).
                val centerDist = eye.distanceTo(victim.pos)
                val nearestFace = centerDist - vh.width / 2.0
                if (nearestFace > maxReach + 0.5) {
                    val level = max(1.0, ceil((nearestFace - maxReach) * 2.0))
                    flag(attacker, ctx, level, "Reach", ev.tick)
                }
                return
            }
            if (minDist > maxReach + 0.5) {
                val level = max(1.0, ceil((minDist - maxReach) * 2.0))
                flag(attacker, ctx, level, "Reach", ev.tick)
            }
        } catch (_: Throwable) {
            // fail-open
        }
    }

    private class ReachContext : CheckContext()
}
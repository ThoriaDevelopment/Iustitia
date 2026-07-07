package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.history.Evidence
import dev.iustitia.math.AABB
import dev.iustitia.math.HitboxSizes
import dev.iustitia.math.RayAABB
import dev.iustitia.math.Vectors
import dev.iustitia.protocol.ProtocolDetector
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.LagCombatCorrelator
import dev.iustitia.tracking.TrackedPlayer
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.max

/**
 * Lag-compensated reach, ported from Nemesis `RangeA` + Grim `Reach`/`ReachUtils`.
 *
 * On each inferred [AttackEvent] (attacker A → victim V), measures the minimum
 * attacker-eye → victim-hitbox ray intercept over candidate looks (current + last
 * yaw/pitch, Grim's idle-packet trick) × candidate victim positions (current +
 * last ~3 ticks from V's ring buffer, Nemesis lag comp). The held-item attack
 * range is never broadcast, so we assume vanilla 3.0 and flag past 3.8 (3.0 + 0.1 hitbox margin
 * = 3.1 legit ceiling, +0.7 headroom for client-side interpolation lag on fast/dash combat).
 * A client-side check can't match Polar's server-side 3.3+ reach detection, so this is a
 * blatant-only detector — see the in-box/fallback headroom notes below.
 *
 * Exemptions: attacker in vehicle, victim dead, attacker just teleported this tick,
 * non-player victim (filtered upstream). The hitbox is expanded by the vanilla
 * ItemAttackRange margin (0.1 baseline, +0.1 extra on 1.8 whose hitbox is also wider).
 *
 * **HITBOX-vs-REACH split (Grim, plan §3/§8 step 4):** when none of the candidate looks
 * catches the hitbox, the miss splits by distance — beyond reach (`nearestFace >
 * maxReach + 0.8`) is the distance flag (the miss is expected there; the distance is the
 * signal); within reach (`nearestFace <= maxReach`) is the `hitboxMiss` sub-flag — the
 * attack landed while no recent look touched the expanded hitbox of a *hittable* victim,
 * the HitBox / silent-aim tell (stronger than a distance flag, level 2.0). The bare miss
 * carries between-samples interpolation FP, so `hitboxMiss` gates on a CLEAR angular miss:
 * the min angle over the 3 candidate looks vs the hitbox center must exceed 30° (at
 * within-reach distance the hitbox spans ≤~17° — head/feet aim — so 30° off-center is
 * unambiguously "looking away", not interpolation). The middle distance band
 * (`maxReach < nearestFace <= maxReach + 0.8`) is ambiguous and fails open. Shares this
 * check's VL pool; distinct label `HitboxMiss` (sub-flag, no new check id).
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
            // Vanilla ItemAttackRange expands the target hitbox by 0.1 (hitboxMargin) on every
            // version; the old 0.0005 baseline was far too tight on 1.21.11, so legit 3.0–3.5
            // attacks read as box-misses and fell through to the over-firing nearestFace path
            // (miscalibrated — flags wrong players). 0.1 baseline on all versions + the existing
            // +0.1 on 1.8 (whose hitbox is also wider) restores the documented reach geometry.
            val margin = 0.1 + if (ProtocolDetector.is1_8OrLess) 0.1 else 0.0
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
                // None of the 3 candidate looks × ring positions caught the hitbox. A miss splits
                // by distance (HITBOX-vs-REACH, Grim, plan §3/§8 step 4): beyond reach the miss is
                // expected and the distance is the signal (the "face" flag); within reach a real
                // look would have caught the hitbox, so a miss there is the HitBox / silent-aim
                // tell (the `hitboxMiss` sub-flag). Lag-comp the fallback: use the closest of the
                // victim's current + recent ring positions (same lag comp as the in-box path), not
                // just current — a victim who walked out of reach by the attack tick was in reach
                // 1-2 ticks ago, and current-only misread them as out-of-reach. (Chunk-unloaded ⇒
                // never-FP preserved: an unloaded victim chunk yields a near origin/pos, always
                // within reach and never flags.)
                var bestCenter = Double.MAX_VALUE
                var bestVp: Vec3d = victim.pos
                for (vp in positions) {
                    val d = eye.distanceTo(vp)
                    if (d < bestCenter) { bestCenter = d; bestVp = vp }
                }
                val nearestFace = bestCenter - vh.width / 2.0
                // hitboxMiss sub-flag: victim WITHIN reach but no candidate look touched the
                // expanded hitbox → attacked while looking away from a hittable victim. The bare
                // miss carries between-samples interpolation FP, so gate on a CLEAR angular miss:
                // the min angle over the 3 candidate looks vs the hitbox center must exceed
                // HITBOX_MISS_ANGLE. At within-reach distance the hitbox spans ≤~17° (head/feet
                // aim), so 30° off-center is unambiguously "looking away", not interpolation.
                // Using the closest ring position + the closest candidate look is the most
                // favorable-to-the-attacker reading → lowest FP.
                if (nearestFace <= maxReach) {
                    val center = bestVp.add(0.0, vh.height / 2.0, 0.0)
                    val dir = center.subtract(eye)
                    val dirLen = dir.length()
                    if (dirLen > 0.01) {
                        var minAngle = Double.MAX_VALUE
                        for (look in looks) {
                            val cosA = look.dotProduct(dir) / (look.length() * dirLen)
                            val angle = Math.toDegrees(acos(maxOf(-1.0, minOf(1.0, cosA))))
                            if (angle < minAngle) minAngle = angle
                        }
                        if (minAngle > HITBOX_MISS_ANGLE) {
                            flag(attacker, ctx, VL_HITBOX_MISS, "HitboxMiss", ev.tick, Evidence(
                                subLabel = "miss", measurement = minAngle, threshold = HITBOX_MISS_ANGLE,
                                pos = eye, victim = victim.uuid,
                                extra = "nearestFace=$nearestFace angle=${"%.1f".format(minAngle)}°"))
                        }
                    }
                    // within reach but the angular miss wasn't clear → interpolation, fail-open
                    return
                }
                // Headroom 0.8 (blatant-only) on the FALLBACK path: the lag-comp MIN over the ring
                // still over-credits fast-combat / dash-item hits, where both fighters move fast
                // so every recent victim position sits ~3.5–4.0 away → nearestFace>3.4 fired on
                // Polar-clean players (916japa vl 33.5, 4nson 29 — 6 corroborated-FP alerts vs one
                // real tatortot9yr catch). 0.8 (nearestFace > 4.0) drops those: no legit dash-hit
                // has every recent position beyond 4.0. A real 4.0+ reach aura is blatant enough to
                // still clear it. The IN-BOX path below (look caught the hitbox → facing the
                // victim) keeps the tight 0.4, so a facing reach hacker at 3.4+ (tatortot9yr's
                // tier) is still caught there.
                if (nearestFace > maxReach + 0.8) {
                    val level = max(1.0, ceil((nearestFace - maxReach) * 2.0))
                    flag(attacker, ctx, level, "Reach", ev.tick, Evidence(
                        subLabel = "face", measurement = nearestFace, threshold = maxReach + 0.8,
                        pos = eye, victim = victim.uuid, extra = "nearest-face over reach"))
                    lagRangeAmplify(attacker, victim, ctx, eye, ev)
                }
                return
            }
            // In-box path: a candidate look caught the hitbox, so the measured distance is real.
            // Headroom 0.8 (matches the fallback): client-side interpolation LAGS the victim's
            // real server position, so during fast / dash-item combat every recent ring position
            // sits ~3.4–3.7 away even when the server sees the victim at ≤3.0 — the in-box path
            // at 0.4 over-measured this and alerted on 5 Polar-clean players in one session
            // (4nson vl 49, Chargedupwizrd 47, athinaslime 28 — 0 TP alerts, all FPs; Polar's
            // server-side reach catches 3.3+ that a client-side check simply can't via lag-comp).
            // 0.8 (flag past 3.8) makes reach a blatant-only detector: vanilla 3.0 + the 0.1
            // hitbox margin = 3.1 legit ceiling, so 3.8+ is unambiguously beyond reach even with
            // dash-lag noise. A real 3.8+ reach aura still clears it; legit dash-combat does not.
            if (minDist > maxReach + 0.8) {
                val level = max(1.0, ceil((minDist - maxReach) * 2.0))
                flag(attacker, ctx, level, "Reach", ev.tick, Evidence(
                    subLabel = "in-box", measurement = minDist, threshold = maxReach + 0.8,
                    pos = eye, victim = victim.uuid, extra = "hitbox intercept"))
                lagRangeAmplify(attacker, victim, ctx, eye, ev)
            }
        } catch (_: Throwable) {
            // fail-open
        }
    }

    /**
     * Axis B amplifier (plan §2.2/§6): an over-distance flag coincident with a self-induced
     * attacker position freeze → Lag Range. The attacker holds its outgoing position stream
     * so the server keeps an old (closer) position for it while it slides to a real over-distance
     * hit — observable as a local freeze episode (entity-local, not global-lag) around the attack.
     * Adds a distinct-label sub-flag sharing `reach`'s VL pool (no new check id). Only the
     * distance flags trigger it; `hitboxMiss` (within-reach silent-aim) is a different cheat.
     */
    private fun lagRangeAmplify(
        attacker: TrackedPlayer, victim: TrackedPlayer, ctx: CheckContext, eye: Vec3d, ev: AttackEvent,
    ) {
        try {
            val lag = LagCombatCorrelator.combatCorrelatedLag(attacker.uuid, ev.tick, LAG_CORR_WINDOW)
            if (lag >= MIN_LAG_FREEZE) {
                flag(attacker, ctx, VL_LAG_CORR, "Reach(LagRange)", ev.tick, Evidence(
                    subLabel = "lag-correlated", measurement = lag.toDouble(), threshold = MIN_LAG_FREEZE.toDouble(),
                    pos = eye, victim = victim.uuid, extra = "attacker self-freeze around attack"))
            }
        } catch (_: Throwable) {
            // fail-open
        }
    }

    private class ReachContext : CheckContext()

    private companion object {
        // -- hitboxMiss sub-flag (HITBOX-vs-REACH split, plan §3/§8 step 4) --
        /** Flag level for the hitbox-miss sub-flag — "stronger than a distance flag" (§3); the
         *  distance flag's level is `max(1.0, ceil((d-maxReach)*2))` (scales 1.0+). Tuned in step 14. */
        const val VL_HITBOX_MISS = 2.0
        /** Min angle (deg) between the closest candidate look and the hitbox center for a clear
         *  hitbox miss. At within-reach distance the hitbox spans ≤~17° (head/feet aim), so 30°
         *  off-center is unambiguously "looking away", not between-samples interpolation. */
        const val HITBOX_MISS_ANGLE = 30.0
        // -- Lag Range amplifier (Axis B, plan §3/§8 step 6) --
        /** Window (ticks) around the attack within which an attacker self-freeze counts as Lag
         *  Range. Lag Range holds through the attack + interpolation, so a slightly wider window
         *  than the tight combat-timed cheats. Tuned in step 14. */
        const val LAG_CORR_WINDOW = 8
        /** Min local-freeze ticks (coincident with combat) to amplify — a 1-tick micro-stutter is
         *  not a Lag-Range hold. */
        const val MIN_LAG_FREEZE = 2
        /** Amplifier sub-flag level — "weight up" on top of the distance flag. Tuned in step 14. */
        const val VL_LAG_CORR = 1.0
    }
}
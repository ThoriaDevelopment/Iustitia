package dev.iustitia.checks.combat

import dev.iustitia.NumFmt
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
import kotlin.math.sqrt

/**
 * Lag-compensated reach, ported from Nemesis `RangeA` + Grim `Reach`/`ReachUtils`.
 *
 * On each inferred [AttackEvent] (attacker A → victim V), measures the minimum
 * attacker-eye → victim-hitbox ray intercept over candidate looks (current + last
 * yaw/pitch, Grim's idle-packet trick) × candidate victim positions (current +
 * last ~3 ticks from V's ring buffer, Nemesis lag comp). The attack range is the
 * `generic.entity_interaction_range` attribute (vanilla 3.0; the 1.21 Spear extends it to ~4.5 via
 * an attribute modifier — `data/minecraft/tags/item/spears.json`, NOT an item class), read live off
 * the attacker's entity with a per-tick ring max over the swing→hurt window so a spear→sword swap
 * keeps the spear ceiling for the legitimate swing-tick hit. We flag past `maxReach + 0.8` (3.0 +
 * 0.1 hitbox margin = 3.1 legit ceiling, +0.7 headroom for client-side interpolation lag on
 * fast/dash combat; a spear's 4.5 → 5.3 blatant-only ceiling).
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
            // 1.21 reach is the `generic.entity_interaction_range` attribute (the Spear extends it to
            // ~4.5 via an attribute modifier, not an item class) — read the attacker's live range,
            // falling open to the configured vanilla default on any failure. The hurt tick (ev.tick)
            // can lag the swing tick by `hurtLookback` (2), so a spear→sword swap between swing and
            // hurt would already read the swapped (3.0) value at ev.tick — take the max sampled range
            // over the last REACH_LAG_WINDOW ticks (lag-comp, mirroring the victim ring at :96) so
            // the spear's 4.5 ceiling covers the legitimate swing-tick hit.
            val liveReach = try {
                attacker.entity?.getEntityInteractionRange() ?: cfg.threshold
            } catch (_: Throwable) { cfg.threshold }
            val maxReach = maxOf(liveReach, attacker.reachMaxSince(ev.tick - REACH_LAG_WINDOW))

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

            // ReachContext (not just the base CheckContext): the hitboxMiss episode keeps its
            // own ring + latch on the context (see ReachContext below).
            val ctx = contextOf(attacker.uuid) as ReachContext

            // ---------------------------------------------------------------
            // Exact-geometry path (motionless pair) -- ghost-tier reach.
            // ---------------------------------------------------------------
            // The 0.8 headroom on the ray paths below exists for exactly one reason: client-side
            // interpolation lags the server's position while a player MOVES, so a measured distance
            // can read up to ~0.7 long during fast / dash combat. That error is identically zero
            // when neither fighter has moved -- the client's positions ARE the server's -- so this
            // path drops the ray entirely and compares the **vanilla reach metric itself** (eye to
            // the closest point of the victim's hitbox, unexpanded, exactly what the server
            // measures) against the interaction range with only the hitbox-margin headroom. That is
            // the only way a client-side check can see a ghost-tier 3.6-block bite: through the ray
            // it measures 3.2 and never clears the 0.8-headroom 3.8 bar, which is why that whole
            // tier used to be invisible. A motionless pair cannot be a dash-lag false positive by
            // construction, so this does not re-open the FPs the headroom was introduced to close.
            if (motionlessPair(attacker, victim, ev.tick)) {
                var closest = Double.MAX_VALUE
                for (vp in positions) {
                    val box = AABB.around(vp.x, vp.y, vp.z, vh.width, vh.height)
                    val d = sqrt(box.closestPointSqDistance(eye.x, eye.y, eye.z))
                    if (d < closest) closest = d
                }
                // Sustained-episode gate, for the same economy reason as the per-hit combat checks: a
                // sub-blatant reach hit is `ceil((3.3-3.0)*2) = 1.0` of level against a `0.5`/tick
                // decay, and hits land about one per 6 ticks, so the VL can never climb toward the
                // 10.0 setback however many times the cheater does it. Requiring the pattern and
                // alerting once for the episode is what makes the tier actionable; the measurement
                // itself is exact here, so no legitimate hit can feed the pattern.
                val over = closest > maxReach + STATIC_HEADROOM
                val sustainedNow = sustained(ctx, over, STILL_WINDOW, STILL_MIN)
                if (sustainedNow) {
                    flagEpisode(attacker, ctx, "Reach", ev.tick, Evidence(
                        subLabel = "motionless", measurement = closest, threshold = maxReach + STATIC_HEADROOM,
                        pos = eye, victim = victim.uuid,
                        extra = "hit from ${NumFmt.d(digits = 2, v = closest)} blocks while neither fighter had moved (vanilla max ${NumFmt.d(digits = 1, v = maxReach)})"))
                    lagRangeAmplify(attacker, victim, ctx, eye, ev)
                } else {
                    rearmEpisode(ctx, sustainedNow)
                }
                return
            }

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
                    var minAngle = Double.MAX_VALUE
                    if (dirLen > 0.01) {
                        for (look in looks) {
                            val cosA = look.dotProduct(dir) / (look.length() * dirLen)
                            val angle = Math.toDegrees(acos(maxOf(-1.0, minOf(1.0, cosA))))
                            if (angle < minAngle) minAngle = angle
                        }
                    }
                    val miss = dirLen > 0.01 && minAngle > HITBOX_MISS_ANGLE
                    // Episode-gated with its own ring + latch (an angular tell, not a distance
                    // violation — it must not mix labels into the distance episode ring): the
                    // old flat 2.0 per clear miss roughly broke even against the 0.25/tick decay
                    // at combat cadence, so a silent-aimmer could flag forever without ever
                    // alerting. ≥[MISS_MIN] clear misses in the last [MISS_WINDOW] hits → one
                    // episode alert at setbackVL+1.0; a facing hit releases the latch.
                    ctx.missRing.addFirst(miss)
                    while (ctx.missRing.size > MISS_WINDOW) ctx.missRing.pollLast()
                    var missCount = 0
                    for (v in ctx.missRing) if (v) missCount++
                    if (ctx.missRing.size >= MISS_WINDOW && missCount >= MISS_MIN) {
                        if (!ctx.missEpisode) {
                            ctx.missEpisode = true
                            flag(attacker, ctx, setbackVL + 1.0, "HitboxMiss", ev.tick, Evidence(
                                subLabel = "miss", measurement = minAngle, threshold = HITBOX_MISS_ANGLE,
                                pos = eye, victim = victim.uuid,
                                extra = "looked ${NumFmt.d(digits = 1, v = minAngle)}° off a hittable victim (within reach, not facing them)"))
                        }
                    } else if (!miss) {
                        ctx.missEpisode = false
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
                // Sustained-episode gate, same economy as the motionless path: the sub-blatant
                // edge level (~1.0/hit) roughly breaks even against the 0.25/tick decay at
                // combat cadence, so the old per-hit flag could pin VL forever without ever
                // alerting. Shares the motionless path's ring — both record "a recent hit
                // measured out of range" — so a mixed-pattern reach cheater is judged on one
                // coherent window. lagRangeAmplify rides the episode alert as before.
                val overFace = nearestFace > maxReach + 0.8
                val sustainedFace = sustained(ctx, overFace, STILL_WINDOW, STILL_MIN)
                if (sustainedFace) {
                    flagEpisode(attacker, ctx, "Reach", ev.tick, Evidence(
                        subLabel = "face", measurement = nearestFace, threshold = maxReach + 0.8,
                        pos = eye, victim = victim.uuid,
                        extra = "hit from ${NumFmt.d(digits = 2, v = nearestFace)} blocks (vanilla max ${NumFmt.d(digits = 1, v = maxReach)})"))
                    lagRangeAmplify(attacker, victim, ctx, eye, ev)
                } else {
                    rearmEpisode(ctx, sustainedFace)
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
            // Episode-gated like the face path (same decay-break-even rationale).
            val overInBox = minDist > maxReach + 0.8
            val sustainedInBox = sustained(ctx, overInBox, STILL_WINDOW, STILL_MIN)
            if (sustainedInBox) {
                flagEpisode(attacker, ctx, "Reach", ev.tick, Evidence(
                    subLabel = "in-box", measurement = minDist, threshold = maxReach + 0.8,
                    pos = eye, victim = victim.uuid,
                    extra = "hit from ${NumFmt.d(digits = 2, v = minDist)} blocks (vanilla max ${NumFmt.d(digits = 1, v = maxReach)})"))
                lagRangeAmplify(attacker, victim, ctx, eye, ev)
            } else {
                rearmEpisode(ctx, sustainedInBox)
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

    /**
     * True when **both** fighters have been positionally motionless across the recent ring
     * window, horizontal and vertical.
     *
     * This is the precondition for trusting a distance measurement at a near-vanilla ceiling: the
     * only systematic error in a client-side reach measurement is interpolation of a moving
     * player's server position, and a player who has not moved has nothing to interpolate. Both
     * sides are required because the attacker's own interpolated eye position biases the measured
     * distance just as the victim's does. Deliberately conservative thresholds (2 cm horizontal,
     * 2 cm vertical over up to four samples): anything that moved even slightly keeps the full
     * 0.8 headroom. Fail-closed (returns false) on a short/absent ring.
     */
    private fun motionlessPair(attacker: TrackedPlayer, victim: TrackedPlayer, tick: Int): Boolean =
        isMotionless(attacker, tick) && isMotionless(victim, tick)

    private fun isMotionless(tp: TrackedPlayer, tick: Int): Boolean {
        // Aggregation only, so walk the ring's own storage instead of materializing Vec3d samples.
        var n = 0
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minZ = Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        try {
            tp.ring.forEachRecent(3, tick) { x, y, z ->
                n++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (z < minZ) minZ = z
                if (z > maxZ) maxZ = z
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        } catch (_: Throwable) {
            // Same verdict as an absent/short ring below: not provably motionless, so keep the full
            // 0.8 headroom. Fail-closed.
            return false
        }
        if (n < 3) return false
        return (maxX - minX) < STILL_EPS && (maxZ - minZ) < STILL_EPS && (maxY - minY) < STILL_EPS
    }

    private class ReachContext : CheckContext() {
        /** Rolling clear-miss verdicts (hitboxMiss episode window) + its own latch, separate
         *  from the shared distance episode ring/latch. */
        val missRing = java.util.concurrent.ConcurrentLinkedDeque<Boolean>()
        var missEpisode = false
    }

    private companion object {
        // -- hitboxMiss sub-flag (HITBOX-vs-REACH split, plan §3/§8 step 4): episode-gated, one
        //    alert at setbackVL+1.0 per sustained clear-miss pattern (see MISS_WINDOW/MISS_MIN). --
        /** Lag-comp window (ticks) for the attacker's interaction-range ring — covers the
         *  swing→hurt gap (`ProtocolDetector.hurtLookback` = 2 on 1.21) so a spear→sword swap
         *  between the swing and the (later) hurt tick keeps the spear's reach ceiling. */
        const val REACH_LAG_WINDOW = 3
        /** Rolling window of clear hitbox misses the silent-aim episode is judged over. */
        const val MISS_WINDOW = 4
        /** Clear hitbox misses required in the window to confirm a silent-aim episode. */
        const val MISS_MIN = 2
        /** Headroom (blocks) over the vanilla interaction range on the **motionless-pair** path,
         *  where interpolation error is provably absent. The vanilla reach check allows
         *  `range + hitboxMargin` = 3.1 for a player target, so 3.2 leaves 0.1 of headroom above the
         *  legitimate ceiling while clearing a ghost-tier 3.6-block bite (whose true closest-point
         *  distance is 3.3) by the same 0.1. */
        const val STATIC_HEADROOM = 0.2
        /** Rolling window of motionless-pair hits the ghost-tier episode is judged over. */
        const val STILL_WINDOW = 6
        /** Over-range motionless-pair hits required in the window. The metric is exact and the
         *  threshold carries 0.1 of headroom on both sides, so 2 of the last 6 is a safety net
         *  against a one-off geometry edge, not a statistical filter. */
        const val STILL_MIN = 2
        /** Max per-axis travel (blocks) across the ring window that still counts as motionless. */
        const val STILL_EPS = 0.02
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
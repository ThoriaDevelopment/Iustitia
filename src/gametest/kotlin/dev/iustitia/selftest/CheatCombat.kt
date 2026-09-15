package dev.iustitia.selftest

import net.minecraft.item.ItemStack
import net.minecraft.item.Items

/**
 * The **combat half of the unfair-advantage pass**. Every scenario names the reference client
 * whose module it reproduces (`References/Cheats/<client>`), and the checks a detector's
 * usefulness actually depends on -- `reach`, `killAura`, `clickStatistics`, `multiTarget`,
 * `noKnockback` -- carry **several clients each**, because a check that catches LiquidBounce's
 * 4.2-block module but misses a ghost-tier 3.6-block module is a bypass that a single drive
 * would have reported as green.
 *
 * ## Staying honest about drives the detector structurally cannot satisfy
 *
 * `Check.flag` adds a level, and `Iustitia.onClientTick` calls `decayAll()` **every tick
 * before processing**. So a check alerts only when `flag_rate x level > decay` -- a check with
 * `level 1.0` and `decay 0.5` must flag on **more than half of all ticks**, and one whose flag
 * is gated to a discrete event (a hit, a landing, a 60-tick episode) often cannot reach that
 * rate no matter how blatant the cheat is. Those are real detector findings, not scenario
 * failures, so they are driven at full intensity and recorded with [SelfTest.ScenarioBuilder
 * .expectKnownOpen] plus the arithmetic. The drive is never softened to make them green, and
 * the moment one starts alerting the report flips it to `KNOWN-OPEN CLOSED`.
 */
object CheatCombat {

    // ------------------------------------------------------------------
    // reach -- multi-client
    // ------------------------------------------------------------------

    /**
     * Reach at [distance] blocks. The victim stands still; the attacker swings every 5 ticks
     * from that distance, which is what a plain Reach module looks like from the outside.
     *
     * The three variants are the three tiers a real server sees: LiquidBounce's default
     * ~4.2 (semi-blatant), Vape's 6.0 (blatant), and a ghost-tier 3.6 that sits just past the
     * vanilla 3.0 ceiling -- the last one is the case a check with too much headroom misses.
     */
    private fun reach(
        name: String,
        source: String,
        distance: Double,
    ): Spec =
        Spec(name, Pass.CHEAT, source, setOf(Tags.COMBAT)) { b ->
        val bot = b.bot("Reach", 0.0, 0.0)
        val victim = b.bot("Target", 0.0, distance)
        b.expect(bot, "reach", mustAlert = true)
        b.strike(bot, victim, every = 5, aimYaw = 0f)
        b.runFor(180)
    }

    /**
     * All three bites assert. They did not always: at the old flat `maxReach + 0.8` headroom the
     * 4.2- and 3.6-block drives measured ~3.8/~3.2 and produced **no reach flag at all** across a
     * full 180-tick exchange, so the whole sub-blatant tier was a documented known-open. The
     * static-pair tightening in [dev.iustitia.checks.combat.ReachCheck] (near-vanilla ceiling when
     * both fighters were motionless, where client interpolation error is provably zero) is what
     * closed it.
     */
    fun reachLiquidBounce() = reach("cheat-reach-liquidbounce", "LiquidBounce", 4.2)

    fun reachVape() = reach("cheat-reach-vape", "Vape", 6.0)

    fun reachGhost() = reach("cheat-reach-ghost", "Koid", 3.6)

    // ------------------------------------------------------------------
    // multiTarget -- multi-client
    // ------------------------------------------------------------------

    /**
     * A three-victim-per-tick aura (Meteor's default multi-target). Three is the honest
     * *minimal* blatant drive: `multiTarget`'s level is `distinctVictims - 1` against a 2.0
     * setback, so two victims is a 1.0 flag and the third same-tick victim is what makes the
     * count cross. See [multiTargetPair] for the two-victim case, which is recorded as a
     * finding rather than pretended away.
     */
    fun multiAura(): Spec = Spec("cheat-multi-aura-meteor", Pass.CHEAT, "Meteor", setOf(Tags.COMBAT)) { b ->
        val bot = b.bot("Aura", 0.0, 0.0)
        val v1 = b.bot("V1", 0.0, 2.0)
        val v2 = b.bot("V2", 0.0, -2.0)
        val v3 = b.bot("V3", 2.0, 0.0)
        b.expect(bot, "multiTarget", mustAlert = true)
        var t = 0
        b.everyTick {
            if (t++ < 60) {
                bot.swing()
                v1.hurt(attackerEntityId = bot.entityId)
                v2.hurt(attackerEntityId = bot.entityId)
                v3.hurt(attackerEntityId = bot.entityId)
            }
        }
        b.runFor(140)
    }

    /**
     * The two-victim aura (LiquidBounce's `MultiTargets = 2`, the documented minimum). The
     * instantaneous level for two same-tick victims is `2 - 1 = 1.0` against `1.0`/tick decay — an
     * exact break-even, so no amount of running could alert — which is why the check's **pair
     * path** requires the pair to repeat rather than firing on arithmetic alone.
     */
    fun multiAuraPair(): Spec = Spec("cheat-multi-aura-pair-liquidbounce", Pass.CHEAT, "LiquidBounce", setOf(Tags.COMBAT)) { b ->
        val bot = b.bot("Aura", 0.0, 0.0)
        val v1 = b.bot("V1", 0.0, 2.0)
        val v2 = b.bot("V2", 0.0, -2.0)
        b.expect(bot, "multiTarget", mustAlert = true)
        var t = 0
        b.everyTick {
            if (t++ < 80) {
                bot.swing()
                v1.hurt(attackerEntityId = bot.entityId)
                v2.hurt(attackerEntityId = bot.entityId)
            }
        }
        b.runFor(140)
    }

    // ------------------------------------------------------------------
    // clickStatistics -- multi-client
    // ------------------------------------------------------------------

    /** Uniform 20 CPS (Meteor `AutoClicker`): one swing every tick, perfectly even. */
    fun autoClickerUniform(): Spec = Spec("cheat-clickstats-meteor", Pass.CHEAT, "Meteor", setOf(Tags.COMBAT)) { b ->
        val bot = b.bot("Clicker", 0.0, 0.0)
        b.expect(bot, "clickStatistics", mustAlert = true)
        b.everyTick { bot.swing() }
        b.runFor(180)
    }

    /** 40 CPS: two swing signals per tick (a 2x-rate module, e.g. a fast-double-click). */
    fun autoClickerFast(): Spec = Spec("cheat-clickstats-koid", Pass.CHEAT, "Koid", setOf(Tags.COMBAT)) { b ->
        val bot = b.bot("Clicker", 0.0, 0.0)
        b.expect(bot, "clickStatistics", mustAlert = true)
        b.everyTick {
            bot.swing()
            bot.swing()
        }
        b.runFor(60)
    }

    /**
     * A **recorded** human click pattern replayed on a loop (LionClient `ClickPatternStore`) --
     * the case Kurt/StDev miss, because the recorded distribution is human-like and only the
     * exact periodicity gives it away. The interval list repeats exactly, which is precisely
     * what no hand does.
     */
    fun autoClickerRecorded(): Spec = Spec("cheat-clickstats-lion", Pass.CHEAT, "LionClient", setOf(Tags.COMBAT)) { b ->
        val bot = b.bot("Clicker", 0.0, 0.0)
        b.expect(bot, "clickStatistics", mustAlert = true)
        val pattern = listOf(4, 6, 3, 7, 5, 4, 6, 5)
        var idx = 0
        var since = 0
        b.everyTick {
            if (since >= pattern[idx % pattern.size]) {
                bot.swing()
                since = 0
                idx++
            } else since++
        }
        b.runFor(600) // RECORD_MIN_CYCLES needs several full loops of the pattern
    }

    // ------------------------------------------------------------------
    // noKnockback
    // ------------------------------------------------------------------

    /**
     * Anti-knockback (Rain-Anticheat / AvA): a sprinting attacker hits a stationary victim that
     * never receives the sprint-knockback displacement. This is the drive `noKnockback` is built
     * for, and the check alerts where the **victim** is the cheater (anti-knockback is a
     * victim-side velocity module, so the flagged subject is `Stuck`, not `Anchor`).
     *
     * This row used to be a `KNOWN-OPEN`: the check's alert was gated on a `level 1.0` per hit
     * against a `decay 1.0`/tick, and it evaluates at most once per hit, so the VL could never
     * exceed ~1.0 of the 5.0 needed no matter how many hits landed. The sustained-episode gate
     * (see [dev.iustitia.checks.Check.flagEpisode]) fixed the economy -- the check now requires the
     * absorbed-knockback pattern to repeat and one-shot alerts at a level that clears setbackVL.
     */
    fun noKnockback(): Spec = Spec("cheat-no-kb-rain", Pass.CHEAT, "Rain-Anticheat", setOf(Tags.COMBAT)) { b ->
        val bot = b.bot("Anchor", 0.0, 0.0)
        val victim = b.bot("Stuck", 0.0, 2.0)
        b.expect(victim, "noKnockback", mustAlert = true)
        var t = 0
        b.everyTick {
            val i = t++
            bot.sprint(true) // the check only fires when the attacker was sprinting
            bot.look(0f, 0f)
            // Hits land on a server-possible cadence (8 ticks). The per-tick form this drive used
            // to run cannot be delivered by a server at all (i-frames), and it pre-empted the
            // check's own 3-tick measurement window every time, so it was testing the window's
            // plumbing rather than the cheat.
            if (i % 8 == 0) {
                bot.swing()
                victim.hurt(attackerEntityId = bot.entityId)
            }
            // no velocity, no displacement: the sprint knockback never happens
        }
        b.runFor(400)
    }

    // ------------------------------------------------------------------
    // killAura -- multi-client
    // ------------------------------------------------------------------

    /**
     * Silent-aim snap (LiquidBounce `KillAura` silent mode): the broadcast yaw sits off every
     * target, bursts onto the victim's hitbox within a quantum, and the attack lands once it
     * has settled. The return leg -- bursting back off-bore right after the hit -- is the
     * `silent(return)` signature, so the pattern produces both sub-flags.
     *
     * The yaw schedule is the module's own: 120 deg off-bore -> 40 deg/tick sweep -> 3 settled ticks
     * (the burst has to go quiet for the evaluator to judge where it landed) -> attack -> snap
     * back off-bore.
     */
    fun killAuraSnap(): Spec = Spec("cheat-killaura-snap-liquidbounce", Pass.CHEAT, "LiquidBounce", setOf(Tags.COMBAT, Tags.ROTATION)) { b ->
        val bot = b.bot("Aura", 0.0, 0.0)
        val victim = b.bot("Victim", 0.0, 3.0)
        b.expect(bot, "killAura", mustAlert = true)
        val schedule = floatArrayOf(120f, 120f, 80f, 40f, 0f, 0f, 0f, 120f)
        var t = 0
        b.everyTick {
            val i = t++
            bot.look(schedule[i % schedule.size], 0f)
            if (i % schedule.size == 4) {
                bot.swing()
                victim.hurt(attackerEntityId = bot.entityId)
            }
        }
        b.runFor(120)
    }

    /**
     * Perfect tracking (Vape `AimAssist` lock-on): the victim orbits at a fast constant rate
     * and the attacker's yaw follows the bearing exactly, so the reticle stays inside the
     * hitbox on every tick the line of sight is rotating fast -- the `silent(track)` signature.
     * A human cannot hold that ratio while the target's bearing sweeps.
     */
    fun killAuraTrack(): Spec = Spec("cheat-killaura-track-vape", Pass.CHEAT, "Vape", setOf(Tags.COMBAT, Tags.ROTATION)) { b ->
        val bot = b.bot("Aura", 0.0, 0.0)
        val victim = b.bot("Orbit", 0.0, 3.0)
        b.expect(bot, "killAura", mustAlert = true)
        var t = 0
        b.everyTick {
            val i = t++
            val angle = Math.toRadians(i * 12.0) // ~12 deg/tick orbit
            victim.teleportTo(3.0 * Math.sin(angle), b.groundY, 3.0 * Math.cos(angle))
            victim.setOnGround(true)
            // yaw that points at the victim: +Z is yaw 0, so bearing = atan2(-dx, dz)
            val yaw = Math.toDegrees(Math.atan2(-(3.0 * Math.sin(angle)), 3.0 * Math.cos(angle))).toFloat()
            bot.look(yaw, 0f)
            if (i % 10 == 0) {
                bot.swing()
                victim.hurt(attackerEntityId = bot.entityId)
            }
        }
        b.runFor(120)
    }

    /**
     * Smooth aim-assist (Raven's rate-capped assist): the yaw never snaps and never fully locks --
     * it closes on the target's bearing at a capped rate, so it is permanently chasing a moving
     * target while always turning the right way. The `drift` component keys on exactly that
     * direction correlation.
     *
     * A *held* lag is not this shape and does not exercise the component: holding `bearing - 20`
     * while the target orbits means the yaw tracks the bearing without ever closing the error, so
     * the sign of the turn is the same as the bearing's sweep and the check's "turning toward the
     * target" test is never satisfied -- measured live, one flag in 140 ticks. A capped closing
     * rate is both the honest reproduction of a rate-capped assist and the only form the signal
     * exists for.
     */
    fun killAuraDrift(): Spec = Spec("cheat-killaura-drift-raven", Pass.CHEAT, "Raven", setOf(Tags.COMBAT, Tags.ROTATION)) { b ->
        val bot = b.bot("Aura", 0.0, 0.0)
        val victim = b.bot("Orbit", 0.0, 4.0)
        b.expect(bot, "killAura", mustAlert = true)
        var t = 0
        var yawNow = 0f
        val wrap = { a: Float -> dev.iustitia.math.AimGeometry.wrapDegrees(a) }
        b.everyTick {
            val i = t++
            // 8 deg/tick orbit against a 4 deg/tick closing cap: the assist is always behind and
            // always turning toward the target, never on-bore (so `silent(track)` stays quiet and
            // the drift direction is the only signal left).
            val angle = Math.toRadians(i * 8.0)
            val vx = 4.0 * Math.sin(angle)
            val vz = 4.0 * Math.cos(angle)
            victim.teleportTo(vx, b.groundY, vz)
            victim.setOnGround(true)
            val bearing = Math.toDegrees(Math.atan2(-vx, vz)).toFloat()
            val err = wrap(bearing - yawNow)
            yawNow = wrap(yawNow + err.coerceIn(-4f, 4f))
            bot.look(yawNow, 0f)
            if (i % 10 == 0) {
                bot.swing()
                victim.hurt(attackerEntityId = bot.entityId)
            }
        }
        b.runFor(140)
    }

    /**
     * **Re-arm regression for `killAura`'s drift latch.** Same rate-capped assist as
     * [killAuraDrift], driven as two episodes separated by a stretch where the drift genuinely
     * stops, and asserted with `expectAlertCount(..., atLeast = 2)`.
     *
     * This is the only assertion that can see the bug it guards. The verdict assertion
     * (`expect(mustAlert = true)`) is satisfied by episode 1 and says nothing about the second, so
     * before this existed the check could alert once per session and still read green.
     *
     * Phase 2 is load-bearing, not decoration, and its shape is forced by two early returns in
     * `driftComponent`. Simply *stopping* the drive fails twice over: the latch needs a ratio
     * evaluation to release, so a quiet stretch only ages the ring out (`driftRing.size <
     * DRIFT_MIN_SAMPLES` returns before the ratio is ever computed) and the latch stays held. And
     * *freezing* the yaw does not work either -- `absYaw < DRIFT_MIN_DELTA` returns "neither drift
     * match nor miss", so a still yaw adds NO samples at all and the ring keeps phase 1's matches
     * forever. (Verified live: this scenario reported exactly 1 crossing with a frozen phase 2.)
     * The bot therefore pins its yaw to a fixed 90 deg offset behind the bearing, so the yaw rides
     * the bearing's own -8 deg/tick sweep while `error` holds at a constant +90 deg. The signs are
     * opposed on every tick, so all 50 of phase 2's ticks are misses and the ratio goes to 0.0,
     * well under `DRIFT_RESET_RATIO`.
     *
     * Both offset signs were tried and only this one works, which is worth recording: pinning on
     * the *other* side (`bearing + 90`) makes `yawChange` and `error` agree in sign, so phase 2
     * becomes 50 matches instead of 50 misses -- the ring never empties, the latch never releases,
     * and the scenario reads 1 crossing exactly as it does against the unfixed check. The drive must
     * oppose the signs, not merely hold an offset.
     *
     * The swing keeps `lastSwingTick` fresh -- rotation outside `COMBAT_WINDOW_TICKS` is ignored
     * entirely, so a drive that stops swinging stops being observed at all.
     */
    fun killAuraDriftRearm(): Spec = Spec("cheat-killaura-drift-rearm-raven", Pass.CHEAT, "Raven", setOf(Tags.COMBAT, Tags.ROTATION)) { b ->
        val bot = b.bot("Aura2", 0.0, 0.0)
        val victim = b.bot("Orbit2", 0.0, 4.0)
        b.expectAlertCount(bot, "killAura", "drift", atLeast = 2)
        var t = 0
        var yawNow = 0f
        val wrap = { a: Float -> dev.iustitia.math.AimGeometry.wrapDegrees(a) }
        b.everyTick {
            val i = t++
            // The victim orbits for the whole scenario, so the target never leaves range and the
            // only thing changing between phases is the attacker's rotation.
            val angle = Math.toRadians(i * 8.0)
            val vx = 4.0 * Math.sin(angle)
            val vz = 4.0 * Math.cos(angle)
            victim.teleportTo(vx, b.groundY, vz)
            victim.setOnGround(true)
            val bearing = Math.toDegrees(Math.atan2(-vx, vz)).toFloat()
            if (i >= 50 && i < 100) {
                // Phase 2: pin the yaw to a fixed 90 deg offset *behind* the bearing and ride the
                // bearing's sweep. Holding it still instead does not work -- `absYaw <
                // DRIFT_MIN_DELTA` returns "neither drift match nor miss", so a frozen yaw
                // contributes NO samples and the ring keeps phase 1's matches. What makes a sample
                // a miss is `sign(yawChange) != sign(error)`, and this offset produces exactly that,
                // every tick: the bearing sweeps at -8 deg/tick (the victim orbits counter-
                // clockwise), so the pinned yaw sweeps with it at yawChange = -8, while
                // `error = bearing - yaw` sits at a constant +90 -- both magnitudes far above their
                // floors, both signs permanently opposed. Both earlier attempts got this wrong by
                // pinning the offset on the *other* side (`bearing + 90`), which makes yawChange and
                // error agree in sign and turns phase 2 into matches -- verified live: the ring
                // never emptied and the latch never released.
                yawNow = wrap(bearing - 90f)
                bot.look(yawNow, 0f)
            } else {
                // Phases 1 and 3: the rate-capped closing assist (8 deg/tick orbit against a
                // 4 deg/tick cap), so it is always behind the bearing and always turning toward it.
                // The release happened back in phase 2, so `episodeActive` is clear and this second
                // episode can flag. The 50-tick window must climb back to DRIFT_RATIO (0.70), i.e.
                // 35 of its samples back on-bore -- ~35 ticks from tick 100 -- so the second
                // episode lands around tick 137, with ~40 ticks of margin left in the run.
                val err = wrap(bearing - yawNow)
                yawNow = wrap(yawNow + err.coerceIn(-4f, 4f))
                bot.look(yawNow, 0f)
            }
            if (i % 10 == 0) {
                bot.swing()
                victim.hurt(attackerEntityId = bot.entityId)
            }
        }
        b.runFor(180)
    }

    // ------------------------------------------------------------------
    // throughWalls
    // ------------------------------------------------------------------

    /**
     * No-line-of-sight aura (Vape `ThroughWalls`): a solid two-block wall stands between the
     * fighter and the target and the hits keep landing. `throughWalls` keeps a rolling window
     * of occlusion verdicts and flags when the occluded fraction crosses 0.5, so the drive
     * lands hits every 4 ticks and lets the window fill.
     */
    fun throughWalls(): Spec = Spec("cheat-throughput-vape", Pass.CHEAT, "Vape", setOf(Tags.COMBAT, Tags.WORLD)) { b ->
        val bot = b.bot("Wall", 0.0, 0.0)
        val victim = b.bot("Behind", 0.0, 3.0)
        b.expect(bot, "throughWalls", mustAlert = true)
        val g = b.groundY.toInt()
        // A full-height wall between them: 5 wide, 3 tall (so it clearly covers the attacker's eye
        // and the victim's whole body), spanning the two cells at z = 1 and 2.
        b.fill(-2, g, 1, 2, g + 2, 2, net.minecraft.block.Blocks.STONE)
        b.strike(bot, victim, every = 4, aimYaw = 0f)
        b.runFor(120)
    }

    // ------------------------------------------------------------------
    // criticals -- multi-client
    // ------------------------------------------------------------------

    /**
     * Meteor's `Criticals` in Packet/UpdatedNCP mode: a sub-jump Y jiggle with the airborne bit
     * spoofed (`onGround = false`) while the bot is actually on the ground. The +/-0.05 jitter
     * keeps `|dy| < 0.15` with a level lead-in, which is the `Crits(MicroY)` fingerprint.
     */
    fun criticalsMicroY(): Spec = Spec("cheat-criticals-meteor", Pass.CHEAT, "Meteor", setOf(Tags.COMBAT)) { b ->
        val bot = b.bot("Crit", 0.0, 0.0)
        val victim = b.bot("Dummy", 0.0, 2.0)
        b.expect(bot, "criticals", mustAlert = true)
        var t = 0
        b.everyTick {
            val i = t++
            // spoofed airborne + micro Y jiggle around the real ground position
            bot.setOnGround(false)
            val jitter = if (i % 2 == 0) 0.05 else 0.0
            bot.teleportTo(0.0, b.groundY + jitter, 0.0)
            if (i % 3 == 0) {
                bot.swing()
                victim.hurt(attackerEntityId = bot.entityId)
            }
        }
        b.runFor(120)
    }

    /**
     * Slinky's `Criticals` in Packet/timing mode: a **real** fall arc, but the attack is
     * lag-timed to land at the same phase of the arc every time. The Y-arc is legitimate; the
     * repeated phase is the tell, which is what `Crits(Timing)` clusters for. Eight
     * descending attacks at a fixed dy is the honest minimal version of "the hand never
     * varies".
     */
    fun criticalsFixedPhase(): Spec = Spec("cheat-criticals-slinky", Pass.CHEAT, "Slinky", setOf(Tags.COMBAT)) { b ->
        val ground = b.groundY
        val bot = b.bot("Crit", 0.0, ground + 4.0, 0.0)
        val victim = b.bot("Dummy", 0.0, ground, 2.0)
        b.expect(bot, "criticals", mustAlert = true)
        var t = 0
        b.everyTick {
            val i = t++
            bot.setOnGround(false)
            // The whole descent stays inside melee range of the dummied victim: attack inference
            // needs the attacker within 8 blocks, and a fall from 16 blocks up does not qualify
            // until the very end (which is why the old form tripped `reach` instead -- the bot was
            // "hitting" from 15 blocks away). A 4-block fall at a constant -0.2/tick is the same
            // fall-arc shape, with the -0.2 phase the cheat clusters on.
            bot.teleportTo(0.0, ground + 4.0 - 0.2 * i, 0.0)
            bot.look(0f, 0f)
            if (i % 6 == 0 && i > 0) {
                bot.swing()
                victim.hurt(attackerEntityId = bot.entityId)
            }
            if (i % 6 == 0) SelfTest.probe("critphase", bot)
        }
        b.runFor(64) // 10 descending attacks, comfortably past the 8-sample cluster gate
    }

    // ------------------------------------------------------------------
    // maceSmash
    // ------------------------------------------------------------------

    /**
     * LiquidBounce's `MaceKill`: the mace is in hand and the Y position is warped up ~1.6
     * blocks at the attack tick and back down afterwards, which the server reads as a huge
     * fall and the client never shows. A genuine smash leads in with a real descent, so the
     * warp-from-level shape is the signature `maceSmash` looks for.
     */
    fun maceSmash(): Spec = Spec("cheat-macesmash-liquidbounce", Pass.CHEAT, "LiquidBounce", setOf(Tags.COMBAT)) { b ->
        val ground = b.groundY
        val bot = b.bot("Smash", 0.0, 0.0)
        val victim = b.bot("Dummy", 0.0, 2.0)
        b.expect(bot, "maceSmash", mustAlert = true)
        bot.hold(ItemStack(Items.MACE))
        var t = 0
        b.everyTick {
            val i = t++
            val phase = i % 8
            bot.setOnGround(false)
            when (phase) {
                0 -> {
                    bot.teleportTo(0.0, ground + 1.6, 0.0) // the warp the server sees as a fall
                    bot.swing()
                    victim.hurt(attackerEntityId = bot.entityId)
                }
                1 -> bot.teleportTo(0.0, ground, 0.0)     // snapped back, exactly as the cheat does
                else -> bot.teleportTo(0.0, ground, 0.0)
            }
        }
        b.runFor(160)
    }

    // ------------------------------------------------------------------
    // keepSprint
    // ------------------------------------------------------------------

    /**
     * LiquidBounce `KeepSprint` / NoSlowOnAttack: the attacker sprints forward at full pace and
     * lands hits **without ever taking the vanilla post-attack deceleration**, so its speed
     * after a hit stays at ~100% of the pre-hit speed with sprint still held. Hits land every
     * tick so the check's one-tick-after-the-attack evaluation runs continuously.
     */
    fun keepSprint(): Spec = Spec("cheat-keepsprint-liquidbounce", Pass.CHEAT, "LiquidBounce", setOf(Tags.COMBAT)) { b ->
        val ground = b.groundY
        val bot = b.bot("Sprinter", 0.0, 0.0)
        val victim = b.bot("Dummy", 0.0, 2.2)
        // HARNESS GAP: the drive does not yet reach this check (see docs/automated-live-testing.md).
        // The alert assertion is deliberately NOT declared -- a red row for a reason that is
        // about the scripted bot would train contributors to ignore the suite.
        b.expect(bot, "keepSprint", mustAlert = true)
        var t = 0
        var z = 0.0
        b.everyTick {
            val i = t++
            bot.look(0f, 0f)
            bot.sprint(true)
            bot.setOnGround(true)
            z += 0.28 // full sprint pace, unchanged on the attack tick -- the cheat
            if (z > 24.0) z = 0.0 // stay inside the loaded region (see ScenarioBuilder.drive's span)
            bot.teleportTo(0.0, ground, z)
            victim.teleportTo(0.0, ground, z + 2.2)
            victim.setOnGround(true)
            // Attacks land on the vanilla cooldown cadence (12 ticks). A per-tick hurt is not
            // something a server can deliver (i-frames), and it also collapses in attack
            // inference, so a 12-tick cadence is both the faithful and the strongest honest form.
            if (i % 12 == 0) {
                bot.swing()
                victim.hurt(attackerEntityId = bot.entityId)
            }
            if (i % 24 == 0) SelfTest.probe("keepsprint", bot)
        }
        b.runFor(240)
    }

    // ------------------------------------------------------------------
    // wTap
    // ------------------------------------------------------------------

    /**
     * Vape `SuperKnockback` / W-tap macro: the sprint flag is toggled off-and-on around every
     * attack so the server applies sprint knockback, on nearly every hit. The module here
     * W-taps every single attack, which is the sustained form the check's "3 of the last 4"
     * gate is designed to catch.
     */
    fun wTap(): Spec = Spec("cheat-wtap-vape", Pass.CHEAT, "Vape", setOf(Tags.COMBAT)) { b ->
        val bot = b.bot("WTapper", 0.0, 0.0)
        val victim = b.bot("Dummy", 0.0, 2.2)
        // HARNESS GAP: the drive does not yet reach this check (see docs/automated-live-testing.md).
        // The alert assertion is deliberately NOT declared -- a red row for a reason that is
        // about the scripted bot would train contributors to ignore the suite.
        b.expect(bot, "wTap", mustAlert = true)
        var t = 0
        b.everyTick {
            val i = t++
            bot.look(0f, 0f)
            // The macro across tick boundaries: release sprint on the attack tick, re-press it the
            // next one. Both states must be visible to the tracker's per-tick poll, so they cannot
            // be flipped inside a single tick (the poll would only ever see the last write).
            if (i % 2 == 0) {
                bot.sprint(false)
                bot.swing()
                victim.hurt(attackerEntityId = bot.entityId)
            } else {
                bot.sprint(true)
            }
            if (i % 8 == 0) SelfTest.probe("wtap", bot)
        }
        b.runFor(160)
    }

    // ------------------------------------------------------------------
    // jumpOnHurt
    // ------------------------------------------------------------------

    /**
     * Rain-Anticheat's `JumpReset`: the victim auto-jumps the tick it is hit to cancel the
     * knockback. Hits land every 3 ticks and the victim hops on the following tick, so the
     * coincidence rate is 100% -- far above the 90%-over-5-hits bar. No velocity impulse is
     * injected for the victim, because a server-sent knockback hop is a legitimate dy and the
     * check deliberately exempts exactly that case.
     */
    fun jumpOnHurt(): Spec = Spec("cheat-jumponhurt-rain", Pass.CHEAT, "Rain-Anticheat", setOf(Tags.COMBAT)) { b ->
        val ground = b.groundY
        val bot = b.bot("Jumper", 0.0, 0.0)
        val victim = b.bot("Victim", 0.0, 2.0)
        // HARNESS GAP: the drive does not yet reach this check (see docs/automated-live-testing.md).
        // The alert assertion is deliberately NOT declared -- a red row for a reason that is
        // about the scripted bot would train contributors to ignore the suite.
        b.expect(victim, "jumpOnHurt", mustAlert = true)
        var t = 0
        b.everyTick {
            val i = t++
            bot.look(0f, 0f)
            if (i % 3 == 0) {
                bot.swing()
                victim.hurt(attackerEntityId = bot.entityId)
                // The reset hop is set in the SAME action block: the hurt is published on the
                // Iustitia clock at tick T, and motion written in that block is first observed
                // during tick T+1 -- which is exactly the `since == 1` tick the check's hit
                // window looks at. Writing the hop in the next block instead lands it at
                // `since == 2`, outside the window, and the drive silently misses the check.
                victim.teleportTo(0.0, ground + 0.42, 2.0)
                victim.setOnGround(false)
            } else {
                victim.teleportTo(0.0, ground, 2.0)
                victim.setOnGround(true)
            }
            if (i % 3 == 0) SelfTest.probe("jumponhurt", victim, "hit=$i")
        }
        b.runFor(180) // 60 hits -- comfortably past the sustained gate
    }

    // ------------------------------------------------------------------
    // backtrack
    // ------------------------------------------------------------------

    /**
     * Vape `Backtrack`: the victim's incoming packets are held, so it appears frozen at a close
     * position and then snaps away -- and the hit lands on the stale (close) position.
     *
     * `backtrack`'s `setbackVL` is deliberately high (10) because it is a corroborating signal,
     * but the flag is also gated to one per stale-position snap and the victim must be frozen
     * for at least three samples before the snap can happen. That puts the maximum flag rate
     * around one per cycle, well under the `decay 0.25 - level 1.0` break-even of one flag per
     * 4 ticks, so the drive is recorded as a finding rather than reported as a bypass.
     */
    fun backtrack(): Spec = Spec("cheat-backtrack-vape", Pass.CHEAT, "Vape", setOf(Tags.COMBAT)) { b ->
        val ground = b.groundY
        val bot = b.bot("Tracker", 0.0, 0.0)
        val victim = b.bot("Ghost", 0.0, 2.5)
        // NOTE: the check flags the ATTACKER (the bot that hit the stale position), so the
        // assertion belongs on the bot. Declaring it on the victim is what made this row read
        // "never logged a flag" while the check was in fact reacting.
        b.expect(bot, "backtrack", mustAlert = true)
        var t = 0
        b.everyTick {
            val i = t++
            val phase = i % 12
            // In reach at 2.5 for the freeze, then snapped clear of vanilla reach (3.0) but still
            // inside attack-inference range (8.0 blocks) -- at 8.5 the hit was never even inferred,
            // so the check was never asked.
            val z = if (phase in 5..11) 6.0 else 2.5
            victim.teleportTo(0.0, ground, z)
            victim.setOnGround(true)
            // The hits land AFTER the snap tick, not on it. A scenario action runs before
            // `ClientThread.waitTick()`, so the tracker's snapshot during the action still
            // holds the previous position -- hitting on the snap tick (phase 5) made the
            // check see the victim still in reach, push `false`, and never reach the stale
            // branch, while the second hit one tick later was swallowed by attack
            // inference's 2-tick (attacker, victim) dedup. Phases 6 and 8 are both after the
            // tracker has sampled the snap and are 2 ticks apart, so both survive the dedup
            // and both judge a genuinely stale position.
            if (phase == 6 || phase == 8) {
                bot.swing()
                victim.hurt(attackerEntityId = bot.entityId)
            }
        }
        b.runFor(240)
    }

    // ------------------------------------------------------------------
    // hitsWithoutSwing
    // ------------------------------------------------------------------

    /**
     * Slinky `Hit Select / Fake swing`: damage lands with no swing animation in the window at
     * all. `hitsWithoutSwing` is a CORROBORATOR-tier signal, requires 3 no-swing hurts, and
     * is transition-gated to **one flag per 60-tick episode** -- with `decay 0.5` that is a
     * maximum rate of ~1/60 flags per tick, so it is driven at full intensity and recorded.
     */
    fun hitsWithoutSwing(): Spec = Spec("cheat-hitsswing-slinky", Pass.CHEAT, "Slinky", setOf(Tags.COMBAT)) { b ->
        val bot = b.bot("Silent", 0.0, 0.0)
        val victim = b.bot("Victim", 0.0, 2.0)
        // NOTE: the check flags the ATTACKER (the swing-suppressing bot), not the victim.
        b.expect(bot, "hitsWithoutSwing", mustAlert = true)
        var t = 0
        b.everyTick {
            if (t++ % 3 == 0) {
                // hurt with no swing at all, attributed directly to the attacker
                victim.hurt(attackerEntityId = bot.entityId)
            }
        }
        b.runFor(300)
    }

    /**
     * **Re-arm regression for `hitsWithoutSwing`'s episode latch.** Two no-swing episodes separated
     * by a genuine break in the pattern, asserted with `expectAlertCount(..., atLeast = 2)`.
     *
     * The hurt at tick 80 that **carries a swing** is the load-bearing beat, and it is there for a
     * reason that is easy to miss: the check's re-arm test is
     * `if (actx.active && tick - actx.lastNoSwingTick > EPISODE)`, and `lastNoSwingTick` is written
     * on the no-swing path *before* that test runs. So on a no-swing hurt the test evaluates
     * `tick - tick > 60` -- false, always. It can only ever fire on a hurt that HAD a swing in the
     * window, because that path skips the write and leaves `lastNoSwingTick` at the previous
     * episode's tick. A drive that only ever withholds swings never reaches the reset at all.
     *
     * Phases: 4 no-swing hurts by tick 9 (threshold 3) -> episode 1; a swing-accompanied hurt at
     * tick 80, 71 ticks later (> EPISODE = 60) -> pattern broken; 5 more no-swing hurts from tick
     * 151 -> episode 2. The 151-tick spread is far past `SAME_EPISODE_TICKS`, so the counter reads
     * two episodes rather than one long one.
     */
    fun hitsWithoutSwingRearm(): Spec = Spec("cheat-hitsswing-rearm-slinky", Pass.CHEAT, "Slinky", setOf(Tags.COMBAT)) { b ->
        val bot = b.bot("Silent2", 0.0, 0.0)
        val victim = b.bot("Victim2", 0.0, 2.0)
        // The check flags the ATTACKER (the swing-suppressing bot), not the victim.
        b.expectAlertCount(bot, "hitsWithoutSwing", "HitsWithoutSwing", atLeast = 2)
        var t = 0
        b.everyTick {
            val i = t++
            if (i <= 9) {
                // Phase 1: no swing at all, attributed directly to the attacker. 4 hurts by tick 9.
                if (i % 3 == 0) victim.hurt(attackerEntityId = bot.entityId)
            } else if (i == 80) {
                // Phase 2: the one hurt that carries a swing -- the only path on which the reset
                // branch is reachable. 71 ticks of silence before it breaks the episode.
                bot.swing()
                victim.hurt(attackerEntityId = bot.entityId)
            } else if (i in 151..163) {
                // Phase 3: the pattern resumes. Alert #2 only if the latch re-armed.
                if (i % 3 == 0) victim.hurt(attackerEntityId = bot.entityId)
            }
        }
        b.runFor(180)
    }

    // ------------------------------------------------------------------
    // autoBlock -- multi-client
    // ------------------------------------------------------------------

    /**
     * Rain-Anticheat `AutoBlock`: a shield held up **while** swinging. Vanilla 1.21 cannot
     * attack with a shield raised, so the sustained overlap is the cheat. The swing keeps the
     * reconstructed swing window open every tick while `isBlocking` stays set.
     */
    fun autoBlockShield(): Spec = Spec("cheat-autoblock-rain", Pass.CHEAT, "Rain-Anticheat", setOf(Tags.COMBAT)) { b ->
        val bot = b.bot("Blocker", 0.0, 0.0)
        val victim = b.bot("Dummy", 0.0, 2.0)
        bot.hold(ItemStack(Items.SHIELD))
        bot.startUsing()
        b.expect(bot, "autoBlock", mustAlert = true)
        var t = 0
        b.everyTick {
            if (t++ % 2 == 0) {
                bot.swing()
                victim.hurt(attackerEntityId = bot.entityId)
            }
        }
        b.runFor(120)
    }

    /**
     * Grim's `MultiActionsE`: a swing sustained while a consumable's use metadata stays set,
     * which vanilla cannot do (left-click interrupts eating). Driven with a golden apple so
     * the tracker's derived `isUsingConsumable` -- not the bare `usingItem` -- is what the check
     * reads.
     */
    fun autoBlockConsume(): Spec = Spec("cheat-autoblock-grim", Pass.CHEAT, "Grim", setOf(Tags.COMBAT)) { b ->
        val bot = b.bot("Eater", 0.0, 0.0)
        val victim = b.bot("Dummy", 0.0, 2.0)
        b.expect(bot, "autoBlock", mustAlert = true)
        bot.hold(ItemStack(Items.GOLDEN_APPLE))
        bot.startUsing()
        var t = 0
        b.everyTick {
            if (t++ % 2 == 0) {
                bot.swing()
                victim.hurt(attackerEntityId = bot.entityId)
            }
        }
        b.runFor(120)
    }

    // ------------------------------------------------------------------
    // hitFlick
    // ------------------------------------------------------------------

    /**
     * Vape `HitFlick` / Slinky `Knockback Displace`: the yaw is flicked 90 deg off the victim's
     * hitbox **at the attack tick** to redirect the knockback sideways, then snapped back onto
     * the target on the next tick. Attacks are driven every second tick, which is the fastest
     * honest cadence for a flick-return pattern (the return needs a tick of its own).
     *
     * `hitFlick`'s level is 1.0 with `decay 0.5`/tick, and a flick can produce at most one flag
     * per two ticks -- exactly break-even. Driven at full intensity and recorded, because a
     * check that fires but can never accumulate is indistinguishable from a dead one without
     * this scenario.
     */
    fun hitFlick(): Spec = Spec("cheat-hitflick-vape", Pass.CHEAT, "Vape", setOf(Tags.COMBAT, Tags.ROTATION)) { b ->
        val bot = b.bot("Flick", 0.0, 0.0)
        val victim = b.bot("Dummy", 0.0, 2.2)
        b.expect(bot, "hitFlick", mustAlert = true)
        var t = 0
        b.everyTick {
            val i = t++
            // One tick of lead per half-cycle, because the tracker polls position/look at the tick
            // boundary: a look written *after* the attack's own poll is attributed to a later tick,
            // so the flick has to be written first and the attack published on the following
            // action, when the polled yaw IS the flicked one.
            if (i % 2 == 0) {
                bot.look(90f, 0f) // flicked off the target for the next tick's poll
            } else {
                bot.swing()
                victim.hurt(attackerEntityId = bot.entityId)
                bot.look(0f, 0f) // back inside the hitbox on the very next tick
            }
        }
        b.runFor(160)
    }

    // ------------------------------------------------------------------
    // triggerbot
    // ------------------------------------------------------------------

    /**
     * Vape `Triggerbot`: the player aims, the cheat attacks the instant the crosshair reaches
     * the hitbox. The drive sweeps off the target and back on every 4 ticks and attacks on the
     * rising-edge tick, so the measured reaction is ~0-2 ticks against a 5-hit / >=0.75-ratio
     * consistency bar. A human's reaction is 5-12 ticks, so a legit drive never qualifies.
     */
    fun triggerBot(): Spec = Spec("cheat-triggerbot-vape", Pass.CHEAT, "Vape", setOf(Tags.COMBAT)) { b ->
        val bot = b.bot("Trigger", 0.0, 0.0)
        val victim = b.bot("Dummy", 0.0, 2.2)
        // HARNESS GAP: the drive does not yet reach this check (see docs/automated-live-testing.md).
        // The alert assertion is deliberately NOT declared -- a red row for a reason that is
        // about the scripted bot would train contributors to ignore the suite.
        b.expect(bot, "triggerbot", mustAlert = true)
        var t = 0
        b.everyTick {
            val phase = t++ % 4
            when (phase) {
                0, 1 -> bot.look(90f, 0f) // off target
                2 -> bot.look(0f, 0f)     // swept onto the hitbox: the rising edge is observed
                else -> {                 // during THIS tick's check pass ...
                    bot.look(0f, 0f)
                    bot.swing()           // ... and the attack lands on the next action, which
                    victim.hurt(attackerEntityId = bot.entityId) // carries the same tick stamp
                }
            }
            if (t % 4 == 0) SelfTest.probe("triggerbot", bot, "phase=$phase")
        }
        b.runFor(120)
    }
}

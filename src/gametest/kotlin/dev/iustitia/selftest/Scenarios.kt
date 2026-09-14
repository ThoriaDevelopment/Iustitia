package dev.iustitia.selftest

import net.minecraft.item.ItemStack
import net.minecraft.item.Items

/**
 * The **legitimate pass**: bots that mirror vanilla play. Nothing here may alert -- every
 * assertion in this file is a false-positive gate.
 *
 * The pass is organised as a handful of *composite* scenarios rather than one scenario per
 * check, because world creation dominates runtime and a single vanilla walk exercises a dozen
 * checks at once. A scenario asserts quiet on every check whose precondition it plausibly
 * creates, which is the conservative form: a check that cannot fire without a precondition
 * (no elytra equipped, no item being used, no wall) is still asserted quiet here, so a future
 * edit that drops a precondition fails loudly instead of silently widening.
 *
 * Guards get their own benign twin: `legit-knockback` applies the knockback impulse
 * `cheat-no-kb-rain` withholds, `legit-shield-eat` holds the shield/eat state
 * `cheat-autoblock-*` abuses, and `legit-water`/`legit-ladder` present the climbable and
 * liquid contexts the wall-climb and Jesus drives must stay silent in. A guard with no legit
 * twin is an unverified guard.
 */
object Scenarios {

    /**
     * Sanity, run first: a spawned, idle bot must reach the tracker (otherwise every later
     * scenario's result is meaningless) and must stay quiet while standing still. The
     * expected-quiet set is the checks a stationary bot plausibly touches; asserting the
     * whole movement family here means a change that makes any of them fire on a motionless
     * player is caught before it can pollute a real scenario's verdict.
     */
    fun pipelineSmoke(): Spec = Spec("pipeline-smoke", Pass.LEGIT, "vanilla", setOf(Tags.GUARD)) { b ->
        val bot = b.bot("Smoke", 0.0, 0.0)
        Assertions("pipeline-smoke").expectTracked(bot)
        b.expectQuiet(bot, CheckIds.MOVEMENT)
        b.expectQuiet(bot, "reach", "killAura", "clickStatistics", "multiTarget", "throughWalls")
        b.runFor(60)
    }

    /**
     * Every vanilla ground/air locomotion a real player performs, at once:
     *
     * - a sprint->walk->idle pacing sweep (speedEnvelope's legal band, packetGap's steady
     *   per-tick deltas),
     * - discrete vanilla hops and a stretch of continuous **sprint-jumps** (the exact jump
     *   arc the fly/long-jump physics models are calibrated against -- a real jump is 0.42,
     *   so this is where a fly check that cannot tell a jump from a hover shows up),
     * - a slow yaw sweep that **crosses the +/-180 deg boundary** (aimWrap is specified to wrap
     *   rather than flag, so the boundary crossing is a required regression case, not an
     *   optional one).
     *
     * Everything on the ground, because a bot hovering in mid-air is legitimately flaggable
     * (verified live in the first run: a y=100 spawn tripped `flyEnvelope` at peakVL 15.5).
     */
    fun legitLocomotion(): Spec = Spec(
        "legit-locomotion", Pass.LEGIT, "vanilla", setOf(Tags.MOVEMENT, Tags.ROTATION),
    ) { b ->
        val bot = b.bot("Vanilla", 0.0, 0.0)
        b.expectQuiet(bot, CheckIds.MOVEMENT)
        b.expectQuiet(bot, "criticals", "reach", "killAura", "throughWalls", "multiTarget")

        val ground = b.groundY
        var t = 0
        var y = ground
        var v = 0.0
        var air = false
        var z = -20.0 // start offset so the whole lane stays inside the loaded region
        var nextJump = 150 // jumps live in the third phase, where each hop has a grounded tail
        var yaw = -172.0

        b.everyTick {
            val i = t++
            val pace = when {
                i < 90 -> 0.28   // sprint (~5.6 b/s), 25 blocks
                i < 150 -> 0.215  // walk (~4.3 b/s), 13 blocks
                i < 260 -> 0.20   // jump-run: ~11 airborne + ~3 grounded ticks per hop
                else -> 0.0       // idle + the yaw sweep
            }
            z -= pace

            if (air) {
                // A jumping player is NOT on-ground. The first version of this drive left the
                // flag true through the whole arc, which reads to the tracker as a bot standing
                // in mid-air and (correctly, for that input) trips the ground-spoof path.
                bot.setOnGround(false)
                v = (v - 0.08) * 0.98
                y += v
                if (y <= ground) {
                    y = ground; v = 0.0; air = false; bot.setOnGround(true)
                }
            } else {
                y = ground
                bot.setOnGround(true)
                if (i >= nextJump && i < 260) {
                    air = true
                    v = 0.42            // vanilla jump impulse
                    y += v
                    // The jump tick is airborne in the packet, as a real client sends it (the
                    // landing tick above already reported onGround = true).
                    bot.setOnGround(false)
                    // One hop per 14 ticks: ~11 airborne, then ~3 genuinely grounded ticks. The
                    // grounded tail is load-bearing, not decoration. `noFallDamage` accumulates
                    // fallAccum over airborne ticks and only clears it on a tick where
                    // groundedProxy holds -- and groundedProxy needs |dy| < 0.01, which a
                    // *continuous* hop chain never produces (verified live: the chain-hopping
                    // version of this drive accumulated 12.5 blocks across ~16 hops and then
                    // reported "landed a 12.5-block fall with no hurt signal" the moment the bot
                    // finally stood still. Ordinary locomotion must stay clean, which is what
                    // the grounded ticks buy; the dedicated legit-bunnyhop scenario documents the
                    // chain case as a detector false positive instead of hiding it here.
                    nextJump = i + 14
                }
            }

            // slow sweep across the +/-180 deg boundary (wrapped delta stays ~6 deg, so aimWrap quiet)
            yaw += 6.0
            if (yaw > 180.0) yaw -= 360.0
            bot.look(yaw.toFloat(), 10f)
            bot.teleportTo(0.0, y, z)
        }
        b.runFor(300)
    }

    /**
     * Continuous bunny-hopping (jump the tick you land), then standing still -- every tick legal,
     * and the regression guard for `noFallDamage`'s touchdown reset.
     *
     * This drive once recorded a **false positive** (peakVL 37): `groundedProxy` is
     * `|dy| < 0.01 && solidBelow`, a hop chain never produces such a tick, so fallAccum summed
     * ~1.1 per hop and ~8 hops crossed the 8.0 threshold. Two things were wrong, and both are
     * fixed: the detector keyed its reset on that strict proxy instead of on a real touchdown
     * (now: groundedProxy *or* onGroundPacket + a solid block within 0.05), and this drive sent the
     * **wrong onGround bit** -- it reported airborne on the very tick its feet reached the surface,
     * which is not a packet shape any vanilla client sends (Entity.move() sets onGround from the
     * collision, so the landing tick's packet says grounded). Each hop is a separate, damage-free
     * ~1-block fall, so the accumulator must reset every hop; the chain format is kept because it
     * is the input that proves it does.
     */
    fun legitBunnyHop(): Spec = Spec("legit-bunnyhop", Pass.LEGIT, "vanilla", setOf(Tags.MOVEMENT)) { b ->
        val bot = b.bot("Hopper", 0.0, 0.0)
        b.expectQuiet(bot, CheckIds.MOVEMENT)

        val ground = b.groundY
        var t = 0
        var y = ground
        var v = 0.0
        var air = false
        var z = -8.0
        b.everyTick {
            val i = t++
            if (air) {
                v = (v - 0.08) * 0.98
                y += v
                if (y <= ground) {
                    // The tick the feet reach the surface: the packet for this tick carries
                    // onGround = true, and it is the only support event a continuous chain
                    // produces (the hop re-launches next tick, so |dy| is never < 0.01).
                    y = ground; v = 0.0; air = false
                    bot.setOnGround(true)
                } else {
                    bot.setOnGround(false)
                }
            } else {
                y = ground
                // Launch the tick after touchdown -- the vanilla cadence. The jump tick is
                // airborne in the packet, so onGround is cleared rather than left true.
                if (i < 200) {
                    air = true
                    v = 0.42
                    y += v
                    bot.setOnGround(false)
                } else {
                    bot.setOnGround(true)
                }
            }
            if (i < 200) z -= 0.22
            bot.teleportTo(0.0, y, z)
        }
        b.runFor(260)
    }

    /**
     * Honest melee combat: the attacker sprints **forward** into a retreating victim at a real
     * 0.25 b/t, aims at the victim's torso continuously, and lands hits on a jittered 4-7 tick
     * cadence (~11 CPS with human variance). On the tick after each hit the attacker decelerates
     * to ~0.6x -- the vanilla post-attack slowdown, which is exactly what keepSprint is defined
     * to leave alone, so a keepSprint implementation that ignores the sprint flag or the
     * deceleration fails here.
     *
     * Aim is held on the target from the first tick and the first hit lands 12 ticks later,
     * which keeps the triggerbot drive honest: the crosshair's rising edge is long past, so the
     * only way this scenario trips triggerbot is if the check stops requiring a rising edge.
     *
     * The attacker moves **forward along its facing** because a sprinting player who strafes or
     * backpedals is (correctly) omnisprint's business -- a legit drive must not accidentally be a
     * cheat drive, so the pair chases along +Z with the attacker's yaw facing that way.
     */
    fun legitCombat(): Spec = Spec(
        "legit-combat", Pass.LEGIT, "vanilla", setOf(Tags.COMBAT, Tags.ROTATION),
    ) { b ->
        val attacker = b.bot("Fighter", 0.0, 0.0)
        val victim = b.bot("Dummy", 0.0, 2.2)
        b.expectQuiet(attacker, CheckIds.COMBAT)
        b.expectQuiet(attacker, "aimWrap", "rotationSnapBack", "rotationTracking")
        b.expectQuiet(attacker, CheckIds.MOVEMENT)
        b.expectQuiet(victim, "noKnockback", "jumpOnHurt", "backtrack")
        b.expectQuiet(victim, CheckIds.MOVEMENT)

        val ground = b.groundY
        val jitter = listOf(4, 5, 5, 6, 5, 4, 7, 5, 6, 5) // human-ish cadence, ~3-5 CPS
        val aim = Jitter()
        var idx = 0
        var since = -12 // first hit lands 12 ticks after the drive starts (no fast triggerbot hit)
        var slowTicks = 0
        var z = -8.0
        var victimZ = -5.8 // 2.2 blocks apart, whole encounter inside the loaded region
        // Vanilla sprint-knockback excursion, in addition to the shared retreat.
        // `noKnockback` compares the victim's post-hit displacement against the enemy's KB
        // impulse, so a "legitimate combat" drive whose victim only drifts with the shared
        // retreat is presenting a victim that took ~45% of its knockback -- which is the
        // anti-knockback signature itself, and (once the check could actually alert) it fired on
        // this scenario. A real victim gets shoved ~0.6 blocks over the 3-tick window; the
        // excursion is then unwound so the long-run geometry (and the 2.2-block gap the `reach`
        // guard depends on) is unchanged.
        var kbPush = 0.0
        var kbTicksLeft = 0

        b.everyTick {
            // Aim with a *hand's* variance: the pitch wanders around the torso instead of
            // holding a byte-identical value. A perfectly constant aim is an aimbot by
            // construction, and rotationTracking rightly flags it (verified live), so a legit
            // drive that holds the crosshair still is testing nothing.
            attacker.look(0f, 12f + aim.deg(12.0))
            attacker.sprint(true)
            attacker.setOnGround(true)
            val attacking = since >= jitter[idx % jitter.size]
            // Vanilla applies its ~0.6x post-attack deceleration ON the tick the attack lands,
            // so the strike tick itself is already slowed. Applying it from the *next* tick
            // instead (the original form) left the attack tick at full speed, which is exactly
            // keepSprint's signature -- the drive was modelling the cheat, not the legitimate
            // player, and keepSprint (rightly) fired on it. The deceleration is held for several
            // ticks because the inferred AttackEvent can lag the strike by the hurt look-back and
            // keepSprint evaluates exactly one tick after its attack tick.
            val step = if (attacking || slowTicks > 0) 0.15 else 0.25
            if (attacking) slowTicks = 3 else if (slowTicks > 0) slowTicks--
            z += step
            // The victim retreats by exactly the same step as the attacker (so the gap stays 2.2
            // for the whole encounter -- a constant *relative* speed is what keeps a chase honest;
            // if the victim outpaced the decelerating attacker the gap would creep past vanilla
            // reach and `reach` would correctly flag a scenario that is meant to be legitimate),
            // plus the knockback excursion.
            // The excursion is applied as its *change*, not its value, so the burst and the unwind
            // cancel exactly and the victim's long-run track stays on the attacker's. (Adding the
            // offset itself, as the first form of this did, gains the victim 0.22 blocks every
            // cycle and walks the gap from 2.2 out past 7 blocks within one encounter -- which is
            // exactly what `reach` then, correctly, flagged.)
            if (attacking) kbTicksLeft = 2
            val kbTarget = if (kbTicksLeft > 0) { kbTicksLeft--; 0.6 } else 0.0
            val kbPrev = kbPush
            kbPush += (kbTarget - kbPush).coerceIn(-0.3, 0.3)
            victimZ += step + (kbPush - kbPrev)
            attacker.teleportTo(0.0, ground, z)
            victim.teleportTo(0.0, ground, victimZ)
            victim.setOnGround(true)

            if (attacking) {
                attacker.strike(victim)
                since = 0
                idx++
            } else since++
        }
        b.runFor(170)
    }

    /**
     * The legit twin of the anti-knockback drive: a sprinting attacker hits a **stationary**
     * victim that then actually slides with the server's knockback (velocity impulse plus three
     * ticks of displacement). `noKnockback` must stay silent -- if it fires here it is reading
     * "the victim did not move" from a victim that plainly did.
     */
    fun legitKnockback(): Spec = Spec(
        "legit-knockback", Pass.LEGIT, "Rain-Anticheat", setOf(Tags.COMBAT, Tags.GUARD),
    ) { b ->
        val attacker = b.bot("Kb", 0.0, 0.0)
        val victim = b.bot("Slide", 0.0, 2.2)
        b.expectQuiet(victim, "noKnockback", "jumpOnHurt", "backtrack")
        b.expectQuiet(attacker, "noKnockback", "keepSprint", "hitFlick", "triggerbot")

        val ground = b.groundY
        var since = 8
        var slide = 0
        var slideZ = 0.0
        b.everyTick {
            attacker.look(0f, 0f)
            attacker.sprint(true)
            attacker.setOnGround(true)
            if (slide > 0) { // the KB slide the server broadcasts
                slideZ += 0.28
                slide--
            } else slideZ = 0.0
            victim.teleportTo(0.0, ground, 2.2 + slideZ)
            victim.setOnGround(true)
            if (since++ >= 10) {
                attacker.strike(victim) // swing + hurt + velocity impulse
                slide = 4
                since = 0
            }
        }
        b.runFor(200)
    }

    /**
     * A real gravity fall from 20 blocks with the **landing hurt** the server reports. The hurt
     * is the point: a long fall that lands with no hurt is the no-fall-damage cheat signature,
     * and the check correctly flagged the first version of this drive (peakVL 33) before the
     * hurt was injected. Descent uses vanilla gravity + drag so the arc is the one the flight
     * physics models expect.
     */
    fun legitFall(): Spec = Spec("legit-fall", Pass.LEGIT, "vanilla", setOf(Tags.MOVEMENT)) { b ->
        val ground = b.groundY
        val bot = b.bot("Faller", 0.0, ground + 20.0, 0.0)
        b.expectQuiet(
            bot, "noFallDamage", "flyEnvelope", "teleport", "packetGap", "criticals",
            "longJump", "speedEnvelope", "elytraSpeed",
        )
        var v = 0.0
        var cur = ground + 20.0
        var landed = false
        b.everyTick {
            if (!landed && cur > ground) {
                v = (v - 0.08) * 0.98
                cur = maxOf(ground, cur + v)
                bot.teleportTo(0.0, cur, 0.0)
                bot.setOnGround(false)
                if (cur <= ground) {
                    landed = true
                    bot.setOnGround(true)
                    bot.hurt() // the fall damage a real server broadcasts on landing
                }
            }
        }
        b.runFor(140)
    }

    /**
     * Legit Speed II sprint at ~7 b/s -- only legal *because* of the effect, so this is the
     * regression case for the SpeedEnvelope cap raise (and for longJump's speed-factor raise).
     */
    fun legitSpeedPotion(): Spec = Spec("legit-speed-potion", Pass.LEGIT, "vanilla", setOf(Tags.MOVEMENT)) { b ->
        val bot = b.bot("Potion", 0.0, 0.0)
        b.expectQuiet(bot, "speedEnvelope", "longJump", "flyEnvelope", "packetGap")
        bot.speedEffect(added = true, amplifier = 1) // Speed II
        b.drive(bot, 0.0, 0.0, -0.35, grounded = true) // ~7 b/s
        b.runFor(180)
    }

    /**
     * Swimming in real water: feet in the liquid, the swim pose set, real horizontal motion.
     * `waterWalk` must not read the swim as walking on the surface, `sprintHack`'s water case
     * must respect its `!swimming` gate, and the block-interaction checks (phaseClip, spider,
     * stepHeight) must not read a body inside water as inside a solid.
     */
    fun legitWater(): Spec = Spec(
        "legit-water", Pass.LEGIT, "vanilla", setOf(Tags.WORLD, Tags.MOVEMENT),
    ) { b ->
        val bot = b.bot("Swimmer", 20.0, 0.0)
        b.expectQuiet(
            bot, "sprintHack", "phaseClip", "noSlow", "spider", "stepHeight",
            "speedEnvelope", "flyEnvelope",
        )
        // `waterWalk` used to false-flag this drive: it fires on a player who is simply *in* the
        // water, because the bare signature (liquid non-solid block at the feet, moving, level Y)
        // describes a surface swimmer exactly and the `tp.swimming` gate never opens for a
        // non-sprint swimmer. The check now exempts a body whose feet sit inside the liquid rather
        // than on its top face; this drive is the regression guard for that fix.
        b.expectQuiet(bot, "waterWalk")
        // A real pool: the surface the bot stands on is replaced by water, so its feet are in
        // the liquid exactly as they are when swimming.
        b.fill(16, (b.groundY - 1).toInt(), -10, 24, (b.groundY - 1).toInt(), 10, net.minecraft.block.Blocks.WATER)
        var z = 6.0
        b.everyTick {
            // Re-assert the swim pose every tick: the client recomputes `isSwimming` from the
            // fluid state on its own schedule and will clear a one-shot set, which reads to the
            // checks as "in water, not swimming" -- a legit swimmer would then be flagged by
            // waterWalk's surface-walk test.
            bot.swimming(true)
            z -= 0.15
            bot.teleportTo(20.0, b.groundY - 0.4, z)
            bot.setOnGround(false)
        }
        b.runFor(120)
    }

    /**
     * Climbing a real ladder: the climbable block beside the bot, ascending at a constant
     * 0.2 b/t, off-ground. `spider` must respect its non-climbable gate (a ladder climb is the
     * legit case that gate exists for), and `flyEnvelope` must tell a ladder ascent from a
     * hover -- a constant vertical rise with no support is the one shape both checks see.
     */
    fun legitLadder(): Spec = Spec("legit-ladder", Pass.LEGIT, "vanilla", setOf(Tags.WORLD, Tags.MOVEMENT)) { b ->
        val bot = b.bot("Climber", 30.0, 0.0)
        b.expectQuiet(bot, "spider", "stepHeight", "speedEnvelope", "packetGap")
        // A real ladder climb rises ~0.2 b/t with `onGround = false`, and `flyEnvelope` had a
        // climbable exemption only on its ascend branch -- so the FlyB friction band matched a
        // ladder ascent and flagged it every tick to peakVL 24.5, until the Levitation guard took
        // over ~20 ticks later. The exemption now covers every vertical sub-flag; this drive is the
        // regression guard for that fix.
        b.expectQuiet(bot, "flyEnvelope")
        val base = 0.0
        val g = b.groundY
        // a ladder column beside the bot, spanning the climb
        b.fill(30, g.toInt(), 1, 30, (g + 14).toInt(), 1, net.minecraft.block.Blocks.LADDER)
        var t = 0
        b.everyTick {
            val i = t++
            bot.teleportTo(30.0, g + 0.2 * i, base)
            bot.setOnGround(false)
        }
        b.runFor(60)
    }

    /**
     * Honest elytra gliding: deployed, moving 1.4 b/t (28 bps, inside vanilla's ~30 cap) at a
     * realistic descent pitch. The elytra drives must not read a normal glide as ElytraFly, and
     * the generic flight checks must not read a deployed glide as hover/ascend.
     */
    fun legitElytra(): Spec = Spec("legit-elytra", Pass.LEGIT, "vanilla", setOf(Tags.MOVEMENT)) { b ->
        val bot = b.bot("Glider", 0.0, b.groundY + 40.0, 18.0)
        b.expectQuiet(bot, "elytraSpeed", "flyEnvelope", "speedEnvelope", "longJump", "packetGap")
        // A deployed elytra needs the chest slot to hold one: the client's own glide check
        // (`canGlide`) clears the flag without it, and the tracker would then read the bot as a
        // plain falling player rather than a glider.
        bot.equip(net.minecraft.entity.EquipmentSlot.CHEST, ItemStack(Items.ELYTRA))
        b.everyTick {
            bot.gliding(true) // re-assert: the flag is recomputed by the client each tick
            bot.look(0f, -25f)
            bot.setOnGround(false)
        }
        // 1.4 b/t (~28 bps) at a realistic descent pitch, held inside the loaded region
        b.drive(bot, 0.0, -0.05, -1.4, grounded = false, span = 40.0)
        b.runFor(120)
    }

    /**
     * The benign twins of the auto-use cheats: a shield raised with **no** attack, and eating
     * while walking slowly. Both are the exact metadata pair (`isBlocking` / `isUsingConsumable`
     * plus `usingItem`) the autoblock and NoSlow drives abuse, so if the overlap gates ever lose
     * their no-attack condition this scenario is where it shows up.
     */
    fun legitShieldEat(): Spec = Spec("legit-shield-eat", Pass.LEGIT, "vanilla", setOf(Tags.GUARD)) { b ->
        val bot = b.bot("Guard", 0.0, 0.0)
        b.expectQuiet(bot, "autoBlock", "noSlow", "killAura", "sprintHack", "waterWalk")
        bot.hold(ItemStack(Items.SHIELD))
        bot.startUsing()
        b.drive(bot, 0.0, 0.0, -0.05, grounded = true) // ~1 b/s -- below noSlow's 4 bps cap
        b.runFor(80)

        // swap to a consumable, walk at the vanilla eat-slow pace
        bot.stopUsing()
        bot.hold(ItemStack(Items.GOLDEN_APPLE))
        bot.startUsing()
        b.runFor(120)
    }

    /**
     * A **wind-charge jump**: the launch, the arc and the landing -- the 1.21+ movement every
     * KitPvP player does, and the one legit behavior whose *correct* vanilla outcome is
     * indistinguishable from a cheat signature by motion alone.
     *
     * Three mechanics this drive reproduces, each load-bearing:
     *
     * - **The launch is a no-damage burst.** A wind charge deals no entity damage, so the server
     *   broadcasts no hurt, and most servers do not rebroadcast another player's
     *   `EntityVelocityUpdate` either. Iustitia therefore detects the impulse from the motion
     *   itself (`burstTick`: Δy > 0.8 with prevΔy < 0.15, armed once at the onset) -- the
     *   exemption `flyEnvelope` and `noSlow` consume. This drive presents the real arc rather
     *   than a teleport so that detector path is exercised instead of bypassed.
     * - **The arc is vanilla**: ~1.5 b/t vertical at the impulse (a wind charge fired at the
     *   feet), decaying under the same gravity+drag model `legit-fall` uses, peaking ~10 blocks
     *   above the ground. That height is the point: the descent clears `noFallDamage`'s 8.0
     *   `fallAccum` threshold.
     * - **The landing carries no hurt signal, and that is correct vanilla.** A wind charge
     *   negates fall damage up to the launch height (MC-268383 / MC-272821), so launching and
     *   landing on the same surface is a legal flight the server never damages. `legit-fall`
     *   injects the landing hurt because a real dump onto the ground *is* damaging; injecting one
     *   here would fabricate the signal that decides the assertion and would hide the input this
     *   scenario exists to test.
     *
     * A plain vanilla jump (0.42 impulse, with a genuinely grounded tail) is in the timeline too,
     * so the "wind charge *and* a jump" case is covered, and the launch repeats because several
     * checks only evaluate after a second episode.
     *
     * This drive found a real false positive (peakVL 19 vs setbackVL 4) and is now the regression
     * test for its fix: `noFallDamage` re-bases its accumulator on the burst's own launch point,
     * so the 12.1-block descent above that point no longer counts. Its twin
     * `cheat-nofall-burst-spoof-meteor` drives the evasion attempt -- a cheat that fakes the same
     * impulse for the sole purpose of earning the exemption.
     */
    fun legitWindCharge(): Spec = Spec(
        "legit-windcharge", Pass.LEGIT, "vanilla", setOf(Tags.MOVEMENT),
    ) { b ->
        val bot = b.bot("Charged", 0.0, 0.0)
        // The whole movement family must stay silent -- including `noFallDamage`, which used to
        // false-flag here (peakVL 19) because it never consulted the tracker's `burstTick`.
        b.expectQuiet(bot, CheckIds.MOVEMENT)
        b.expectQuiet(bot, "criticals", "reach", "killAura", "multiTarget")

        val ground = b.groundY
        bot.hold(ItemStack(Items.WIND_CHARGE))
        var t = 0
        var y = ground
        var v = 0.0            // vertical velocity, blocks/tick
        var z = -6.0           // offset so both arcs stay inside the loaded region
        var air = false
        var groundedTicks = 0
        var launches = 0
        var jumps = 0
        b.everyTick {
            val i = t++
            if (air) {
                v = (v - 0.08) * 0.98
                y += v
                z -= 0.15      // a slightly off-vertical aim; a perfectly vertical one is 0 horizontal
                if (y <= ground) {
                    y = ground; v = 0.0; air = false; groundedTicks = 0
                    bot.setOnGround(true)
                    // Deliberately no hurt() here -- see the KDoc above. Nothing was damaged.
                } else {
                    bot.setOnGround(false)
                }
            } else {
                bot.setOnGround(true)
                groundedTicks++
                // Timeline: settle -> launch -> land -> settle -> vanilla jump -> land -> settle
                // -> launch again -> land -> settle. The grounded gaps are load-bearing:
                // groundedProxy needs |dy| < 0.01, which a chain of arcs never provides.
                when {
                    launches == 0 && i >= 40 && groundedTicks >= 8 -> {
                        air = true; launches++; v = 1.5; y += v; bot.setOnGround(false)
                    }
                    launches == 1 && jumps == 0 && i >= 90 && groundedTicks >= 25 -> {
                        air = true; jumps++; v = 0.42; y += v; bot.setOnGround(false)
                    }
                    launches == 1 && jumps == 1 && i >= 140 && groundedTicks >= 25 -> {
                        air = true; launches++; v = 1.5; y += v; bot.setOnGround(false)
                    }
                    else -> {}
                }
            }
            bot.teleportTo(0.0, y, z)
        }
        b.runFor(230)
    }

    /**
     * Legit aerial mace smash (1.21, the Spear-Mace FFA staple): a player falls from a great
     * height and lands a smash attack on the way down. Vanilla **negates all fall damage
     * accumulated prior to the smash** ("resets the player's fall height"), so the smash
     * landing carries NO hurt signal -- which, unguarded, is byte-for-byte the
     * `noFallDamage` `landed-no-hurt` signature. This drive found a real false positive class
     * in the live-log audit (NoFall was 34% of all events on 43 players) and is the regression
     * test for its fix: a confirmed attack correlation (swing + victim hurt, via
     * `AttackInference`) while descending with a mace in hand re-bases the accumulator, so the
     * landing is judged on the ~2-block post-smash descent only. The smash repeats so the
     * re-arm is exercised too (the accumulator must refill on the second fall).
     *
     * Honesty notes carried from `legit-windcharge`:
     * - the landing deliberately carries NO hurt -- nothing was damaged: the ~2-block descent
     *   after the smash is under vanilla's 3-block damage threshold, so a real server never
     *   damages it, and injecting a hurt would fabricate the input this scenario tests;
     * - the attack is real combat (swing + victim hurt at ~3.3 blocks), so the combat family
     *   is asserted quiet on the attacker -- including `maceSmash`, whose warp-from-level
     *   signature must not read a genuine descent as a warp;
     * - the victim takes one hurt from a NON-sprinting attacker, so `noKnockback` (gated on
     *   sprint-KB) stays quiet without a fabricated excursion.
     */
    fun legitMaceSmash(): Spec = Spec(
        "legit-mace-smash", Pass.LEGIT, "vanilla", setOf(Tags.MOVEMENT, Tags.COMBAT),
    ) { b ->
        // The faller spawns GROUNDED and each fall begins with a server teleport up to the drop
        // height (a real FFA faller: walked up a tower / was pearled up, then fell). A first
        // version of this drive spawned the bot 24 blocks up and "settled" there for the
        // pre-launch ticks -- a stationary mid-air player with dy=0 for 40 ticks is byte-for-byte
        // the Fly(FlyB) sustained-hover signature, and the scenario false-flagged `flyEnvelope`
        // (peakVL 5.5, live-verified). The teleport launch is also what a real fall looks like,
        // and the teleport exemption windows (flyEnvelope 10t, noFallDamage 5t) expire long before
        // the ~45-tick descent is judged. The victim is at 1.0 block horizontal so the smash reads
        // as a legitimate in-range hit (a 2.0-block offset put the face distance past reach's 3.8
        // tolerance and sub-flagged `reach` at vl 3.0).
        val ground = b.groundY
        val faller = b.bot("Smasher", 0.0, ground, 0.0)
        val victim = b.bot("Dummy", 1.0, ground, 0.0)
        b.expectQuiet(faller, CheckIds.MOVEMENT)
        b.expectQuiet(faller, CheckIds.COMBAT)
        b.expectQuiet(victim, CheckIds.MOVEMENT)
        b.expectQuiet(victim, "noKnockback", "jumpOnHurt", "backtrack")

        faller.hold(ItemStack(Items.MACE))
        var t = 0
        var y = ground
        var v = 0.0
        var air = false
        var groundedTicks = 0
        var smashes = 0
        var launched = false
        var pendingHurt = false
        b.everyTick {
            val i = t++
            // The victim's hurt lands the tick AFTER the swing (a server round-trip), so the
            // attack correlation reads the attacker's settled snapshot. Publishing the hurt on
            // the swing tick itself made the correlation read the *previous* snapshot -- one
            // tick higher on a ~2 b/t descent -- inflating the measured hit distance past
            // reach's 3.8 tolerance (sub-threshold vl 5.0, live-verified).
            if (pendingHurt) {
                pendingHurt = false
                victim.hurt(attackerEntityId = faller.entityId)
            }
            if (air) {
                v = (v - 0.08) * 0.98
                y += v
                if (y <= ground) {
                    // Landed. Deliberately NO hurt -- the smash negated the fall damage, and
                    // the ~2-block post-smash descent is under vanilla's damage threshold.
                    y = ground; v = 0.0; air = false; groundedTicks = 0
                    faller.setOnGround(true)
                } else {
                    faller.setOnGround(false)
                    // Smash on the way down, while ~2.2 blocks above the surface: a real
                    // swing + the victim's hurt, exactly what a server broadcasts.
                    if (!launched && y <= ground + 2.2) {
                        launched = true
                        faller.swing()
                        pendingHurt = true
                    }
                }
            } else {
                faller.setOnGround(true)
                groundedTicks++
                // settle -> teleport up -> fall+smash -> land -> settle -> again (re-arm proof)
                if (smashes == 0 && i >= 40 && groundedTicks >= 8) {
                    air = true; launched = false; smashes++
                    y = ground + 24.0; v = 0.0
                    faller.setOnGround(false)
                } else if (smashes == 1 && i >= 120 && groundedTicks >= 25) {
                    air = true; launched = false; smashes++
                    y = ground + 24.0; v = 0.0
                    faller.setOnGround(false)
                }
            }
            faller.teleportTo(0.0, y, 0.0)
        }
        b.runFor(230)
    }
}

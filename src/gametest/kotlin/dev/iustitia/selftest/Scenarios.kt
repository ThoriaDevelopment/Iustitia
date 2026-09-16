package dev.iustitia.selftest

import dev.iustitia.event.HurtSource
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

    /**
     * Walking up a **slab/full-block staircase**: the legit terrain-following shape `flyEnvelope`
     * used to read as flight.
     *
     * The staircase alternates a bottom slab (top at +0.5) and a full block (top at +1.0) so every
     * column is exactly **0.5 higher** than the one before it -- a real ramp, built at the vanilla
     * step height (vanilla steps 0.6; a slab is 0.5), so nothing about this drive exceeds what a
     * player is allowed to walk up. The cadence is 4 ticks per column: one tick that steps up
     * (+0.5, the tick the client resolves the collision) and three genuinely grounded ticks
     * standing on the new column (Δy = 0). That is what a tracked player's step-up looks like to
     * `EntityTrackerManager`, whose Δy comes from the **raw** entity position fields
     * (`updateSnapshot`: `Vec3d(e.getX(), e.getY(), e.getZ())`), not from render interpolation --
     * one 0.5 spike, then level ground. Horizontal motion is a constant 0.25/tick -- deliberately
     * *below* `legitLocomotion`'s proven-clean 0.28/tick with no `sprint()`, so `speedEnvelope` and
     * `stepHeight` (which needs a Δy above 0.6) are never the thing under test here. Measured: the
     * step ticks do register a single sub-threshold `speedEnvelope` flag (peak VL 1.0 against
     * setbackVL 5.0, from the 0.5 vertical rise joining the 0.25 horizontal in the envelope), so
     * this drive is not a *silent* one for that check -- it is only asserted quiet in the sense
     * every other legit scenario uses, i.e. no alert.
     *
     * **What this drive proves.** Two sustained counters span the grounded gaps unless a grounded
     * tick clears them:
     *
     * - `breachTicks` — the physics-breach gate needs 2 *consecutive* breaches, and a step-up spike
     *   breaches easily (its `prevDeltaY ≈ 0`, so `expectedY ≈ −0.078` and the 0.5 rise clears
     *   `expectedY + threshold` by a wide margin). With the counter standing across the flat ticks,
     *   step 2 is the second "consecutive" breach and `Fly` flags on **every step from the second**.
     *   This is the assertion that discriminates: pre-fix **17** flags (measured), post-fix 0.
     * - `ascendTicks` — asserted quiet here as a **forward guard, not a discriminator**. It reads 0
     *   in both states, and why is worth recording, because the audit pointed at the ascend block
     *   rather than the breach: on a fixed step cadence the counter can never exceed **1**, from two
     *   independent directions. (i) The jump recognizer (`prevDeltaY < 0.15 && 0.3 < dy < 1.0`)
     *   fires on a spike and re-arms only after 6 quiet ticks, so at a 4-tick cadence it re-arms on
     *   every *other* step -- and on a re-arm tick `lastJumpTick` is set to that very tick, so the
     *   ascend gate's own `tick - lastJumpTick > 2` fails (`0 > 2`) and zeroes the counter. (ii) On
     *   the steps where it does increment, the three intervening level ticks zero it again (their
     *   `dy` is 0, not `> 0.2`). Six *consecutive* ascending ticks are therefore unreachable. The
     *   count is asserted anyway, so a future loosening of either gate is caught rather than
     *   silently re-introducing the false positive.
     *
     * Every other fly sub-signal is inert by construction: `Fly(FlyB)`'s band tops out at
     * `expectedY + 0.1 ≈ 0.02` so the 0.5 spike is far above it, `Fly(Hover)`/`Fly(Blink)`/
     * `Fly(AntiKick)` all sit *after* the grounded branch (a grounded tick returns before them),
     * the climbable branch never opens (stone/slab, not a ladder), and `hasBlockAbove` samples the
     * bot's own column, which never has anything above the surface index.
     */
    fun legitFlyRamp(): Spec = Spec(
        "legit-fly-ramp", Pass.LEGIT, "vanilla", setOf(Tags.MOVEMENT, Tags.WORLD),
    ) { b ->
        val bot = b.bot("Ramp", 0.0, 0.0)
        b.expectQuiet(bot, CheckIds.MOVEMENT)
        b.expectQuiet(bot, "criticals", "reach", "killAura", "multiTarget")
        // The discriminating assertion: this check's flags sit below setbackVL (1.0 per flag against
        // a 0.5/tick decay, at most one flag per 4-tick step), so `expectQuiet` alone cannot tell
        // "the false positive was removed" from "the check was never driven". The flag count can.
        b.expectFlagCount(bot, "flyEnvelope", "Fly", atMost = 0)
        b.expectFlagCount(bot, "flyEnvelope", "Fly(Ascend)", atMost = 0)

        val g = b.groundY
        val gi = g.toInt()
        val steps = 20
        // surface(column s) = g + 0.5*s. Odd columns carry a bottom slab (collision top at
        // top+0.5), even columns a full block (top at top+1); the stack below each is filled so the
        // column is solid all the way down to the world's own surface block at gi - 1. Verified
        // column by column: the slab's `top` is gi + (s-1)/2 and the full block's is gi + s/2 - 1,
        // so floor(y - 0.05) lands on the surface block at every level tick (see the KDoc).
        for (s in 1..steps) {
            val slab = s % 2 == 1
            val top = if (slab) gi + (s - 1) / 2 else gi + s / 2 - 1
            b.place(
                0, top, s,
                if (slab) net.minecraft.block.Blocks.STONE_SLAB else net.minecraft.block.Blocks.STONE,
            )
            b.fill(0, gi - 1, s, 0, top - 1, s, net.minecraft.block.Blocks.STONE)
        }
        var t = 0
        b.everyTick {
            val i = t++
            // One column per 4 ticks, entered as a **single-tick** +0.5 spike on the step tick and
            // held flat for the other three -- the shape a remote player's raw position delta
            // actually produces for a step-up (see the KDoc). Horizontal motion is a constant
            // 0.25/tick, deliberately *below* `legitLocomotion`'s proven-clean 0.28/tick, so
            // `speedEnvelope` is never the thing under test here.
            val col = minOf(i / 4, steps)
            bot.teleportTo(0.0, g + 0.5 * col, 0.25 * i)
            bot.setOnGround(true)
        }
        // i runs 0..4*steps, so z ends at 20.0 -- exactly the last placed column. Running longer
        // would walk the bot off the terrain into unloaded air at the same y.
        b.runFor(4 * steps + 1)
    }

    /**
     * Holding the crosshair still while the target **strafes across it** -- the legitimate timing
     * shape `triggerbot` used to read as a sub-reaction auto-attacker.
     *
     * The attacker is stationary, does not sprint, and never moves its aim at all: the crosshair
     * sits on the +Z lane at yaw 0 / pitch 0. The victim strafes sinusoidally through that lane
     * (`|x| <= 0.3` is the hitbox half-width plus the check's own `RayAABB` margin), so the
     * crosshair-to-hitbox **rising edge is created by the victim**, twice per cycle. The strike
     * lands on the tick *after* the edge -- clicking as a target crosses your crosshair is how a
     * human hits a strafer, and it is also the input the check measures, since `process` runs a
     * tick behind the attack.
     *
     * **What this drive proves.** Pre-fix the edge unconditionally starts an engagement clock, so
     * every hit reads as a reaction of 1 tick: ~15 hits, all "fast", ratio 1.0 -- past `MIN_SAMPLES`
     * (5), past the 4-fast-hit threshold and past `RATIO` (0.75), which `flagEpisode`s at
     * `setbackVL + 1` and **alerts**. Post-fix the held-aim discriminator requires the attacker's own
     * aim to have moved within `AIM_TURN_WINDOW` (3) ticks, and it never does
     * (`lastAimMoveTick` stays at its −10000 sentinel), so no clock is started, `fast` is false on
     * every hit, and the check is silent. Both the alert and the flag count are asserted, because
     * the flag count is what distinguishes a real fix from a scenario that stopped driving the edge.
     *
     * The strafe period is **varied** (26 / 30 / 34 ticks, switching every 8 ticks) on purpose: a
     * metronomic hit cadence is `clickStatistics`' own uniform-auto-clicker signature, and a fixed
     * period would make this legit drive trip a different check. The victim holds its yaw rather
     * than sweeping it, matching `legitCombat`'s victims: a player strafing laterally at a fixed
     * facing is ordinary play, and the victim is not the subject of any rotation assertion here.
     *
     * Assertions on the attacker are deliberately not `CheckIds.COMBAT` wholesale: the attacker's
     * aim is byte-identical every tick, which is a constant-aim signature by construction, so the
     * rotation family is left out of the quiet set exactly as `legit-knockback` leaves it out. What
     * is asserted is the set this drive's preconditions actually create (a held crosshair on a
     * moving target, a non-sprinting attacker, a metronomic-ish click rate).
     */
    fun legitTriggerbotStrafe(): Spec = Spec(
        "legit-triggerbot-strafe", Pass.LEGIT, "vanilla", setOf(Tags.COMBAT),
    ) { b ->
        val attacker = b.bot("Camper", 0.0, 0.0)
        val victim = b.bot("Strafer", 0.0, 2.2)
        b.expectQuiet(attacker, "triggerbot", "reach", "throughWalls", "criticals", "keepSprint")
        b.expectQuiet(attacker, "wTap", "hitFlick", "multiTarget", "hitsWithoutSwing")
        b.expectFlagCount(attacker, "triggerbot", "Triggerbot", atMost = 0)
        b.expectQuiet(victim, "noKnockback", "jumpOnHurt", "backtrack")
        b.expectQuiet(victim, CheckIds.MOVEMENT)

        val ground = b.groundY
        var t = 0
        var phase = 0.0
        var x = 0.0
        var pendingStrike = false
        b.everyTick {
            val i = t++
            // held crosshair: a real player's aim is *here*, never on the target -- that is the
            // whole point of the drive
            attacker.look(0f, 0f)
            attacker.setOnGround(true)

            // Strafing through the lane. The period steps 26 -> 30 -> 34 so the hit cadence is not
            // a metronome (see the KDoc).
            phase += 2.0 * Math.PI / (26.0 + 4.0 * ((i / 8) % 3))
            val xPrev = x
            x = 0.9 * kotlin.math.sin(phase)
            val inside = kotlin.math.abs(x) <= 0.3005
            val wasIn = kotlin.math.abs(xPrev) <= 0.3005
            // The victim's yaw is held, not swept: a player strafing laterally at a fixed facing is
            // ordinary play, and `legitCombat`'s victims hold theirs too.
            victim.teleportTo(x, ground, 2.2)
            victim.setOnGround(true)

            // The stale edge resolves FIRST, so the strike lands one tick *after* the crossing --
            // that one-tick delay is exactly the "reaction" the check measures. It goes out only
            // while the target is still on the crosshair: `process`'s disengage branch clears the
            // engagement, which would leave no edge to measure.
            if (pendingStrike) {
                pendingStrike = false
                if (inside) attacker.strike(victim)
            }
            // ... and this tick's rising edge is armed for the next one.
            if (inside && !wasIn) pendingStrike = true
        }
        b.runFor(200)
    }

    /**
     * A **sprinting attacker hitting an airborne victim** -- the legit shape `noKnockback`'s
     * VelocityB sub-signal used to read as a vertical-KB cancel.
     *
     * VelocityB's premise is "the upward knockback launched them *off the ground*": on a
     * broadcast-velocity server the victim's first-airborne Δy should be ≈ the KB's own `vy`. That
     * premise is only defined for a victim who **was grounded** when the hit landed. This drive
     * presents the case the code used to judge anyway: the victim is bunny-hopping in place, and
     * the strike goes out **three ticks into the arc**, so at the hit the victim is already
     * airborne and rising on its own 0.42 jump arc.
     *
     * **What this drive proves.** Pre-fix the capture was ungated, so the first in-window tick that
     * found the victim airborne recorded the *jump's* Δy (0.083 by then, below `JUMP_LO` = 0.38, so
     * it is not even spared by the vanilla-jump exemption) as the victim's vertical-KB response:
     * ratio ≈ 0.23 against `kbVy` 0.36, well under `VELOCITYB_RATIO` (0.995), and `NoKB(VelocityB)`
     * flags on every hit — 18 times over 220 ticks. Post-fix `kbVictimGrounded` is false at the hit,
     * no capture happens, and the sub-signal is silent.
     *
     * The horizontal KB is **real**, not withheld: the victim takes the full server impulse and
     * slides ~0.84 blocks across the window (the `legit-knockback` slide idiom, unwound afterwards
     * so the 2.2-block geometry is unchanged), which is well over the `0.61 x impulse x friction`
     * bar -- so the VelocityC magnitude test genuinely *passes* and the only thing this scenario
     * isolates is the vertical sub-signal. Because the magnitude test passes, `NoKB`'s VL never
     * accumulates and `expectQuiet(victim, "noKnockback")` holds in **both** states; the flag count
     * on the distinct `NoKB(VelocityB)` label is the discriminating assertion.
     *
     * The three-tick offset is **not** cosmetic, and the RED run is what proved it: a strike on the
     * first tick after a launch puts a Δy of 0.333 on the very tick of the hit, so *every* hit is a
     * jump-on-hit — the `jumpOnHurt` (JumpReset) signature, which that check flagged, correctly, on
     * this scenario's first run. A continuously hopping victim hit on an arbitrary arc tick is the
     * legitimate case; a victim whose every hit coincides with a hop is the cheat. This scenario has
     * to present the former to be about `noKnockback` at all.
     *
     * The victim's movement family is deliberately **not** asserted: the hop chain is a proven
     * clean input (`legit-bunnyhop`) but the slide's snap-back is a 1.12-block single-tick step
     * that belongs to a different check's business, and asserting it here would make this scenario
     * a movement test wearing a combat label. Note also that the harness's `velocity()` publishes
     * only the `VelocitySignal` half of what a real EntityVelocityUpdate does — the
     * `EntityTrackerManager.markVelocity` half has no harness counterpart, so `tp.velocityTick` is
     * never set for a bot and the `kbHop` exemptions in `jumpOnHurt`/`FlyEnvelope`/`LongJump`/
     * `SpeedEnvelope`/`Teleport` are unreachable here. That is why the offset above has to be
     * correct on its own rather than leaning on the KB-hop exemption.
     */
    fun legitNoKbAirborne(): Spec = Spec(
        "legit-nokb-airborne", Pass.LEGIT, "Rain-Anticheat", setOf(Tags.COMBAT, Tags.GUARD),
    ) { b ->
        val attacker = b.bot("KbAir", 0.0, 0.0)
        val victim = b.bot("Hopper", 0.0, 2.2)
        b.expectQuiet(attacker, "noKnockback", "keepSprint", "hitFlick", "triggerbot")
        b.expectQuiet(victim, "noKnockback", "jumpOnHurt", "backtrack")
        b.expectFlagCount(victim, "noKnockback", "NoKB(VelocityB)", atMost = 0)

        val ground = b.groundY
        var t = 0
        var y = ground
        var v = 0.0
        var air = false
        var launchTick = -1000
        var slide = 0
        var slideZ = 0.0
        b.everyTick {
            val i = t++
            attacker.look(0f, 0f)
            attacker.sprint(true)
            attacker.setOnGround(true)

            // the victim's bunny hop: launch the tick after touchdown, vanilla jump impulse and
            // the shared gravity+drag model (the arc `legit-bunnyhop` proves is clean)
            if (air) {
                v = (v - 0.08) * 0.98
                y += v
                if (y <= ground) { y = ground; v = 0.0; air = false }
            } else {
                air = true; v = 0.42; y += v; launchTick = i
            }
            // Strike THREE ticks into the arc: at the hit the victim is airborne on its own arc
            // (which is the input this scenario exists to present) but nowhere near its launch
            // impulse. `kbVictimGrounded` is sampled from the tracker snapshot at the hit, so the
            // launch has to be earlier than the hit. Three ticks is the earliest offset that keeps
            // the *whole* 3-tick check window airborne while keeping the hit off the hop: the arc's
            // Δy is 0.42 on the launch tick and 0.333 on the next, then 0.248 / 0.165 / 0.083, and
            // `jumpOnHurt` reads `Δy > 0.3` on the hit tick and the one after — so a strike at
            // launch+1 presents a Δy of 0.333 on the very tick of the hit and is, by construction,
            // a 100%-coincident jump-on-hit: the JumpReset signature, which that check flags
            // correctly and which is emphatically not what this scenario is about. At launch+3 the
            // victim is still rising on the arc (groundedProxy false, onGroundPacket false) while
            // the hit's own Δy is 0.165, and `jumpOnHurt` resolves the hit as a counter-example.
            if (i == launchTick + 3) {
                attacker.strike(victim)
                slide = 4
            }
            // the KB slide the server broadcasts, on the ticks after the hit -- 3 of the 4 fall in
            // the check's 3-tick window
            if (slide > 0) { slideZ += 0.28; slide-- } else slideZ = 0.0
            victim.teleportTo(0.0, y, 2.2 + slideZ)
            // `air` was just updated for this tick, so on-ground is its negation: false on the
            // launch tick (they just jumped), true on the landing tick, false mid-arc.
            victim.setOnGround(!air)
        }
        b.runFor(220)
    }

    /**
     * A vanilla **sword sweep onto two adjacent opponents** -- the legit shape `multiTarget`'s pair
     * path used to read as a two-target aura.
     *
     * A 1.9+ sword sweep damages every entity in the arc on the **same tick**, so two adjacent
     * players at 2.2 and 2.6 blocks on the attacker's facing are hit legitimately, twice per second,
     * by ordinary melee. That is exactly what the check's *same-tick* flag is for and is expected
     * here: it fires at level 1.0 against a 1.0/tick decay, so it can never accumulate and never
     * alerts (this scenario asserts the alert, not the flag).
     *
     * **What this drive proves.** The pair path is the module detector, and its gate says "2 of the
     * last 4 *ticks*". Pre-fix the gate was fed by an **event** ring: two same-tick hurts per sweep
     * push two `true` samples, so a single sweep fills half the window and the second sweep -- ten
     * ticks later, on nothing but ordinary melee -- satisfied it. `flagEpisode` then added
     * `setbackVL + 1` on top of the sweep's own same-tick flag (2 + 1 = 3 > setbackVL 2) and the
     * legitimate player **alerted**. Post-fix the ring holds one sample per tick and prunes anything
     * older than `tick - 3`, so at a 4+-tick cadence each sweep sees only its own tick: size 1,
     * below `PAIR_MIN` (2), no episode flag, no alert.
     *
     * The `pair-sustained` subLabel assertion is what makes the fix observable rather than merely
     * quiet: both the sweep's legitimate same-tick flag and the module flag report under the label
     * `"MultiTarget"`, so a label-only count would read ~11 legitimate sweep flags and fail even on
     * the fixed code (this is why `SelfTestHooks.flagCountFor` takes a subLabel).
     *
     * The chassis is `legit-combat`'s: the attackers chase along +Z at the vanilla pace, both victims
     * retreat in lockstep (so the 2.2 / 2.6 geometry is constant and `reach` stays honest), each hit
     * carries the real sprint-KB excursion and its unwind, and the hits land on a jittered 4-7 tick
     * cadence -- so `noKnockback`, `keepSprint` and `clickStatistics` stay quiet on inputs this
     * suite already proves they tolerate.
     */
    fun legitMultiTargetSweep(): Spec = Spec(
        "legit-multitarget-sweep", Pass.LEGIT, "vanilla", setOf(Tags.COMBAT, Tags.ROTATION),
    ) { b ->
        val attacker = b.bot("Sweeper", 0.0, 0.0)
        val near = b.bot("Near", 0.0, 2.2)
        val far = b.bot("Far", 0.0, 2.6)
        b.expectQuiet(attacker, CheckIds.COMBAT)
        b.expectQuiet(attacker, "aimWrap", "rotationSnapBack", "rotationTracking")
        b.expectQuiet(attacker, CheckIds.MOVEMENT)
        b.expectQuiet(near, "noKnockback", "jumpOnHurt", "backtrack")
        b.expectQuiet(far, "noKnockback", "jumpOnHurt", "backtrack")
        // The module detector's own flag, isolated from the sweep's legitimate same-tick flag.
        b.expectFlagCount(attacker, "multiTarget", "MultiTarget", atMost = 0, subLabel = "pair-sustained")

        val ground = b.groundY
        val jitter = listOf(4, 5, 5, 6, 5, 4, 7, 5, 6, 5)
        val aim = Jitter()
        var idx = 0
        var since = -12
        var slowTicks = 0
        var z = -8.0
        var victimZ = -5.8
        var kbPush = 0.0
        var kbTicksLeft = 0
        b.everyTick {
            attacker.look(0f, 12f + aim.deg(12.0))
            attacker.sprint(true)
            attacker.setOnGround(true)
            val attacking = since >= jitter[idx % jitter.size]
            val step = if (attacking || slowTicks > 0) 0.15 else 0.25
            if (attacking) slowTicks = 3 else if (slowTicks > 0) slowTicks--
            z += step
            if (attacking) kbTicksLeft = 2
            val kbTarget = if (kbTicksLeft > 0) { kbTicksLeft--; 0.6 } else 0.0
            val kbPrev = kbPush
            kbPush += (kbTarget - kbPush).coerceIn(-0.3, 0.3)
            victimZ += step + (kbPush - kbPrev)
            attacker.teleportTo(0.0, ground, z)
            near.teleportTo(0.0, ground, victimZ)
            far.teleportTo(0.0, ground, victimZ + 0.4)
            near.setOnGround(true)
            far.setOnGround(true)

            if (attacking) {
                // one swing, the whole arc damaged: the sweep's shape is two hurts on one tick
                attacker.strike(near)
                attacker.strike(far)
                since = 0
                idx++
            } else since++
        }
        b.runFor(170)
    }

    /**
     * A player holding left-click on a block they cannot break: the shape [ClickStatisticsCheck] reads as a
     * fixed-delay autoclicker.
     *
     * Vanilla swings the arm once per tick while a block is being broken, and the server rebroadcasts those
     * animations on a fixed clock. Both halves were verified against the 1.21.11 bytecode:
     *
     * - `MinecraftClient.handleBlockBreaking` calls `player.swingHand(MAIN_HAND)` whenever
     *   `interactionManager.updateBlockBreakingProgress(...)` returns true, and that method's
     *   already-breaking branch ends in an unconditional `return true`. So a player who continues a dig
     *   swings every tick, including on a block whose progress can never reach 1.0: bedrock's
     *   `calcBlockBreakingDelta` is 0, so the branch is taken for as long as the button is held.
     * - The swing itself is the client's. `handleBlockBreaking` calls `player.swingHand(MAIN_HAND)`, and
     *   `ClientPlayerEntity.swingHand` animates locally and sends a `HandSwingC2SPacket`; the server's
     *   `onHandSwing` re-swings its own player, and that re-swing is what the observer receives:
     *   `LivingEntity.swingHand` relays `EntityAnimationS2CPacket` to other nearby players, never back to the
     *   digger, and only once `handSwingTicks >= getHandSwingDuration() / 2`. The duration is the held item's
     *   `swing_animation` duration, 6 ticks for a bare hand or a pickaxe, with Haste subtracting
     *   `1 + amplifier` and Mining Fatigue adding `(1 + amplifier) * 2`. The relay therefore lands on a fixed
     *   interval, `floor(duration / 2) + 1`:
     *   4 at rest, 3 under Haste I or II (durations 5 and 4 both floor to 2), 5 under Mining Fatigue I
     *   (duration 8), 7 under an elder guardian's Mining Fatigue III (duration 12), and 2 from Haste III/IV or
     *   a custom `SWING_ANIMATION` duration of 3 or 2. Only a command-granted Haste V or higher, or an item
     *   whose duration is 1 or 0, relays every tick, and the guard deliberately does not exempt that case,
     *   because a 1-tick relay is indistinguishable from the 20 CPS autoclicker the check exists to catch.
     *   Nothing about why the arm moved reaches that gate.
     *
     * Iustitia only ever sees the relayed stream, so a continuous digger arrives as [SwingSignal]s at a
     * constant interval. [ClickStatisticsCheck] records the tick delta between consecutive swings and flags
     * `ClickStats(StDev)` when `populationStDev < 0.45` over 40 samples. On a constant interval every delta
     * is identical, the stdev is exactly 0.0, and ordinary digging produces the flag.
     *
     * This alerts rather than merely filling history. The sub-signal flags at level 1.0 per swing against a
     * `decay` of 0.05 per tick, so a 4-tick relay is +0.25/tick gross versus 0.05/tick of decay: vl crosses
     * the 5.0 setback about 25 ticks after the 40-sample window fills at 160 ticks. A Haste I or II relay
     * shortens the interval to 3 and raises the climb to about +0.28/tick. The two remaining sub-signals do
     * not own this shape and are not asserted here, because neither can fire on it: `MathUtil.excessKurtosis`
     * returns 0.0 on zero variance, which sits above the -0.7 bar, and `detectLoop` rejects a constant prefix
     * outright with `if (minV != maxV) return p`. The Kurt window is 600 samples on top of that, which this
     * drive does not reach.
     *
     * The drive is a stationary bot swinging on the vanilla relay interval for 300 ticks, about 15 seconds of
     * continuous digging. It is stationary on purpose: a player breaking a block stands still, so a still bot
     * leaves every motion component, `scaffoldRotation` and the movement checks with nothing to react to, and
     * a failure here can only be the swing cadence. The dig state is published for the same reason the swing
     * is: harness bots are client-side display entities the server never sees, so no
     * `BlockBreakingProgressS2CPacket` is ever sent for them. `SelfTest.dig()` publishes the signal the
     * packet path would have published, and it is published once and never refreshed, exactly as
     * `ServerPlayerInteractionManager.continueMining` treats a block whose breaking delta is 0.
     */
    fun legitMiningCadence(): Spec = Spec(
        "legit-mining-cadence", Pass.LEGIT, "vanilla", setOf(Tags.COMBAT),
    ) { b ->
        val bot = b.bot("Digger", 0.0, 0.0)
        b.expectQuiet(bot, CheckIds.COMBAT)
        b.expectQuiet(bot, "scaffoldRotation")
        // The discriminating assertion. Every `ClickStats` flag sits at level 1.0 or 2.0, and `expectQuiet`
        // alone cannot separate "the false positive is gone" from "the cadence was never driven". A flag
        // count can, and the subLabel pins the one sub-signal a constant interval owns.
        b.expectFlagCount(bot, "clickStatistics", "ClickStats(StDev)", atMost = 0, subLabel = "stdev")

        // The dig state that makes these swings the server's rather than the player's. Published
        // once, never refreshed: that is the bedrock shape (breaking delta 0, so
        // `continueMining` broadcasts progress 0 exactly once and then nothing).
        bot.dig()

        // 4 ticks is `getHandSwingDuration()/2 + 1` at the default item swing duration of 6.
        b.everyTick(4) { bot.swing() }
        b.runFor(300)
    }

    /**
     * A player digging continuously 6 blocks from a teammate who is taking damage that carries no attacker
     * id: the shape `reach` reads as a 6-block hit.
     *
     * A swing is only evidence of an attack because [AttackInference] says so, and its correlation is
     * proximity and timing alone. `correlate` filters candidates on exactly four things: the candidate is not
     * the victim, the candidate is tracked, the candidate is within 8.0 blocks of the victim, and the
     * candidate has an unspent swing in the window `[hurtTick - 2, hurtTick + 1]` (1.8 widens that to
     * `[-3, +2]`). It never reads [HurtSource]. There is no facing test, no line-of-sight test and no reach
     * test, and the window includes a swing made *after* the hurt (`+fwd`).
     *
     * A player digging on the server's 4-tick relay therefore satisfies that filter permanently. A stride-4
     * swing stream places exactly one swing in any 4-tick window, so every hurt on a victim within 8 blocks
     * correlates, whoever caused it. Damage with no attacker id is the worst case, because no real attacker
     * can outrank the digger: `best` is chosen by nearest distance, and an environmental hurt has no
     * candidate at 0 blocks to win.
     *
     * `ReachCheck` then measures the digger to the victim for real, 6.0 blocks here, and its motionless path
     * fires without ever asking whether the attacker was aiming at anything. `maxReach` is 3.0 for a player
     * with no range modifier and `STATIC_HEADROOM` is 0.2, so `closest` lands about 2.5 blocks over the
     * 3.2 bar. Two violations in a six-event window sustain it (`STILL_WINDOW` / `STILL_MIN`), and
     * `flagEpisode` flags at `setbackVL + 1.0`, which is 11.0 against a setback of 10.0. One flag alerts.
     * Both bodies are motionless, which is the one thing this path requires and the one thing a miner and a
     * burnt teammate actually are.
     *
     * The digger faces **away** from the victim on purpose. Facing is not an input to the correlation, so
     * turning away cannot protect the digger, and pinning the look at 180 degrees keeps `rotationTracking`
     * from firing on a genuine aim match and keeps this scenario discriminating on the misattribution alone.
     * The 6 blocks are chosen to sit inside the correlator's 8.0 reach while landing well outside `reach`'s
     * 3.2 bar, so the two thresholds cannot be confused for each other.
     *
     * The victim's hurts are spaced 10 ticks apart for a reason: `sustained` needs the ring full before it
     * can report (6 events), so the flag becomes reachable at about tick 60 and the drive runs 120.
     *
     * Two guards in `correlate` close this, and both are live on this drive. A hurt that carries an attacker
     * id can only be claimed by that attacker, and a hurt with no attacker id on a non-VELOCITY channel can
     * no longer be claimed by a candidate the server currently has digging. The VELOCITY carve-out is
     * deliberate: a knockback impulse is real evidence of a hit, so silencing it for diggers would hand a
     * damage-suppressing aura a bypass. The dig state is published here for the same reason the swing is:
     * the harness bots are client-side display entities the server never sees, so no
     * `BlockBreakingProgressS2CPacket` is ever sent for them.
     */
    fun legitMiningNearHurt(): Spec = Spec(
        "legit-mining-near-hurt", Pass.LEGIT, "vanilla", setOf(Tags.COMBAT),
    ) { b ->
        val digger = b.bot("Digger", 0.0, 0.0)
        val victim = b.bot("Tunnelmate", 0.0, 6.0)
        b.expectQuiet(digger, CheckIds.COMBAT)
        // The discriminator: `reach` shares its "Reach" label across four sub-signals, and only the
        // distance-only motionless one can be reached from here.
        b.expectFlagCount(digger, "reach", "Reach", atMost = 0, subLabel = "motionless")

        // The dig state the second guard reads. Published once and never refreshed: a miner grinding an
        // unbreakable block sends exactly one progress-0 packet and then silence.
        digger.dig()

        b.everyTick {
            digger.look(180f, 0f)
            digger.setOnGround(true)
            victim.setOnGround(true)
        }
        b.everyTick(4) { digger.swing() }
        // Environmental damage, exactly as an observer sees it: a hurt on the victim with no attacker id.
        b.everyTick(10) { victim.hurt(attackerEntityId = -1, source = HurtSource.DAMAGE_TILT) }
        b.runFor(120)
    }
}

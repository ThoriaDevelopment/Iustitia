package dev.iustitia.selftest

import net.minecraft.block.Blocks
import net.minecraft.item.ItemStack
import net.minecraft.item.Items

/**
 * The **movement half of the unfair-advantage pass**: flight, speed, teleport/blink, fall
 * damage, wall-climb, step, long-jump, NoSlow, omnisprint, water-sprint, Jesus, elytra, phase,
 * pitch and scaffold, plus the rotation and packet-flow family.
 *
 * The provenance rule from [CheatCombat] applies unchanged: each scenario names the reference
 * client whose module it reproduces, and the movement checks that carry the most weight
 * (`speedEnvelope`, `flyEnvelope`) have several clients each, because flight and speed modules
 * differ so much between clients that one drive is not evidence of coverage.
 *
 * The same VL arithmetic governs expectations: with `decayAll()` running every tick before
 * processing, a check alerts only while `flag_rate x level > decay`. Where a check's own gates
 * cap that rate below the break-even, the drive is at full intensity and the arithmetic is
 * recorded as a known-open finding rather than reported as a pass.
 */
object CheatMovement {

    // ------------------------------------------------------------------
    // speedEnvelope -- multi-client
    // ------------------------------------------------------------------

    private fun speed(name: String, source: String, pace: Double): Spec = Spec(
        name, Pass.CHEAT, source, setOf(Tags.MOVEMENT),
    ) { b ->
        val bot = b.bot("Speeder", 0.0, 0.0)
        b.expect(bot, "speedEnvelope", mustAlert = true)
        b.drive(bot, 0.0, 0.0, -pace, grounded = true)
        b.runFor(160)
    }

    /** LiquidBounce `Speed` at ~12 b/s -- clearly past the 10 bps flat cap. */
    fun speedLiquidBounce() = speed("cheat-speed-liquidbounce", "LiquidBounce", 0.60)

    /** Koid / LionClient `Speed` at ~18 b/s (a blatant module). */
    fun speedBlatant() = speed("cheat-speed-koid", "Koid", 0.90)

    /**
     * Meteor's `Speed` at 1.35x sprint ~ 7.6 b/s -- the **sub-cap** tier that has to be caught
     * by the momentum model rather than the flat 10 bps cap. Recorded as an open calibration
     * item: either the model depends on movement state a synthetic teleporting bot never
     * builds (a harness artifact) or sub-cap speed hacks are a genuine gap.
     */
    fun speedSubCap(): Spec = Spec("cheat-speed-meteor", Pass.CHEAT, "Meteor", setOf(Tags.MOVEMENT)) { b ->
        val bot = b.bot("Speeder", 0.0, 0.0)
        b.expectKnownOpen(
            bot, "speedEnvelope",
            note = "1.35x sprint (~7.6 b/s) is inside the flat 10 bps cap, so it depends on the sub-cap " +
                "momentum model, which did not fire for a synthetic drive -- open question: harness artifact " +
                "(the model reads real movement state) or a genuine sub-cap gap",
        )
        b.drive(bot, 0.0, 0.0, -0.38, grounded = true)
        b.runFor(200)
    }

    // ------------------------------------------------------------------
    // flyEnvelope -- multi-client
    // ------------------------------------------------------------------

    /**
     * A bot in the open air that never descends and never lands -- the shape every flight module
     * produces and no vanilla entity can hold.
     *
     * [mode] selects the client's particular trick:
     * - `hover` -- Meteor `Flight` in hover mode: Y frozen.
     * - `ascend` -- LiquidBounce `Flight`: a slow sustained climb (~2 b/s).
     * - `strafehop` -- Fusion's strafe-hop: the exact constant dy 0.40123128, which is
     *   distinguishable from a real jump arc only because it never decays.
     * - `antikick` -- Itami's anti-kick flight: mostly still with tiny periodic descent blips
     *   (~-0.03 every 20 ticks) to dodge the server's floating check.
     */
    private fun fly(name: String, source: String, mode: String): Spec =
        Spec(name, Pass.CHEAT, source, setOf(Tags.MOVEMENT)) { b ->
        val base = b.groundY + 8.0
        val bot = b.bot("Flyer", 0.0, base, 0.0)
        b.expect(bot, "flyEnvelope", mustAlert = true)
        var t = 0
        b.everyTick {
            val i = t++
            bot.setOnGround(false)
            val y = when (mode) {
                "hover" -> base
                "ascend" -> base + 0.10 * i
                "strafehop" -> base + 0.40123128 * i
                "antikick" -> base - 0.03 * (i / 20)
                else -> base
            }
            bot.teleportTo(0.0, y, 0.0)
        }
        b.runFor(160)
    }

    fun flyHover() = fly("cheat-fly-hover-meteor", "Meteor", "hover")
    fun flyAscend() = fly("cheat-fly-ascend-liquidbounce", "LiquidBounce", "ascend")
    fun flyStrafeHop() = fly("cheat-fly-strafehop-fusion", "Fusion", "strafehop")
    fun flyAntiKick() = fly("cheat-fly-antikick-itami", "Itami", "antikick")

    // ------------------------------------------------------------------
    // teleport / packetGap
    // ------------------------------------------------------------------

    /**
     * Vertical clipping (VClip / SlyPort): a 2.6-block single-tick jump out of a level tick,
     * repeated with a settle tick in between (the continuity gate requires the *previous* tick
     * to have been level, which is exactly why a clipper alternates warp and hold).
     *
     * Since the episode-gate fix, ≥2 clips inside a 20-tick window alert once at
     * `setbackVL + 1.0` — a repeated clipper (5 clips / 20 ticks here) is reported, while a
     * one-off ender-pearl / catch-up snap stays sub-setback.
     */
    fun verticalClip(): Spec = Spec("cheat-teleport-vclip-koid", Pass.CHEAT, "Koid", setOf(Tags.MOVEMENT, Tags.PACKET)) { b ->
        val ground = b.groundY
        val bot = b.bot("Clipper", 0.0, 0.0)
        b.expect(bot, "teleport", mustAlert = true)
        var t = 0
        b.everyTick {
            val i = t++
            val k = i / 4 // warp, then three level ticks
            bot.setOnGround(false)
            bot.teleportTo(0.0, ground + 2.6 * k, 0.0)
        }
        b.runFor(200)
    }

    /**
     * Horizontal teleport (SlyPort): a 4.5-block sideways jump from a level tick, repeated.
     * Episode-gated like [VerticalClip] — the repeated port sustains the clip pattern and alerts.
     */
    fun horizontalClipper(): Spec = Spec("cheat-teleport-slyport-koid", Pass.CHEAT, "Koid", setOf(Tags.MOVEMENT, Tags.PACKET)) { b ->
        val ground = b.groundY
        val bot = b.bot("Clipper", 0.0, 0.0)
        b.expect(bot, "teleport", mustAlert = true)
        var t = 0
        b.everyTick {
            val i = t++
            val k = i / 4
            bot.setOnGround(true)
            bot.teleportTo(0.0, ground, -4.5 * k)
        }
        b.runFor(200)
    }

    /**
     * Blink / FakeLag (Vape `Blink`, Slinky `Lag Range`): the bot's own packets are held so the
     * entity freezes for a stretch and then snaps a large distance in one tick. The freeze must
     * clear at least 5 ticks and the snap must exceed 2 blocks, so the cycle cannot repeat
     * faster than every 6 ticks -- recorded, because one flag per cycle is below the 0.5/tick
     * break-even for a level-1.0 flag.
     */
    fun blink(): Spec = Spec("cheat-packetgap-blink-vape", Pass.CHEAT, "Vape", setOf(Tags.PACKET)) { b ->
        val ground = b.groundY
        val bot = b.bot("Blinker", 0.0, 0.0)
        b.expectKnownOpen(
            bot, "packetGap",
            note = "the check needs a >=5-tick freeze before the snap, so a blink cycle is >=6 ticks: one " +
                "level-1.0 flag per cycle is ~0.17 flags/tick against decay 0.5 -- Blink can never alert " +
                "no matter how long it pulses",
        )
        var t = 0
        b.everyTick {
            val i = t++
            val phase = i % 8
            bot.setOnGround(true)
            if (phase == 7) bot.teleportTo(0.0, ground, -5.0 * (i / 8 + 1)) // the flush
            else bot.teleportTo(0.0, ground, -5.0 * (i / 8))                 // frozen
        }
        b.runFor(200)
    }

    // ------------------------------------------------------------------
    // noFallDamage -- multi-client
    // ------------------------------------------------------------------

    /**
     * A long fall onto solid ground with **no landing hurt** (Vape `NoFall` / Meteor `NoFall`):
     * the client tells the server it landed softly, so the server never broadcasts damage. The
     * check's per-landing level scales with the accumulated fall, so this crosses the setback
     * in a single (very high) flag -- the honest high-intensity form of the pattern.
     */
    fun noFall(): Spec = Spec("cheat-nofall-vape", Pass.CHEAT, "Vape", setOf(Tags.MOVEMENT)) { b ->
        val ground = b.groundY
        val bot = b.bot("Faller", 0.0, ground + 24.0, 0.0)
        b.expect(bot, "noFallDamage", mustAlert = true)
        var v = 0.0
        var cur = ground + 24.0
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
                    // deliberately NO hurt: this is the cheat signature
                }
            }
        }
        b.runFor(140)
    }

    /**
     * The mace-smash exemption's own evasion attempt (Vape-style `NoFall` on a mace kit):
     * swing the mace mid-descent to *look* like a smash is starting, but publish **no victim
     * hurt** — so no [dev.iustitia.event.AttackEvent] forms and the re-base must not arm. The
     * landing is judged exactly like `cheat-nofall-vape` and must still flag; if this ever goes
     * quiet, the swing alone (without a confirmed hit) has become an evasion route for the
     * exemption.
     */
    fun noFallMaceEvade(): Spec = Spec("cheat-nofall-mace-evade", Pass.CHEAT, "Vape", setOf(Tags.MOVEMENT)) { b ->
        val ground = b.groundY
        val bot = b.bot("Evader", 0.0, ground + 24.0, 0.0)
        b.expect(bot, "noFallDamage", mustAlert = true)
        bot.hold(ItemStack(Items.MACE))
        var v = 0.0
        var cur = ground + 24.0
        var landed = false
        var swung = false
        b.everyTick {
            if (!landed && cur > ground) {
                v = (v - 0.08) * 0.98
                cur = maxOf(ground, cur + v)
                bot.teleportTo(0.0, cur, 0.0)
                bot.setOnGround(false)
                // swing once mid-descent, no victim hurt published: no AttackEvent, no re-base
                if (!swung && cur < ground + 20.0) {
                    swung = true
                    bot.swing()
                }
                if (cur <= ground) {
                    landed = true
                    bot.setOnGround(true)
                    // deliberately NO hurt: the cheat signature this must still catch
                }
            }
        }
        b.runFor(140)
    }

    /**
     * Ground-spoof variant (Meteor `NoFall` Ground packet mode): the server-reported
     * on-ground bit is claimed **true** while the player is still in the air — the vanilla
     * server resets its fall-distance accumulator on that report, so the landing never
     * accumulates damage. Observable as `onGroundPacket` true with provably no solid below
     * while a real fall accrues.
     *
     * Since the episode-gate fix, ≥3 consecutive spoof ticks with >4 blocks accrued alert
     * once at `setbackVL + 1.0` (the old flat 1.0/tick flag exactly met the 1.0/tick decay
     * and could never cross the setback). The drive used to assert this as known-open —
     * and, unnoticed, its old form set the ground bit FALSE, so it never exercised the
     * spoof path at all.
     */
    fun noFallSpoof(): Spec = Spec("cheat-nofall-spoof-meteor", Pass.CHEAT, "Meteor", setOf(Tags.MOVEMENT)) { b ->
        val ground = b.groundY
        val bot = b.bot("Spoofer", 0.0, ground + 22.0, 0.0)
        b.expect(bot, "noFallDamage", mustAlert = true)
        var t = 0
        b.everyTick {
            val i = t++
            // physically airborne and descending, but the reported ground bit says landed
            bot.setOnGround(true)
            bot.teleportTo(0.0, ground + 22.0 - 0.35 * i, 0.0)
        }
        b.runFor(80)
    }

    /**
     * **NoFall evasion attempt**: the cheater fakes the no-damage-burst impulse that
     * `legit-windcharge` legitimately relies on, for the sole purpose of earning the exemption,
     * and then keeps falling far below the point it fired it -- landing with no hurt on a fall the
     * server should have punished.
     *
     * It imitates the *real* signature rather than an arbitrary jolt, which is what makes it a
     * meaningful test: the tracker arms `burstTick` from the motion (`Δy > 0.8` with
     * `prevΔy < 0.15`), so the drive starts level, fires a jump-sized Δy of 1.2 on that level tick,
     * then holds >0.5 b/t of mid-air drift so the tracker's other arm branch (10 b/s, above sprint)
     * re-arms the burst on every remaining tick -- the worst case for any implementation that
     * exempts "a landing near a burst".
     *
     * Neither form may buy an exemption. The detector re-bases its accumulator on the impulse's own
     * start (`pos.y − Δy`) and keeps the **highest** such point: an impulse fired while descending
     * sits *above* the falling player, so it can only raise the floor, never lower it; and a burst
     * never clears the accumulator. The 28 blocks accrued below that floor are therefore still
     * judged at touchdown, exactly as a plain `cheat-nofall-vape` fall is.
     */
    fun noFallBurstSpoof(): Spec = Spec(
        "cheat-nofall-burst-spoof-meteor", Pass.CHEAT, "Meteor", setOf(Tags.MOVEMENT),
    ) { b ->
        val ground = b.groundY
        val bot = b.bot("BurstSpoof", 0.0, ground + 28.0, 0.0)
        b.expect(bot, "noFallDamage", mustAlert = true)
        var v = 0.0
        var y = ground + 28.0
        var z = 0.0
        var landed = false
        var t = 0
        b.everyTick {
            val i = t++
            if (landed) return@everyTick
            when {
                // one level tick so the faked impulse lands on the shape burstTick requires
                i < 1 -> bot.setOnGround(false)
                i == 1 -> {
                    v = 1.2
                    y += v
                    bot.setOnGround(false)
                }
                else -> {
                    v = (v - 0.08) * 0.98
                    y += v
                    z += 0.55      // > 0.5 b/t: re-arms the burst every tick
                    bot.setOnGround(false)
                    if (y <= ground) {
                        y = ground
                        landed = true
                        bot.setOnGround(true)
                        // deliberately NO hurt: the cheat signature this must still catch
                    }
                }
            }
            bot.teleportTo(0.0, y, z)
        }
        b.runFor(160)
    }

    // ------------------------------------------------------------------
    // spider / stepHeight / longJump
    // ------------------------------------------------------------------

    /**
     * Spider / wall-climb (AvA `Spider`, Rain's port): a sustained ascent beside a solid
     * non-climbable wall, off-ground, no liquid. The check needs >10 consecutive ascending
     * ticks against the wall, then flags every tick -- comfortably above decay 0.5.
     */
    fun spider(): Spec = Spec("cheat-spider-ava", Pass.CHEAT, "AvA", setOf(Tags.WORLD, Tags.MOVEMENT)) { b ->
        val g = b.groundY
        val bot = b.bot("Spider", 0.0, 0.0)
        b.expect(bot, "spider", mustAlert = true)
        // a tall stone column in the cell next to the bot
        b.fill(1, g.toInt(), 0, 1, (g + 24).toInt(), 0, Blocks.STONE)
        var t = 0
        b.everyTick {
            val i = t++
            bot.setOnGround(false)
            bot.teleportTo(0.0, g + 0.3 * i, 0.0)
        }
        b.runFor(120)
    }

    /**
     * Step (Meteor `Step`): a 1.5-block single-tick rise from a grounded tick, with a solid
     * block inside the vanilla step reach below the new feet.
     *
     * Recorded with the arithmetic: the flag needs the *previous* tick to register as grounded,
     * and a step tick cannot be grounded, so a step can flag at most every other tick -- right
     * at decay 0.5 for a level-1.0 flag. The drive places the block and steps repeatedly so
     * the finding is demonstrated, not asserted.
     */
    fun step(): Spec = Spec("cheat-step-meteor", Pass.CHEAT, "Meteor", setOf(Tags.WORLD, Tags.MOVEMENT)) { b ->
        val g = b.groundY
        val bot = b.bot("Stepper", 0.0, 0.0)
        b.expectKnownOpen(
            bot, "stepHeight",
            note = "a step's flag requires the previous tick to have been grounded, and a step tick is not " +
                "grounded: maximum 0.5 flags/tick against decay 0.5 at level 1.0, so Step caps the VL near " +
                "1.0 and never alerts at default tuning",
        )
        var t = 0
        b.everyTick {
            val i = t++
            when (i % 3) {
                0 -> {
                    bot.setOnGround(true)
                    bot.teleportTo(0.0, g, 0.0)
                    b.clear(0, g.toInt(), 0)
                }
                1 -> {
                    // the block being stepped onto, plus the 1.5-block rise from the grounded tick
                    b.place(0, g.toInt(), 0, Blocks.STONE)
                    bot.setOnGround(false)
                    bot.teleportTo(0.0, g + 1.5, 0.0)
                }
                else -> {
                    bot.setOnGround(false)
                    bot.teleportTo(0.0, g + 1.5, 0.0)
                }
            }
        }
        b.runFor(150)
    }

    /**
     * LongJump (LiquidBounce `LongJump`): the launch carries ~12+ b/s of horizontal motion on
     * the first airborne ticks -- more than double a real sprint-jump launch (~6 b/s).
     *
     * Recorded: one flag per launch against decay 0.5 at level 1.0 needs a launch every second
     * tick, which a real jump arc cannot do.
     */
    fun longJump(): Spec = Spec("cheat-longjump-liquidbounce", Pass.CHEAT, "LiquidBounce", setOf(Tags.MOVEMENT)) { b ->
        val ground = b.groundY
        val bot = b.bot("Leaper", 0.0, 0.0)
        b.expectKnownOpen(
            bot, "longJump",
            note = "one level-1.0 flag per boosted launch, and a launch needs a full jump arc (~12 ticks) " +
                "to arm -- ~0.08 flags/tick against decay 0.5, so a boosted launch can never accumulate",
        )
        var t = 0
        b.everyTick {
            val i = t++
            val phase = i % 14
            if (phase == 0) {
                bot.setOnGround(true)
                bot.teleportTo(0.0, ground, -0.0)
            } else if (phase in 1..12) {
                // vanilla-ish arc shape, but the horizontal boost is the cheat
                val arc = 0.42 * phase - 0.08 * phase * phase
                bot.setOnGround(false)
                bot.teleportTo(0.0, ground + maxOf(0.0, arc), -1.4 * (phase - 1))
            } else {
                bot.setOnGround(true)
                bot.teleportTo(0.0, ground, -1.4 * 11)
            }
        }
        b.runFor(140)
    }

    // ------------------------------------------------------------------
    // noSlow
    // ------------------------------------------------------------------

    /**
     * NoSlow (Koid / LionClient `NoSlow`): full sprint speed while the eat/drink metadata stays
     * set. Vanilla slows an eater to ~20%, so 10 b/s with an apple in hand is unambiguously the
     * cheat. The check flags every qualifying tick, which clears its decay.
     */
    fun noSlow(): Spec = Spec("cheat-noslow-koid", Pass.CHEAT, "Koid", setOf(Tags.MOVEMENT)) { b ->
        val bot = b.bot("Eater", 0.0, 0.0)
        // HARNESS GAP: the drive does not yet reach this check (see docs/automated-live-testing.md).
        // The alert assertion is deliberately NOT declared -- a red row for a reason that is
        // about the scripted bot would train contributors to ignore the suite.
        b.expectDriveGap(
            bot, "noSlow",
            note = "no noSlow flag; only speedEnvelope reacted, and only sub-threshold (71 flags, peakVL 1.0), so the using-item + movement metadata pair the check needs is not yet produced",
        )
        bot.hold(ItemStack(Items.GOLDEN_APPLE))
        var t = 0
        b.everyTick {
            val i = t++
            bot.startUsing() // re-assert: the client clears use state on its own timer
            bot.setOnGround(true)
            bot.teleportTo(0.0, b.groundY, -0.5 * i) // 10 b/s
        }
        b.runFor(80)
    }

    // ------------------------------------------------------------------
    // omnisprint -- multi-client
    // ------------------------------------------------------------------

    private fun omniSprint(name: String, source: String, dx: Double, dz: Double): Spec =
        Spec(name, Pass.CHEAT, source, setOf(Tags.MOVEMENT)) { b ->
        val bot = b.bot("Omni", 0.0, 0.0)
        // HARNESS GAP: the drive does not yet reach this check (see docs/automated-live-testing.md).
        // The alert assertion is deliberately NOT declared -- a red row for a reason that is
        // about the scripted bot would train contributors to ignore the suite.
        b.expectDriveGap(
            bot, "backwardSprint",
            note = "no backwardSprint flag; only speedEnvelope reacted, and only sub-threshold (89 flags, peakVL 1.0)",
        )
        bot.look(0f, 0f) // facing +Z for the whole drive
        b.drive(bot, dx, 0.0, dz, grounded = true)
        b.runFor(120)
    }

    /** Meteor `Sprint` (omnisprint): sprinting straight backwards at 6 b/s. */
    fun omniBackward() = omniSprint("cheat-omnisprint-backward-meteor", "Meteor", 0.0, 0.3)

    /** LiquidBounce `Sprint` strafe mode: sprint held on a pure sideways move. */
    fun omniStrafe() = omniSprint("cheat-omnisprint-strafe-liquidbounce", "LiquidBounce", 0.3, 0.0)

    // ------------------------------------------------------------------
    // wallSprint / sprintHack -- multi-client
    // ------------------------------------------------------------------

    /**
     * WallSprint (Grim `SprintE`): the sprint flag is forced to stay set while the player is
     * pressed against a wall, which vanilla cancels the instant forward motion stops. The bot
     * stands still against a wall with sprint held and zero forward speed -- the pinned state a
     * real player running *at* the wall never reaches.
     */
    fun wallSprint(): Spec = Spec("cheat-wallsprint-grim", Pass.CHEAT, "Grim", setOf(Tags.WORLD, Tags.MOVEMENT)) { b ->
        val g = b.groundY
        val bot = b.bot("Pinned", 0.0, -0.1)
        b.expect(bot, "wallSprint", mustAlert = true)
        // the wall the bot is pressed against: the cell directly ahead along yaw 0 (+Z)
        b.fill(0, g.toInt(), 0, 0, (g + 1).toInt(), 0, Blocks.STONE)
        bot.look(0f, 0f)
        b.everyTick {
            bot.sprint(true)
            bot.setOnGround(true)
            bot.teleportTo(0.0, g, -0.1)
        }
        b.runFor(120)
    }

    /**
     * SprintHack is three impossible sprint/hostile-state combinations. Each variant drives the
     * one the named client exposes:
     * - `water` -- Grim `SprintG` (sprinting with feet in water and head above it),
     * - `sneak` -- LiquidBounce (sprint and sneak metadata both set; vanilla forbids it),
     * - `blind` -- Itami (sprint under Blindness; vanilla cancels sprint).
     */
    private fun sprintHack(name: String, source: String, mode: String): Spec =
        Spec(name, Pass.CHEAT, source, setOf(Tags.MOVEMENT)) { b ->
        val g = b.groundY
        val bot = b.bot("Sprint", 10.0, 0.0)
        // Per-variant expectation: the sneak and blind drives DO reach the check (verified live:
        // both alert, sprintHack peaked at 55.0), while the water variant does not -- so only the
        // water case is recorded as a harness gap. A shared helper must not flatten that
        // difference: it would retire two working assertions to hide one broken drive.
        if (mode == "water") {
            // HARNESS GAP: the drive does not yet reach this check (see docs/automated-live-testing.md).
            // The alert assertion is deliberately NOT declared -- a red row for a reason that is
            // about the scripted bot would train contributors to ignore the suite.
            b.expectDriveGap(
                bot, "sprintHack",
                note = "the water variant never produced a sprintHack flag, while the sneak and blind " +
                    "variants of this same drive alert (sprintHack peaked at 55.0) and only waterWalk " +
                    "reacted here (8 flags, peakVL 1.5) -- so the water-specific metadata the check " +
                    "gates on (sprint while the feet are in liquid) is not yet presented by the drive",
            )
        } else {
            b.expect(bot, "sprintHack", mustAlert = true)
        }
        if (mode == "water") {
            // feet in the liquid, head above it (the Grim SprintG shape)
            b.fill(10, (g - 1).toInt(), -6, 10, (g - 1).toInt(), 6, Blocks.WATER)
        }
        if (mode == "blind") bot.blindness(true)
        var t = 0
        b.everyTick {
            val i = t++
            bot.sprint(true)
            when (mode) {
                "sneak" -> {
                    bot.sneak(true)
                    bot.setOnGround(true)
                    bot.teleportTo(10.0, g, 0.0)
                }
                "water" -> {
                    bot.setOnGround(true)
                    bot.teleportTo(10.0, g, -0.05 * i)
                }
                else -> {
                    bot.setOnGround(true)
                    bot.teleportTo(10.0, g, -0.05 * i)
                }
            }
        }
        b.runFor(120)
    }

    fun sprintHackWater() = sprintHack("cheat-sprinthack-water-grim", "Grim", "water")
    fun sprintHackSneak() = sprintHack("cheat-sprinthack-sneak-liquidbounce", "LiquidBounce", "sneak")
    fun sprintHackBlind() = sprintHack("cheat-sprinthack-blind-itami", "Itami", "blind")

    // ------------------------------------------------------------------
    // waterWalk
    // ------------------------------------------------------------------

    /**
     * Jesus / WaterWalk (Slinky `Jesus`, Meteor `LiquidWalk`): the bot walks across a pool at
     * ~5 b/s with a near-constant Y, held up by the liquid surface instead of swimming in it.
     * The check needs three sustained qualifying ticks and then flags every tick.
     */
    fun waterWalk(): Spec = Spec("cheat-waterwalk-jesus", Pass.CHEAT, "Slinky", setOf(Tags.WORLD, Tags.MOVEMENT)) { b ->
        val g = b.groundY
        val bot = b.bot("Jesus", 20.0, 6.0)
        b.expect(bot, "waterWalk", mustAlert = true)
        // the pool surface: the block the bot's feet occupy when standing "on" the water
        b.fill(16, (g - 1).toInt(), -2, 24, (g - 1).toInt(), 12, Blocks.WATER)
        var t = 0
        b.everyTick {
            val i = t++
            bot.setOnGround(true)
            bot.teleportTo(20.0, g, 6.0 - 0.25 * i) // ~5 b/s, level Y
        }
        b.runFor(100)
    }

    // ------------------------------------------------------------------
    // elytraSpeed
    // ------------------------------------------------------------------

    /**
     * ElytraFly (LiquidBounce `ElytraFly`): a deployed glide sustained at 48 b/s -- well past
     * the 40 bps blatant cap and far past what a level glide can produce.
     *
     * Since the episode-gate fix, the sustained over-cap glide alerts once at `setbackVL + 1.0`
     * per episode (the old flat 1.0/tick exactly met the 1.0/tick decay and could never
     * accumulate).
     */
    fun elytraFly(): Spec = Spec("cheat-elytra-fly-liquidbounce", Pass.CHEAT, "LiquidBounce", setOf(Tags.MOVEMENT)) { b ->
        val bot = b.bot("Elytra", 0.0, b.groundY + 60.0, 0.0)
        b.expect(bot, "elytraSpeed", mustAlert = true)
        var t = 0
        b.everyTick {
            val i = t++
            bot.gliding(true) // re-assert; the client clears the flag when it stops gliding
            bot.look(0f, 0f)
            bot.setOnGround(false)
            bot.teleportTo(0.0, b.groundY + 60.0 - 0.02 * i, -2.4 * i) // 48 b/s, level pitch
        }
        b.runFor(140)
    }

    // ------------------------------------------------------------------
    // phaseClip
    // ------------------------------------------------------------------

    /**
     * Phase / no-clip (Koid `Phase`, LiquidBounce `NoClip`): the bot's whole body moves through
     * a two-block full-cube wall. Both body bands are inside full cubes and the bot keeps moving
     * horizontally, which is what separates a phaser from a player pinned in a corner.
     */
    fun phase(): Spec = Spec("cheat-phase-koid", Pass.CHEAT, "Koid", setOf(Tags.WORLD, Tags.MOVEMENT)) { b ->
        val g = b.groundY
        val bot = b.bot("Phaser", 30.0, 0.0)
        b.expect(bot, "phaseClip", mustAlert = true)
        // a solid 2x2x2 pocket the bot travels inside
        b.fill(30, g.toInt(), 0, 31, (g + 2).toInt(), 1, Blocks.STONE)
        var t = 0
        b.everyTick {
            val i = t++
            bot.setOnGround(false)
            // move between the two x cells inside the solid block, so both body bands stay submerged
            bot.teleportTo(30.2 + 0.6 * (i % 2), g, 0.5)
        }
        b.runFor(100)
    }

    // ------------------------------------------------------------------
    // rotation / pitch / scaffold
    // ------------------------------------------------------------------

    /**
     * Out-of-range pitch (NCM `BadPacketsD`): the server clamps pitch to +/-90 deg, so a broadcast
     * pitch beyond it came from a client that never clamped. `pitchBound` has decay 0 and resets
     * on the first in-bounds value, so a locked out-of-range pitch climbs straight to the
     * setback -- one of the few checks whose economy makes a single sustained signal sufficient.
     */
    fun pitchBound(): Spec = Spec("cheat-pitchbound-badpackets", Pass.CHEAT, "NCM", setOf(Tags.ROTATION)) { b ->
        val bot = b.bot("Pitch", 0.0, 0.0)
        // HARNESS GAP: the drive does not yet reach this check (see docs/automated-live-testing.md).
        // The alert assertion is deliberately NOT declared -- a red row for a reason that is
        // about the scripted bot would train contributors to ignore the suite.
        b.expectDriveGap(
            bot, "pitchBound",
            note = "no pitchBound flag at all, so the out-of-range pitch this check reads is never presented to the tracker by the drive",
        )
        b.everyTick { bot.look(0f, 95f) } // impossible from a legit client
        b.runFor(60)
    }

    /**
     * Aim snap (LiquidBounce `AimBot`): a single-tick yaw rotation far past any human mouse
     * movement, out of a still tick.
     *
     * The drive holds each bearing for **two** ticks and then snaps to the other. That hold is
     * not decoration: the check only judges a tick whose *previous* wrapped delta was near-still, so
     * a drive that alternated the yaw every tick would produce ±170 deltas on both ticks and never
     * present a snap out of rest at all -- it would have been testing nothing. Two-tick holds give
     * one judged snap opportunity every two ticks, which is the strongest honest cadence the check
     * can consume.
     */
    fun aimWrap(): Spec = Spec("cheat-aimwrap-snap-liquidbounce", Pass.CHEAT, "LiquidBounce", setOf(Tags.ROTATION)) { b ->
        val bot = b.bot("Snapper", 0.0, 0.0)
        b.expect(bot, "aimWrap", mustAlert = true)
        var t = 0
        b.everyTick {
            val i = t++
            // hold each bearing for two ticks, then snap 170 deg: one judged snap every 2 ticks
            bot.look(if ((i / 2) % 2 == 0) 170f else 0f, 0f)
        }
        b.runFor(160)
    }

    /**
     * Snap-back aura (LiquidBounce `KillAura` + snap-back): the yaw sits on the travel bearing,
     * snaps onto the victim for the attack tick, then returns to the travel bearing -- target
     * acquired -> attack -> reset. The return-to-travel requirement is what separates this from a
     * legitimate target switch.
     *
     * The snap-back signature needs two ticks per episode (attack tick + return tick), so a
     * per-event `1.0` flag could only ever reach `0.5`/tick -- exactly the decay. The check now
     * requires the pattern to repeat and alerts once for the episode (see
     * [dev.iustitia.checks.Check.flagEpisode]); this drive repeats it on every second tick.
     */
    fun snapBack(): Spec = Spec("cheat-rotsnapback-liquidbounce", Pass.CHEAT, "LiquidBounce", setOf(Tags.ROTATION, Tags.COMBAT)) { b ->
        val bot = b.bot("Snap", 0.0, 0.0)
        val victim = b.bot("Victim", 0.0, 2.0)
        b.expect(bot, "rotationSnapBack", mustAlert = true)
        var t = 0
        // The yaw is written one tick BEFORE the tick it must be observed on. A scenario action
        // runs before `waitTick()`, so the tracker's snapshot trails the action by a tick: the
        // natural "look at the victim on the attack tick" form presented the *travel* bearing at
        // the attack (verified live: `ATK yaw=60 lastYaw=0`, 60 deg off the bearing, never primed)
        // and the check was asked only about hits that were not facing the victim at all.
        //
        // Cycle of three ticks: travel, snap, hit. The snap tick is the one whose value the
        // tracker reports while the hit lands, and the tick before the hit is what supplies the
        // check's `preAttackYaw` travel bearing -- so it must still read 60.
        b.everyTick {
            val i = t++
            bot.look(if (i % 3 == 1) 0f else 60f, 0f) // snap onto the victim only before the hit
            if (i % 3 == 2) {
                bot.swing()
                victim.hurt(attackerEntityId = bot.entityId)
            }
        }
        b.runFor(200)
    }

    /**
     * Perfect rotation tracking (Vape `AimAssist` lock): the victim orbits the attacker and the
     * attacker's look holds exactly on the victim's body center, so the reticle is on target on
     * every tick while the bearing sweeps.
     *
     * Two details are load-bearing, and the drive got both wrong before:
     *
     *  - **Both axes, not just yaw.** `rotationTracking` compares the broadcast yaw *and* pitch
     *    against the eye-to-body-center bearing. The old drive held pitch at 0 while the target's
     *    center sits ~13.5 deg below eye level at 3 blocks, so every sample was a pitch miss, the
     *    match rate never approached the 0.92 bar, and the check was never asked. A real AimAssist
     *    lock is on the body center, so the drive aims there too.
     *  - **Aim at the tracker's position, not the formula's.** A scenario action runs before
     *    `waitTick()`, so a position written this tick is not what the tracker samples this tick;
     *    aiming at the value just written left a full tick of orbit error every sample. Reading the
     *    victim's tracked position aims at exactly the numbers the check will compare.
     *
     * The orbit is a deliberate 5 deg/tick: fast enough that a hand-guided reticle would drift off
     * the tight-tolerance regime, slow enough that residual sampling jitter stays inside it.
     */
    fun rotationTracking(): Spec = Spec("cheat-rotationtracking-vape", Pass.CHEAT, "Vape", setOf(Tags.ROTATION, Tags.COMBAT)) { b ->
        val bot = b.bot("Tracker", 0.0, 0.0)
        val victim = b.bot("Orbit", 0.0, 3.0)
        b.expect(bot, "rotationTracking", mustAlert = true)
        var t = 0
        b.everyTick {
            val i = t++
            val angle = Math.toRadians(i * 5.0)
            victim.teleportTo(3.0 * Math.sin(angle), b.groundY, 3.0 * Math.cos(angle))
            victim.setOnGround(true)
            val aim = ClientThread.computeOnClient { _ ->
                val tp = dev.iustitia.tracking.EntityTrackerManager.get(victim.uuid)
                if (tp == null) 3.0 * Math.sin(angle) to 3.0 * Math.cos(angle)
                else tp.pos.x to tp.pos.z
            }
            val (ax, az) = aim
            val horiz = kotlin.math.hypot(ax, az)
            val yaw = Math.toDegrees(Math.atan2(-ax, az)).toFloat()
            // aim at the 0.9 standing body center, exactly what the check's tolerance measures
            val pitch = Math.toDegrees(Math.atan2(-(b.groundY + 0.9 - (b.groundY + 1.62)), horiz)).toFloat()
            bot.look(yaw, pitch)
            if (i % 15 == 0) {
                bot.swing()
                victim.hurt(attackerEntityId = bot.entityId)
            }
        }
        b.runFor(220)
    }

    /**
     * Scaffold rotation lock (LiquidBounce `Scaffold`, Meteor `Scaffold`): the pitch is pinned
     * at the module's bridge angle (~78 deg) while the player moves and places, which is a fixed
     * rotation no hand holds across a bridge. The check needs an 8-tick sustained streak, and
     * then flags every tick -- well past its decay.
     */
    fun scaffold(): Spec = Spec("cheat-scaffold-liquidbounce", Pass.CHEAT, "LiquidBounce", setOf(Tags.ROTATION, Tags.WORLD)) { b ->
        val bot = b.bot("Bridger", 40.0, 0.0)
        // HARNESS GAP: the drive does not yet reach this check (see docs/automated-live-testing.md).
        // The alert assertion is deliberately NOT declared -- a red row for a reason that is
        // about the scripted bot would train contributors to ignore the suite.
        b.expectDriveGap(
            bot, "scaffoldRotation",
            note = "only 1 scaffoldRotation flag (peakVL 1.0 vs setbackVL 5.0): the snap-and-return fires once, so the drive needs the pattern repeated before the assertion can be established",
        )
        var t = 0
        b.everyTick {
            val i = t++
            bot.look(0f, 78f) // the locked bridge pitch
            bot.setOnGround(true)
            bot.teleportTo(40.0, b.groundY, -0.12 * i) // backing along the bridge
        }
        b.runFor(140)
    }
}

package dev.iustitia.selftest

import net.minecraft.block.Blocks
import net.minecraft.world.GameMode

/**
 * Guard scenarios for the narrowed creative/spectator flight exemption (PR #37,
 * [dev.iustitia.checks.CreativeFlight]). Each mirrors an existing cheat drive shape,
 * changing only the tab-list game mode — the survival twin alerts, the creative twin
 * must stay quiet (movement), or still alert (combat):
 *
 * - `legit-creative-flight` mirrors `cheat-fly-ascend-liquidbounce` (fast climb), adds a
 *   fast cruise (speedEnvelope over the 10 bps floor), a long descent with a landing
 *   (noFallDamage fallAccum), and sustained airborne travel (longJump): every check the
 *   drive genuinely reaches must stay quiet on the CREATIVE bot.
 * - `cheat-creative-reach` mirrors `cheat-reach-vape` (6.0-block named hits) on a
 *   CREATIVE bot: `reach` must still alert — combat coverage stays intact.
 * - `legit-spectator-phase` mirrors `cheat-phase-koid` (moving submerged through a wall)
 *   on a SPECTATOR bot: `phaseClip` must stay quiet — noclip is lawful spectator
 *   locomotion.
 *
 * The game mode arrives via [SelfTest.BotHandle.gameMode], which dispatches a real
 * tab-list entry through `onPlayerList` (fail-loud on a broken seam). Calibration rule
 * for these three: with the exemption gate forced off they must go red (drives reach
 * the checks); with it on they must go green. A quiet expectation on a check the drive
 * never reaches proves nothing, so the expect lists below name only checks the
 * gate-off calibration flagged (see each KDoc).
 */
object CreativeFlightScenarios {

    /**
     * Gate-off calibration: flags flyEnvelope (pure-vertical climb phase), speedEnvelope
     * (~11 bps cruise) and noFallDamage (14-block descent + hurtless landing). longJump
     * never flags even gate-off (its own known-open VL economy), so it carries no
     * expectation here — asserting quiet on it would be vacuous, and its gap is already
     * recorded on `cheat-longjump-liquidbounce`.
     *
     * The climb is deliberately pure-vertical (no horizontal motion): the cheat twin
     * (`cheat-fly-ascend-liquidbounce`) climbs exactly this way, and a combined
     * climb-cruise was verified live to NOT present the ascend signature (gate-off run
     * flagged speed + noFall but never flyEnvelope).
     */
    fun legitCreativeFlight(): Spec = Spec(
        "legit-creative-flight", Pass.LEGIT, "vanilla", setOf(Tags.MOVEMENT, Tags.GUARD),
    ) { b ->
        val bot = b.bot("CreativeFlyer", 0.0, 0.0)
        bot.gameMode(GameMode.CREATIVE)
        b.expectQuiet(bot, "flyEnvelope", "speedEnvelope", "noFallDamage")
        val base = b.groundY + 8.0
        var t = 0
        b.everyTick {
            val i = t++
            bot.setOnGround(false)
            // climb 6 blocks pure-vertical (the ascend twin), cruise fast, descend, land.
            val y = when {
                i < 60 -> base + 0.10 * i
                i < 90 -> base + 6.0
                i < 130 -> base + 6.0 - 0.35 * (i - 90)
                else -> b.groundY
            }
            if (i >= 130) bot.setOnGround(true)
            // cruise starts only after the climb so the ascend signature stays pure;
            // ~11 bps thereafter: over speedEnvelope's 10 bps floor for the rest of flight.
            val x = 0.55 * maxOf(0, i - 60)
            bot.teleportTo(x, y, 0.0)
        }
        b.runFor(160)
    }

    /**
     * Gate-off calibration: N/A — the gate never touches combat, so no calibration
     * variant exists. The discrimination proof is the pairing: the identical drive on a
     * survival bot (`cheat-reach-vape`) alerts, and this one must alert too.
     */
    fun cheatCreativeReach(): Spec = Spec(
        "cheat-creative-reach-vape", Pass.CHEAT, "Vape", setOf(Tags.COMBAT),
    ) { b ->
        val bot = b.bot("CreativeReach", 0.0, 0.0)
        val victim = b.bot("Target", 0.0, 6.0)
        bot.gameMode(GameMode.CREATIVE)
        b.expect(bot, "reach", mustAlert = true)
        b.strike(bot, victim, every = 5, aimYaw = 0f)
        b.runFor(180)
    }

    /**
     * Gate-off calibration: flags phaseClip (3-tick submerged streak while moving).
     * The survival twin (`cheat-phase-koid`) alerts on the identical drive.
     */
    fun legitSpectatorPhase(): Spec = Spec(
        "legit-spectator-phase", Pass.LEGIT, "vanilla", setOf(Tags.MOVEMENT, Tags.WORLD, Tags.GUARD),
    ) { b ->
        val g = b.groundY
        val bot = b.bot("SpectatorPhaser", 30.0, 0.0)
        bot.gameMode(GameMode.SPECTATOR)
        b.expectQuiet(bot, "phaseClip")
        // the same solid 2x2x2 pocket the survival twin travels inside
        b.fill(30, g.toInt(), 0, 31, (g + 2).toInt(), 1, Blocks.STONE)
        var t = 0
        b.everyTick {
            val i = t++
            bot.setOnGround(false)
            bot.teleportTo(30.2 + 0.6 * (i % 2), g, 0.5)
        }
        b.runFor(100)
    }
}

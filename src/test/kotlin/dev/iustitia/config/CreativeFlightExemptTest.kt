package dev.iustitia.config

import com.google.gson.JsonParser
import dev.iustitia.checks.CreativeFlight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the `creativeFlightExempt` toggle wiring behind the narrowed
 * creative/spectator flight exemption (issue behind PR #26). The tab-list game-mode
 * read itself needs a live client and is covered there; what THESE pin is everything
 * around it that is separable: the exempt id sets resolve to registered checks, the
 * toggle serializes, a preset apply never overwrites it, and combat checks are not in
 * either set. No Minecraft/Fabric objects.
 */
class CreativeFlightExemptTest {

    @Test
    fun `exempt sets resolve to registered checks`() {
        val registered = IustitiaConfig().checks().map { it.first }.toSet()
        for (id in CreativeFlight.FLIGHT_EXEMPT_IDS + CreativeFlight.SPECTATOR_EXTRA_IDS) {
            assertTrue(id in registered, "exempt id \"$id\" is not a registered check")
        }
    }

    @Test
    fun `no combat check is exempt`() {
        val combat = setOf(
            "reach", "multiTarget", "clickStatistics", "throughWalls", "criticals",
            "maceSmash", "noKnockback", "keepSprint", "wTap", "jumpOnHurt", "backtrack",
            "hitsWithoutSwing", "killAura", "autoBlock", "hitFlick", "triggerbot",
        )
        val exempt = CreativeFlight.FLIGHT_EXEMPT_IDS + CreativeFlight.SPECTATOR_EXTRA_IDS
        for (id in combat) {
            assertFalse(id in exempt, "combat check \"$id\" must keep coverage on creatives")
        }
    }

    @Test
    fun `toggle serializes and defaults on`() {
        assertTrue(IustitiaConfig().creativeFlightExempt, "default must be on (the fix works out of the box)")
        val json = ConfigManager.configToJson(IustitiaConfig().apply { creativeFlightExempt = false })
        val obj = JsonParser.parseString(json).asJsonObject
        assertTrue(obj.has("creativeFlightExempt"), "toggle missing from serialized config")
        assertFalse(obj.get("creativeFlightExempt").asBoolean)
    }

    @Test
    fun `preset content never carries the toggle`() {
        val template = IustitiaConfig().apply { creativeFlightExempt = false }
        assertFalse(
            ConfigManager.presetContentObj(template).has("creativeFlightExempt"),
            "preset apply must not overwrite this environmental preference",
        )
    }

    @Test
    fun `preset apply preserves the target toggle value`() {
        val target = IustitiaConfig().apply { creativeFlightExempt = false }
        val template = PresetManager.builtIn("standard")!!
        assertTrue(ConfigManager.configFromJsonInto(ConfigManager.presetContentObj(template), target))
        assertFalse(target.creativeFlightExempt, "standard preset flipped the toggle")
    }
}

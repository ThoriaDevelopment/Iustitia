package dev.iustitia.config

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the five built-in presets ([PresetManager.builtIns] + the `builtIn` templates),
 * holding the profile table as assertions rather than as prose in a doc. No Minecraft/Fabric objects:
 * the apply under test is the exact sequence [PresetManager.apply] runs, minus the disk save and the
 * live-reload hook (template → [ConfigManager.presetContentObj] → [ConfigManager.configFromJsonInto]).
 *
 * The profiles exist to trade what gets caught against how much noise the reader wades through, and
 * every one of those trades is a config value. A scaled profile that quietly stopped scaling, a
 * "quiet" profile whose alert tier drifted back to verbose, or a `strict` that lost one of the seven
 * checks `standard` ships off would all still resolve, apply and save; only these tests notice.
 */
class PresetBuiltInsTest {

    /** Apply a built-in onto [target] the way [PresetManager.apply] does, and hand it back. */
    private fun applied(name: String, target: IustitiaConfig = IustitiaConfig()): IustitiaConfig {
        val template = PresetManager.builtIn(name) ?: error("builtIn(\"$name\") returned null")
        assertTrue(
            ConfigManager.configFromJsonInto(ConfigManager.presetContentObj(template), target),
            "the apply path refused the '$name' template",
        )
        return target
    }

    /** Every display/alert field the profiles differ on. */
    private data class Profile(
        val alertLevel: Int,
        val alertBatching: Boolean,
        val alertThrottleTicks: Int,
        val joinGraceTicks: Int,
        val audioCues: Boolean,
        val audioVolume: Double,
        val compactMode: Boolean,
        val transcriptPanel: Boolean,
        val nametagBurstPulse: Boolean,
        val lagHudIcon: Boolean,
        val sensitivitySubstrate: Boolean,
        val nametagGreenEnabled: Boolean,
        val verbose: Boolean,
    )

    /** The table, in one place: what each profile's chat lines look like and how loud they are. */
    private val profiles: Map<String, Profile> = mapOf(
        "standard" to Profile(
            alertLevel = 1, alertBatching = true, alertThrottleTicks = 40, joinGraceTicks = 600,
            audioCues = false, audioVolume = 0.6, compactMode = false, transcriptPanel = false,
            nametagBurstPulse = false, lagHudIcon = true, sensitivitySubstrate = false,
            nametagGreenEnabled = true, verbose = false,
        ),
        "lenient" to Profile(
            alertLevel = 0, alertBatching = true, alertThrottleTicks = 40, joinGraceTicks = 600,
            audioCues = false, audioVolume = 0.6, compactMode = false, transcriptPanel = false,
            nametagBurstPulse = true, lagHudIcon = false, sensitivitySubstrate = false,
            nametagGreenEnabled = true, verbose = false,
        ),
        "strict" to Profile(
            alertLevel = 1, alertBatching = true, alertThrottleTicks = 40, joinGraceTicks = 600,
            audioCues = false, audioVolume = 0.6, compactMode = true, transcriptPanel = false,
            nametagBurstPulse = false, lagHudIcon = true, sensitivitySubstrate = false,
            nametagGreenEnabled = true, verbose = false,
        ),
        "moderation" to Profile(
            alertLevel = 2, alertBatching = false, alertThrottleTicks = 20, joinGraceTicks = 100,
            audioCues = true, audioVolume = 0.8, compactMode = true, transcriptPanel = true,
            nametagBurstPulse = false, lagHudIcon = true, sensitivitySubstrate = true,
            nametagGreenEnabled = true, verbose = false,
        ),
        "debug" to Profile(
            alertLevel = 2, alertBatching = true, alertThrottleTicks = 0, joinGraceTicks = 0,
            audioCues = true, audioVolume = 0.6, compactMode = true, transcriptPanel = true,
            nametagBurstPulse = true, lagHudIcon = true, sensitivitySubstrate = true,
            nametagGreenEnabled = true, verbose = true,
        ),
    )

    /** The stock setbackVL values that are not 5.0, so the scaled ones can be held exactly. */
    private val stockOffsets: Map<String, Map<String, Double>> = mapOf(
        "strict" to mapOf(
            "reach" to 5.0, "backtrack" to 5.0, "throughWalls" to 4.0,
            "noFallDamage" to 2.0, "multiTarget" to 1.0,
        ),
        "moderation" to mapOf(
            "reach" to 7.5, "backtrack" to 7.5, "throughWalls" to 6.0,
            "noFallDamage" to 3.0, "multiTarget" to 1.5,
        ),
        "lenient" to mapOf(
            "reach" to 20.0, "backtrack" to 20.0, "throughWalls" to 16.0,
            "noFallDamage" to 8.0, "multiTarget" to 4.0,
        ),
    )

    @Test
    fun `only setbackVL scales, by the documented factor, and decay and threshold stay stock`() {
        val factors = mapOf(
            "standard" to 1.0, "lenient" to 2.0, "strict" to 0.5, "moderation" to 0.75, "debug" to 0.5,
        )
        val stock = IustitiaConfig()

        for ((name, factor) in factors) {
            val t = applied(name)
            for ((id, cc) in t.checks()) {
                val s = stock.slice(id)
                assertEquals(s.setbackVL * factor, cc.setbackVL, 1e-9, "'$name' scaled $id setbackVL to the wrong value")
                assertEquals(s.decay, cc.decay, 0.0, "'$name' changed $id decay; only setbackVL may scale between profiles")
                assertEquals(s.threshold, cc.threshold, 0.0, "'$name' changed $id threshold; only setbackVL may scale between profiles")
            }
        }
    }

    @Test
    fun `the five checks that do not default to 5_0 scale to exact decimals`() {
        // Every stock setbackVL is a short decimal, so each scaled value lands exactly on a decimal
        // rather than on a repeating fraction. A future edit that changed a stock default into
        // something like 5.1 would show up here as a drift the scaled profiles would carry silently.
        for ((name, want) in stockOffsets) {
            val t = applied(name)
            for ((id, v) in want) {
                assertEquals(v, t.slice(id).setbackVL, 0.0, "'$name' $id setbackVL")
            }
        }
    }

    @Test
    fun `standard lenient and moderation ship the declared seven checks off and everything else on`() {
        for (name in listOf("standard", "lenient", "moderation")) {
            val t = IustitiaConfig()
            // Neither direction can pass by accident: one of the seven starts ON and one check the
            // profile does not name starts OFF.
            t.slice("teleport").enabled = true
            t.slice("killAura").enabled = false
            applied(name, t)

            for ((id, cc) in t.checks()) {
                if (id in PresetManager.standardOffChecks) {
                    assertFalse(cc.enabled, "'$name' left '$id' enabled; it ships the server-normalized checks off")
                } else {
                    assertTrue(cc.enabled, "'$name' disabled '$id', which is not on standardOffChecks")
                }
            }
        }
    }

    @Test
    fun `strict and debug turn every check on`() {
        for (name in listOf("strict", "debug")) {
            val t = IustitiaConfig()
            for (id in PresetManager.standardOffChecks) t.slice(id).enabled = false
            t.slice("killAura").enabled = false
            applied(name, t)

            for ((id, cc) in t.checks()) {
                assertTrue(cc.enabled, "'$name' left '$id' disabled; this profile is meant to run every check")
            }
        }
    }

    @Test
    fun `every display and alert field matches the profile table`() {
        assertEquals(profiles.keys, PresetManager.builtInNames.toSet(), "the test table and the built-ins disagree")

        for ((name, want) in profiles) {
            val t = applied(name)
            fun fail(field: String, got: Any?) { throw AssertionError("'$name' $field = $got, expected the profile table's value") }

            if (t.alertLevel != want.alertLevel) fail("alertLevel", t.alertLevel)
            if (t.alertBatching != want.alertBatching) fail("alertBatching", t.alertBatching)
            if (t.alertThrottleTicks != want.alertThrottleTicks) fail("alertThrottleTicks", t.alertThrottleTicks)
            if (t.joinGraceTicks != want.joinGraceTicks) fail("joinGraceTicks", t.joinGraceTicks)
            if (t.audioCues != want.audioCues) fail("audioCues", t.audioCues)
            if (t.audioVolume != want.audioVolume) fail("audioVolume", t.audioVolume)
            if (t.compactMode != want.compactMode) fail("compactMode", t.compactMode)
            if (t.transcriptPanel != want.transcriptPanel) fail("transcriptPanel", t.transcriptPanel)
            if (t.nametagBurstPulse != want.nametagBurstPulse) fail("nametagBurstPulse", t.nametagBurstPulse)
            if (t.lagHudIcon != want.lagHudIcon) fail("lagHudIcon", t.lagHudIcon)
            if (t.sensitivitySubstrate != want.sensitivitySubstrate) fail("sensitivitySubstrate", t.sensitivitySubstrate)
            // Green is the nametag tier, not a chat band, and no profile turns it off.
            if (t.nametagGreenEnabled != want.nametagGreenEnabled) fail("nametagGreenEnabled", t.nametagGreenEnabled)
            if (t.verbose != want.verbose) fail("verbose", t.verbose)
        }
    }

    @Test
    fun `debug sets every boolean in its preset content to true`() {
        // "Everything on" as a walk of the serialized tree rather than 34 hand-listed fields, so a
        // config field added later is covered without touching this test. Anything false is either a
        // boolean debug forgot or a new exclusion that has to be argued for.
        val obj = JsonParser.parseString(ConfigManager.presetContentJson(PresetManager.builtIn("debug")!!)).asJsonObject
        val notTrue = mutableListOf<String>()

        fun walk(path: String, e: JsonElement) {
            if (e.isJsonObject) {
                e.asJsonObject.entrySet().forEach { (k, v) -> walk("$path/$k", v) }
            } else if (e.isJsonPrimitive && e.asJsonPrimitive.isBoolean && !e.asBoolean) {
                notTrue.add(path)
            }
        }
        obj.entrySet().forEach { (k, v) -> walk(k, v) }

        assertTrue(notTrue.isEmpty(), "debug left these booleans false: $notTrue")
    }

    @Test
    fun `debug is preset content only and leaves per-user state alone`() {
        val target = IustitiaConfig().apply {
            mutedChecks.add("reach")
            mutedPlayers.add("Someone")
            wizardCompleted = true
            persistenceEnabled = true
            playclipMode = IustitiaConfig.PlayclipMode.LEGACY
        }

        val template = PresetManager.builtIn("debug")!!
        val obj = JsonParser.parseString(ConfigManager.presetContentJson(template)).asJsonObject
        for (k in listOf("mutedChecks", "mutedPlayers", "wizardCompleted", "persistenceEnabled", "playclipMode")) {
            assertFalse(obj.has(k), "debug's preset content carries the excluded key '$k'")
        }

        assertTrue(ConfigManager.configFromJsonInto(ConfigManager.presetContentObj(template), target))
        assertEquals(listOf("reach"), target.mutedChecks)
        assertEquals(listOf("Someone"), target.mutedPlayers)
        assertTrue(target.wizardCompleted)
        assertTrue(target.persistenceEnabled)
        assertEquals(IustitiaConfig.PlayclipMode.LEGACY, target.playclipMode)
    }

    @Test
    fun `built-in metadata is consistent and resolves case-insensitively`() {
        assertEquals(
            listOf("standard", "lenient", "strict", "moderation", "debug"),
            PresetManager.builtInNames,
            "the built-ins changed order or count, which changes the listing and the help row",
        )
        assertEquals(
            PresetManager.builtIns.size,
            PresetManager.builtInNames.toSet().size,
            "two built-ins share a name",
        )
        assertEquals(
            listOf("standard"),
            PresetManager.builtIns.filter { it.recommended }.map { it.name },
            "exactly one built-in is the recommended one",
        )
        assertEquals(
            listOf("debug"),
            PresetManager.builtIns.filter { it.diagnostic }.map { it.name },
            "exactly one built-in is diagnostic",
        )
        // The wizard offers one button per non-diagnostic built-in, so this is what keeps it at four
        // buttons. It is deliberately not a fifth, and debug stays a command-only profile.
        assertEquals(4, PresetManager.builtIns.count { !it.diagnostic })

        for (b in PresetManager.builtIns) {
            assertTrue(b.blurb.isNotEmpty(), "'${b.name}' has no blurb")
            assertTrue(b.blurb.all { it.isNotBlank() }, "'${b.name}' has a blank blurb line")
            assertTrue(
                b.blurb.all { it.length <= 56 },
                "'${b.name}' has a blurb line longer than the wizard's 56-character button width: " +
                    b.blurb.filter { it.length > 56 },
            )
            assertEquals(b.name, b.label.lowercase(), "'${b.name}' label '${b.label}' is not the name in display case")
            assertTrue(PresetManager.isBuiltIn(b.name.uppercase()), "isBuiltIn is not case-insensitive for '${b.name}'")
            assertTrue(PresetManager.builtIn(b.name.uppercase()) != null, "builtIn is not case-insensitive for '${b.name}'")
        }

        assertFalse(PresetManager.isBuiltIn("no-such-preset"))
        assertEquals(null, PresetManager.builtIn("no-such-preset"))
    }

    @Test
    fun `every built-in name is refused as a custom preset name`() {
        // The refusal paths must never touch disk, so only built-in names are safe to pass here.
        for (n in PresetManager.builtInNames) {
            assertFalse(PresetManager.saveCustom(n), "saveCustom accepted the built-in name '$n'")
            assertFalse(PresetManager.saveCustom(n.uppercase()), "saveCustom accepted '${n.uppercase()}'")
            assertFalse(PresetManager.deleteCustom(n), "deleteCustom accepted the built-in name '$n'")
        }
    }

    @Test
    fun `each built-in resolves to its own object, so one apply cannot see another's deltas`() {
        // The branches are built from everyday(), which hands out a fresh config. Sharing one would
        // let an apply's deltas (or a caller's mutation of the returned template) leak into the next
        // resolve, which is exactly the kind of state a preset must not carry.
        for (n in PresetManager.builtInNames) {
            val a = PresetManager.builtIn(n)!!
            val b = PresetManager.builtIn(n)!!
            assertFalse(a === b, "builtIn(\"$n\") returned the same instance twice")
        }
        val first = PresetManager.builtIn("standard")!!
        first.slice("reach").setbackVL = 123.0
        first.alertLevel = 2
        val second = PresetManager.builtIn("standard")!!
        assertEquals(IustitiaConfig().slice("reach").setbackVL, second.slice("reach").setbackVL)
        assertEquals(1, second.alertLevel)
    }
}

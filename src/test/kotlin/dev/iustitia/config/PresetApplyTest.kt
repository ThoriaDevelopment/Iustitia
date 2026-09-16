package dev.iustitia.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the preset apply path ([PresetManager] + [ConfigManager]'s schema-derived
 * preset content). No Minecraft/Fabric objects involved — the apply sequence under test is the
 * exact one [PresetManager.apply] runs, minus the disk save and the live-reload hook:
 * template → [ConfigManager.presetContentObj] → [ConfigManager.configFromJsonInto].
 */
class PresetApplyTest {

    /** The apply path PresetManager.apply runs (without save/onConfigReloaded, which need Fabric). */
    private fun applyInto(template: IustitiaConfig, target: IustitiaConfig): Boolean =
        ConfigManager.configFromJsonInto(ConfigManager.presetContentObj(template), target)

    @Test
    fun `built-in preset applies preset content and preserves excluded keys`() {
        val target = IustitiaConfig().apply {
            alertLevel = 0
            nametagPrefixes = false
            replayCapture = false
            // per-user state that a preset must never overwrite:
            mutedChecks.add("reach")
            mutedPlayers.add("Someone")
            wizardCompleted = true
            persistenceEnabled = false
            playclipMode = IustitiaConfig.PlayclipMode.LEGACY
            // a marker on the calibration stamp: the preset must never write configVersion
            configVersion = IustitiaConfig.CONFIG_VERSION + 7
        }
        val template = PresetManager.builtIn("standard")!!

        assertTrue(applyInto(template, target))

        // preset content applied
        assertEquals(template.alertLevel, target.alertLevel)
        assertEquals(template.nametagPrefixes, target.nametagPrefixes)
        assertEquals(template.replayCapture, target.replayCapture)
        // excluded keys keep the target's values
        assertEquals(listOf("reach"), target.mutedChecks)
        assertEquals(listOf("Someone"), target.mutedPlayers)
        assertTrue(target.wizardCompleted)
        assertFalse(target.persistenceEnabled)
        assertEquals(IustitiaConfig.PlayclipMode.LEGACY, target.playclipMode)
        // configVersion is read as the migration stamp but never assigned into the target
        assertEquals(IustitiaConfig.CONFIG_VERSION + 7, target.configVersion)
    }

    @Test
    fun `standard applies its display profile and leaves check calibration at the defaults`() {
        val target = IustitiaConfig().apply {
            alertLevel = 2
            alertBatching = false
            verbose = true
            lagHudIcon = false
            slice("reach").setbackVL = 99.0
        }

        assertTrue(applyInto(PresetManager.builtIn("standard")!!, target))

        // display/alert profile landed
        assertEquals(PresetManager.builtIn("standard")!!.alertLevel, target.alertLevel)
        assertEquals(PresetManager.builtIn("standard")!!.alertBatching, target.alertBatching)
        assertFalse(target.verbose)
        // the one light visual the standard preset keeps: the lag indicator must be forced ON
        // even when the pre-apply state had it off
        assertTrue(target.lagHudIcon)
        // calibration untouched: the built-in must not scale any check's numbers
        assertEquals(IustitiaConfig().slice("reach").setbackVL, target.slice("reach").setbackVL)
        assertTrue(target.slice("wTap").enabled)
    }

    @Test
    fun `standard disables exactly the server-normalized checks and nothing else`() {
        val target = IustitiaConfig()
        // Pre-apply state where a check the profile turns OFF is on and one it leaves alone is
        // off, so neither direction can pass by accident.
        target.slice("teleport").enabled = true
        target.slice("killAura").enabled = false

        assertTrue(applyInto(PresetManager.builtIn("standard")!!, target))

        for (id in PresetManager.standardOffChecks) {
            assertFalse(
                target.slice(id).enabled,
                "standard left '$id' enabled: the everyday profile must ship the server-normalized checks off",
            )
        }
        // The other direction: `enabled` is preset content, so the apply writes it for every check
        // it knows about. Nothing outside the declared seven may end up off, and a check the profile
        // does not name comes back ON even when the pre-apply state had it off (that is what makes an
        // apply a profile rather than a patch; user mutes live in `mutedChecks`, which is excluded).
        val all = IustitiaConfig().checks().map { it.first }
        assertEquals(all.size, all.toSet().size, "duplicate check id in checks()")
        for (id in all) {
            if (id in PresetManager.standardOffChecks) continue
            assertTrue(
                target.slice(id).enabled,
                "standard left '$id' disabled, and it is not on standardOffChecks",
            )
        }
        assertTrue(target.slice("killAura").enabled, "standard did not write killAura's enabled flag")
    }

    @Test
    fun `every id on standardOffChecks is a real check`() {
        // The preset disables through slice(id), and slice() fails open to a throwaway config for an
        // unknown id, so a typo in the list would disable nothing while the tests above happily
        // agreed with it. Anchor the list to the registry.
        val registry = IustitiaConfig().checks().map { it.first }.toSet()
        for (id in PresetManager.standardOffChecks) {
            assertTrue(id in registry, "'$id' is on standardOffChecks but is not a registered check id")
        }
    }

    @Test
    fun `stale calibration stamp applies enabled but keeps the target's calibration`() {
        // A custom preset saved before a recalibration round carries an older configVersion:
        // its calibration fields must NOT re-introduce themselves, but user choices (enabled)
        // still apply.
        val stale = IustitiaConfig().apply {
            configVersion = IustitiaConfig.CONFIG_VERSION - 1
            slice("reach").enabled = false
            slice("reach").setbackVL = 9.9
            slice("reach").decay = 0.1
            slice("reach").threshold = 123.0
        }
        val target = IustitiaConfig().apply {
            slice("reach").setbackVL = 4.2
            slice("reach").decay = 0.3
            slice("reach").threshold = 3.5
        }

        assertTrue(applyInto(stale, target))

        assertFalse(target.slice("reach").enabled) // non-calibration preference applied
        assertEquals(4.2, target.slice("reach").setbackVL) // stale calibration NOT applied
        assertEquals(0.3, target.slice("reach").decay)
        assertEquals(3.5, target.slice("reach").threshold)
    }

    @Test
    fun `current-stamp preset applies its calibration`() {
        val target = IustitiaConfig().apply { slice("reach").setbackVL = 99.0 }
        val template = IustitiaConfig().apply { slice("reach").setbackVL = 3.3 }

        assertTrue(applyInto(template, target))

        assertEquals(3.3, target.slice("reach").setbackVL)
    }

    @Test
    fun `absent fields keep the target's values (older preset file tolerance)`() {
        val target = IustitiaConfig().apply { replayCapture = false }
        val obj = com.google.gson.JsonObject().apply { addProperty("alertLevel", 2) }

        assertTrue(ConfigManager.configFromJsonInto(obj, target))

        assertEquals(2, target.alertLevel) // present → applied
        assertFalse(target.replayCapture) // absent → keeps the target's value
    }

    @Test
    fun `presetContentJson writes preset content only but keeps the configVersion stamp`() {
        val json = ConfigManager.presetContentJson(IustitiaConfig().apply {
            mutedPlayers.add("Secret")
            mutedChecks.add("reach")
            wizardCompleted = true
            persistenceEnabled = false
            playclipMode = IustitiaConfig.PlayclipMode.LEGACY
        })
        val obj = com.google.gson.JsonParser.parseString(json).asJsonObject

        assertFalse(obj.has("mutedPlayers"))
        assertFalse(obj.has("mutedChecks"))
        assertFalse(obj.has("wizardCompleted"))
        assertFalse(obj.has("persistenceEnabled"))
        assertFalse(obj.has("playclipMode"))
        // the calibration-migration stamp survives, so older builds can still apply the file
        assertEquals(IustitiaConfig.CONFIG_VERSION, obj.get("configVersion").asInt)
        // preset content is intact
        assertTrue(obj.has("alertLevel"))
        assertTrue(obj.has("reach"))
    }

    @Test
    fun `saved preset file round-trips through the apply path`() {
        val live = IustitiaConfig().apply {
            alertLevel = 2
            slice("reach").setbackVL = 2.75
        }
        val fileObj = com.google.gson.JsonParser.parseString(ConfigManager.presetContentJson(live)).asJsonObject
        val target = IustitiaConfig()

        assertTrue(ConfigManager.configFromJsonInto(fileObj, target))

        assertEquals(live.alertLevel, target.alertLevel)
        assertEquals(2.75, target.slice("reach").setbackVL)
    }

    @Test
    fun `custom preset named like a built-in is refused at save time`() {
        assertFalse(PresetManager.saveCustom("STANDARD"))
        assertFalse(PresetManager.deleteCustom("standard"))
        assertTrue(PresetManager.isBuiltIn("Standard"))
        assertFalse(PresetManager.isBuiltIn("Debug"))
    }

    @Test
    fun `a built-in name with surrounding whitespace is refused too (name normalized before the guard)`() {
        // "standard " sanitizes to "standard" — it must hit the built-in guard, not write a dead
        // shadow file that the built-in always shadows on apply. (Only built-in names are used
        // here: a non-built-in name would hit the real preset dir, and these refusal paths must
        // never touch disk.)
        assertFalse(PresetManager.saveCustom("  STANDARD  "))
        assertFalse(PresetManager.deleteCustom(" Standard "))
    }

    @Test
    fun `a mistyped preset value throws from the honest read path`() {
        // A hand-edited preset with a wrong-typed value must fail the read (not be silently
        // swallowed into a half-read config that then applies as "success"). A JsonArray where
        // a double is expected is a guaranteed throw on every Gson version.
        val obj = com.google.gson.JsonParser
            .parseString("""{"enabled": true, "audioVolume": [1, 2]}""")
            .asJsonObject

        kotlin.test.assertFails { ConfigManager.configFromJsonInto(obj, IustitiaConfig()) }
    }

    @Test
    fun `a throwing read never leaves the live config half-applied (apply's scratch pattern)`() {
        // PresetManager.apply reads into a scratch config first and promotes only a completed
        // read. A read that throws mid-schema must leave the caller's live config untouched.
        val live = IustitiaConfig().apply {
            enabled = false
            alertLevel = 1
            audioVolume = 0.3
        }
        val obj = com.google.gson.JsonParser
            .parseString("""{"enabled": true, "audioVolume": [1, 2]}""")
            .asJsonObject

        val scratch = IustitiaConfig()
        kotlin.test.assertFails { ConfigManager.configFromJsonInto(obj, scratch) }

        // The failed read mutated only the scratch: the live config is exactly as it was.
        assertFalse(live.enabled)
        assertEquals(1, live.alertLevel)
        assertEquals(0.3, live.audioVolume)

        // ...and the promotion path from a completed scratch read lands on the live config:
        val good = IustitiaConfig().apply { alertLevel = 2 }
        val goodScratch = IustitiaConfig()
        ConfigManager.configFromJsonInto(ConfigManager.presetContentObj(good), goodScratch)
        assertTrue(ConfigManager.configFromJsonInto(ConfigManager.presetContentObj(goodScratch), live))
        assertEquals(2, live.alertLevel)
    }

    @Test
    fun `preset file round-trip through the scratch pattern preserves tuned values`() {
        // The double serialization apply() now runs (template → scratch → live) must not
        // lose the template's values along the way.
        val template = IustitiaConfig().apply {
            alertLevel = 2
            slice("reach").setbackVL = 2.75
        }
        val scratch = IustitiaConfig()
        val live = IustitiaConfig()

        ConfigManager.configFromJsonInto(ConfigManager.presetContentObj(template), scratch)
        ConfigManager.configFromJsonInto(ConfigManager.presetContentObj(scratch), live)

        assertEquals(template.alertLevel, live.alertLevel)
        assertEquals(2.75, live.slice("reach").setbackVL)
        // exclusions survive the second round-trip too
        assertFalse(com.google.gson.JsonParser
            .parseString(ConfigManager.presetContentJson(scratch)).asJsonObject.has("playclipMode"))
    }

    @Test
    fun `removed nuclear-cue key is ignored on load and dropped on save`() {
        // audioNuclear (the opt-in "nuclear" red cue) was removed; an existing config file that
        // still carries the key must keep loading (the conditional read skips unknown keys) and
        // the next save must no longer write it — no CONFIG_VERSION bump, since it is not a
        // check-calibration field.
        val target = IustitiaConfig().apply { audioCues = true }
        val legacy = com.google.gson.JsonObject().apply { addProperty("audioNuclear", true) }
        assertTrue(ConfigManager.configFromJsonInto(legacy, target))
        assertTrue(target.audioCues)

        // The re-serialized config has no audioNuclear key (unknown keys never round-trip).
        assertFalse(com.google.gson.JsonParser
            .parseString(ConfigManager.configToJson(target)).asJsonObject.has("audioNuclear"))
    }
}
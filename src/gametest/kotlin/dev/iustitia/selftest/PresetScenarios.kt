package dev.iustitia.selftest

import dev.iustitia.config.ConfigManager
import dev.iustitia.config.PresetManager
import dev.iustitia.selftest.SelfTest.BotHandle

/**
 * Preset / config-management scenarios. Presets are observer tooling (the `/ius preset`
 * control surface), so they ride in the REPLAY pass group — they assert configuration
 * mechanics, not detector verdicts, and restore every field they touch.
 *
 * These exist because the preset feature's two confirmed audit findings were both
 * *silently* wrong in ways no other scenario could see:
 *
 *  1. **The disabled-check gate** — combat checks are bus-driven (each subscribes to
 *     AttackEvent/SwingSignal in its own init), so the tick loop's `if (!c.enabled)`
 *     never reached them and a toggled-off combat check kept flagging while `/ius toggle`
 *     reported OFF. The gate now lives at the `Check.flag` chokepoint; [PresetDisabledGate]
 *     proves a disabled check produces **zero** VL accumulation and re-arms when enabled.
 *  2. **Schema-derived apply** — the old hand-written `copyPresetContent` list had drifted
 *     13 fields behind the config schema (replay/clip/chathist + the detection-affecting
 *     `sensitivitySubstrate`), so applies silently skipped them. The apply path now flows
 *     through the config serializer itself; [PresetApplyCoverage] proves the formerly-drifted
 *     fields are covered and the documented exclusions (`playclipMode`, mutes, persistence…)
 *     still hold.
 */
object PresetScenarios {

    /**
     * A disabled check must be fully inert: no VL accumulation, no alert — and the same
     * drive must alert again once the check is re-enabled. Drive = the Vape-tier blatant
     * reach (6.0 blocks, the strongest reach drive in the suite) so phase 2's expectation
     * is generous while phase 1's silence can only be explained by the gate, not by a
     * weak drive.
     */
    class PresetDisabledGate : SelfTest.Scenario("preset-disabled-check-gate", "REPLAY") {
        override fun run(b: SelfTest.ScenarioBuilder) {
            val bot = b.bot("Reach", 0.0, 0.0)
            val victim = b.bot("Target", 0.0, 6.0)
            val id = "reach"

            // Save + clear the slice (in place — slice() returns the live CheckConfig).
            val saved = ClientThread.computeOnClient { _ ->
                val cc = ConfigManager.config.slice(id)
                listOf(cc.enabled, cc.setbackVL, cc.decay, cc.threshold)
            }

            // ---- phase 1: check disabled, blatant drive runs ----
            ClientThread.runOnClient { _ ->
                val cc = ConfigManager.config.slice(id)
                cc.enabled = false
                cc.setbackVL = 1.0   // maximally sensitive: any flag would alert immediately,
                cc.decay = 0.0       // so silence here can ONLY be the disabled gate
            }
            // The strike drive runs through BOTH phases (only the enabled flag flips between
            // them) — a drive that stops at the phase boundary would make phase 2's silence a
            // harness gap, not a gate assertion.
            var t = 0
            b.everyTick {
                if (t % 5 == 0) bot.strike(victim)
                t++
            }
            b.runFor(100)
            val peakDisabled = ClientThread.computeOnClient { _ ->
                SelfTestHooks.peakVlFor(bot.uuid)[id] ?: 0.0
            }
            check(peakDisabled <= 0.0) {
                "PRESET/GATE failure: check '$id' accumulated VL to $peakDisabled while its " +
                    "config slice was disabled (setbackVL=1, decay=0, so any flag would show). " +
                    "The Check.flag disabled-check gate is broken: a toggled-off check is " +
                    "flagging/alerting behind /ius toggle's back."
            }

            // ---- phase 2: same drive, check re-enabled -> must alert ----
            ClientThread.runOnClient { _ ->
                val cc = ConfigManager.config.slice(id)
                cc.enabled = true
                cc.setbackVL = saved[1] as Double
                cc.decay = saved[2] as Double
                cc.threshold = saved[3] as Double
            }
            b.runFor(200)
            val alertedAfter = ClientThread.computeOnClient { _ ->
                id in SelfTestHooks.alertedChecksFor(bot.uuid)
            }
            check(alertedAfter) {
                val peak = ClientThread.computeOnClient { _ -> SelfTestHooks.peakVlFor(bot.uuid)[id] ?: 0.0 }
                "PRESET/GATE failure: check '$id' never alerted after being re-enabled " +
                    "(peakVL=$peak over 150 ticks of a 6-block reach drive that alerts the " +
                    "stock check well inside 180). The gate may be latching (never releasing) " +
                    "or the re-enable did not propagate."
            }

            // ---- restore the slice exactly ----
            ClientThread.runOnClient { _ ->
                val cc = ConfigManager.config.slice(id)
                cc.enabled = saved[0] as Boolean
                cc.setbackVL = saved[1] as Double
                cc.decay = saved[2] as Double
                cc.threshold = saved[3] as Double
            }
        }
    }

    /**
     * Applying a built-in preset must drive detection state through the schema-derived
     * path: the formerly-drifted fields (replay/clip/chathist + `sensitivitySubstrate`)
     * are now preset content, the documented exclusions (`playclipMode`, mutes,
     * `persistenceEnabled`, `wizardCompleted`) still hold, and Standard's display profile
     * lands on the config while Standard's check slices stay at stock calibration (the
     * scaled profiles are [PresetBuiltInCoverage]'s job). The check
     * `enabled` flags are asserted in both directions: exactly the seven on
     * [dev.iustitia.config.PresetManager.standardOffChecks] go off and every other check
     * stays on. That is the invariant this scenario exists to hold now that the built-in
     * ships a detection-scope choice: a preset that silently disabled an eighth check, or
     * one that let the seven drift back on, would be invisible everywhere else.
     *
     * The tail of this scenario re-applies `standard` and saves, which is how the run-dir
     * config comes back to the built-in baseline. It is no longer what protects the LATER
     * scenarios in the same boot: `TestConfigSnapshot.restore()` now puts the captured
     * preset content back (calibration + display fields), from a `finally`, so the tail is
     * about leaving a sensible file on disk rather than about isolation.
     */
    class PresetApplyCoverage : SelfTest.Scenario("preset-apply-coverage", "REPLAY") {
        override fun run(b: SelfTest.ScenarioBuilder) {
            // Stand-in pre-apply user state, chosen to catch BOTH failure modes:
            //  - a field the old copy list dropped (clipEntities=false, chathistEnabled=false,
            //    sensitivitySubstrate=true) must be OVERWRITTEN by the apply (preset content);
            //  - an excluded field (playclipMode=LEGACY, a muted check, persistence on)
            //    must SURVIVE the apply (not preset content).
            val saved = ClientThread.computeOnClient { _ ->
                val c = ConfigManager.config
                // global fields the scenario touches (restore list)
                listOf(
                    "playclipMode" to c.playclipMode,
                    "clipEntities" to c.clipEntities,
                    "chathistEnabled" to c.chathistEnabled,
                    "sensitivitySubstrate" to c.sensitivitySubstrate,
                    "persistenceEnabled" to c.persistenceEnabled,
                    "mutedChecks" to c.mutedChecks.toList(),
                )
            }
            ClientThread.runOnClient { _ ->
                val c = ConfigManager.config
                c.playclipMode = dev.iustitia.config.IustitiaConfig.PlayclipMode.LEGACY
                c.clipEntities = false
                c.chathistEnabled = false
                c.sensitivitySubstrate = true
                c.persistenceEnabled = true
                c.mutedChecks.add("killAura")
            }

            val ok = ClientThread.computeOnClient { _ -> PresetManager.apply("standard") }
            check(ok) { "PRESET/APPLY failure: PresetManager.apply(\"standard\") returned false." }

            ClientThread.computeOnClient { _ ->
                val c = ConfigManager.config
                fun fail(msg: String): Nothing = throw ScenarioFailed("PRESET/APPLY failure: $msg")

                // (a) formerly-drifted fields ARE preset content now — the apply overwrote them.
                if (c.clipEntities) { /* default-true template over the false live value = applied */ }
                else fail("clipEntities was not overwritten by the apply — the replay-field drift is back (a hand list is winning again).")
                if (!c.chathistEnabled) fail("chathistEnabled was not overwritten by the apply — chathist fields are drifting out of preset content.")
                if (c.sensitivitySubstrate) fail("sensitivitySubstrate stayed at its pre-apply value — a DETECTION-affecting field has dropped out of preset content.")

                // (b) exclusions hold: user state survived the apply untouched.
                if (c.playclipMode != dev.iustitia.config.IustitiaConfig.PlayclipMode.LEGACY)
                    fail("playclipMode was overwritten by the preset apply — it is documented as user-controlled and excluded.")
                if ("killAura" !in c.mutedChecks)
                    fail("mutedChecks was cleared by the preset apply — mute lists are documented as not-touched.")
                if (!c.persistenceEnabled)
                    fail("persistenceEnabled was overwritten by the preset apply — persistence is documented as not-touched.")

                // (c) standard semantics: the display profile landed; the server-normalized checks
                //     are DISABLED by the built-in and nothing else is; standard's calibration is
                //     not scaled (the four scaled profiles are asserted in PresetBuiltInCoverage).
                if (ConfigManager.config.alertLevel != 1)
                    fail("standard alertLevel did not land on the live config (want 1).")
                val off = dev.iustitia.config.PresetManager.standardOffChecks
                for ((id, cc) in ConfigManager.config.checks()) {
                    if (id in off) {
                        if (cc.enabled) fail("standard left '$id' enabled; the everyday profile ships the server-normalized checks off.")
                    } else if (!cc.enabled) {
                        fail("standard disabled '$id', which is not on standardOffChecks; the profile must turn off exactly the declared seven.")
                    }
                }
                if (off.size != 7) fail("standardOffChecks has ${off.size} entries, expected 7.")
                val want = dev.iustitia.config.IustitiaConfig().slice("reach").setbackVL
                val got = ConfigManager.config.slice("reach").setbackVL
                if (kotlin.math.abs(got - want) > 1e-6)
                    fail("standard touched reach setbackVL: got $got, stock default $want. Standard ships stock calibration; only lenient/strict/moderation/debug scale setbackVL.")
            }

            // ---- restore pre-apply user state ----
            ClientThread.runOnClient { _ ->
                val c = ConfigManager.config
                for ((k, v) in saved) {
                    when (k) {
                        "playclipMode" -> c.playclipMode = v as dev.iustitia.config.IustitiaConfig.PlayclipMode
                        "clipEntities" -> c.clipEntities = v as Boolean
                        "chathistEnabled" -> c.chathistEnabled = v as Boolean
                        "sensitivitySubstrate" -> c.sensitivitySubstrate = v as Boolean
                        "persistenceEnabled" -> c.persistenceEnabled = v as Boolean
                        "mutedChecks" -> {
                            c.mutedChecks.clear()
                            @Suppress("UNCHECKED_CAST")
                            c.mutedChecks.addAll(v as List<String>)
                        }
                    }
                }
                // Re-apply the standard profile so the post-scenario config is back on the
                // built-in baseline; save() mirrors what an apply does (debounced, off-thread).
                try { PresetManager.apply("standard") } catch (_: Throwable) {}
                ConfigManager.save()
            }
        }
    }

    /**
     * Every built-in preset must reach the LIVE config with the values `PresetBuiltInsTest` holds:
     * the check-enable contract, the `setbackVL` ratio with `decay`/`threshold` untouched, the alert
     * tier, and a display canary set.
     *
     * [PresetApplyCoverage] proves one built-in travels the schema-derived path and that the
     * exclusions hold. This scenario covers the part that would fail silently: a profile that landed
     * its display flags but not its scaling, or a scaled profile whose factor was mistyped, still
     * resolves, applies and saves, and every downstream assertion in the suite runs at whatever
     * sensitivity leaked out of it. The full 13-field table stays in the JVM test; the canaries here
     * exist to prove the values actually arrive on the live config object.
     *
     * The config is restored from its own captured preset content in a `finally`, and saved, so a
     * failure mid-run cannot leave the run-dir `iustitia.json` holding a scaled profile. The harness
     * `TestConfigSnapshot` restore would fix it in memory but writes nothing.
     */
    class PresetBuiltInCoverage : SelfTest.Scenario("preset-builtin-coverage", "REPLAY") {

        /** What a live apply of one built-in must land. [checksOff] means "ships the seven on
         *  [dev.iustitia.config.PresetManager.standardOffChecks] disabled"; the rest are canaries. */
        private data class Want(
            val factor: Double,
            val checksOff: Boolean,
            val alertLevel: Int,
            val compactMode: Boolean,
            val lagHudIcon: Boolean,
            val nametagBurstPulse: Boolean,
            val audioCues: Boolean,
            val transcriptPanel: Boolean,
            val joinGraceTicks: Int,
            val alertThrottleTicks: Int,
            val sensitivitySubstrate: Boolean,
        )

        private val wanted: Map<String, Want> = mapOf(
            "standard" to Want(1.0, true, 1, false, true, false, false, false, 600, 40, false),
            "lenient" to Want(2.0, true, 0, false, false, true, false, false, 600, 40, false),
            "strict" to Want(0.5, false, 1, true, true, false, false, false, 600, 40, false),
            "moderation" to Want(0.75, true, 2, true, true, false, true, true, 100, 20, true),
            "debug" to Want(0.5, false, 2, true, true, true, true, true, 0, 0, true),
        )

        override fun run(b: SelfTest.ScenarioBuilder) {
            // The live config's own preset content, restored in the finally below.
            val saved = ClientThread.computeOnClient { _ -> ConfigManager.presetContentJson(ConfigManager.config) }
            // Other subsystems hold the config REFERENCE, so an apply has to mutate the object
            // rather than swap a new one in. Compared by identity, not by value.
            val instance = ClientThread.computeOnClient { _ -> ConfigManager.config }

            try {
                ClientThread.computeOnClient { _ ->
                    fun fail(msg: String): Nothing = throw ScenarioFailed("PRESET/BUILTIN failure: $msg")
                    val stock = dev.iustitia.config.IustitiaConfig()

                    for (name in PresetManager.builtInNames) {
                        val want = wanted[name]
                            ?: fail("built-in '$name' has no expectation in this scenario; add it rather than let a new profile go unverified")
                        if (!PresetManager.apply(name)) fail("PresetManager.apply(\"$name\") returned false")

                        val c = ConfigManager.config
                        for ((id, cc) in c.checks()) {
                            val s = stock.slice(id)
                            val wantVl = s.setbackVL * want.factor
                            if (kotlin.math.abs(cc.setbackVL - wantVl) > 1e-9)
                                fail("'$name' left $id setbackVL at ${cc.setbackVL}, expected $wantVl (stock ${s.setbackVL} x${want.factor})")
                            if (cc.decay != s.decay || cc.threshold != s.threshold)
                                fail("'$name' moved $id decay/threshold to ${cc.decay}/${cc.threshold} (stock ${s.decay}/${s.threshold}); only setbackVL differs between profiles")
                            val wantEnabled = if (want.checksOff) id !in PresetManager.standardOffChecks else true
                            if (cc.enabled != wantEnabled)
                                fail("'$name' left $id enabled=${cc.enabled}, expected $wantEnabled")
                        }

                        if (c.alertLevel != want.alertLevel) fail("'$name' alertLevel=${c.alertLevel}, expected ${want.alertLevel}")
                        if (c.compactMode != want.compactMode) fail("'$name' compactMode=${c.compactMode}, expected ${want.compactMode}")
                        if (c.lagHudIcon != want.lagHudIcon) fail("'$name' lagHudIcon=${c.lagHudIcon}, expected ${want.lagHudIcon}")
                        if (c.nametagBurstPulse != want.nametagBurstPulse) fail("'$name' nametagBurstPulse=${c.nametagBurstPulse}, expected ${want.nametagBurstPulse}")
                        if (c.audioCues != want.audioCues) fail("'$name' audioCues=${c.audioCues}, expected ${want.audioCues}")
                        if (c.transcriptPanel != want.transcriptPanel) fail("'$name' transcriptPanel=${c.transcriptPanel}, expected ${want.transcriptPanel}")
                        if (c.joinGraceTicks != want.joinGraceTicks) fail("'$name' joinGraceTicks=${c.joinGraceTicks}, expected ${want.joinGraceTicks}")
                        if (c.alertThrottleTicks != want.alertThrottleTicks) fail("'$name' alertThrottleTicks=${c.alertThrottleTicks}, expected ${want.alertThrottleTicks}")
                        if (c.sensitivitySubstrate != want.sensitivitySubstrate) fail("'$name' sensitivitySubstrate=${c.sensitivitySubstrate}, expected ${want.sensitivitySubstrate}")
                        // Green is the nametag tier, not a chat band; no profile may drop it.
                        if (!c.nametagGreenEnabled) fail("'$name' turned the green nametag tick off; green is the nametag tier and every profile keeps it")
                    }

                    // An unknown name applies nothing at all, and reports that it did not.
                    val beforeName = ConfigManager.config.alertLevel
                    val beforeVl = ConfigManager.config.slice("reach").setbackVL
                    if (PresetManager.apply("no-such-preset-xyz")) fail("apply() reported success for a name that is not a preset")
                    if (ConfigManager.config.alertLevel != beforeName || ConfigManager.config.slice("reach").setbackVL != beforeVl)
                        fail("a FAILED apply still changed the live config (alertLevel $beforeName -> ${ConfigManager.config.alertLevel}, reach setbackVL $beforeVl -> ${ConfigManager.config.slice("reach").setbackVL})")
                }

                val after = ClientThread.computeOnClient { _ -> ConfigManager.config }
                check(after === instance) {
                    "PRESET/BUILTIN failure: the live config instance changed across preset applies. " +
                        "Subsystems hold that reference, so an apply must mutate it in place; a swapped " +
                        "instance means half the running mod is now reading a config nobody updates."
                }
            } finally {
                // Put the run-dir config back the way this scenario found it. The harness snapshot
                // restore covers the in-memory config from a finally of its own; this covers the file.
                try {
                    ClientThread.runOnClient { _ ->
                        ConfigManager.configFromJsonInto(
                            com.google.gson.JsonParser.parseString(saved).asJsonObject,
                            ConfigManager.config,
                        )
                        ConfigManager.save()
                    }
                } catch (_: Throwable) {}
            }
        }
    }
}

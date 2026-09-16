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
     * lands on the config while the check slices stay at stock calibration.
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

                // (c) standard semantics: display profile landed, and the check slices stay at
                //     stock calibration — no setbackVL scaling, no disabled checks.
                if (ConfigManager.config.alertLevel != 1)
                    fail("standard alertLevel did not land on the live config (want 1).")
                if (!ConfigManager.config.slice("wTap").enabled)
                    fail("standard disabled wTap — built-ins must not disable checks.")
                val want = dev.iustitia.config.IustitiaConfig().slice("reach").setbackVL
                val got = ConfigManager.config.slice("reach").setbackVL
                if (kotlin.math.abs(got - want) > 1e-6)
                    fail("standard touched reach setbackVL: got $got, stock default $want — built-ins must not scale calibration.")
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
}

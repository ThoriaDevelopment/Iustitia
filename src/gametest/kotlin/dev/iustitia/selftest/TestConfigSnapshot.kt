package dev.iustitia.selftest

import dev.iustitia.config.ConfigManager
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.exempt.Exemptions
import java.util.UUID

/**
 * A captured snapshot of the live config plus a quiet default profile, applied before
 * a scenario runs. Scenarios MUST run detection under known-good settings -- a
 * contributor's local config (a 600-tick join grace, an applied preset, muted checks,
 * batching) would silently corrupt both the false-positive and the bypass assertions.
 *
 * [restore] puts the exact previous state back, so running the harness never changes
 * the developer's client config. The harness temporarily sets:
 *
 * - `joinGraceTicks = 0` -- bots "join" when they spawn; a 30s grace would suppress
 *   every alert in a 200-tick scenario.
 * - `alertThrottleTicks = 0` -- each crossing is independently observable.
 * - `alertBatching = false` -- no quiet-window flush dependency in assertions.
 * - `alertsEnabled = true` -- a contributor who silenced chat must not lose the alert tap.
 * - **every check's `enabled` flag = true** -- the bypass assertions are the reason. Nine cheat
 *   scenarios require an alert from a check the `standard` preset ships disabled
 *   (`flyEnvelope`, `teleport` x2, `noFallDamage` x3, `phaseClip`, `hitsWithoutSwing` x2), and
 *   the first-launch wizard's General button applies exactly that preset. Without this a
 *   contributor who picked General (or toggled a check off by hand) would read nine confident
 *   "BYPASS" rows for drives that work. The flags are restored in [restore], so a run still
 *   leaves the developer's config as it found it.
 * - `persistenceEnabled = false` -- the harness never touches the roaming store.
 * - `wizardCompleted = true` -- the first-launch setup wizard otherwise opens
 *   `SetupWizardScreen` the instant the world joins, which blocks the client
 *   gametest framework's world-join predicate until it times out (verified live:
 *   the run failed with "Timed out waiting for predicate" and the wizard screen in
 *   the log). Setting the flag is exactly what the wizard itself does on first open.
 *
 * [restore] also puts back the whole PRESET CONTENT of the config it captured, parsed fresh and read
 * back through `ConfigManager.configFromJsonInto`. That is the backstop for every field the explicit
 * list above does not name, and the two that matter are per-check calibration and the display
 * fields: a scenario that applies a scaled preset (`lenient` doubles every `setbackVL`) would
 * otherwise leave every LATER scenario in the same boot running at the wrong sensitivity, and one
 * that applies `debug` would leave the wrong transcript/HUD/replay toggles on. It runs from
 * [SelfTest.runScenario]'s `finally`, so it holds even when a scenario throws before reaching its own
 * restore code. A capture that fails leaves the backstop null and the explicit list still runs.
 *
 * The snapshot also clears and restores the session exemption list, because an
 * exempted player is invisible to every check at the `Check.flag` chokepoint and
 * would silently empty both passes.
 *
 * `ConfigManager.config`'s setter is private by design (the live reference must not
 * be swapped out from under debounced saves), so the harness mutates fields in place
 * on the client thread -- the same thread YACL edits run on.
 */
class TestConfigSnapshot private constructor(
    private val savedFields: Map<String, Any?>,
    private val savedExemptions: List<Pair<UUID, String>>,
    private val savedPresetContent: String?,
) {
    companion object {
        /** Capture the live config, stash the harness-touched fields, and apply the profile. */
        fun captureAndApply(): TestConfigSnapshot {
            lateinit var snap: TestConfigSnapshot
            ClientThread.runOnClient { _ ->
                val c = ConfigManager.config
                val saved = mapOf<String, Any?>(
                    "joinGraceTicks" to c.joinGraceTicks,
                    "alertThrottleTicks" to c.alertThrottleTicks,
                    "alertBatching" to c.alertBatching,
                    "persistenceEnabled" to c.persistenceEnabled,
                    "alertsEnabled" to c.alertsEnabled,
                    "verbose" to c.verbose,
                    "wizardCompleted" to c.wizardCompleted,
                    "replayCapture" to c.replayCapture,
                    // Per-check enabled flags: restored in restore(). Keyed by id so a check added
                    // later is covered without touching this map.
                    "checkEnabled" to c.checks().associate { (id, cc) -> id to cc.enabled },
                )
                val exemptions = Exemptions.all()
                Exemptions.clear()
                // The whole preset content, for restore()'s backstop: per-check calibration and the
                // display fields, none of which the explicit map above covers.
                val presetContent = try { ConfigManager.presetContentJson(c) } catch (_: Throwable) { null }
                snap = TestConfigSnapshot(saved, exemptions, presetContent)
                c.joinGraceTicks = 0
                c.alertThrottleTicks = 0
                c.alertBatching = false
                c.persistenceEnabled = false
                c.alertsEnabled = true
                // Every check ON: an applied preset (standard ships seven checks off) or a manual
                // /ius toggle would otherwise read as a bypass on a drive that works. See the
                // class doc.
                c.checks().forEach { (_, cc) -> cc.enabled = true }
                // Verbose is normally off (the flag tap is what the report reads), but the
                // calibration loop needs the *sub-flag label* + measured value behind a false
                // positive or a bypass -- set -Pselftest.verbose (live_selftest.py
                // --verbose-log) and the game log carries every flag line.
                c.verbose = System.getProperty("iustitia.selftest.verbose") == "1"
                // The replay pass asserts the rolling buffer fills; a contributor who turned
                // capture off must not get a confusing "capture is broken" failure.
                c.replayCapture = true
                c.wizardCompleted = true // keep the setup wizard off the world-join path
            }
            return snap
        }
    }

    /** Put the developer's config + exemptions back exactly as they were. */
    fun restore() {
        ClientThread.runOnClient { _ ->
            val c = ConfigManager.config
            c.joinGraceTicks = savedFields["joinGraceTicks"] as Int
            c.alertThrottleTicks = savedFields["alertThrottleTicks"] as Int
            c.alertBatching = savedFields["alertBatching"] as Boolean
            c.persistenceEnabled = savedFields["persistenceEnabled"] as Boolean
            c.alertsEnabled = savedFields["alertsEnabled"] as Boolean
            c.verbose = savedFields["verbose"] as Boolean
            c.wizardCompleted = savedFields["wizardCompleted"] as Boolean
            c.replayCapture = savedFields["replayCapture"] as Boolean
            @Suppress("UNCHECKED_CAST")
            val savedEnabled = savedFields["checkEnabled"] as Map<String, Boolean>
            c.checks().forEach { (id, cc) -> cc.enabled = savedEnabled[id] ?: cc.enabled }
            // Backstop for everything the explicit lines above do not name: per-check calibration
            // (setbackVL/decay/threshold) and the display fields. A scenario that applies a scaled
            // preset would otherwise leave every later scenario in this boot at the wrong
            // sensitivity. configFromJsonInto MUTATES the JsonObject it is handed (it strips the
            // excluded keys), so this parses a fresh one rather than reusing a cached object.
            savedPresetContent?.let { json ->
                try {
                    ConfigManager.configFromJsonInto(
                        com.google.gson.JsonParser.parseString(json).asJsonObject,
                        c,
                    )
                } catch (_: Throwable) {}
            }
            Exemptions.clear()
            savedExemptions.forEach { (uuid, name) -> Exemptions.load(uuid, name) }
        }
    }
}

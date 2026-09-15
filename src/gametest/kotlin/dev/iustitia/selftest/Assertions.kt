package dev.iustitia.selftest

import dev.iustitia.history.FlagHistory
import dev.iustitia.selftest.SelfTest.BotHandle

/**
 * Assertion helpers for the two passes. Every failure message is written to be
 * pasted into a PR: it names the scenario, the expected evidence, and what was
 * actually observed (peak VLs, alerted checks).
 *
 * The two core verbs mirror the project's verification philosophy:
 *
 * - [expectAlert] -- the cheat pass: "a cheating bot MUST trip this check."
 *   A missing alert is a bypass.
 * - [expectNoAlert] -- the legit pass: "a vanilla-accurate bot MUST NOT trip this
 *   check." A fired alert is a false positive.
 */
class ScenarioFailed(message: String) : RuntimeException(message)

class Assertions(private val scenarioName: String) {

    /** The cheating bot must have alerted [checkId]. */
    fun expectAlert(bot: BotHandle, checkId: String) {
        val alerted = SelfTestHooks.alertedChecksFor(bot.uuid)
        if (checkId !in alerted) {
            val vls = SelfTestHooks.peakVlFor(bot.uuid)
            throw ScenarioFailed(
                "BYPASS in $scenarioName: bot '${bot.name}' did not alert '$checkId'.\n" +
                    "  alerted: ${alerted.sorted()}\n" +
                    "  peakVL:  $vls\n" +
                    "  If the check is behaviorally correct but the scenario under-drives it, " +
                    "raise the scenario's intensity (documented per scenario in the coverage table); " +
                    "if the check misses a blatant pattern, fix the detector, not the scenario."
            )
        }
    }

    /** The legit bot must not have alerted [checkId]. */
    fun expectNoAlert(bot: BotHandle, checkId: String) {
        val alerted = SelfTestHooks.alertedChecksFor(bot.uuid)
        if (checkId in alerted) {
            val vl = SelfTestHooks.peakVlFor(bot.uuid)[checkId]
            throw ScenarioFailed(
                "FALSE POSITIVE in $scenarioName: bot '${bot.name}' alerted '$checkId' (peakVL=$vl).\n" +
                    "  alerted: ${alerted.sorted()}\n" +
                    "  The legit pass must stay silent. If a guard (lag/teleport/chunk) is missing, " +
                    "add it to the check; if the scenario accidentally drives cheat-like behavior, fix the scenario."
            )
        }
    }

    /** The bot's nametag tier must be [expected] at assertion time. */
    fun expectTier(bot: BotHandle, expected: FlagHistory.Tier) {
        val actual = FlagHistory.tierFor(bot.uuid)
        if (actual != expected) {
            throw ScenarioFailed(
                "TIER mismatch in $scenarioName: bot '${bot.name}' is $actual, expected $expected.\n" +
                    "  alerted checks: ${SelfTestHooks.alertedChecksFor(bot.uuid).sorted()}"
            )
        }
    }

    /** The bot must be tracked by the pipeline at all (sanity for every other assertion). */
    fun expectTracked(bot: BotHandle) {
        val tracked = dev.iustitia.tracking.EntityTrackerManager.get(bot.uuid)
        if (tracked == null) {
            throw ScenarioFailed(
                "TRACKING failure in $scenarioName: bot '${bot.name}' is not tracked. " +
                    "The pipeline never saw the bot -- check spawn/teleport helpers before debugging the check."
            )
        }
    }

    /**
     * The bot must have alerted [checkId] at least [atLeast] **distinct episodes** under [label].
     *
     * The twin of `ScenarioBuilder.expectAlertCount` for scenarios that assert inside their own body
     * rather than at the end. See that method for what separates two episodes and why recurrence
     * needs its own verb: [expectAlert] cannot see a check whose episode latch never re-arms.
     */
    fun expectAlertCount(bot: BotHandle, checkId: String, label: String, atLeast: Int = 2) {
        val observed = SelfTestHooks.alertCountFor(bot.uuid, checkId, label)
        if (observed < atLeast) {
            val peak = SelfTestHooks.peakVlFor(bot.uuid)[checkId]
            throw ScenarioFailed(
                "NO RE-ARM in $scenarioName: bot '${bot.name}' alerted '$checkId' [$label] " +
                    "$observed time(s), expected >= $atLeast (peakVL=$peak).\n" +
                    "  alerted: ${SelfTestHooks.alertedChecksFor(bot.uuid).sorted()}\n" +
                    "  The check fired but never re-armed -- its episode latch is still held from the " +
                    "first episode. If the second episode was under-driven, lengthen or strengthen it " +
                    "before touching the detector."
            )
        }
    }
}

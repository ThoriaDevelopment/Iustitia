package dev.iustitia.selftest

import dev.iustitia.history.FlagHistory
import dev.iustitia.selftest.SelfTest.BotHandle

/**
 * Assertion helpers for the three passes. Every failure message is written to be
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

/**
 * Counts the inline harness assertions a scenario made, so "asserted and passed" can be told apart
 * from "asserted nothing".
 *
 * Without it, a scenario that declares no `expect*` and throws nothing reports green: the report's
 * `passed` formula has nothing to be false about. The verdict is not hypothetical for the REPLAY
 * and preset scenarios, which assert inside their own body and register no expectation, so a
 * scenario whose assertions were gutted during a refactor keeps passing while checking nothing.
 *
 * Global mutable state, deliberately: the harness runs one scenario at a time. A `check` inside a
 * `ClientThread.runOnClient` block runs on the client thread rather than the gametest thread, hence
 * the atomic.
 */
object AssertionTally {
    private val hits = java.util.concurrent.atomic.AtomicInteger()

    /** Record one inline assertion. */
    fun hit() { hits.incrementAndGet() }

    /** Assertions made since the last [reset]. */
    fun sinceReset(): Int = hits.get()

    /** Forget the running count (called before each scenario body). */
    fun reset() { hits.set(0) }
}

/**
 * The harness's own `check`, which shadows `kotlin.check` inside this package.
 *
 * It exists for two reasons rather than reusing the stdlib verb: it feeds [AssertionTally], and it
 * throws [ScenarioFailed] instead of `IllegalStateException`, so the runner classifies a failed
 * inline assertion exactly as it classifies a failed `expect*` and nothing has to guess whether an
 * `IllegalStateException` from a scenario was a real assertion or an engine bug.
 *
 * Prefer the two-arg form: it carries the PR-grade message the rest of this file's assertions carry.
 */
fun check(value: Boolean, lazyMessage: () -> String) {
    AssertionTally.hit()
    if (!value) throw ScenarioFailed(lazyMessage())
}

/** [check] without a message. Kept because the replay scenarios use it for self-describing conditions. */
fun check(value: Boolean) {
    AssertionTally.hit()
    if (!value) throw ScenarioFailed("assertion failed: a replay/preset condition held no longer (no message given)")
}

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

    /**
     * The bot must have flagged [checkId] under [label] at most [atMost] times.
     *
     * The twin of `ScenarioBuilder.expectFlagCount` for scenarios that assert inside their own body.
     * It is the assertion a false-positive fix needs when the flagging level never reaches
     * `setbackVL`: [expectNoAlert] would pass on a check that was simply never driven, whereas this
     * reports the flag count that proves the check *was* driven and stayed silent.
     *
     * [subLabel] restricts the count to one flag site of a check that shares one label across
     * sites — see `ScenarioBuilder.expectFlagCount`.
     */
    fun expectFlagCount(bot: BotHandle, checkId: String, label: String, atMost: Int = 0, subLabel: String? = null) {
        val observed = SelfTestHooks.flagCountFor(bot.uuid, checkId, label, subLabel)
        if (observed > atMost) {
            val peak = SelfTestHooks.peakVlFor(bot.uuid)[checkId]
            throw ScenarioFailed(
                "FALSE POSITIVE in $scenarioName: bot '${bot.name}' flagged '$checkId' [$label]" +
                    (subLabel?.let { " subLabel='$it'" } ?: "") +
                    " $observed time(s), expected <= $atMost (peakVL=$peak).\n" +
                    "  The legitimate shape still reaches this flag. Narrow the check's guard rather " +
                    "than the scenario, or the FP this assertion documents is not actually fixed."
            )
        }
    }
}

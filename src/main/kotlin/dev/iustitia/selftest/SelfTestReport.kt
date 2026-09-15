package dev.iustitia.selftest

/**
 * A machine-readable result of one self-test scenario. Collected by the self-test
 * harness (`src/gametest`) and asserted on by the scenario classes; also written
 * verbatim into the JSON report consumed by `scripts/live_selftest.py` and PRs.
 *
 * Every field is a plain value so the JSON mapping in [toJson] stays trivial and
 * the report can never leak more than what was explicitly recorded. No player
 * names beyond the synthetic bot names used by the scenarios.
 *
 * Failure is ALWAYS judgment-based: a scenario records `passed=false` only when an
 * assertion inside the scenario failed. A pipeline that returns no alerts at all is
 * a valid result if the scenario expected none (the legit pass), and a crash inside
 * a scenario is a failure with [error] populated.
 */
data class ScenarioReport(
    val scenario: String,
    val pass: String,
    /**
     * The reference client (or "vanilla") whose behavior this scenario reproduces. It is
     * what lets the runner build the per-client catch matrix: `speedEnvelope` caught the
     * LiquidBounce drive but missed the Meteor one is a different sentence than "speed is
     * untested". Provenance is REQUIRED for cheat scenarios — a drive with no named source
     * is an invented pattern, not a reproduced one.
     */
    val source: String = "vanilla",
    /** Coarse behavior tags (`combat`, `movement`, `packet`, `world`, `replay`, `guard`). */
    val tags: Set<String> = emptySet(),
    /** Every check this scenario asserts on, and the direction it asserts: true = must alert. */
    val expectations: Map<String, Boolean> = emptyMap(),
    val passed: Boolean,
    /** Per-check evidence observed by the scenario. Key: check id, value: peak VL. */
    val vl: Map<String, Double>,
    /** Check ids that crossed their alert threshold during the scenario. */
    val alertedChecks: Set<String>,
    /** Check ids the scenario explicitly expected to alert that did NOT. */
    val missedChecks: Set<String>,
    /** Check ids the scenario expected to stay silent that DID alert. */
    val falsePositives: Set<String>,
    /**
     * Alert **episodes** observed per `checkId[label]` key. Records recurrence, which
     * [alertedChecks] cannot: a check whose episode latch never re-arms still appears there once.
     */
    val alertCounts: Map<String, Int> = emptyMap(),
    /**
     * Recurrence assertions that did not reach their required episode count. A hard failure
     * (these fold into [passed]), unlike [knownOpen] / [driveGaps] — a scenario declared that a
     * check must re-arm, and it did not. Each line carries the observed count, the required count
     * and the peak VL so a reviewer can tell an un-re-arming latch from an under-driven second
     * episode.
     */
    val alertCountMisses: List<String> = emptyList(),
    /**
     * Known-open findings: expectations that are deliberately not part of pass/fail because
     * the detector currently cannot satisfy them (a documented unreachable setback, a
     * sub-threshold tuning gap). Reported on EVERY run so the gap stays visible in pull
     * requests and never silently disappears, without turning the suite permanently red.
     * Each entry is a human-readable line (check id + what happened + the note).
     */
    val knownOpen: List<String> = emptyList(),
    /**
     * Harness gaps: scenarios whose DRIVE does not yet reach the check it targets.
     *
     * Distinct from [knownOpen], and the distinction is the whole point. `knownOpen` says "the
     * detector cannot catch this yet" -- a finding about Iustitia. This says "our scripted bot
     * does not yet reproduce the input that check reacts to" -- a finding about the TEST, which
     * is what the zero-flag signature actually means (the check logged no flag at all, so it was
     * never asked). Keeping them apart stops the suite from either reporting a broken drive as a
     * detector bypass or quietly demoting a real bypass to "not tested yet"; the runner prints
     * both, and `--coverage` still counts a harness gap as uncovered so the gap stays visible.
     *
     * Recorded, never asserted on: these are the work queue for extending the drives.
     */
    val driveGaps: List<String> = emptyList(),
    /** Wall-clock duration of the scenario in milliseconds. */
    val durationMs: Long,
    /** Non-null when the scenario threw: the exception class + message. */
    val error: String? = null,
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "scenario" to scenario,
        "pass" to pass,
        "source" to source,
        "tags" to tags.sorted(),
        "expectations" to expectations,
        "passed" to passed,
        "vl" to vl,
        "alertedChecks" to alertedChecks.sorted(),
        "missedChecks" to missedChecks.sorted(),
        "falsePositives" to falsePositives.sorted(),
        "alertCounts" to alertCounts,
        "alertCountMisses" to alertCountMisses,
        "knownOpen" to knownOpen,
        "driveGaps" to driveGaps,
        "durationMs" to durationMs,
        "error" to error,
    )

    companion object {
        fun fromJson(m: Map<*, *>): ScenarioReport = ScenarioReport(
            scenario = m["scenario"] as? String ?: "?",
            pass = m["pass"] as? String ?: "?",
            source = m["source"] as? String ?: "vanilla",
            tags = (m["tags"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet(),
            expectations = (m["expectations"] as? Map<*, *>)?.entries?.associate {
                (it.key as? String ?: "?") to (it.value as? Boolean ?: false)
            } ?: emptyMap(),
            passed = m["passed"] as? Boolean ?: false,
            vl = (m["vl"] as? Map<*, *>)?.entries?.associate {
                (it.key as? String ?: "?") to ((it.value as? Number)?.toDouble() ?: 0.0)
            } ?: emptyMap(),
            alertedChecks = (m["alertedChecks"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet(),
            missedChecks = (m["missedChecks"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet(),
            falsePositives = (m["falsePositives"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet(),
            alertCounts = (m["alertCounts"] as? Map<*, *>)?.entries?.associate {
                (it.key as? String ?: "?") to ((it.value as? Number)?.toInt() ?: 0)
            } ?: emptyMap(),
            alertCountMisses = (m["alertCountMisses"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            knownOpen = (m["knownOpen"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            driveGaps = (m["driveGaps"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            durationMs = (m["durationMs"] as? Number)?.toLong() ?: 0L,
            error = m["error"] as? String,
        )
    }
}

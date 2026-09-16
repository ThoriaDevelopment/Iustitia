package dev.iustitia.selftest

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The recording tap used by the automated live-test harness. A tiny, dev-gated
 * singleton: it does nothing unless the JVM property `fabric.selftest` is set (only
 * the gametest run sets it), in which case `Check.flag` reports every flag here so
 * the harness can assert per-check VL and alert crossings.
 *
 * This is deliberately in the main source set (not the gametest source set) because
 * `Check.flag` is the one chokepoint every flag routes through — but the hook itself
 * must stay inert in normal play:
 *
 * - [isEnabled] reads a JVM system property once. Normal clients never set it, so
 *   the flag path costs one volatile boolean read.
 * - [recordFlag] only appends to maps keyed by the flagging player's uuid (and, for the flag and
 *   episode counters, by the alert label and the flag site's `subLabel` — see [flagCountFor] and
 *   [alertCountFor]).
 * - [clear] wipes the recording between scenarios.
 *
 * No persistence, no I/O, no reflection — the recorded state is process-local and
 * dies with the test JVM. It never touches alerts, chat, tiering, or history; the
 * harness reads it instead of scraping chat, which keeps the assertions deterministic.
 */
object SelfTestHooks {

    /**
     * Crossings closer together than this many ticks belong to the **same** alert episode.
     *
     * Alert crossings are not one per episode. `Check.flag` reports `crossedAlert = ctx.vl >
     * setbackVL` on *every* flag, and a slow-decay check keeps its VL above the setback for tens
     * of ticks after the single `flagEpisode` that pushed it there — so one episode can produce a
     * burst of crossings, and a per-crossing count would read a single alert as a dozen.
     *
     * Chaining by tick distance is the fix; a false→true transition test is NOT. With that decay
     * the crossing stream can stay "true" across the whole quiet gap between two genuine episodes
     * (no sub-flag fires, so no false is ever observed), which makes a transition counter
     * *undercount* and report the second episode as missing.
     *
     * **Constraint:** this must stay below every episode gate in the suite, or two genuinely
     * separate episodes chain into one and a re-arm regression silently false-greens. Current
     * gates: `HitsWithoutSwingCheck.EPISODE` (60 ticks), `KillAuraCheck.DRIFT_WINDOW_TICKS` (50).
     * Lower this only after checking every check that declares an episode gate.
     */
    private const val SAME_EPISODE_TICKS = 30

    /**
     * Identity of one flagged sub-pattern: a check id, the alert label it flags under, and the
     * flag site's `Evidence.subLabel` (null when the site carries no evidence).
     *
     * The subLabel is what separates two flag sites that deliberately share one label. `multiTarget`
     * is the worked example: its legitimate same-tick sweep flag and its `pair-sustained` module
     * flag both report as `"MultiTarget"`, so a label-only count cannot assert "the pair path
     * stopped firing on legitimate play" without counting the ~11 legitimate sweep flags with it.
     * Episode counting ignores the subLabel (see [recordFlag]) — an episode is per label, which is
     * what a re-arm assertion asks about.
     */
    private data class FlagKey(val checkId: String, val label: String, val subLabel: String?)

    /** Running episode count for one [FlagKey]: how many distinct alert episodes, and when the last crossing was. */
    private class AlertEpisodes {
        var count: Int = 0
        var lastCrossingTick: Int = Int.MIN_VALUE
    }

    @Volatile
    private var enabled: Boolean = try {
        System.getProperty("fabric.selftest") != null
    } catch (_: Throwable) { false }

    @Volatile
    private var recording: Boolean = false

    /** Peak VL per (player uuid, check id) since the last [clear]. */
    private val peakVl = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Double>>()

    /** Check ids whose VL crossed setbackVL per player since the last [clear]. */
    private val alerted = ConcurrentHashMap<UUID, MutableSet<String>>()

    /**
     * Alert **episodes** per player, per (check id, label) since the last [clear].
     *
     * Keyed by label, not by check id alone, because one check pools one VL across several flag
     * labels. `killAura` is the worked example: seven flag sites (`drift`, `VL_AIM`,
     * `VL_SILENT_TRACK`, …) share the id and the VL pool, so a check-wide count picks up every
     * unrelated sub-flag and inflates. Only `driftComponent` calls `flagEpisode` there, so a
     * label-scoped count is exactly that check's episode latch — and a check-wide one would let a
     * second-episode assertion pass on code whose latch is still stuck.
     */
    private val alertEpisodes = ConcurrentHashMap<UUID, ConcurrentHashMap<FlagKey, AlertEpisodes>>()

    /**
     * Raw flag count per player, per [FlagKey] since the last [clear] — every [recordFlag], not just
     * the crossings.
     *
     * Exists because a flag below its setback is otherwise unobservable. A check that flags at a
     * level smaller than `setbackVL` and is evaluated at most once per event (one hit, one swing)
     * can never accumulate past the decay, so it never appears in [alerted] *by construction* — and
     * a scenario asserting only "no alert" cannot tell "the false positive was removed" from "the
     * check was never driven". Counting the flags themselves is the observable that distinguishes
     * them: the FP shape flags N times, the fixed shape flags 0.
     */
    private val flagCounts = ConcurrentHashMap<UUID, ConcurrentHashMap<FlagKey, Int>>()

    fun isEnabled(): Boolean = enabled

    fun startRecording() {
        if (!enabled) return
        recording = true
    }

    fun stopRecording() {
        recording = false
    }

    fun clear() {
        peakVl.clear()
        alerted.clear()
        alertEpisodes.clear()
        flagCounts.clear()
    }

    /** Called from [dev.iustitia.checks.Check.flag] when self-test mode is active. */
    fun recordFlag(
        uuid: UUID, checkId: String, label: String, subLabel: String?,
        vl: Double, crossedAlert: Boolean, tick: Int,
    ) {
        if (!recording) return
        val perPlayer = peakVl.getOrPut(uuid) { ConcurrentHashMap() }
        perPlayer.merge(checkId, vl) { a, b -> maxOf(a, b) }
        flagCounts.getOrPut(uuid) { ConcurrentHashMap() }
            .merge(FlagKey(checkId, label, subLabel), 1) { a, b -> a + b }
        if (crossedAlert) {
            alerted.getOrPut(uuid) { ConcurrentHashMap.newKeySet() }.add(checkId)
            // Episodes are counted per (check, label) with the subLabel normalised away: the
            // re-arm contract is the check's single `episodeActive` latch, which is per context and
            // therefore per check, not per flag site. Keeping the subLabel here would split one
            // latch's crossings across several keys and read a single alert as several episodes.
            val episodes = alertEpisodes
                .getOrPut(uuid) { ConcurrentHashMap() }
                .getOrPut(FlagKey(checkId, label, null)) { AlertEpisodes() }
            // A gap of at least SAME_EPISODE_TICKS since the previous crossing starts a new episode;
            // anything closer is the same episode still ringing. lastCrossingTick advances either
            // way, so a continuous burst keeps chaining forward instead of re-counting each tick.
            if (episodes.lastCrossingTick == Int.MIN_VALUE ||
                tick - episodes.lastCrossingTick >= SAME_EPISODE_TICKS
            ) {
                episodes.count++
            }
            episodes.lastCrossingTick = tick
        }
    }

    /**
     * How many distinct alert episodes [checkId] produced for [uuid] under [label] since the last
     * [clear]. See [SAME_EPISODE_TICKS] for what separates two episodes.
     */
    fun alertCountFor(uuid: UUID, checkId: String, label: String): Int =
        alertEpisodes[uuid]?.get(FlagKey(checkId, label, null))?.count ?: 0

    /**
     * How many times [checkId] flagged for [uuid] under [label] since the last [clear] — every flag,
     * crossing or not. See [flagCounts] for why a sub-setback flag count is the assertion a
     * false-positive fix needs.
     *
     * With [subLabel] set, counts only that flag site. With it null (the default), sums every site
     * that reports under [label] — the original label-scoped behaviour, so existing callers are
     * unchanged. Pass a subLabel when a check deliberately shares one label across sites and the
     * assertion is about one of them; `multiTarget` is the worked example (see [FlagKey]).
     */
    fun flagCountFor(uuid: UUID, checkId: String, label: String, subLabel: String? = null): Int {
        val perPlayer = flagCounts[uuid] ?: return 0
        val exact = perPlayer[FlagKey(checkId, label, subLabel)]
        if (exact != null || subLabel != null) return exact ?: 0
        var total = 0
        for ((key, n) in perPlayer) if (key.checkId == checkId && key.label == label) total += n
        return total
    }

    fun peakVlFor(uuid: UUID): Map<String, Double> =
        peakVl[uuid]?.toMap() ?: emptyMap()

    fun alertedChecksFor(uuid: UUID): Set<String> =
        alerted[uuid]?.toSet() ?: emptySet()

    /** Union of every alerted check across all players — used by whole-scene assertions. */
    fun allAlertedChecks(): Set<String> =
        alerted.values.fold(mutableSetOf<String>()) { acc, s -> acc.addAll(s); acc }
}

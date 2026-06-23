package dev.iustitia.history

import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory session store backing the usability features: per-player flag history (`/ius hist`),
 * the session health panel (`/ius status`), the alert hover tooltip counts, the mute counts, and
 * the nametag tier (green/yellow/red). All data is session-scoped (cleared by [reset] on
 * `/ius reset` and game-join) and lives only in the client process — nothing is written to disk or
 * sent to the server.
 *
 * Two counters per player:
 *  - [flags] — every flag the check layer raised (verbose-level included), capped at
 *    [CAP] most-recent per player so memory is bounded over a long session.
 *  - [sessionAlertCount] — counts real alert *events* (post-throttle, see [AlertManager]), i.e.
 *    the number of times a player actually crossed setbackVL this session. Drives the tier.
 *
 * Tiering (user-confirmed "strict + corroboration + decay" — calibrated so a yellow/red
 * nametag implies ≥95% confidence the player is actually cheating, to avoid false
 * hackusations; this is display logic only, the checks themselves are untouched):
 *  - **RED** — ≥ [RED_DISTINCT] (3) DISTINCT red-capable checks alerted on this player.
 *    Independent cross-check corroboration — only blatant multi-signal cheaters reach red.
 *    [CORROBORATOR] checks (killAura) count toward the 3 only alongside ≥1 primary
 *    red-capable alert; they never initiate a tier on their own.
 *  - **YELLOW** — ≥1 primary [DEFINITIVE] (red-capable) check alerted. A lone killAura, or
 *    any tier-NEUTRAL signal (rotationTracking / noFallDamage / speedEnvelope / …), does
 *    NOT yellow — those are FP-prone on legit play (intense combat, KitPvP arena-drop, dash)
 *    and must not mark a legit player suspect.
 *  - **GREEN** — no red-capable alert this session.
 *  - **Decay** — a tier fades one level (red→yellow→green) per [DECAY_MS] (~10 min) with no
 *    red-capable alert, so a stale / one-off flag does not persist as a false accusation.
 *    The peak tier reached is latched (a proven cheater doesn't fully clean by idling); a
 *    resuming cheater re-elevates the instant they alert again.
 *
 * All paths fail-open: a history/tier error is swallowed so it never blocks a flag or crashes
 * the render path that reads [tierFor].
 */
object FlagHistory {

    /** Max flags retained per player (most-recent). Bounds memory over long sessions. */
    private const val CAP = 50

    data class Flag(
        val tick: Int,
        val checkId: String,
        val label: String,
        val vl: Double,
        val wallClockMs: Long,
        /** Per-flag "why" payload (reach dist, fly Δy, blocked rays, …). `null` for checks that
         *  don't enrich — see [Evidence]. Read-only capture; changes no check logic. */
        val evidence: Evidence? = null,
    )

    enum class Tier { GREEN, YELLOW, RED }

    /**
     * Primary red-capable checks (the former "definitive" set, trimmed): a single alert from
     * any of these marks a player YELLOW; ≥ [RED_DISTINCT] distinct ones (with optional
     * [CORROBORATOR] help) mark RED. **noFallDamage was removed** — its KitPvP arena-drop FP
     * latched RED on every player dropping into the arena. **killAura was moved to
     * [CORROBORATOR]** — it's uncorroborated (no Polar equiv) and FP'd on legit players, so it
     * must not initiate a tier alone. speedEnvelope / rotationTracking / the minor signals stay
     * out (tier-neutral). This is tier metadata, not check logic — the checks themselves are
     * unchanged.
     */
    val DEFINITIVE: Set<String> = setOf(
        "reach", "multiTarget", "criticals", "autoBlock", "throughWalls",
        "hitFlick", "flyEnvelope", "longJump", "phaseClip", "teleport",
        "timerRate", "waterWalk", "scaffoldRotation",
    )

    /**
     * Checks that corroborate but never initiate a tier: a [CORROBORATOR] alert counts toward
     * RED's ≥ [RED_DISTINCT] distinct total ONLY when ≥1 primary [DEFINITIVE] check has also
     * alerted (proper diagnostic — it adds weight to an already-suspect player, never flags a
     * clean one on its own). killAura is the sole member (silent-aim suite: strong when it
     * agrees with reach/criticals/etc., weak / FP-prone standalone).
     */
    val CORROBORATOR: Set<String> = setOf("killAura")

    /** Distinct red-capable (primary + corroborating) checks that must alert to mark RED. */
    private const val RED_DISTINCT = 3

    /** Idle window after which a tier fades one level (red→yellow→green). ~10 min. */
    private const val DECAY_MS = 10L * 60 * 1000

    private val flagsByUuid = ConcurrentHashMap<UUID, ArrayDeque<Flag>>()
    private val alertCountByUuid = ConcurrentHashMap<UUID, Int>()
    /** All-time distinct red-capable checks (primary ∪ corroborator) that alerted per player.
     *  Drives the latched peak tier; monotonic (a check that alerted once stays in the set). */
    private val alertedChecksByUuid = ConcurrentHashMap<UUID, HashSet<String>>()
    /** Wall-clock of the last red-capable alert per player — the decay clock (idle since this). */
    private val lastAlertWallMsByUuid = ConcurrentHashMap<UUID, Long>()
    private val nameByUuid = ConcurrentHashMap<UUID, String>()

    /** Accurate per-(player, check) flag COUNT this session. Unlike [flagsByUuid] (capped at
     *  [CAP] most-recent), this is a running total so the profile card / report can show "47
     *  reach flags" even after old flags rolled out of the deque. */
    private val flagCounts = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Int>>()
    /** Peak VL reached per (player, check) this session — for the max-VL-per-check bar + card. */
    private val maxVlByUuidCheck = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Double>>()

    /** Record a flag (called from [dev.iustitia.checks.Check.flag] for every flag, verbose-level).
     *  [evidence] is the optional per-flag "why" payload (see [Evidence]); `null` for checks that
     *  don't enrich. */
    fun recordFlag(
        uuid: UUID, name: String, checkId: String, label: String, vl: Double, tick: Int,
        evidence: Evidence? = null,
    ) {
        try {
            noteName(uuid, name)
            val dq = flagsByUuid.getOrPut(uuid) { ArrayDeque() }
            synchronized(dq) {
                dq.addFirst(Flag(tick, checkId, label, vl, System.currentTimeMillis(), evidence))
                while (dq.size > CAP) dq.removeLast()
            }
            // Accurate count + peak VL (fail-open: an aggregation error never blocks a flag).
            flagCounts.getOrPut(uuid) { ConcurrentHashMap() }.merge(checkId, 1) { a, b -> a + b }
            maxVlByUuidCheck.getOrPut(uuid) { ConcurrentHashMap() }.merge(checkId, vl) { a, b -> kotlin.math.max(a, b) }
        } catch (_: Throwable) {
            // fail-open: history must never block a flag
        }
    }

    /** Record a real alert event (called from AlertManager.alert AFTER throttle passes). */
    fun recordAlert(uuid: UUID, checkId: String, name: String, tick: Int) {
        try {
            noteName(uuid, name)
            alertCountByUuid.compute(uuid) { _, v -> (v ?: 0) + 1 }
            // Tier-relevant alerts only: a primary [DEFINITIVE] check self-standing marks yellow
            // (and contributes to red); a [CORROBORATOR] (killAura) adds red weight. Everything
            // else (noFall / speedEnvelope / rotationTracking / minor signals) is tier-NEUTRAL —
            // its FPs must NOT mark a legit player suspect. The decay clock (lastAlertWallMs)
            // resets only on these tier-relevant alerts, so tier-neutral spam can't keep a stale
            // tier alive.
            if (checkId in DEFINITIVE || checkId in CORROBORATOR) {
                val set = alertedChecksByUuid.computeIfAbsent(uuid) { HashSet() }
                synchronized(set) { set.add(checkId) }
                lastAlertWallMsByUuid[uuid] = System.currentTimeMillis()
            }
        } catch (_: Throwable) {
            // fail-open
        }
    }

    /** Remember the latest known name for a uuid (so history/tier work after despawn). */
    fun noteName(uuid: UUID, name: String) {
        try {
            if (name.isNotEmpty()) nameByUuid[uuid] = name
        } catch (_: Throwable) {}
    }

    fun flags(uuid: UUID): List<Flag> = try {
        val dq = flagsByUuid[uuid] ?: return emptyList()
        synchronized(dq) { dq.toList() }
    } catch (_: Throwable) { emptyList() }

    fun flagsForCheck(uuid: UUID, checkId: String): List<Flag> =
        flags(uuid).filter { it.checkId == checkId }

    fun sessionAlertCount(uuid: UUID): Int = alertCountByUuid[uuid] ?: 0

    /** Per-(player, check) flag counts this session (accurate total, not capped). Sorted by count desc. */
    fun flagCounts(uuid: UUID): Map<String, Int> = try {
        (flagCounts[uuid] ?: emptyMap()).entries
            .sortedByDescending { it.value }
            .associate { it.key to it.value }
    } catch (_: Throwable) { emptyMap() }

    /** Peak VL per (player, check) this session. Powers the max-VL-per-check bar. */
    fun maxVlByCheck(uuid: UUID): Map<String, Double> = try {
        (maxVlByUuidCheck[uuid] ?: emptyMap()).entries
            .sortedByDescending { it.value }
            .associate { it.key to it.value }
    } catch (_: Throwable) { emptyMap() }

    /** First→last flag tick for [uuid] (from the deque tail/head), or null if no flags. */
    fun span(uuid: UUID): Pair<Int, Int>? = try {
        val dq = flagsByUuid[uuid] ?: return null
        synchronized(dq) {
            if (dq.isEmpty()) return null
            // newest-first deque: head = last flag, tail = first flag.
            dq.first().tick to dq.last().tick
        }
    } catch (_: Throwable) { null }

    /** The check id with the most flags this session for [uuid], or null. */
    fun topCheck(uuid: UUID): String? = try {
        flagCounts(uuid).entries.firstOrNull()?.key
    } catch (_: Throwable) { null }

    /**
     * The one-line cheat-confidence summary for the profile card (#2) + report. Built from the
     * red-capable alerted-check set + the corroborator, e.g. `"Reach + KillAura corroborated,
     * 3 alerts"`, `"ThroughWalls, 1 alert"`, or `"clean (no red-capable alerts)"`.
     */
    fun confidenceLine(uuid: UUID): String = try {
        val set = alertedChecksByUuid[uuid]
        val alerts = sessionAlertCount(uuid)
        if (set == null || set.isEmpty()) {
            return if (alerts > 0) "tier-neutral signals only ($alerts alerts)" else "clean (no red-capable alerts)"
        }
        val primaries = set.filter { it in DEFINITIVE }
        val corroborators = set.filter { it in CORROBORATOR }
        val parts = ArrayList<String>(2)
        if (primaries.isNotEmpty()) parts += primaries.joinToString(" + ")
        if (corroborators.isNotEmpty()) parts += "${corroborators.joinToString(" + ")} corroborated"
        val body = if (parts.isEmpty()) "no red-capable alerts" else parts.joinToString(", ")
        "$body, $alerts alert${if (alerts == 1) "" else "s"}"
    } catch (_: Throwable) { "clean (no red-capable alerts)" }

    fun tierFor(uuid: UUID): Tier = try {
        val set = alertedChecksByUuid[uuid]
        if (set == null || set.isEmpty()) return Tier.GREEN
        val primary: Int
        val hasCorroborator: Boolean
        synchronized(set) {
            primary = set.count { it in DEFINITIVE }
            hasCorroborator = set.any { it in CORROBORATOR }
        }
        // Lone corroborator (killAura) with no primary → not enough to accuse: GREEN.
        if (primary == 0) return Tier.GREEN
        // killAura corroborates the primaries (counts as +1 distinct toward RED's ≥3).
        val distinct = primary + (if (hasCorroborator) 1 else 0)
        val peak = when {
            distinct >= RED_DISTINCT -> Tier.RED
            primary >= 1 -> Tier.YELLOW
            else -> Tier.GREEN
        }
        if (peak == Tier.GREEN) return Tier.GREEN
        // Decay: fade one tier per DECAY_MS of idle since the last red-capable alert. The peak
        // is latched (all-time distinct is monotonic), so a resuming cheater snaps back to peak.
        val lastMs = lastAlertWallMsByUuid[uuid] ?: return peak
        val tiersDropped = ((System.currentTimeMillis() - lastMs) / DECAY_MS).toInt()
        if (tiersDropped <= 0) return peak
        when (peak) {
            Tier.RED -> when {
                tiersDropped >= 2 -> Tier.GREEN
                tiersDropped == 1 -> Tier.YELLOW
                else -> Tier.RED
            }
            Tier.YELLOW -> if (tiersDropped >= 1) Tier.GREEN else Tier.YELLOW
            Tier.GREEN -> Tier.GREEN
        }
    } catch (_: Throwable) { Tier.GREEN }

    /** Total alert events this session (all players). */
    val totalAlerts: Int get() = alertCountByUuid.values.sum()

    /** Players ranked by alert count, descending. Returns (name, count). */
    fun topOffenders(limit: Int): List<Pair<String, Int>> = try {
        alertCountByUuid.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { (nameByUuid[it.key] ?: it.key.toString().take(8)) to it.value }
    } catch (_: Throwable) { emptyList() }

    /** Case-insensitive name → uuid resolution against the known-name map. */
    fun resolveName(query: String): UUID? = try {
        val q = query.trim()
        if (q.isEmpty()) return null
        // exact (case-insensitive) first
        nameByUuid.entries.firstOrNull { it.value.equals(q, ignoreCase = true) }?.key
            // else a unique prefix match
            ?: nameByUuid.entries.firstOrNull { it.value.startsWith(q, ignoreCase = true) }?.key
    } catch (_: Throwable) { null }

    /** uuid → last-known name (for displaying muted-player lists by a friendly name). */
    fun nameFor(uuid: UUID): String? = try { nameByUuid[uuid] } catch (_: Throwable) { null }

    /** Names known this session (for tab-completion of `/ius hist <name>` etc.). */
    fun knownNames(): List<String> = try {
        nameByUuid.values.distinct().sorted()
    } catch (_: Throwable) { emptyList() }

    fun reset() {
        try {
            flagsByUuid.clear()
            alertCountByUuid.clear()
            alertedChecksByUuid.clear()
            lastAlertWallMsByUuid.clear()
            nameByUuid.clear()
            flagCounts.clear()
            maxVlByUuidCheck.clear()
        } catch (_: Throwable) {
            // fail-open
        }
    }
}
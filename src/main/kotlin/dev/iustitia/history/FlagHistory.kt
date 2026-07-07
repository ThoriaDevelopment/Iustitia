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
 *  - **RED** — ≥ [RED_DISTINCT] (2) DISTINCT red-capable checks alerted on this player.
 *    Independent cross-check corroboration — blatant multi-signal cheaters reach red.
 *    [CORROBORATOR] checks (killAura) count toward the 2 only alongside ≥1 primary
 *    red-capable alert; they never initiate a tier on their own.
 *  - **YELLOW** — ≥1 primary [DEFINITIVE] (red-capable) check alerted, OR a lone
 *    [CORROBORATOR] (killAura) alert. The lone-corroborator→YELLOW path is a deliberate
 *    loosening (was GREEN): a player the silent-aim suite alone alerted on is a suspect
 *    worth a yellow prefix. Any tier-NEUTRAL signal (rotationTracking / noFallDamage /
 *    speedEnvelope / …) still does NOT yellow — those are FP-prone on legit play (intense
 *    combat, KitPvP arena-drop, dash) and must not mark a legit player suspect.
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

    enum class Tier(val label: String) { GREEN("GREEN"), YELLOW("YELLOW"), RED("RED") }

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
        "reach", "multiTarget", "criticals", "maceSmash", "autoBlock", "throughWalls",
        "hitFlick", "flyEnvelope", "longJump", "phaseClip", "teleport",
        "waterWalk", "scaffoldRotation",
        "wallSprint", "spider", "sprintHack",
    )

    /**
     * Checks that corroborate but never initiate a tier: a [CORROBORATOR] alert counts toward
     * RED's ≥ [RED_DISTINCT] distinct total ONLY when ≥1 primary [DEFINITIVE] check has also
     * alerted (proper diagnostic — it adds weight to an already-suspect player, never flags a
     * clean one on its own). killAura is the silent-aim suite (strong when it agrees with
     * reach/criticals/etc., weak / FP-prone standalone); hitsWithoutSwing is the no-swing-attack
     * signal (weak / noisy — an out-of-view attacker or packet ordering can drop the swing — so
     * it corroborates a killaura case but never initiates a tier on its own).
     */
    val CORROBORATOR: Set<String> = setOf("killAura", "hitsWithoutSwing")

    /** Distinct red-capable (primary + corroborating) checks that must alert to mark RED.
     *  Loosened 3→2 (display-only) so a 2-signal-corroborated cheater reaches RED instead of
     *  stalling at YELLOW — the strict ≥3 rarely fired on quiet servers. The smallest integer
     *  step available; revert to 3 if RED prefixes become too common. */
    private const val RED_DISTINCT = 2

    /** Idle window after which a tier fades one level (red→yellow→green). ~10 min. */
    private const val DECAY_MS = 10L * 60 * 1000

    private val flagsByUuid = ConcurrentHashMap<UUID, ArrayDeque<Flag>>()
    private val alertCountByUuid = ConcurrentHashMap<UUID, Int>()
    /** All-time distinct red-capable checks (primary ∪ corroborator) that alerted per player.
     *  Drives the latched peak tier; monotonic (a check that alerted once stays in the set). */
    private val alertedChecksByUuid = ConcurrentHashMap<UUID, HashSet<String>>()
    /** Wall-clock of the last red-capable alert per player — the decay clock (idle since this). */
    private val lastAlertWallMsByUuid = ConcurrentHashMap<UUID, Long>()
    /** Game tick of the last red-capable (DEFINITIVE ∪ CORROBORATOR) alert per player — drives the
     *  nametag burst-pulse ("fresh flag within last 3s") and the `/ius evidence`/snapshot "fresh"
     *  marker. Read cross-thread from the render mixin; benign one-tick staleness, fail-open. */
    val lastRedAlertTick = ConcurrentHashMap<UUID, Int>()
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
            // Keep the roaming persistence store in sync (debounced, no-op when the toggle is off).
            try { dev.iustitia.persistence.PersistenceManager.saveHistory() } catch (_: Throwable) {}
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
                lastRedAlertTick[uuid] = tick
            }
            try { dev.iustitia.persistence.PersistenceManager.saveHistory() } catch (_: Throwable) {}
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
        val alerts = sessionAlertCount(uuid)
        val set = alertedChecksByUuid[uuid]
        if (set == null) {
            return if (alerts > 0) "tier-neutral signals only ($alerts alerts)" else "clean (no red-capable alerts)"
        }
        // Snapshot the set under its lock: recordAlert adds to this set from the network thread
        // while these readers run on the render thread, and an unsynchronized iterate would CME
        // (caught by the outer fail-open, but it flashed a wrong tier for one frame). tierFor
        // already locks; the readers now do too. Display-only.
        val body = synchronized(set) {
            if (set.isEmpty()) return@confidenceLine (
                if (alerts > 0) "tier-neutral signals only ($alerts alerts)" else "clean (no red-capable alerts)"
            )
            val primaries = set.filter { it in DEFINITIVE }
            val corroborators = set.filter { it in CORROBORATOR }
            val parts = ArrayList<String>(2)
            if (primaries.isNotEmpty()) parts += primaries.joinToString(" + ")
            if (corroborators.isNotEmpty()) parts += "${corroborators.joinToString(" + ")} corroborated"
            if (parts.isEmpty()) "no red-capable alerts" else parts.joinToString(", ")
        }
        "$body, $alerts alert${if (alerts == 1) "" else "s"}"
    } catch (_: Throwable) { "clean (no red-capable alerts)" }

    /**
     * Numeric cheat-confidence score 0–99 for the nametag badge + `/ius session` "who peaked
     * highest". A weighted blend of the tier base (green 15 / yellow 55 / red 82), a recency bonus
     * (0–12, fresh at the last red-capable alert, decaying to 0 over [DECAY_MS]), a diversity bonus
     * (0–8, +2 per distinct red-capable check up to 4), and a +3 corroborator bonus. Capped at 99.
     * Display-only — derives from the existing tier/alert state, changes no check logic. Fail-open.
     */
    fun confidenceScore(uuid: UUID): Int = try {
        val tier = tierFor(uuid)
        val base = when (tier) { Tier.GREEN -> 15; Tier.YELLOW -> 55; Tier.RED -> 82 }
        val lastMs = lastAlertWallMsByUuid[uuid] ?: 0L
        val recency = if (lastMs > 0) {
            val frac = ((System.currentTimeMillis() - lastMs).toDouble() / DECAY_MS).coerceIn(0.0, 1.0)
            (12 * (1.0 - frac)).toInt().coerceIn(0, 12)
        } else 0
        // Read the set under its lock (recordAlert mutates it cross-thread; see confidenceLine).
        val set = alertedChecksByUuid[uuid]
        val (primary, hasCorr) = if (set == null) 0 to false
        else synchronized(set) { set.count { it in DEFINITIVE } to set.any { it in CORROBORATOR } }
        val distinct = primary + if (hasCorr) 1 else 0
        val diversity = minOf(distinct, 4) * 2
        val corr = if (hasCorr) 3 else 0
        (base + recency + diversity + corr).coerceIn(0, 99)
    } catch (_: Throwable) { 0 }

    /**
     * One-line "why this tier" explanation for the (deferred render) nametag-hover tooltip and the
     * transcript/snapshot exports, e.g. `"RED: 3 primary checks (reach, backtrack, multiTarget)
     * within 12s"`. Built from the alerted-check set + the flag tick span. Fail-open.
     */
    fun confidenceExplanation(uuid: UUID): String = try {
        val set = alertedChecksByUuid[uuid]
        if (set == null) {
            return if (sessionAlertCount(uuid) > 0) "tier-neutral signals only (no red-capable alert)" else "clean (no red-capable alerts)"
        }
        // Snapshot the set contents under its lock (recordAlert mutates cross-thread; see
        // confidenceLine). primaries + the full-CSV are local snapshots safe to use outside.
        val (empty, primaries, allCsv) = synchronized(set) {
            Triple(set.isEmpty(), set.filter { it in DEFINITIVE }, set.joinToString(", "))
        }
        if (empty) {
            return if (sessionAlertCount(uuid) > 0) "tier-neutral signals only (no red-capable alert)" else "clean (no red-capable alerts)"
        }
        val tier = tierFor(uuid)
        val tierName = tier.label
        val sp = span(uuid)
        val window = if (sp == null) "" else " within ${(sp.second - sp.first) / 20}s"
        if (primaries.isEmpty()) {
            "$tierName: corroborator only ($allCsv)$window"
        } else {
            "$tierName: ${primaries.size} primary check${if (primaries.size == 1) "" else "s"} " +
                "(${primaries.joinToString(", ")})$window"
        }
    } catch (_: Throwable) { "clean (no red-capable alerts)" }

    // --- persistence accessors (read by PersistenceManager, written by mergePersisted) ---

    /** The red-capable alerted-check set for [uuid] (primary ∪ corroborator). Read-only copy. */
    fun alertedChecksOf(uuid: UUID): Set<String> = try {
        val set = alertedChecksByUuid[uuid] ?: return emptySet()
        synchronized(set) { set.toSet() }
    } catch (_: Throwable) { emptySet() }

    /** Wall-clock ms of the last red-capable alert for [uuid] (the decay clock), or 0. */
    fun lastAlertWallMsOf(uuid: UUID): Long = try { lastAlertWallMsByUuid[uuid] ?: 0L } catch (_: Throwable) { 0L }

    /** Every uuid known to FlagHistory (union of all backing-map keys) — for the persistable sweep. */
    fun knownUuids(): Set<UUID> = try {
        val s = LinkedHashSet<UUID>()
        s.addAll(flagsByUuid.keys); s.addAll(alertCountByUuid.keys); s.addAll(alertedChecksByUuid.keys)
        s.addAll(nameByUuid.keys); s.addAll(flagCounts.keys); s.addAll(maxVlByUuidCheck.keys)
        s
    } catch (_: Throwable) { emptySet() }

    /**
     * Restore persisted state for [uuid] (loaded by [dev.iustitia.persistence.PersistenceManager] on
     * startup when persistence is on). Merges into every backing map so `/ius hist`/`/ius note`/the
     * tier show pre-restart data. The tier itself decays naturally from [lastAlertWallMs] (a restart
     * usually exceeds [DECAY_MS], so a stale RED fades to GREEN until the player re-alerts — the flag
     * timeline + counts persist regardless). Fail-open.
     */
    fun mergePersisted(
        uuid: UUID, name: String, alertCount: Int, alertedChecks: Set<String>,
        lastAlertWallMs: Long, flagCountMap: Map<String, Int>, maxVlMap: Map<String, Double>,
        persistedFlags: List<Flag>,
        // Ids of checks still registered in this build — supplied by the caller
        // (PersistenceManager, from ConfigManager.config.checks()). Used as a one-way migration
        // filter: persisted entries for a REMOVED check (e.g. timerRate after its deletion) are
        // dropped before merging, so they can't survive forever in alertedChecks / flagCounts /
        // maxVl / flags — which would otherwise re-evaluate against the trimmed DEFINITIVE set
        // (silently downgrading a latched RED tier) and render as dangling rows in /ius hist +
        // /ius report. emptySet (default) = filter disabled, preserving the pre-filter behaviour
        // (defensive against an init-order gap where the caller can't yet read the check set).
        validCheckIds: Set<String> = emptySet(),
    ) {
        try {
            val filterOn = validCheckIds.isNotEmpty()
            val alerted = if (filterOn) alertedChecks.filter { it in validCheckIds }.toSet() else alertedChecks
            val fc = if (filterOn) flagCountMap.filterKeys { it in validCheckIds } else flagCountMap
            val mv = if (filterOn) maxVlMap.filterKeys { it in validCheckIds } else maxVlMap
            val flags = if (filterOn) persistedFlags.filter { it.checkId in validCheckIds } else persistedFlags
            if (name.isNotEmpty()) nameByUuid[uuid] = name
            if (alertCount > 0) alertCountByUuid[uuid] = alertCount
            if (alerted.isNotEmpty()) {
                val set = alertedChecksByUuid.computeIfAbsent(uuid) { HashSet() }
                synchronized(set) { set.addAll(alerted) }
            }
            if (lastAlertWallMs > 0) lastAlertWallMsByUuid[uuid] = lastAlertWallMs
            if (fc.isNotEmpty()) {
                val m = flagCounts.computeIfAbsent(uuid) { ConcurrentHashMap() }
                fc.forEach { (cid, c) -> m.merge(cid, c) { a, b -> a + b } }
            }
            if (mv.isNotEmpty()) {
                val m = maxVlByUuidCheck.computeIfAbsent(uuid) { ConcurrentHashMap() }
                mv.forEach { (cid, v) -> m.merge(cid, v) { a, b -> kotlin.math.max(a, b) } }
            }
            if (flags.isNotEmpty()) {
                val dq = flagsByUuid.getOrPut(uuid) { ArrayDeque() }
                synchronized(dq) {
                    // persistedFlags are oldest→newest; insert at the tail (oldest end) so live
                    // flags (added at head) stay newest. Cap to [CAP].
                    flags.forEach { dq.addLast(it) }
                    while (dq.size > CAP) dq.removeLast()
                }
            }
        } catch (_: Throwable) {
            // fail-open: a bad persisted record never blocks the session
        }
    }

    fun tierFor(uuid: UUID): Tier = try {
        val set = alertedChecksByUuid[uuid]
        if (set == null || set.isEmpty()) return Tier.GREEN
        val primary: Int
        val hasCorroborator: Boolean
        synchronized(set) {
            primary = set.count { it in DEFINITIVE }
            hasCorroborator = set.any { it in CORROBORATOR }
        }
        // killAura corroborates the primaries (counts as +1 distinct toward RED's ≥RED_DISTINCT).
        val distinct = primary + (if (hasCorroborator) 1 else 0)
        val peak = when {
            distinct >= RED_DISTINCT -> Tier.RED
            primary >= 1 -> Tier.YELLOW
            hasCorroborator -> Tier.YELLOW  // loosened: lone killAura now YELLOW (was GREEN)
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

    /** [nameFor] or a short uuid fallback — the `nameFor(uuid) ?: uuid.toString().take(8)` pattern
     *  repeated across the report builders, [dev.iustitia.replay.ReplayState.focusName], and clip
     *  feedback. Consolidated here so the fallback shape can't drift. Fail-open to the short id. */
    fun nameOrShort(uuid: UUID): String = try { nameFor(uuid) ?: uuid.toString().take(8) } catch (_: Throwable) {
        uuid.toString().take(8)
    }

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
            lastRedAlertTick.clear()
            nameByUuid.clear()
            flagCounts.clear()
            maxVlByUuidCheck.clear()
        } catch (_: Throwable) {
            // fail-open
        }
    }

    /**
     * Wipe one player's session history (used by `/ius clear <player>`). Removes every trace of
     * [uuid] from all backing maps so the player's tier resets to GREEN, flag timeline clears, and
     * counts/peak-VL drop — a clean slate mid-session. Note this does NOT purge the per-check VL
     * contexts (those live in [dev.iustitia.checks.Check]); the caller ([dev.iustitia.Iustitia]
     * clearPlayerFlags) does that via `Check.purge`. Fail-open.
     */
    fun clearPlayer(uuid: UUID) {
        try {
            flagsByUuid.remove(uuid)
            alertCountByUuid.remove(uuid)
            alertedChecksByUuid.remove(uuid)
            lastAlertWallMsByUuid.remove(uuid)
            lastRedAlertTick.remove(uuid)
            // name is intentionally KEPT so /ius hist <name> + tab-completion still resolve after a
            // clear (the player is still tracked; only their flags are wiped).
            flagCounts.remove(uuid)
            maxVlByUuidCheck.remove(uuid)
        } catch (_: Throwable) {
            // fail-open
        }
    }
}
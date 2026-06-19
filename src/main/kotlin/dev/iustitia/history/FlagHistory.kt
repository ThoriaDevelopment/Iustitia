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
 * Tiering (user-confirmed "definitive + sticky"):
 *  - **RED** — a [DEFINITIVE] check alerted on this player. Sticky for the session: a proven
 *    cheater doesn't drop back to yellow/green when they pause. `definitiveAlerted` is latched.
 *  - **YELLOW** — ≥1 alert this session, but no definitive check alerted (borderline signals).
 *  - **GREEN** — no alerts this session (low verbose-only flags are fine — "mostly legit").
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
    )

    enum class Tier { GREEN, YELLOW, RED }

    /**
     * Checks whose alert "proves cheating" → latches a player to RED. Conservative on purpose
     * (Hypixel-style flags were true positives — the standing constraint is to not loosen TP
     * detection): excludes speedEnvelope (historical lag FP), clickStatistics/rotationTracking
     * (inferential), and the minor rotation/packet signals. Easy to tune later.
     */
    val DEFINITIVE: Set<String> = setOf(
        "reach", "killAura", "multiTarget", "criticals", "autoBlock", "throughWalls",
        "hitFlick", "noFallDamage", "flyEnvelope", "longJump", "phaseClip", "teleport",
        "timerRate", "waterWalk", "scaffoldRotation",
    )

    private val flagsByUuid = ConcurrentHashMap<UUID, ArrayDeque<Flag>>()
    private val alertCountByUuid = ConcurrentHashMap<UUID, Int>()
    private val definitiveByUuid = ConcurrentHashMap<UUID, Boolean>()
    private val nameByUuid = ConcurrentHashMap<UUID, String>()

    /** Record a flag (called from [dev.iustitia.checks.Check.flag] for every flag, verbose-level). */
    fun recordFlag(uuid: UUID, name: String, checkId: String, label: String, vl: Double, tick: Int) {
        try {
            noteName(uuid, name)
            val dq = flagsByUuid.getOrPut(uuid) { ArrayDeque() }
            synchronized(dq) {
                dq.addFirst(Flag(tick, checkId, label, vl, System.currentTimeMillis()))
                while (dq.size > CAP) dq.removeLast()
            }
        } catch (_: Throwable) {
            // fail-open: history must never block a flag
        }
    }

    /** Record a real alert event (called from AlertManager.alert AFTER throttle passes). */
    fun recordAlert(uuid: UUID, checkId: String, name: String, tick: Int) {
        try {
            noteName(uuid, name)
            alertCountByUuid.compute(uuid) { _, v -> (v ?: 0) + 1 }
            if (checkId in DEFINITIVE) definitiveByUuid[uuid] = true
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

    fun tierFor(uuid: UUID): Tier = try {
        when {
            definitiveByUuid[uuid] == true -> Tier.RED
            (alertCountByUuid[uuid] ?: 0) > 0 -> Tier.YELLOW
            else -> Tier.GREEN
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
            definitiveByUuid.clear()
            nameByUuid.clear()
        } catch (_: Throwable) {
            // fail-open
        }
    }
}
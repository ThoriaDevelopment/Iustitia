package dev.iustitia.exempt

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player anticheat exemption list (`/ius exempt`). An exempted player is skipped at the single
 * detection chokepoint [dev.iustitia.checks.Check.flag] — no VL is added, no flag is recorded, no
 * alert fires — so the player is effectively invisible to every check (movement + combat; all flags
 * route through `flag`). Tracking, replay capture, and rendering still run for the player (an exempt
 * player is simply green/never-flagged, not untracked); only detection is suppressed.
 *
 * Forward-looking only: exempting does NOT clear a player's existing flags/VL — use `/ius clear
 * <player>` for that. Exemptions are NOT cleared on world change (a modder's trusted-player list
 * shouldn't wipe between servers), unlike the per-session detection state.
 *
 * Persists to `%APPDATA%/.iustitia/exemptions.json` via [dev.iustitia.persistence.PersistenceManager]
 * when the persistence toggle is on (mirrors [dev.iustitia.persistence.NoteStore]); session-only
 * otherwise. All paths fail-open. **Local-only** — nothing is sent to the server.
 */
object Exemptions {

    /** uuid → last-known name (name kept only for display in `/ius exempt` listing). */
    private val exempt = ConcurrentHashMap<UUID, String>()

    fun isExempt(uuid: UUID): Boolean = try { exempt.containsKey(uuid) } catch (_: Throwable) { false }

    /** Set exemption on/off. Returns the new exempt state. Persists (debounced) when persistence is on. */
    fun set(uuid: UUID, name: String, on: Boolean): Boolean {
        try {
            if (on) exempt[uuid] = name else exempt.remove(uuid)
            try { dev.iustitia.persistence.PersistenceManager.saveExemptions() } catch (_: Throwable) {}
        } catch (_: Throwable) {}
        return on
    }

    /** Toggle exemption. Returns the new exempt state. */
    fun toggle(uuid: UUID, name: String): Boolean = set(uuid, name, !isExempt(uuid))

    /** Populate from persisted load — does NOT re-schedule a save (avoids load/save churn). */
    fun load(uuid: UUID, name: String) {
        try { exempt[uuid] = name } catch (_: Throwable) {}
    }

    /** All exempted players (uuid → name), sorted by name — for the `/ius exempt` listing + serialization. */
    fun all(): List<Pair<UUID, String>> = try {
        exempt.entries.map { it.key to it.value }.sortedBy { it.second.lowercase() }
    } catch (_: Throwable) { emptyList() }

    fun nameFor(uuid: UUID): String? = try { exempt[uuid] } catch (_: Throwable) { null }

    /** Clear all exemptions (not wired to world-change [dev.iustitia.Iustitia.resetAll] by design). */
    fun clear() { try { exempt.clear() } catch (_: Throwable) {} }
}
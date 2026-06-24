package dev.iustitia.persistence

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory moderator-notation store for `/ius note`. Notes follow a player for the rest of the
 * session regardless of the persistence toggle — the toggle only controls whether they're written
 * to `%APPDATA%/.iustitia/notes.json` (via [PersistenceManager]) so they survive a restart.
 *
 * Categories are the four the user specified: `closet` (subtle/low-confidence cheat), `blatant`
 * (obvious), `needsReview` (watch but undecided), `legit` (cleared — suppress future hackusation).
 * Fail-open everywhere.
 */
object NoteStore {

    enum class Category { CLOSET, BLATANT, NEEDS_REVIEW, LEGIT }

    data class Note(
        val uuid: UUID,
        var name: String,
        var category: Category,
        var text: String,
        var tick: Int,
    )

    private val notes = ConcurrentHashMap<UUID, Note>()

    fun set(uuid: UUID, name: String, category: Category, text: String, tick: Int) {
        try {
            notes[uuid] = Note(uuid, name, category, text, tick)
            // Debounced write to notes.json (no-op when the persistence toggle is off).
            try { dev.iustitia.persistence.PersistenceManager.saveNote() } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    fun get(uuid: UUID): Note? = try { notes[uuid] } catch (_: Throwable) { null }

    fun remove(uuid: UUID) {
        try { notes.remove(uuid)
            try { dev.iustitia.persistence.PersistenceManager.saveNote() } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    /** All notes — for serialization by [PersistenceManager]. */
    fun all(): Collection<Note> = try { notes.values } catch (_: Throwable) { emptyList() }

    fun clear() { try { notes.clear() } catch (_: Throwable) {} }

    fun parseCategory(s: String): Category? = when (s.lowercase()) {
        "closet" -> Category.CLOSET
        "blatant" -> Category.BLATANT
        "needsreview", "needs-review", "review" -> Category.NEEDS_REVIEW
        "legit" -> Category.LEGIT
        else -> null
    }

    fun categoryLabel(c: Category): String = when (c) {
        Category.CLOSET -> "§ecloset"
        Category.BLATANT -> "§cblatant"
        Category.NEEDS_REVIEW -> "§6needs review"
        Category.LEGIT -> "§alegit"
    }
}
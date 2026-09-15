package dev.iustitia.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

/**
 * Shared file-write plumbing for every persisted Iustitia file (config, presets, notes/history,
 * snapshots/exports, chat history, clips, debug reports).
 *
 * **Atomic writes:** [write] / [writeBytes] stage the content at `<path>.tmp` and then
 * [commit] it with `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` — a reader (or a crash) never
 * sees a half-written file; the previous file stays intact until the new one is complete.
 * `ATOMIC_MOVE` isn't guaranteed by every filesystem; on `AtomicMoveNotSupportedException`
 * we fall back to a plain `REPLACE_EXISTING` move (still staged — never an in-place
 * truncate-then-write). Both return `false` on any failure instead of throwing, and clean the
 * temp file up behind them.
 *
 * **One shared IO thread:** [io] hands a write (or any small blocking file operation) to a
 * single daemon executor so caller threads — the client/render thread included — never block
 * on disk. Everything handed to [io] must be already-serialized (build the text on the caller
 * thread; the executor only writes). The thread is a daemon, so tasks submitted near JVM exit
 * may not land — anything that must survive shutdown goes through the synchronous [flush] /
 * [write] paths (the CLIENT_STOPPING hooks + shutdown hooks), not [io].
 */
object AtomicFiles {

    /** Temp-file suffix appended to the target name while staging. */
    private const val TMP_SUFFIX = ".tmp"

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Iustitia-FileIO").apply { isDaemon = true }
    }

    /** Run [block] on the shared daemon IO thread. Never throws; errors inside [block] are the
     *  block's own business (it should fail-open). Rejected tasks (executor shut down) are dropped. */
    fun io(block: () -> Unit) {
        try {
            ioExecutor.execute { try { block() } catch (_: Throwable) {} }
        } catch (_: Throwable) {
            // executor rejected (shutdown) — fail-open, the caller's state stays in memory
        }
    }

    /** Atomically write [text] (UTF-8) to [path]. Returns true iff the file landed. Never throws. */
    fun write(path: Path, text: String): Boolean = try {
        writeBytes(path, text.toByteArray(Charsets.UTF_8))
    } catch (_: Throwable) { false }

    /** Atomically write [bytes] to [path]. Returns true iff the file landed. Never throws. */
    fun writeBytes(path: Path, bytes: ByteArray): Boolean {
        val tmp = tmpFor(path)
        return try {
            try { Files.createDirectories(path.parent) } catch (_: Throwable) {}
            Files.write(tmp, bytes)
            commit(tmp, path)
        } catch (_: Throwable) {
            try { Files.deleteIfExists(tmp) } catch (_: Throwable) {}
            false
        }
    }

    /** Stage path for [path] — `<path>.tmp` next to the target (same directory, so the commit
     *  move never crosses filesystems). */
    fun tmpFor(path: Path): Path = path.resolveSibling(path.fileName.toString() + TMP_SUFFIX)

    /** Move a fully-written [tmp] file onto [path] (used by stream writers that fill the temp
     *  file themselves, e.g. [dev.iustitia.replay.ClipStore]). Returns true iff the file landed;
     *  cleans the temp file up on failure. Never throws. */
    fun commit(tmp: Path, path: Path): Boolean = try {
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Throwable) {
            // ATOMIC_MOVE unsupported on this filesystem — still staged: a plain replace move
            // keeps the no-partial-file guarantee for readers, just not the crash-atomicity.
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            true
        }
    } catch (_: Throwable) {
        try { Files.deleteIfExists(tmp) } catch (_: Throwable) {}
        false
    }
}
package dev.iustitia.replay

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * `.iusclip` file storage. Clips are written to `%APPDATA%/.iustitia/clips` on Windows (roaming, so
 * a multi-instance Modrinth user finds them in one place regardless of instance path) and to the
 * game-dir `.iustitia/clips` elsewhere.
 *
 * Unlike the passive persistence store ([dev.iustitia.persistence.PersistenceManager], gated by
 * [dev.iustitia.config.IustitiaConfig.persistenceEnabled]), a clip is an EXPLICIT user export
 * (`/ius clip`), so it always writes — independent of the persistence toggle. **Local-only** —
 * nothing is uploaded. All paths fail-open: a missing/corrupt file yields a null clip, never a throw.
 */
object ClipStore {

    private const val EXT = ".iusclip"

    /** Roaming clips dir, created if missing. `%APPDATA%/.iustitia/clips` on Windows, game-dir elsewhere. */
    private val clipsDir: Path by lazy {
        val appdata = try { System.getenv("APPDATA") } catch (_: Throwable) { null }
        val base = if (appdata != null) Path.of(appdata).resolve(".iustitia")
        else FabricLoader.getInstance().gameDir.resolve(".iustitia")
        base.resolve("clips")
    }

    /** A display name (no extension) for each `.iusclip` in the dir, sorted. Empty if none / error. */
    fun list(): List<String> = try {
        Files.createDirectories(clipsDir)
        Files.list(clipsDir).use { stream ->
            stream.map { it.fileName.toString() }
                .filter { it.endsWith(EXT) }
                .map { it.removeSuffix(EXT) }
                .sorted()
                .toList()
        }
    } catch (_: Throwable) { emptyList() }

    /** Resolve a user-typed name (with or without the extension) to its clip path. */
    private fun pathFor(name: String): Path {
        val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val withExt = if (safe.endsWith(EXT)) safe else safe + EXT
        return clipsDir.resolve(withExt)
    }

    /**
     * Write [window] (+ optional [focus]) to `<name>.iusclip` and return the display name, or null on
     * any IO/codec error. Called on the client thread (command handler).
     */
    fun save(name: String, window: ReplayBuffer.Window, focus: java.util.UUID?): String? = try {
        Files.createDirectories(clipsDir)
        val path = pathFor(name)
        Files.newOutputStream(path).use { out -> ClipCodec.write(out, window, focus) }
        path.fileName.toString().removeSuffix(EXT)
    } catch (_: Throwable) { null }

    /** Load + decode `<name>.iusclip`, or null if missing/corrupt. Called on the client thread. */
    fun load(name: String): ClipCodec.Clip? = try {
        val path = pathFor(name)
        if (!Files.exists(path)) return null
        Files.newInputStream(path).use { inp -> ClipCodec.read(inp) }
    } catch (_: Throwable) { null }

    /** Read only the header (version + focus + frame/alert counts) for `<name>.iusclip` — cheap, no
     *  per-snap data. Null if missing/corrupt. Used by the clip-manager list to show counts. */
    fun metadata(name: String): ClipCodec.ClipMeta? = try {
        val path = pathFor(name)
        if (!Files.exists(path)) return null
        Files.newInputStream(path).use { inp -> ClipCodec.readHeader(inp) }
    } catch (_: Throwable) { null }

    /** Delete `<name>.iusclip`. Returns true if a file was removed. Fail-open. */
    fun delete(name: String): Boolean = try {
        val path = pathFor(name)
        Files.deleteIfExists(path)
    } catch (_: Throwable) { false }

    /** Human-readable path for feedback messages (e.g. "saved to …"). */
    fun dirDisplay(): String = try { clipsDir.toString() } catch (_: Throwable) { "clips" }
}
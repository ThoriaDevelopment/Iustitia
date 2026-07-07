package dev.iustitia.replay

import net.minecraft.client.MinecraftClient
import net.minecraft.registry.Registries
import net.minecraft.util.math.BlockPos
import net.minecraft.world.chunk.ChunkStatus

/**
 * Captures a bounded cube of the loaded terrain around a clip's action and returns a
 * [TerrainSnapshot] (run-length encoded) to bundle into the `.iusclip`. Called from the `/ius clip`
 * handler on the client thread.
 *
 * ## Why the bbox, not the whole loaded area
 *
 * The client only has **loaded chunks within render distance** — never the server's map file.
 * Capturing the *entire* loaded render distance in one pass is infeasible (a 16-chunk radius × full
 * height is millions of block reads → a multi-second hitch). So the capture is the **bounding box of
 * every player position across the clip window + [MARGIN] each axis**, clamped to loaded chunks: the
 * part of the loaded area that's actually relevant to the clip. For a KitPvP arena fight this is the
 * whole arena; for a spread-out scene the volume cap shrinks the margin. Unloaded chunks inside the
 * bbox are treated as air (fail-open — recent combat is loaded).
 *
 * ## Run-length encoding
 *
 * The bbox is walked y-major → z → x. Consecutive blocks with the same identity (air, or a given
 * block registry id) are coalesced into a [TerrainSnapshot.Run]. An arena is mostly air → the air
 * runs dominate and the file is small; only the non-air shell carries names. Block *registry ids*
 * (`"minecraft:stone"`) are stored — not numeric state ids — so a clip is portable across server
 * versions and dimensions (names resolve on any client, or are skipped fail-open if unregistered).
 * Properties are dropped (the shell renderer draws uniform cuboids).
 *
 * Read-only, fail-open throughout: any error returns null (clip saves with no terrain; playback
 * then shows ghosts relocated but no map — the user can re-record). No outgoing packets, no world
 * edits — this only reads [net.minecraft.client.world.ClientWorld.getBlockState].
 */
object TerrainCapture {

    /** Blocks added around the action bbox each axis (the captured region = player bbox + this). */
    private const val MARGIN = 24

    /** Per-axis half-extent cap around the focus so a spread-out scene can't blow up the volume. */
    private const val CAP_HALF = 48

    /** Total block budget; if the bbox exceeds it the margin is shrunk to fit (fail-open to smaller). */
    const val MAX_TERRAIN_BLOCKS = 250_000

    /**
     * Capture the terrain around [window]'s action. [focus] picks the anchor for the per-axis cap
     * (the focus player's start, else the first-frame centroid). Returns null on any failure / empty
     * bbox / no loaded world — the clip then saves without terrain.
     */
    fun capture(window: ReplayBuffer.Window, focus: java.util.UUID?): TerrainSnapshot? = try {
        val world = MinecraftClient.getInstance().world ?: return null
        if (window.frames.isEmpty()) return null

        // --- 1. player-position bbox across the whole window ---
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (f in window.frames) {
            for (s in f.snaps) {
                if (s.x < minX) minX = s.x; if (s.x > maxX) maxX = s.x
                if (s.y < minY) minY = s.y; if (s.y > maxY) maxY = s.y
                if (s.z < minZ) minZ = s.z; if (s.z > maxZ) maxZ = s.z
            }
        }
        if (minX.isNaN() || maxX < minX) return null

        // --- 2. anchor for the per-axis cap (focus player's start, else first-frame centroid) ---
        val first = window.frames.first().snaps
        val anchor = (first.firstOrNull { it.uuid() == focus } ?: first.firstOrNull())
        val ax = anchor?.x?.toDouble() ?: ((minX + maxX) * 0.5).toDouble()
        val ay = anchor?.y?.toDouble() ?: ((minY + maxY) * 0.5).toDouble()
        val az = anchor?.z?.toDouble() ?: ((minZ + maxZ) * 0.5).toDouble()

        // --- 3. bbox: player extent + MARGIN, clamped to ±CAP_HALF around the anchor, then volume-capped ---
        var loX = (Math.floor(minX.toDouble() - MARGIN)).toInt()
        var hiX = (Math.ceil(maxX.toDouble() + MARGIN)).toInt()
        var loY = (Math.floor(minY.toDouble() - MARGIN)).toInt()
        var hiY = (Math.ceil(maxY.toDouble() + MARGIN)).toInt()
        var loZ = (Math.floor(minZ.toDouble() - MARGIN)).toInt()
        var hiZ = (Math.ceil(maxZ.toDouble() + MARGIN)).toInt()
        loX = maxOf(loX, (Math.floor(ax - CAP_HALF)).toInt()); hiX = minOf(hiX, (Math.ceil(ax + CAP_HALF)).toInt())
        loY = maxOf(loY, (Math.floor(ay - CAP_HALF)).toInt()); hiY = minOf(hiY, (Math.ceil(ay + CAP_HALF)).toInt())
        loZ = maxOf(loZ, (Math.floor(az - CAP_HALF)).toInt()); hiZ = minOf(hiZ, (Math.ceil(az + CAP_HALF)).toInt())
        if (hiX <= loX || hiY <= loY || hiZ <= loZ) return null

        // volume cap: if the bbox exceeds the budget, shrink the margin (the per-axis cap above is the
        // first line of defense; this catches a tall/wide arena that still overflows). We shrink by
        // trimming each axis proportionally from the anchor outward.
        var sx = hiX - loX + 1; var sy = hiY - loY + 1; var sz = hiZ - loZ + 1
        if (sx.toLong() * sy * sz > MAX_TERRAIN_BLOCKS) {
            val scale = Math.cbrt(MAX_TERRAIN_BLOCKS.toDouble() / (sx.toDouble() * sy * sz))
            sx = (sx * scale).toInt().coerceAtLeast(1); sy = (sy * scale).toInt().coerceAtLeast(1); sz = (sz * scale).toInt().coerceAtLeast(1)
            loX = (Math.floor(ax - sx / 2.0)).toInt(); hiX = loX + sx - 1
            loY = (Math.floor(ay - sy / 2.0)).toInt(); hiY = loY + sy - 1
            loZ = (Math.floor(az - sz / 2.0)).toInt(); hiZ = loZ + sz - 1
        }

        // --- 4. precompute a per-chunk loaded cache over the bbox chunk range, so the block walk does
        //        one cheap lookup per block instead of a chunk-manager call (which is a hash lookup +
        //        a ChunkStatus check) per block. Unloaded chunks → all-air (fail-open). ---
        val minCX = loX shr 4; val maxCX = hiX shr 4
        val minCZ = loZ shr 4; val maxCZ = hiZ shr 4
        val loaded = BooleanArray((maxCX - minCX + 1) * (maxCZ - minCZ + 1))
        for (cx in minCX..maxCX) for (cz in minCZ..maxCZ) {
            loaded[(cx - minCX) * (maxCZ - minCZ + 1) + (cz - minCZ)] = try {
                world.chunkManager.getChunk(cx, cz, ChunkStatus.FULL, false) != null
            } catch (_: Throwable) { false }
        }

        // --- 5. walk the bbox y-major → z → x, RLE-coalescing consecutive same-identity blocks ---
        val runs = ArrayList<TerrainSnapshot.Run>()
        var lastName: String? = null
        var lastCount = 0
        for (y in loY..hiY) {
            for (z in loZ..hiZ) {
                val czOff = (z shr 4) - minCZ
                for (x in loX..hiX) {
                    val chunkOK = loaded[((x shr 4) - minCX) * (maxCZ - minCZ + 1) + czOff]
                    val name: String? = if (!chunkOK) null else try {
                        val state = world.getBlockState(BlockPos(x, y, z))
                        if (state.isAir) null else Registries.BLOCK.getId(state.block).toString()
                    } catch (_: Throwable) { null }
                    // coalesce: same identity extends the current run; a change flushes the old + starts new
                    if (name == lastName) {
                        lastCount++
                    } else {
                        if (lastCount > 0) runs.add(TerrainSnapshot.Run(lastCount, lastName))
                        lastName = name
                        lastCount = 1
                    }
                }
            }
        }
        if (lastCount > 0) runs.add(TerrainSnapshot.Run(lastCount, lastName))

        TerrainSnapshot(loX, loY, loZ, hiX - loX + 1, hiY - loY + 1, hiZ - loZ + 1, runs)
    } catch (_: Throwable) {
        null
    }
}
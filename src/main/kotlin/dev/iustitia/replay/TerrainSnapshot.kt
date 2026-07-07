package dev.iustitia.replay

/**
 * A captured cube of terrain bundled into a `.iusclip` (v5+), so a clip recorded on server A can be
 * played back in full on server B with the map visible around the ghosts. The capture is bounded to
 * the **action bbox + margin** (the loaded chunks around the fight) — see [TerrainCapture] for the
 * infeasibility of capturing the whole render distance.
 *
 * ## Storage
 *
 * The region is stored as **run-length encoded runs** over the linearized bbox, iterated y-major → z
 * → x (the order [TerrainCapture] writes them). A [Run] is `(count, name)`; `name == null` means air.
 * Only non-air block names are carried (no properties — the [dev.iustitia.render.TerrainOverlay]
 * shell renderer draws uniform cuboids tinted by block category, so orientation is irrelevant). Block
 * *registry ids* (`"minecraft:stone"`) are used, not numeric state ids — so a clip is portable across
 * server versions and dimensions (a nether block name resolves on the overworld client too, or is
 * skipped fail-open if unregistered).
 *
 * Compact: an arena fight's bbox is mostly air → air runs dominate; the non-air shell is a few
 * thousand entries. The 250k-block capture cap ([TerrainCapture.MAX_TERRAIN_BLOCKS]) bounds the
 * worst case; a spread-out scene shrinks the margin.
 *
 * ## Render
 *
 * [blocks] expands the runs back to absolute `(x, y, z, name)` once at clip load; the
 * [dev.iustitia.render.TerrainOverlay] then face-culls that grid to the visible shell and draws it
 * relocated to the user (see [dev.iustitia.replay.ReplayState.relocOffset]). Render is self-contained
 * — it never re-reads the live world, so a clip's terrain renders even after the source chunks
 * unload or on a different server.
 */
data class TerrainSnapshot(
    /** Bbox origin (min corner), absolute recorded-world block coords. */
    val originX: Int, val originY: Int, val originZ: Int,
    /** Bbox size in blocks (the region is [originX .. originX+sizeX-1] etc.). */
    val sizeX: Int, val sizeY: Int, val sizeZ: Int,
    /** RLE runs over the linearized bbox (y-major → z → x). `name == null` = air. */
    val runs: List<Run>,
) {
    /** One RLE run: [count] consecutive blocks; [name] is the block registry id, or null for air. */
    data class Run(val count: Int, val name: String?)

    /** One expanded block: absolute recorded-world coords + block registry id (never air here). */
    data class Block(val x: Int, val y: Int, val z: Int, val name: String)

    /** Total block count in the bbox (= sum of all run counts). */
    fun volume(): Int = runs.sumOf { it.count }

    /** Non-air block count (for the clip-manager list view + the [ClipCodec.ClipMeta] header). */
    fun nonAirCount(): Int = runs.sumOf { if (it.name == null) 0 else it.count }

    /**
     * Expand the runs back to absolute blocks (the non-air ones). Pure — no world access. Used once
     * at clip load by [dev.iustitia.render.TerrainOverlay] to build the face-culled shell. Iteration
     * order MUST match [TerrainCapture] (y-major → z → x) so the linearized index → (x,y,z) map is
     * the inverse of the writer.
     *
     * Walks the runs with a single advancing cursor (O(blocks + runs)) rather than re-scanning the
     * run list per block — the bbox can hold up to [TerrainCapture.MAX_TERRAIN_BLOCKS] entries, so a
     * per-block linear search would be quadratic.
     */
    fun blocks(): List<Block> = try {
        val out = ArrayList<Block>(nonAirCount())
        var runIdx = 0
        var leftInRun = runs.firstOrNull()?.count ?: 0
        for (y in originY until originY + sizeY) {
            for (z in originZ until originZ + sizeZ) {
                for (x in originX until originX + sizeX) {
                    val name = runs.getOrNull(runIdx)?.name
                    if (name != null) out.add(Block(x, y, z, name))
                    leftInRun--
                    if (leftInRun <= 0) {
                        runIdx++
                        leftInRun = runs.getOrNull(runIdx)?.count ?: 0
                    }
                }
            }
        }
        out
    } catch (_: Throwable) {
        emptyList()
    }
}
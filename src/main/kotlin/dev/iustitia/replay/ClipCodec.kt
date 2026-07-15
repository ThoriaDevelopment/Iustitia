package dev.iustitia.replay

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Binary `.iusclip` codec. A clip is a self-contained recording of the last N seconds of the scene:
 * every tracked player's per-tick position/yaw/**pitch**/pose, plus the real alert events that fired
 * in the window. Played back by [dev.iustitia.render.ReplayRenderer] via [dev.iustitia.replay.ReplayState]
 * exactly like a live `/ius replay`.
 *
 * ## Format (big-endian `DataOutput/InputStream`, length-prefixed UTF)
 *
 * ```
 * magic   : 4 bytes  "IUSC"
 * version : int      (8 — v8 adds per-snap held-item + armor registry-id strings;
 *                     v7 adds per-snap hurtTime/health/maxHealth + the optional totems section;
 *                     v6 adds the optional chunks section [full-chunk world capture];
 *                     v5 adds the optional terrain section; v4 had no terrain;
 *                     v3 had no per-snap swingTicks; v2 had no per-snap pitch; v1 no per-snap name)
 * focus?  : byte 0/1, then uuidMost:long, uuidLeast:long  (the highlighted player, if any)
 * frames  : int count, then per frame:
 *              tick:int, playerCount:int, per player:
 *                  uuidMost:long, uuidLeast:long, x:float, y:float, z:float, yaw:float,
 *                  pitch:float  (v3+ ONLY — v2 stops here),
 *                  swingTicks:int  (v4+ ONLY — v3 stops here),
 *                  pose:byte,
 *                  hurtTime:byte, health:float, maxHealth:float  (v7+ ONLY — v6 stops at pose),
 *                  mainHand:UTF, offHand:UTF, head:UTF, chest:UTF, legs:UTF, feet:UTF  (v8+ ONLY),
 *                  name:UTF
 * alerts  : int count, then per alert:
 *              tick:int, uuidMost:long, uuidLeast:long,
 *              name:UTF, checkId:UTF, label:UTF, vl:float
 * terrain?: (v5+ ONLY) byte 0/1; if 1:
 *              originX:int, originY:int, originZ:int,
 *              sizeX:int, sizeY:int, sizeZ:int,
 *              runCount:int, per run: count:int, nameLen:short, name UTF-8 (short=-1 → air)
 * chunks?: (v6+ ONLY) byte 0/1; if 1:
 *              chunkCount:int, per chunk:
 *                  chunkX:int, chunkZ:int, sectionCount:int,
 *                  per section: sectionY:int, paletteSize:short,
 *                      per palette entry: nameLen:short, name UTF-8,
 *                      dataLen:int, data bytes (4096 if paletteSize ≤ 256 else 8192)
 * totems?: (v7+ ONLY) byte 0/1; if 1:
 *              totemCount:int, per totem: tick:int, uuidMost:long, uuidLeast:long
 * ```
 *
 * ## Backward compatibility
 *
 * [read] accepts **v2 through v8**. v2 clips have no per-snap pitch → pitch defaults to 0 (the
 * ghost still faces its yaw, just won't tilt its head). v3 clips have no per-snap swingTicks →
 * swingTicks defaults to 0 (arms hang still, but the ghost still walks / faces / tilts). v4 and
 * earlier clips have no terrain → terrain defaults to null. v5 and earlier clips have no chunks →
 * chunks defaults to null (playclip then renders the v5 wireframe terrain / ghosts only — exactly the
 * path for an old clip recorded before full-chunk capture). v6 and earlier clips have no per-snap
 * hurtTime/health/maxHealth → they default to 0/20/20 (no red flash, full-health bar) and no totems
 * section → the totem list is empty (no `⚡` badge). v7 and earlier clips have no per-snap equipment
 * → all six slots default to `""` (the skin-only ghost holds nothing and wears no armor). v1 (no
 * per-snap name) is not accepted. **Old v2–v7 clips keep loading** so a user's saved library isn't
 * invalidated.
 * [readHeader] reads only up through the counts (no per-snap data, no terrain runs, no chunk
 * section bodies) — cheap for the clip manager's list view.
 *
 * Compact: a 30s, 40-player v3 clip ≈ 30*20*40 * (8+16+4+1+~12) ≈ 740 KB; the 60s cap keeps it in the
 * low single-digit MB. Terrain is RLE'd (mostly air → few runs), adds ~tens of KB for an arena.
 * Fail-open: a corrupt/short file returns null from [read]/[readHeader] instead of throwing.
 */
object ClipCodec {

    private const val MAGIC = "IUSC"
    const val VERSION = 8
    /** Lowest version [read] will accept (v2 = no per-snap pitch; loaded with pitch defaulting to 0). */
    const val MIN_VERSION = 2

    /** A decoded clip: its frames + alerts + the optional focus player + optional terrain + chunks snapshots. */
    data class Clip(
        val window: ReplayBuffer.Window, val focus: UUID?,
        val terrain: TerrainSnapshot? = null, val chunks: ChunkSnapshot? = null,
    )

    /** Lightweight header (no per-snap data, no terrain runs, no chunk section bodies) for the clip-manager
     *  list. [terrainBlocks] / [chunkSections] feed the row's map-size display. Fail-open. */
    data class ClipMeta(
        val version: Int, val focus: UUID?, val frameCount: Int, val alertCount: Int,
        val terrainBlocks: Int = 0, val chunkSections: Int = 0,
    )

    fun write(out: OutputStream, window: ReplayBuffer.Window, focus: UUID?) {
        try {
            val d = DataOutputStream(out)
            d.writeBytes(MAGIC)            // 4 ASCII bytes (no length prefix)
            d.writeInt(VERSION)
            if (focus == null) d.writeByte(0) else {
                d.writeByte(1)
                d.writeLong(focus.mostSignificantBits)
                d.writeLong(focus.leastSignificantBits)
            }
            d.writeInt(window.frames.size)
            for (f in window.frames) {
                d.writeInt(f.tick)
                d.writeInt(f.snaps.size)
                for (s in f.snaps) {
                    d.writeLong(s.uuidMost)
                    d.writeLong(s.uuidLeast)
                    d.writeFloat(s.x); d.writeFloat(s.y); d.writeFloat(s.z)
                    d.writeFloat(s.yaw)
                    d.writeFloat(s.pitch)
                    d.writeInt(s.swingTicks)
                    d.writeByte(s.pose.toInt())
                    // v7+ per-snap combat-sync fields (hurt time + health). Written before `name`
                    // to group the numeric fields; `name` stays last (length-prefixed UTF).
                    d.writeByte(s.hurtTime.toInt())
                    d.writeFloat(s.health)
                    d.writeFloat(s.maxHealth)
                    // v8+ per-snap equipment: registry-id string per held/armor slot (empty "" = empty
                    // slot). Written before `name` (which stays last, length-prefixed UTF).
                    d.writeUTF(s.mainHand)
                    d.writeUTF(s.offHand)
                    d.writeUTF(s.head)
                    d.writeUTF(s.chest)
                    d.writeUTF(s.legs)
                    d.writeUTF(s.feet)
                    d.writeUTF(s.name)
                }
            }
            d.writeInt(window.alerts.size)
            for (a in window.alerts) {
                d.writeInt(a.tick)
                d.writeLong(a.uuidMost)
                d.writeLong(a.uuidLeast)
                d.writeUTF(a.name)
                d.writeUTF(a.checkId)
                d.writeUTF(a.label)
                d.writeFloat(a.vl)
            }
            // v5+ terrain section. Always written for v5 (byte 0 = no terrain, 1 = terrain present)
            // so the reader can branch cleanly; old v2–v4 clips simply never had this byte.
            val t = window.terrain
            if (t == null) {
                d.writeByte(0)
            } else {
                d.writeByte(1)
                d.writeInt(t.originX); d.writeInt(t.originY); d.writeInt(t.originZ)
                d.writeInt(t.sizeX); d.writeInt(t.sizeY); d.writeInt(t.sizeZ)
                d.writeInt(t.runs.size)
                for (r in t.runs) {
                    d.writeInt(r.count)
                    if (r.name == null) d.writeShort(-1) else {
                        val bytes = r.name.toByteArray(Charsets.UTF_8)
                        d.writeShort(bytes.size)
                        d.write(bytes)
                    }
                }
            }
            // v6+ chunks section (full-chunk world capture). Always written for v6 (byte 0 = no
            // chunks, 1 = chunks present) so the reader branches cleanly; v2–v5 clips never had this
            // byte. data is 4096 bytes when a section's palette has ≤ 256 entries, else 8192.
            val c = window.chunks
            if (c == null) {
                d.writeByte(0)
            } else {
                d.writeByte(1)
                d.writeInt(c.chunks.size)
                for (chunk in c.chunks) {
                    d.writeInt(chunk.chunkX); d.writeInt(chunk.chunkZ)
                    d.writeInt(chunk.sections.size)
                    for (sec in chunk.sections) {
                        d.writeInt(sec.sectionY)
                        d.writeShort(sec.palette.size)
                        for (name in sec.palette) {
                            val bytes = name.toByteArray(Charsets.UTF_8)
                            d.writeShort(bytes.size)
                            d.write(bytes)
                        }
                        d.writeInt(sec.data.size)
                        d.write(sec.data)
                    }
                }
            }
            // v7+ totems section (Totem-of-Undying pop events). Always written for v7 (byte 0 = no
            // totems, 1 = totems present) so the reader branches cleanly; v2–v6 clips never had
            // this byte. Each event is (tick, uuidMost, uuidLeast).
            val totems = window.totems
            if (totems.isEmpty()) {
                d.writeByte(0)
            } else {
                d.writeByte(1)
                d.writeInt(totems.size)
                for (t in totems) {
                    d.writeInt(t.tick)
                    d.writeLong(t.uuidMost)
                    d.writeLong(t.uuidLeast)
                }
            }
            d.flush()
        } catch (_: Throwable) {
            // fail-open: a write error surfaces as a null save result in ClipStore, not a throw
        }
    }

    /** Decode a clip, or null if the stream is missing the magic / wrong version / truncated / corrupt. Fail-open. */
    fun read(input: InputStream): Clip? = try {
        val d = DataInputStream(input)
        if (!checkMagic(d)) return null
        val version = d.readInt()
        if (version < MIN_VERSION || version > VERSION) return null
        val focus = readFocus(d)
        val frames = readFrames(d, version) ?: return null
        val alerts = readAlerts(d) ?: return null
        val terrain = if (version >= 5) readTerrainFlag(d) else null
        val chunks = if (version >= 6) readChunksFlag(d) else null
        val totems = if (version >= 7) readTotemsFlag(d) else emptyList()
        Clip(ReplayBuffer.Window(frames, alerts, terrain, chunks, totems), focus, terrain, chunks)
    } catch (_: Throwable) {
        null
    }

    /** Read only the header (version + focus + counts), or null on any error. Cheap — no per-snap data. */
    fun readHeader(input: InputStream): ClipMeta? = try {
        val d = DataInputStream(input)
        if (!checkMagic(d)) return null
        val version = d.readInt()
        if (version < MIN_VERSION || version > VERSION) return null
        val focus = readFocus(d)
        val frameCount = d.readInt()
        if (frameCount < 0 || frameCount > 12000) return null
        // The frame bodies sit between here and the alert count; skip them whole.
        for (i in 0 until frameCount) {
            d.readInt() // tick
            val pc = d.readInt()
            if (pc < 0 || pc > 256) return null
            for (j in 0 until pc) {
                d.readLong(); d.readLong() // uuidMost/Least
                d.readFloat(); d.readFloat(); d.readFloat() // x/y/z
                d.readFloat() // yaw
                if (version >= 3) d.readFloat() // pitch (v3+ only)
                if (version >= 4) d.readInt()   // swingTicks (v4+ only)
                d.readByte() // pose
                if (version >= 7) { d.readByte(); d.readFloat(); d.readFloat() } // hurtTime/health/maxHealth (v7+)
                if (version >= 8) { repeat(6) { d.readUTF() } } // mainHand/offHand/head/chest/legs/feet (v8+)
                d.readUTF() // name
            }
        }
        val alertCount = d.readInt()
        if (alertCount < 0 || alertCount > 100000) return null
        // v5+ terrain header: flag + run count + skip the run bodies (nameLen + name bytes each).
        // We also sum the non-air run counts for [ClipMeta.terrainBlocks] (the clip-manager row shows
        // a block count). Bound-check runCount so a corrupt file can't force a huge skip loop.
        var terrainBlocks = 0
        if (version >= 5) {
            when (d.readByte().toInt()) {
                0 -> { /* no terrain */ }
                1 -> {
                    d.readInt(); d.readInt(); d.readInt() // origin XYZ
                    d.readInt(); d.readInt(); d.readInt() // size XYZ
                    val runCount = d.readInt()
                    if (runCount < 0 || runCount > 5_000_000) return null
                    for (i in 0 until runCount) {
                        val count = d.readInt()
                        if (count < 0) return null
                        val nameLen = d.readShort().toInt()
                        if (nameLen == -1) {
                            // air run — no bytes to skip
                        } else if (nameLen in 0..32767) {
                            val skipped = d.skipBytes(nameLen)
                            if (skipped != nameLen) return null
                        } else return null
                        if (nameLen != -1) terrainBlocks += count
                    }
                }
                else -> return null
            }
        }
        // v6+ chunks header: flag + chunk count + skip the chunk/section bodies (palette names + data
        // bytes each). We sum section counts for [ClipMeta.chunkSections] (the clip-manager row shows a
        // section count). Bound-check counts so a corrupt file can't force a huge skip loop or OOM.
        var chunkSections = 0
        if (version >= 6) {
            when (d.readByte().toInt()) {
                0 -> { /* no chunks */ }
                1 -> {
                    val chunkCount = d.readInt()
                    if (chunkCount < 0 || chunkCount > 4096) return null
                    for (i in 0 until chunkCount) {
                        d.readInt(); d.readInt() // chunkX, chunkZ
                        val sectionCount = d.readInt()
                        if (sectionCount < 0 || sectionCount > 64) return null
                        chunkSections += sectionCount
                        for (j in 0 until sectionCount) {
                            d.readInt() // sectionY
                            val paletteSize = d.readShort().toInt()
                            if (paletteSize < 0 || paletteSize > 4096) return null
                            for (k in 0 until paletteSize) {
                                val nameLen = d.readShort().toInt()
                                if (nameLen < 0 || nameLen > 32767) return null
                                if (d.skipBytes(nameLen) != nameLen) return null
                            }
                            val dataLen = d.readInt()
                            if (dataLen < 0 || dataLen > 8192) return null
                            if (d.skipBytes(dataLen) != dataLen) return null
                        }
                    }
                }
                else -> return null
            }
        }
        // v7+ totems header: flag + event count + skip the event bodies (tick + uuidMost + uuidLeast
        // = 4 + 8 + 8 = 20 bytes each). Bound-check totemCount so a corrupt file can't force a huge
        // skip loop. We don't surface a totem count in [ClipMeta] (the row shows frames/alerts/sections).
        if (version >= 7) {
            when (d.readByte().toInt()) {
                0 -> { /* no totems */ }
                1 -> {
                    val totemCount = d.readInt()
                    if (totemCount < 0 || totemCount > 100000) return null
                    val skipBytes = totemCount * 20
                    if (d.skipBytes(skipBytes) != skipBytes) return null
                }
                else -> return null
            }
        }
        ClipMeta(version, focus, frameCount, alertCount, terrainBlocks, chunkSections)
    } catch (_: Throwable) {
        null
    }

    private fun checkMagic(d: DataInputStream): Boolean {
        val magic = ByteArray(4); if (d.read(magic) != 4) return false
        return String(magic) == MAGIC
    }

    /**
     * Read the focus flag + optional UUID. Returns `null` for the LEGITIMATE no-focus case (byte 0),
     * the focus UUID for byte 1, and THROWS for any other flag value — the throw is caught by the
     * outer try/catch in [read]/[readHeader] and surfaces as a null clip (a genuinely corrupt file).
     *
     * The throw (not a returned null) is what distinguishes "no focus" from "decode failed": a plain
     * `?: return null` at the call site would wrongly treat a valid no-focus clip as corrupt and drop
     * the whole clip — which is exactly the bug that made `/ius playclip <name>` say "no clip" for every
     * clip saved without a focus (the common case, since [name] is usually a clip filename, not an
     * online player). Callers must use `val focus = readFocus(d)` (NO `?: return null`).
     */
    private fun readFocus(d: DataInputStream): UUID? = when (d.readByte().toInt()) {
        0 -> null
        1 -> UUID(d.readLong(), d.readLong())
        else -> throw java.io.IOException("bad focus flag")
    }

    private fun readFrames(d: DataInputStream, version: Int): List<ReplayBuffer.Frame>? {
        val frameCount = d.readInt()
        if (frameCount < 0 || frameCount > 12000) return null
        val frames = ArrayList<ReplayBuffer.Frame>(frameCount)
        for (i in 0 until frameCount) {
            val tick = d.readInt()
            val pc = d.readInt()
            if (pc < 0 || pc > 256) return null
            val snaps = ArrayList<ReplayBuffer.PlayerSnap>(pc)
            for (j in 0 until pc) {
                val most = d.readLong(); val least = d.readLong()
                val x = d.readFloat(); val y = d.readFloat(); val z = d.readFloat()
                val yaw = d.readFloat()
                val pitch = if (version >= 3) d.readFloat() else 0f
                val swingTicks = if (version >= 4) d.readInt() else 0
                val pose = d.readByte()
                // v7+ per-snap combat-sync fields. Pre-v7 clips default to no-flash / full-health
                // (hurtTime=0, health=maxHealth=20) → an old ghost renders without the new overlays.
                val hurtTime = if (version >= 7) d.readByte() else 0
                val health = if (version >= 7) d.readFloat() else 20f
                val maxHealth = if (version >= 7) d.readFloat() else 20f
                // v8+ per-snap equipment (held items + armor). Pre-v8 clips default to all-empty
                // ("" = empty slot) → the skin-only ghost holds nothing / wears no armor.
                val mainHand = if (version >= 8) d.readUTF() else ""
                val offHand = if (version >= 8) d.readUTF() else ""
                val head = if (version >= 8) d.readUTF() else ""
                val chest = if (version >= 8) d.readUTF() else ""
                val legs = if (version >= 8) d.readUTF() else ""
                val feet = if (version >= 8) d.readUTF() else ""
                val name = d.readUTF()
                snaps.add(ReplayBuffer.PlayerSnap(most, least, x, y, z, yaw, pitch, swingTicks, pose, name, hurtTime, health, maxHealth, mainHand, offHand, head, chest, legs, feet))
            }
            frames.add(ReplayBuffer.Frame(tick, snaps))
        }
        return frames
    }

    private fun readAlerts(d: DataInputStream): List<ReplayBuffer.AlertRec>? {
        val alertCount = d.readInt()
        if (alertCount < 0 || alertCount > 100000) return null
        val alerts = ArrayList<ReplayBuffer.AlertRec>(alertCount)
        for (i in 0 until alertCount) {
            val tick = d.readInt()
            val most = d.readLong(); val least = d.readLong()
            val name = d.readUTF(); val checkId = d.readUTF(); val label = d.readUTF()
            val vl = d.readFloat()
            alerts.add(ReplayBuffer.AlertRec(tick, most, least, name, checkId, label, vl))
        }
        return alerts
    }

    /**
     * Read the v5 terrain flag byte and, if present, the terrain section → [TerrainSnapshot]. Returns
     * null for the no-terrain byte (0). A bad flag value or truncation throws (caught by [read]'s
     * outer try → null clip). Bound-checks sizes + runCount so a corrupt file can't OOM the decoder.
     */
    private fun readTerrainFlag(d: DataInputStream): TerrainSnapshot? = when (d.readByte().toInt()) {
        0 -> null
        1 -> {
            val originX = d.readInt(); val originY = d.readInt(); val originZ = d.readInt()
            val sizeX = d.readInt(); val sizeY = d.readInt(); val sizeZ = d.readInt()
            if (sizeX < 0 || sizeY < 0 || sizeZ < 0 || sizeX > 1024 || sizeY > 1024 || sizeZ > 1024) return null
            val totalBlocks = sizeX.toLong() * sizeY * sizeZ
            if (totalBlocks <= 0 || totalBlocks > 5_000_000) return null
            val runCount = d.readInt()
            if (runCount < 0 || runCount > 5_000_000) return null
            val runs = ArrayList<TerrainSnapshot.Run>(runCount)
            var acc = 0L
            for (i in 0 until runCount) {
                val count = d.readInt()
                if (count < 0) return null
                acc += count
                val nameLen = d.readShort().toInt()
                val name = if (nameLen == -1) null else {
                    if (nameLen < 0 || nameLen > 32767) return null
                    val bytes = ByteArray(nameLen)
                    if (d.read(bytes) != nameLen) return null
                    String(bytes, Charsets.UTF_8)
                }
                runs.add(TerrainSnapshot.Run(count, name))
            }
            if (acc != totalBlocks) return null   // runs must exactly cover the bbox
            TerrainSnapshot(originX, originY, originZ, sizeX, sizeY, sizeZ, runs)
        }
        else -> throw java.io.IOException("bad terrain flag")
    }

    /**
     * Read the v6 chunks flag byte and, if present, the chunks section → [ChunkSnapshot]. Returns null
     * for the no-chunks byte (0). A bad flag value or truncation throws (caught by [read]'s outer try →
     * null clip). Bound-checks chunkCount / sectionCount / paletteSize / dataLen so a corrupt file can't
     * OOM the decoder. `dataLen` must be 4096 (palette ≤ 256) or 8192 (palette > 256); anything else is
     * corrupt → null. Palette names are read as length-prefixed UTF-8 (cap 32767 bytes per name).
     */
    private fun readChunksFlag(d: DataInputStream): ChunkSnapshot? = when (d.readByte().toInt()) {
        0 -> null
        1 -> {
            val chunkCount = d.readInt()
            if (chunkCount < 0 || chunkCount > 4096) return null
            val chunks = ArrayList<ChunkSnapshot.ChunkRec>(chunkCount)
            for (i in 0 until chunkCount) {
                val chunkX = d.readInt(); val chunkZ = d.readInt()
                val sectionCount = d.readInt()
                if (sectionCount < 0 || sectionCount > 64) return null
                val sections = ArrayList<ChunkSnapshot.SectionRec>(sectionCount)
                for (j in 0 until sectionCount) {
                    val sectionY = d.readInt()
                    val paletteSize = d.readShort().toInt()
                    if (paletteSize < 0 || paletteSize > 4096) return null
                    val palette = ArrayList<String>(paletteSize)
                    for (k in 0 until paletteSize) {
                        val nameLen = d.readShort().toInt()
                        if (nameLen < 0 || nameLen > 32767) return null
                        val bytes = ByteArray(nameLen)
                        if (d.read(bytes) != nameLen) return null
                        palette.add(String(bytes, Charsets.UTF_8))
                    }
                    val dataLen = d.readInt()
                    // dataLen must match the palette width: 1 byte × 4096 (palette ≤ 256) or 2 bytes × 4096.
                    val expected = if (paletteSize <= 256) 4096 else 8192
                    if (dataLen != expected) return null
                    val data = ByteArray(dataLen)
                    if (d.read(data) != dataLen) return null
                    sections.add(ChunkSnapshot.SectionRec(sectionY, palette, data))
                }
                chunks.add(ChunkSnapshot.ChunkRec(chunkX, chunkZ, sections))
            }
            ChunkSnapshot(chunks)
        }
        else -> throw java.io.IOException("bad chunks flag")
    }

    /**
     * Read the v7 totems flag byte and, if present, the totems section → a list of
     * [ReplayBuffer.TotemRec]. Returns an empty list for the no-totems byte (0). A bad flag value or
     * truncation throws (caught by [read]'s outer try → null clip). Bound-checks totemCount so a
     * corrupt file can't OOM the decoder. Each event is `(tick:int, uuidMost:long, uuidLeast:long)`.
     */
    private fun readTotemsFlag(d: DataInputStream): List<ReplayBuffer.TotemRec> = when (d.readByte().toInt()) {
        0 -> emptyList()
        1 -> {
            val totemCount = d.readInt()
            if (totemCount < 0 || totemCount > 100000) throw java.io.IOException("bad totem count")
            val out = ArrayList<ReplayBuffer.TotemRec>(totemCount)
            for (i in 0 until totemCount) {
                val tick = d.readInt()
                val most = d.readLong()
                val least = d.readLong()
                out.add(ReplayBuffer.TotemRec(tick, most, least))
            }
            out
        }
        else -> throw java.io.IOException("bad totems flag")
    }
}
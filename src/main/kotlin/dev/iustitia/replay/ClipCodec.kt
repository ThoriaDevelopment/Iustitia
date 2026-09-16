package dev.iustitia.replay

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Binary `.iusclip` codec. A clip is a self-contained recording of the last N seconds of the scene:
 * every tracked player's per-tick position/yaw/body-yaw/head-yaw/pitch/pose/combat-sync/equipment,
 * every nearby non-player entity, the real alert events that fired, and the optional captured world
 * (chunk snapshot + block-change deltas) — played back by [dev.iustitia.render.ReplayRenderer] via
 * [dev.iustitia.replay.ReplayState].
 *
 * ## Format (big-endian `DataOutput/InputStream`, length-prefixed UTF)
 *
 * v13 (this writer) = the **merge** of Iustitia's v8 (alerts + terrain + chunks + totems) and
 * SnapClip's v12 (bodyYaw/headYaw, non-player entities, per-frame segment id, block deltas, segments):
 *
 * ```
 * magic   : 4 bytes  "IUSC"
 * version : int      (13)
 * focus?  : byte 0/1, then uuidMost:long, uuidLeast:long
 * frames  : int count, then per frame:
 *              tick:int, playerCount:int, per player:
 *                  uuidMost:long, uuidLeast:long, x/y/z:float, yaw:float,
 *                  bodyYaw:float, headYaw:float   (v12+; earlier: = yaw)
 *                  pitch:float  (v3+), swingTicks:int (v4+), pose:byte,
 *                  hurtTime:byte, health:float, maxHealth:float  (v7+),
 *                  mainHand/offHand/head/chest/legs/feet:UTF  (v8+), name:UTF
 *              entityCount:int, per entity  (v10+):
 *                  typeId:UTF, uuidMost:long, uuidLeast:long, x/y/z:float,
 *                  bodyYaw/headYaw/pitch:float, pose:byte, hurtTime:byte,
 *                  health:float, maxHealth:float
 *              segmentId:int  (v11+)
 * alerts? : int count, per alert  (v2-8 and v13+ — NOT present in SnapClip v9-12):
 *              tick:int, uuidMost:long, uuidLeast:long, name:UTF, checkId:UTF, label:UTF, vl:float
 * terrain?: byte 0/1; if 1: originX/Y/Z:int, sizeX/Y/Z:int, runCount:int,
 *              per run: count:int, nameLen:short, name UTF-8 (short=-1 → air)   (v5+)
 * chunks? : byte 0/1; if 1: chunkCount:int, per chunk:
 *              chunkX:int, chunkZ:int, sectionCount:int, per section:
 *                  sectionY:int, paletteSize:short, per palette entry: nameLen:short, name UTF-8,
 *                  dataLen:int, data bytes (4096 if paletteSize ≤ 256 else 8192)   (v6+)
 * totems? : byte 0/1; if 1: totemCount:int, per totem: tick:int, uuidMost:long, uuidLeast:long  (v7+)
 * deltas? : byte 0/1; if 1: snapshotIsStart:byte, count:int, per delta:
 *              tick:int, x/y/z:int, before:UTF, after:UTF   (v9+)
 * segments?: byte 0/1; if 1: segCount:int, per segment:
 *              dimensionKey:UTF, firstFrameIndex:int, snapshotIsStart:byte,
 *              chunks(flag+body), deltas(flag+body)   (v11+)
 * ```
 *
 * ## Backward compatibility
 *
 * [read] accepts **v2 through v13**. v2 clips have no pitch; v3 no swingTicks; v4 no terrain; v5 no
 * chunks; v6 no combat-sync; v7 no equipment; v8 no bodyYaw/headYaw or entities. **v9–v12 are
 * SnapClip clips** (block deltas @v9, entities @v10, segments + segmentId @v11, bodyYaw/headYaw @v12)
 * — they carry **no alert timeline** (SnapClip dropped it), so their alert list reads back empty.
 * v13 is the merged superset (SnapClip's sections + alerts). Everything defaults fail-soft when a
 * field is absent. [readHeader] reads only through the counts — cheap for the clip-manager list.
 *
 * Fail-open: a corrupt/short file returns null from [read]/[readHeader] instead of throwing, and
 * [read] records why in [lastReadReason] so the caller can say more than "couldn't read it".
 */
object ClipCodec {

    private const val MAGIC = "IUSC"

    /** Current write version: the merged Iustitia v8 + SnapClip v12 superset. */
    const val VERSION = 13

    /** Lowest version [read] will accept (v2 = no per-snap pitch; loaded with pitch defaulting to 0). */
    const val MIN_VERSION = 2

    /** Last Iustitia-only layout version (v2–v8 carry the alert timeline; v9–v12 are SnapClip). */
    private const val LAST_LEGACY_VERSION = 8

    /** A decoded clip: frames + alerts + the optional focus player + optional terrain + chunks snapshots. */
    data class Clip(
        val window: ReplayBuffer.Window, val focus: UUID?,
        val terrain: TerrainSnapshot? = null, val chunks: ChunkSnapshot? = null,
    )

    /** Lightweight header (no per-snap data, no terrain runs, no chunk section bodies) for the
     *  clip-manager list. [terrainBlocks] / [chunkSections] feed the row's map-size display. Fail-open. */
    data class ClipMeta(
        val version: Int, val focus: UUID?, val frameCount: Int, val alertCount: Int,
        val terrainBlocks: Int = 0, val chunkSections: Int = 0,
    )

    fun write(out: OutputStream, window: ReplayBuffer.Window, focus: UUID?) {
        try {
            val d = DataOutputStream(out)
            d.writeBytes(MAGIC)
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
                    d.writeFloat(s.bodyYaw)
                    d.writeFloat(s.headYaw)
                    d.writeFloat(s.pitch)
                    d.writeInt(s.swingTicks)
                    d.writeByte(s.pose.toInt())
                    d.writeByte(s.hurtTime.toInt())
                    d.writeFloat(s.health)
                    d.writeFloat(s.maxHealth)
                    d.writeUTF(s.mainHand); d.writeUTF(s.offHand)
                    d.writeUTF(s.head); d.writeUTF(s.chest); d.writeUTF(s.legs); d.writeUTF(s.feet)
                    d.writeUTF(s.name)
                }
                d.writeInt(f.entities.size)
                for (e in f.entities) {
                    d.writeUTF(e.typeId)
                    d.writeLong(e.uuidMost); d.writeLong(e.uuidLeast)
                    d.writeFloat(e.x); d.writeFloat(e.y); d.writeFloat(e.z)
                    d.writeFloat(e.bodyYaw); d.writeFloat(e.headYaw); d.writeFloat(e.pitch)
                    d.writeByte(e.pose.toInt())
                    d.writeByte(e.hurtTime.toInt())
                    d.writeFloat(e.health); d.writeFloat(e.maxHealth)
                }
                d.writeInt(f.segmentId)
            }
            writeAlerts(d, window.alerts)
            writeTerrain(d, window.terrain)
            writeChunks(d, window.chunks)
            writeTotems(d, window.totems)
            writeDeltas(d, null)
            writeSegments(d, window.segments)
            d.flush()
        } catch (_: Throwable) {
            // fail-open
        }
    }

    /** A parse that failed for a *known* reason — carries the one-liner that reaches the user. */
    private class ClipFormatException(message: String) : Exception(message)

    /**
     * Why the last [read] returned null (`null` = it succeeded). [ClipStore.load] feeds this to
     * [ClipPlayback] so `/ius playclip` can say what was wrong. Diagnostics only — never branch on it.
     */
    @Volatile
    var lastReadReason: String? = null
        private set

    /**
     * Decode a clip. [expectedBytes] is the length of the underlying clip file, or `-1` when the
     * caller can't know it (a stream, a test buffer); see the consumption check below.
     */
    fun read(input: InputStream, expectedBytes: Long = -1L): Clip? {
        // A structural mis-parse throws ClipFormatException (a reason we can print); anything else is
        // an IO surprise. Both land in lastReadReason, so a refusal is never a bare "no".
        return try {
            val counting = CountingInputStream(input)
            val clip = decode(DataInputStream(counting))
            // Whole-stream consumption. Every section is length-prefixed, so a parse that stops early
            // or runs long has almost certainly read a length from the wrong offset — the byte-shift
            // failure mode the per-field bounds checks can't see, because a shifted count is usually
            // still *plausible*. Before this check such a clip came back as a silent null and looked
            // exactly like a file that isn't there.
            if (expectedBytes >= 0 && counting.count != expectedBytes) {
                throw ClipFormatException(
                    "read ${counting.count} of $expectedBytes bytes — truncated, or written by a " +
                        "layout this build doesn't know"
                )
            }
            lastReadReason = null
            clip
        } catch (e: ClipFormatException) {
            lastReadReason = e.message
            null
        } catch (e: Throwable) {
            lastReadReason = "malformed clip: " + (e.message ?: e.javaClass.simpleName)
            null
        }
    }

    /** The parse itself, without the length guard or the reason bookkeeping (see [read]). */
    private fun decode(d: DataInputStream): Clip {
        if (!checkMagic(d)) throw ClipFormatException("not an .iusclip file (bad magic)")
        val version = d.readInt()
        if (version < MIN_VERSION || version > VERSION) {
            throw ClipFormatException("clip version $version — this build reads $MIN_VERSION–$VERSION")
        }
        val focus = readFocus(d)
        val frames = readFrames(d, version) ?: throw ClipFormatException("malformed frame table")
        // Alerts are present for the Iustitia lineage (v2–v8) and for the merged v13+; SnapClip's
        // v9–v12 dropped them entirely, so those clips read back with no alerts.
        val alerts = if (version <= LAST_LEGACY_VERSION || version >= 13) readAlerts(d) else emptyList()
        val terrain = if (version >= 5) readTerrainFlag(d) else null
        val legacyChunks = if (version >= 6) readChunks(d) else null
        val totems = if (version >= 7) readTotemsFlag(d) else emptyList()
        var legacyBlockDeltas: List<BlockDeltaBuffer.BlockDelta>? = null
        var legacySnapshotIsStart = false
        if (version >= 9) {
            val (bd, sis) = readDeltas(d) ?: throw ClipFormatException("malformed block-delta table")
            legacyBlockDeltas = bd
            legacySnapshotIsStart = sis
        }
        var segments: List<ReplayBuffer.Segment> = emptyList()
        if (version >= 11) segments = readSegments(d) ?: throw ClipFormatException("malformed segment table")
        // Synthesize a segment from a legacy top-level chunk snapshot so the segment-based render path
        // has something to draw for a v6–v10 clip.
        val finalSegments = if (segments.isNotEmpty()) segments
            else if (legacyChunks != null) listOf(
                ReplayBuffer.Segment(
                    dimensionKey = "?",
                    firstFrameIndex = 0,
                    chunks = legacyChunks,
                    blockDeltas = legacyBlockDeltas ?: emptyList(),
                    snapshotIsStart = legacySnapshotIsStart,
                )
            )
            else emptyList()
        return Clip(
            ReplayBuffer.Window(frames, alerts = alerts, terrain = terrain, chunks = legacyChunks, totems = totems, segments = finalSegments),
            focus, terrain, legacyChunks,
        )
    }

    /**
     * Tallying pass-through, so [read] can compare bytes consumed against the file length. Counts the
     * bytes a `DataInputStream` actually pulls ([read] plus the `readFully`/`skipBytes` it delegates
     * to); `available()`/`close()` are the inner stream's, unchanged.
     */
    private class CountingInputStream(private val inner: InputStream) : InputStream() {
        var count: Long = 0L
            private set

        override fun read(): Int {
            val b = inner.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun skip(n: Long): Long {
            val s = inner.skip(n)
            if (s > 0) count += s
            return s
        }

        override fun available(): Int = inner.available()
        override fun close() = inner.close()
    }

    fun readHeader(input: InputStream): ClipMeta? = try {
        val d = DataInputStream(input)
        if (!checkMagic(d)) return null
        val version = d.readInt()
        if (version < MIN_VERSION || version > VERSION) return null
        val focus = readFocus(d)
        val frameCount = d.readInt()
        if (frameCount < 0 || frameCount > 12000) return null
        for (i in 0 until frameCount) {
            d.readInt()                                  // tick
            val pc = d.readInt()
            if (pc < 0 || pc > 256) return null
            for (j in 0 until pc) skipPlayerSnap(d, version)
            if (version >= 10) {
                val ec = d.readInt()
                if (ec < 0 || ec > 1024) return null
                for (k in 0 until ec) {
                    d.readUTF()
                    d.readLong(); d.readLong()
                    d.readFloat(); d.readFloat(); d.readFloat()
                    d.readFloat(); d.readFloat(); d.readFloat()
                    d.readByte(); d.readByte()
                    d.readFloat(); d.readFloat()
                }
            }
            if (version >= 11) d.readInt()               // segmentId
        }
        var alertCount = 0
        if (version <= LAST_LEGACY_VERSION || version >= 13) {
            alertCount = d.readInt()
            if (alertCount < 0 || alertCount > 1_000_000) return null
            for (i in 0 until alertCount) {
                d.readInt(); d.readLong(); d.readLong()
                d.readUTF(); d.readUTF(); d.readUTF(); d.readFloat()
            }
        }
        var terrainBlocks = 0
        if (version >= 5) {
            when (d.readByte().toInt()) {
                0 -> { }
                1 -> {
                    d.readInt(); d.readInt(); d.readInt()
                    d.readInt(); d.readInt(); d.readInt()
                    val runCount = d.readInt()
                    if (runCount < 0 || runCount > 5_000_000) return null
                    for (i in 0 until runCount) {
                        val count = d.readInt()
                        if (count < 0) return null
                        val nameLen = d.readShort().toInt()
                        if (nameLen == -1) { }
                        else if (nameLen in 0..32767) {
                            if (d.skipBytes(nameLen) != nameLen) return null
                            terrainBlocks += count
                        } else return null
                    }
                }
                else -> return null
            }
        }
        var chunkSections = 0
        if (version >= 6) {
            val c = countSkipChunks(d)
            if (c < 0) return null
            chunkSections += c
        }
        if (version >= 7) {
            when (d.readByte().toInt()) {
                0 -> { }
                1 -> {
                    val totemCount = d.readInt()
                    if (totemCount < 0 || totemCount > 100000) return null
                    val skip = totemCount * 20
                    if (d.skipBytes(skip) != skip) return null
                }
                else -> return null
            }
        }
        if (version >= 9) if (!skipDeltas(d)) return null
        if (version >= 11) {
            when (d.readByte().toInt()) {
                0 -> { }
                1 -> {
                    val segCount = d.readInt()
                    if (segCount < 0 || segCount > 1024) return null
                    for (i in 0 until segCount) {
                        d.readUTF(); d.readInt(); d.readByte()
                        val c = countSkipChunks(d)
                        if (c < 0) return null
                        chunkSections += c
                        if (!skipSegmentDeltas(d)) return null
                    }
                }
                else -> return null
            }
        }
        ClipMeta(version, focus, frameCount, alertCount, terrainBlocks, chunkSections)
    } catch (_: Throwable) {
        null
    }

    // ---- fixed / shared ----

    private fun checkMagic(d: DataInputStream): Boolean {
        val magic = ByteArray(4); if (d.read(magic) != 4) return false
        return String(magic) == MAGIC
    }

    private fun readFocus(d: DataInputStream): UUID? = when (d.readByte().toInt()) {
        0 -> null
        1 -> UUID(d.readLong(), d.readLong())
        else -> throw java.io.IOException("bad focus flag")
    }

    /** Read one player snap, branching on [version] (see the format doc). Shared by [read]/[readFrames]. */
    private fun readPlayerSnap(d: DataInputStream, version: Int): ReplayBuffer.PlayerSnap {
        val most = d.readLong(); val least = d.readLong()
        val x = d.readFloat(); val y = d.readFloat(); val z = d.readFloat()
        val yaw = d.readFloat()
        val bodyYaw = if (version >= 12) d.readFloat() else yaw
        val headYaw = if (version >= 12) d.readFloat() else yaw
        val pitch = if (version >= 3) d.readFloat() else 0f
        val swingTicks = if (version >= 4) d.readInt() else 0
        val pose = d.readByte()
        val hurtTime = if (version >= 7) d.readByte() else 0
        val health = if (version >= 7) d.readFloat() else 20f
        val maxHealth = if (version >= 7) d.readFloat() else 20f
        val mainHand = if (version >= 8) d.readUTF() else ""
        val offHand = if (version >= 8) d.readUTF() else ""
        val head = if (version >= 8) d.readUTF() else ""
        val chest = if (version >= 8) d.readUTF() else ""
        val legs = if (version >= 8) d.readUTF() else ""
        val feet = if (version >= 8) d.readUTF() else ""
        val name = d.readUTF()
        return ReplayBuffer.PlayerSnap(
            most, least, x, y, z, yaw, pitch,
            bodyYaw = bodyYaw, headYaw = headYaw,
            swingTicks = swingTicks, pose = pose, name = name,
            hurtTime = hurtTime, health = health, maxHealth = maxHealth,
            mainHand = mainHand, offHand = offHand, head = head, chest = chest, legs = legs, feet = feet,
        )
    }

    /** Skip one player snap without materializing it (header path). */
    private fun skipPlayerSnap(d: DataInputStream, version: Int) {
        d.readLong(); d.readLong()
        d.readFloat(); d.readFloat(); d.readFloat()
        d.readFloat()
        if (version >= 12) { d.readFloat(); d.readFloat() }
        if (version >= 3) d.readFloat()
        if (version >= 4) d.readInt()
        d.readByte()
        if (version >= 7) { d.readByte(); d.readFloat(); d.readFloat() }
        if (version >= 8) { repeat(6) { d.readUTF() } }
        d.readUTF()
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
            for (j in 0 until pc) snaps.add(readPlayerSnap(d, version))
            val entities = if (version >= 10) {
                val ec = d.readInt()
                if (ec < 0 || ec > 1024) return null
                val ent = ArrayList<ReplayBuffer.EntitySnap>(ec)
                for (k in 0 until ec) {
                    val typeId = d.readUTF()
                    val emost = d.readLong(); val eleast = d.readLong()
                    val ex = d.readFloat(); val ey = d.readFloat(); val ez = d.readFloat()
                    val bodyYaw = d.readFloat(); val headYaw = d.readFloat(); val epitch = d.readFloat()
                    val epose = d.readByte(); val ehurt = d.readByte()
                    val ehealth = d.readFloat(); val emaxHealth = d.readFloat()
                    ent.add(ReplayBuffer.EntitySnap(typeId, emost, eleast, ex, ey, ez, bodyYaw, headYaw, epitch, epose, ehurt, ehealth, emaxHealth))
                }
                ent
            } else emptyList()
            val segmentId = if (version >= 11) d.readInt() else 0
            frames.add(ReplayBuffer.Frame(tick, snaps, entities, segmentId))
        }
        return frames
    }

    // ---- alerts (Iustitia feature; not present in SnapClip v9-12) ----

    private fun writeAlerts(d: DataOutputStream, alerts: List<ReplayBuffer.AlertRec>) {
        d.writeInt(alerts.size)
        for (a in alerts) {
            d.writeInt(a.tick)
            d.writeLong(a.uuidMost); d.writeLong(a.uuidLeast)
            d.writeUTF(a.name); d.writeUTF(a.checkId); d.writeUTF(a.label)
            d.writeFloat(a.vl)
        }
    }

    private fun readAlerts(d: DataInputStream): List<ReplayBuffer.AlertRec> {
        val count = d.readInt()
        if (count < 0 || count > 1_000_000) throw java.io.IOException("bad alert count")
        val out = ArrayList<ReplayBuffer.AlertRec>(count)
        for (i in 0 until count) {
            val tick = d.readInt()
            val most = d.readLong(); val least = d.readLong()
            val name = d.readUTF(); val checkId = d.readUTF(); val label = d.readUTF()
            val vl = d.readFloat()
            out.add(ReplayBuffer.AlertRec(tick, most, least, name, checkId, label, vl))
        }
        return out
    }

    // ---- terrain (v5+) ----

    private fun writeTerrain(d: DataOutputStream, t: TerrainSnapshot?) {
        if (t == null) { d.writeByte(0); return }
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
            if (acc != totalBlocks) return null
            TerrainSnapshot(originX, originY, originZ, sizeX, sizeY, sizeZ, runs)
        }
        else -> throw java.io.IOException("bad terrain flag")
    }

    // ---- chunks (v6+) ----

    private fun writeChunks(d: DataOutputStream, snap: ChunkSnapshot?) {
        if (snap == null || snap.chunks.isEmpty()) { d.writeByte(0); return }
        d.writeByte(1)
        d.writeInt(snap.chunks.size)
        for (chunk in snap.chunks) {
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

    private fun readChunks(d: DataInputStream): ChunkSnapshot? = when (d.readByte().toInt()) {
        0 -> null
        1 -> {
            val chunkCount = d.readInt()
            if (chunkCount < 0 || chunkCount > 4096) throw java.io.IOException("bad chunk count")
            val chunks = ArrayList<ChunkSnapshot.ChunkRec>(chunkCount)
            for (i in 0 until chunkCount) {
                val chunkX = d.readInt(); val chunkZ = d.readInt()
                val sectionCount = d.readInt()
                if (sectionCount < 0 || sectionCount > 64) throw java.io.IOException("bad section count")
                val sections = ArrayList<ChunkSnapshot.SectionRec>(sectionCount)
                for (j in 0 until sectionCount) {
                    val sectionY = d.readInt()
                    val paletteSize = d.readShort().toInt()
                    if (paletteSize < 0 || paletteSize > 4096) throw java.io.IOException("bad palette size")
                    val palette = ArrayList<String>(paletteSize)
                    for (k in 0 until paletteSize) {
                        val nameLen = d.readShort().toInt()
                        if (nameLen < 0 || nameLen > 32767) throw java.io.IOException("bad name len")
                        val bytes = ByteArray(nameLen)
                        if (d.read(bytes) != nameLen) throw java.io.IOException("short palette name")
                        palette.add(String(bytes, Charsets.UTF_8))
                    }
                    val dataLen = d.readInt()
                    val expected = if (paletteSize <= 256) 4096 else 8192
                    if (dataLen != expected) throw java.io.IOException("bad data len")
                    val data = ByteArray(dataLen)
                    if (d.read(data) != dataLen) throw java.io.IOException("short data")
                    sections.add(ChunkSnapshot.SectionRec(sectionY, palette, data))
                }
                chunks.add(ChunkSnapshot.ChunkRec(chunkX, chunkZ, sections))
            }
            ChunkSnapshot(chunks)
        }
        else -> throw java.io.IOException("bad chunks flag")
    }

    private fun countSkipChunks(d: DataInputStream): Int = when (d.readByte().toInt()) {
        0 -> 0
        1 -> {
            val chunkCount = d.readInt()
            if (chunkCount < 0 || chunkCount > 4096) return -1
            var sections = 0
            for (i in 0 until chunkCount) {
                d.readInt(); d.readInt()
                val sectionCount = d.readInt()
                if (sectionCount < 0 || sectionCount > 64) return -1
                sections += sectionCount
                for (j in 0 until sectionCount) {
                    d.readInt()
                    val paletteSize = d.readShort().toInt()
                    if (paletteSize < 0 || paletteSize > 4096) return -1
                    for (k in 0 until paletteSize) {
                        val nameLen = d.readShort().toInt()
                        if (nameLen < 0 || nameLen > 32767) return -1
                        if (d.skipBytes(nameLen) != nameLen) return -1
                    }
                    val dataLen = d.readInt()
                    if (dataLen < 0 || dataLen > 8192) return -1
                    if (d.skipBytes(dataLen) != dataLen) return -1
                }
            }
            sections
        }
        else -> -1
    }

    // ---- totems (v7+) ----

    private fun writeTotems(d: DataOutputStream, totems: List<ReplayBuffer.TotemRec>) {
        if (totems.isEmpty()) { d.writeByte(0); return }
        d.writeByte(1)
        d.writeInt(totems.size)
        for (t in totems) {
            d.writeInt(t.tick)
            d.writeLong(t.uuidMost); d.writeLong(t.uuidLeast)
        }
    }

    private fun readTotemsFlag(d: DataInputStream): List<ReplayBuffer.TotemRec> = when (d.readByte().toInt()) {
        0 -> emptyList()
        1 -> {
            val totemCount = d.readInt()
            if (totemCount < 0 || totemCount > 100000) throw java.io.IOException("bad totem count")
            val out = ArrayList<ReplayBuffer.TotemRec>(totemCount)
            for (i in 0 until totemCount) out.add(ReplayBuffer.TotemRec(d.readInt(), d.readLong(), d.readLong()))
            out
        }
        else -> throw java.io.IOException("bad totems flag")
    }

    // ---- block deltas (v9+) ----

    private fun writeDeltas(d: DataOutputStream, list: List<BlockDeltaBuffer.BlockDelta>?) {
        if (list.isNullOrEmpty()) { d.writeByte(0); return }
        d.writeByte(1)
        d.writeByte(0)                       // snapshotIsStart = false for the top-level section
        d.writeInt(list.size)
        for (b in list) writeDelta(d, b)
    }

    private fun writeDelta(d: DataOutputStream, b: BlockDeltaBuffer.BlockDelta) {
        d.writeInt(b.tick)
        d.writeInt(b.x); d.writeInt(b.y); d.writeInt(b.z)
        d.writeUTF(b.before); d.writeUTF(b.after)
    }

    private fun readDeltas(d: DataInputStream): Pair<List<BlockDeltaBuffer.BlockDelta>?, Boolean>? = when (d.readByte().toInt()) {
        0 -> Pair(null, false)
        1 -> {
            val snapshotIsStart = d.readByte().toInt() == 1
            val count = d.readInt()
            if (count < 0 || count > 200_000) return null
            val out = ArrayList<BlockDeltaBuffer.BlockDelta>(count)
            for (i in 0 until count) out.add(readDelta(d))
            Pair(out, snapshotIsStart)
        }
        else -> null
    }

    private fun readDelta(d: DataInputStream): BlockDeltaBuffer.BlockDelta {
        val tick = d.readInt()
        val x = d.readInt(); val y = d.readInt(); val z = d.readInt()
        val before = d.readUTF(); val after = d.readUTF()
        return BlockDeltaBuffer.BlockDelta(tick, x, y, z, before, after)
    }

    /** Segment-body deltas (flag + count + entries, no snapshotIsStart byte). Returns null on a bad
     *  flag/count so the caller can fail the whole read. */
    private fun readSegmentDeltas(d: DataInputStream): List<BlockDeltaBuffer.BlockDelta>? = when (d.readByte().toInt()) {
        0 -> emptyList()
        1 -> {
            val count = d.readInt()
            if (count < 0 || count > 200_000) return null
            val out = ArrayList<BlockDeltaBuffer.BlockDelta>(count)
            for (i in 0 until count) out.add(readDelta(d))
            out
        }
        else -> null
    }

    /** Skip the **top-level** deltas section (flag + snapshotIsStart byte + count + entries). */
    private fun skipDeltas(d: DataInputStream): Boolean = when (d.readByte().toInt()) {
        0 -> true
        1 -> {
            d.readByte()
            val count = d.readInt()
            if (count < 0 || count > 200_000) return false
            for (i in 0 until count) {
                d.readInt(); d.readInt(); d.readInt(); d.readInt()
                d.readUTF(); d.readUTF()
            }
            true
        }
        else -> false
    }

    /** Skip a **segment-body** deltas section (flag + count + entries, no snapshotIsStart byte). */
    private fun skipSegmentDeltas(d: DataInputStream): Boolean = when (d.readByte().toInt()) {
        0 -> true
        1 -> {
            val count = d.readInt()
            if (count < 0 || count > 200_000) return false
            for (i in 0 until count) {
                d.readInt(); d.readInt(); d.readInt(); d.readInt()
                d.readUTF(); d.readUTF()
            }
            true
        }
        else -> false
    }

    // ---- segments (v11+) ----

    private fun writeSegments(d: DataOutputStream, segments: List<ReplayBuffer.Segment>) {
        if (segments.isEmpty()) { d.writeByte(0); return }
        d.writeByte(1)
        d.writeInt(segments.size)
        for (seg in segments) {
            d.writeUTF(seg.dimensionKey)
            d.writeInt(seg.firstFrameIndex)
            d.writeByte(if (seg.snapshotIsStart) 1 else 0)
            writeChunks(d, seg.chunks)
            writeSegmentDeltas(d, seg.blockDeltas)
        }
    }

    /** Segment-body deltas: flag + count + entries, **no** snapshotIsStart byte (this matches
     *  SnapClip's v11/v12 segment layout, which the top-level [writeDeltas] section does not). */
    private fun writeSegmentDeltas(d: DataOutputStream, list: List<BlockDeltaBuffer.BlockDelta>) {
        if (list.isEmpty()) { d.writeByte(0); return }
        d.writeByte(1)
        d.writeInt(list.size)
        for (b in list) writeDelta(d, b)
    }

    private fun readSegments(d: DataInputStream): List<ReplayBuffer.Segment>? = when (d.readByte().toInt()) {
        0 -> emptyList()
        1 -> {
            val segCount = d.readInt()
            if (segCount < 0 || segCount > 1024) return null
            val out = ArrayList<ReplayBuffer.Segment>(segCount)
            for (i in 0 until segCount) {
                val dimensionKey = d.readUTF()
                val firstFrameIndex = d.readInt()
                if (firstFrameIndex < 0) return null
                val snapshotIsStart = d.readByte().toInt() == 1
                val chunks = readChunks(d)
                val deltas = readSegmentDeltas(d) ?: return null
                out.add(ReplayBuffer.Segment(dimensionKey, firstFrameIndex, chunks, deltas, snapshotIsStart))
            }
            out
        }
        else -> null
    }
}

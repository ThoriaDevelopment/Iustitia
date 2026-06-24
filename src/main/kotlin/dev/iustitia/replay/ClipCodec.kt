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
 * version : int      (4 — v3 had no per-snap swingTicks; v2 had no per-snap pitch; v1 no per-snap name)
 * focus?  : byte 0/1, then uuidMost:long, uuidLeast:long  (the highlighted player, if any)
 * frames  : int count, then per frame:
 *              tick:int, playerCount:int, per player:
 *                  uuidMost:long, uuidLeast:long, x:float, y:float, z:float, yaw:float,
 *                  pitch:float  (v3+ ONLY — v2 stops here),
 *                  swingTicks:int  (v4+ ONLY — v3 stops here),
 *                  pose:byte, name:UTF
 * alerts  : int count, then per alert:
 *              tick:int, uuidMost:long, uuidLeast:long,
 *              name:UTF, checkId:UTF, label:UTF, vl:float
 * ```
 *
 * ## Backward compatibility
 *
 * [read] accepts **v2, v3 and v4**. v2 clips have no per-snap pitch → pitch defaults to 0 (the ghost
 * still faces its yaw, just won't tilt its head). v3 clips have no per-snap swingTicks → swingTicks
 * defaults to 0 (arms hang still, but the ghost still walks / faces / tilts). v1 (no per-snap name)
 * **Old v2 clips keep loading** so a user's saved library isn't invalidated by the look-angle
 * addition. [readHeader] reads only up through the counts (no per-snap data) — cheap for the clip
 * manager's list view.
 *
 * Compact: a 30s, 40-player v3 clip ≈ 30*20*40 * (8+16+4+1+~12) ≈ 740 KB; the 60s cap keeps it in the
 * low single-digit MB. Fail-open: a corrupt/short file returns null from [read]/[readHeader] instead
 * of throwing.
 */
object ClipCodec {

    private const val MAGIC = "IUSC"
    const val VERSION = 4
    /** Lowest version [read] will accept (v2 = no per-snap pitch; loaded with pitch defaulting to 0). */
    const val MIN_VERSION = 2

    /** A decoded clip: its frames + alerts + the optional focus player. */
    data class Clip(val window: ReplayBuffer.Window, val focus: UUID?)

    /** Lightweight header (no per-snap data) for the clip-manager list. Fail-open: null on any error. */
    data class ClipMeta(val version: Int, val focus: UUID?, val frameCount: Int, val alertCount: Int)

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
        Clip(ReplayBuffer.Window(frames, alerts), focus)
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
        if (frameCount < 0 || frameCount > 2000) return null
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
                d.readUTF() // name
            }
        }
        val alertCount = d.readInt()
        if (alertCount < 0 || alertCount > 100000) return null
        ClipMeta(version, focus, frameCount, alertCount)
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
        if (frameCount < 0 || frameCount > 2000) return null
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
                val name = d.readUTF()
                snaps.add(ReplayBuffer.PlayerSnap(most, least, x, y, z, yaw, pitch, swingTicks, pose, name))
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
}
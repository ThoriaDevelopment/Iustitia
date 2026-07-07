package dev.iustitia.protocol

/**
 * Detects the negotiated server protocol so version-sensitive logic (Reach margin,
 * attack-inference window) can adapt to 1.8 servers reached via ViaFabricPlus.
 *
 * ViaFabricPlus is a *soft* dependency: we reflect on its translator class and fall
 * back to "modern" (1.21.11) on any failure. Everything here is fail-open; a wrong
 * detection only shifts a margin by ~0.1 — never a crash.
 */
object ProtocolDetector {

    /** 1.8.x protocol id = 47. Anything <= this is treated as "1.8 era". */
    private const val PROTOCOL_1_8 = 47

    /** First protocol carrying full-float yaw/pitch in entity position sync (1.21.2 = 768).
     *  Pre-1.21.2 look is byte-quantized (256/360 ≈ 1.4° yaw, 0.7° pitch) — GCD/sensitivity
     *  detection has no signal there (plan §1.1). */
    private const val PROTOCOL_FULL_FLOAT_LOOK = 768

    /** Fallback when ViaFabricPlus is absent or reflection fails: the native target 1.21.11
     *  (protocol 774). The old 767 (1.21.0) was wrong — on the native target it falsely read
     *  below the full-float-look boundary (768). 767 and 774 are both > 47, so this change is
     *  behavior-neutral for every existing consumer (all branch on [is1_8OrLess]); it only
     *  corrects [fullFloatLook] and the `/ius status` protocol display. */
    private const val FALLBACK = 774

    @Volatile
    private var protocol: Int = FALLBACK

    val current: Int get() = protocol

    /** True iff the connected server speaks 1.8-or-earlier protocol. */
    val is1_8OrLess: Boolean get() = protocol <= PROTOCOL_1_8

    /** True iff the negotiated protocol carries full-float look angles (1.21.2+). GCD/
     *  sensitivity (Axis A) checks gate on this — byte-quantized pre-1.21.2 deltas carry no
     *  GCD signal (plan §1.1). */
    val fullFloatLook: Boolean get() = protocol >= PROTOCOL_FULL_FLOAT_LOOK

    /** Re-detect via ViaFabricPlus reflection; falls back to modern on any error. */
    fun redetect() {
        protocol = try {
            val cls = Class.forName("de.florianmichael.viafabricplus.protocoltranslator.ProtocolTranslator")
            val getTarget = cls.getMethod("getTargetVersion")
            val pv = getTarget.invoke(null) ?: return
            // ViaVersion 5.x: getProtocol(); 4.x: getVersion(). Try both.
            val proto = tryMethod(pv, "getProtocol") ?: tryMethod(pv, "getVersion") ?: FALLBACK
            (proto as? Int) ?: (proto as? Number)?.toInt() ?: FALLBACK
        } catch (_: Throwable) {
            FALLBACK
        }
    }

    private fun tryMethod(obj: Any, name: String): Any? = try {
        obj.javaClass.getMethod(name).invoke(obj)
    } catch (_: Throwable) {
        null
    }

    /** Attack-inference lookback (ticks) — widened on 1.8 to absorb its timing. */
    val hurtLookback: Int get() = if (is1_8OrLess) 3 else 2
    val hurtLookahead: Int get() = if (is1_8OrLess) 2 else 1
}
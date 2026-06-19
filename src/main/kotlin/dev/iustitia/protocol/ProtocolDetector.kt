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

    /** Fallback (1.21 era) when ViaFabricPlus is absent or reflection fails. */
    private const val FALLBACK = 767

    @Volatile
    private var protocol: Int = FALLBACK

    val current: Int get() = protocol

    /** True iff the connected server speaks 1.8-or-earlier protocol. */
    val is1_8OrLess: Boolean get() = protocol <= PROTOCOL_1_8

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
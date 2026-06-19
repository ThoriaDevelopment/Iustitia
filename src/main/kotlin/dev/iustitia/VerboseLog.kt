package dev.iustitia

import dev.iustitia.config.ConfigManager
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostic console sink, gated on [IustitiaConfig.verbose]. When verbose is on,
 * pipeline activity is logged to the client console (latest.log) — **not** to chat —
 * so a real-server validation pass can confirm the tracker is polling, attacks are
 * being inferred, and checks are flagging even when nothing crosses setbackVL.
 *
 * This is what makes "no alerts" interpretable as *clean* rather than *silently broken*:
 * every check + every mixin body is fail-open, so a check that throws on real server
 * data would be swallowed and look identical to a quiet server. Verbose surfaces the
 * pipeline heartbeat (swings/hurts/attacks/flags per 10s) plus per-event AttackEvent and
 * sub-threshold flag lines.
 *
 * All paths fail-open — verbose logging must never throw or block the tick.
 */
object VerboseLog {

    private val logger = LoggerFactory.getLogger("Iustitia")

    /** Heartbeat interval in ticks (200 = 10s); avoids per-tick spam on busy servers. */
    private const val HEARTBEAT_TICKS = 200

    @Volatile
    private var lastDumpTick = 0

    private val swings = AtomicLong(0)
    private val hurts = AtomicLong(0)
    private val attacks = AtomicLong(0)
    private val flags = AtomicLong(0)

    fun isEnabled(): Boolean = try { ConfigManager.config.verbose } catch (_: Throwable) { false }

    fun log(message: String) {
        if (!isEnabled()) return
        try { logger.info(message) } catch (_: Throwable) {}
    }

    /** Render a tracked player as a readable label (username if known, else short uuid). */
    fun nameOf(username: String?, uuid: java.util.UUID): String =
        username?.takeIf { it.isNotEmpty() } ?: uuid.toString().take(8)

    fun countSwing() { if (isEnabled()) swings.incrementAndGet() }
    fun countHurt() { if (isEnabled()) hurts.incrementAndGet() }
    fun countAttack() { if (isEnabled()) attacks.incrementAndGet() }
    fun countFlag() { if (isEnabled()) flags.incrementAndGet() }

    /**
     * Periodic heartbeat: every [HEARTBEAT_TICKS] dump and reset the per-interval counters.
     * [trackedPlayers] is the live count of other-client players the tracker is observing
     * this tick — confirms the tracker is polling. Call once per client tick from the driver.
     */
    fun maybeHeartbeat(tick: Int, trackedPlayers: Int) {
        if (!isEnabled()) return
        try {
            if (tick - lastDumpTick < HEARTBEAT_TICKS) return
            lastDumpTick = tick
            val s = swings.getAndSet(0)
            val h = hurts.getAndSet(0)
            val a = attacks.getAndSet(0)
            val f = flags.getAndSet(0)
            log(
                "pipeline @tick $tick (last ${HEARTBEAT_TICKS}t): tracking=$trackedPlayers " +
                    "swings=$s hurts=$h attacks=$a flags=$f"
            )
        } catch (_: Throwable) {}
    }
}
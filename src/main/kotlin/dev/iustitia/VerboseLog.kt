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
 * ## Delivery is asynchronous
 *
 * [log] hands the line to a bounded queue drained by a single daemon thread; the caller never
 * touches the appender. Measured on a 20+-player fight, the previous synchronous path parked the
 * render thread inside `logger.info` for half the wall clock — 97% of all Iustitia render-thread
 * time — because log4j writes to the console *and* latest.log on the calling thread, and flag
 * volume scales with the number of nearby players. Detection was never the cost; the transcript
 * was.
 *
 * The queue is bounded and drops on overflow (see [LogQueue]): a full queue means the appender
 * cannot keep up, and blocking the tick thread to avoid losing a log line is precisely the bug
 * being fixed. Drops are **counted**, never silent — the heartbeat reports them as `dropped=N`,
 * so a truncated capture cannot be mistaken for a complete one. Note what is dropped is only a
 * *transcript* line: the authoritative per-flag record
 * ([dev.iustitia.history.FlagHistory.recordFlag]) is written synchronously and is unaffected, so
 * `/ius hist` and the report timeline stay exact under any load.
 *
 * All paths fail-open — verbose logging must never throw or block the tick.
 */
object VerboseLog {

    private val logger = LoggerFactory.getLogger("Iustitia")

    /** Heartbeat interval in ticks (200 = 10s); avoids per-tick spam on busy servers. */
    private const val HEARTBEAT_TICKS = 200

    /**
     * Backlog capacity. Sized from the burst shape of the dense-player case: production peaks
     * around a thousand lines/sec for roughly a second while a fight is happening, while the
     * synchronous appender sustains a few hundred/sec — so the queue must absorb a whole burst and
     * drain it during the quiet interval that follows. 16k lines is about an order of magnitude of
     * headroom over that burst and bounds the worst case at a few MB of char data; the point is
     * that the ceiling exists at all.
     */
    private const val QUEUE_CAPACITY = 16_384

    /** Lines the drain thread hands to the appender per pass before re-checking the queue. */
    private const val DRAIN_BATCH = 256

    /** Bounded hand-off; see [LogQueue] for the overflow contract. */
    private val queue = LogQueue(QUEUE_CAPACITY)

    /** Appender thread, created on first use so normal play (verbose off) costs no extra thread. */
    @Volatile
    private var drainThread: Thread? = null

    @Volatile
    private var lastDumpTick = 0

    private val swings = AtomicLong(0)
    private val hurts = AtomicLong(0)
    private val attacks = AtomicLong(0)
    private val flags = AtomicLong(0)
    /** Swallowed exceptions at the driver/bus chokepoints ([Iustitia.warnChokepoint],
     *  [dev.iustitia.event.EventBus] dispatch) — a check or handler that throws on real
     *  server data is otherwise indistinguishable from a quiet server (fail-open means
     *  silent). Surfaced as exc=N in the heartbeat line. */
    private val exc = AtomicLong(0)

    fun isEnabled(): Boolean = try { ConfigManager.config.verbose } catch (_: Throwable) { false }

    /**
     * Enqueue a diagnostic line. O(1) and non-blocking: the appender runs on [drainThread], so
     * this never waits on console/file I/O, and a full backlog drops the line rather than stalling
     * the caller. A no-op when verbose is off.
     */
    fun log(message: String) {
        if (!isEnabled()) return
        try {
            ensureDrainThread()
            queue.offer(message)
        } catch (_: Throwable) {}
    }

    /** Render a tracked player as a readable label (username if known, else short uuid). */
    fun nameOf(username: String?, uuid: java.util.UUID): String =
        username?.takeIf { it.isNotEmpty() } ?: uuid.toString().take(8)

    fun countSwing() { if (isEnabled()) swings.incrementAndGet() }
    fun countHurt() { if (isEnabled()) hurts.incrementAndGet() }
    fun countAttack() { if (isEnabled()) attacks.incrementAndGet() }
    fun countFlag() { if (isEnabled()) flags.incrementAndGet() }
    /** Count a swallowed exception at a driver/bus chokepoint. Called unconditionally by the
     *  chokepoints themselves, but only counted while verbose is on — same contract as the
     *  other counters (the heartbeat only exists under verbose anyway). */
    fun countException() { if (isEnabled()) exc.incrementAndGet() }

    /**
     * Lines queued but not yet written, and the **session-cumulative** drop total. Read by
     * `/ius verbose` when a capture ends, so the transcript on disk can be judged complete or
     * truncated before it is compared against another client. Deliberately not the heartbeat's
     * per-interval delta — a windowed count would read 0 for a capture that dropped lines earlier
     * in the session, which is the opposite of what "can I trust this transcript?" needs.
     * Fail-open.
     */
    fun backlog(): Int = try { queue.size() } catch (_: Throwable) { 0 }
    fun dropCount(): Long = try { queue.dropCount() } catch (_: Throwable) { 0L }

    /**
     * Synchronously write what is still queued, up to [timeoutMs]. Called on client shutdown so
     * the tail of a session — its last heartbeat, its last flags — reaches latest.log instead of
     * dying with the daemon thread. Bounded and fail-open: a wedged appender must never hang
     * teardown, so whatever does not fit the budget is left behind rather than waited on. (Nothing
     * is lost by skipping this mid-session: the drain thread keeps consuming after verbose is
     * switched off, so a backlog finishes writing on its own.)
     */
    fun flush(timeoutMs: Long = 2_000) {
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                emit(queue.poll() ?: return)
            }
        } catch (_: Throwable) {}
    }

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
            val e = exc.getAndSet(0)
            // Lines the appender could not keep up with this interval. Non-zero means the
            // transcript for this window is incomplete (flag *data* is not — FlagHistory is
            // written separately and stays exact).
            val d = queue.resetDrops()
            log(
                "pipeline @tick $tick (last ${HEARTBEAT_TICKS}t): tracking=$trackedPlayers " +
                    "swings=$s hurts=$h attacks=$a flags=$f exc=$e dropped=$d"
            )
        } catch (_: Throwable) {}
    }

    /**
     * Start the appender thread on first use. Double-checked so the steady-state cost of [log] is
     * a single volatile read; the one-time creation lands on whichever thread logged first and is
     * bounded and fail-open.
     */
    private fun ensureDrainThread() {
        if (drainThread != null) return
        synchronized(this) {
            if (drainThread != null) return
            val t = Thread({ drainLoop() }, "Iustitia verbose-log")
            t.isDaemon = true
            t.start()
            drainThread = t
        }
    }

    /**
     * Appender loop. `take()` parks while idle, then empties the rest of the backlog in batches —
     * fewer lock acquisitions per line, so a burst drains faster than a take-per-line loop when
     * production outpaces the appender.
     *
     * Why the batching is what makes this non-blocking: `drainTo` copies lines into a local list
     * and releases the queue lock *before* [emit] touches the appender. The slow part — log4j
     * writing to the console and latest.log — therefore never runs while the lock is held, so a
     * producer's `offer` can only ever wait on an array copy (microseconds), never on disk I/O.
     * Logging inside the drain callback instead would put the I/O back under the lock and hand the
     * tick thread the very stall this class exists to remove.
     *
     * The loop does not exit on a logging failure: a drain thread that dies leaves verbose silently
     * dead, which is the exact failure mode verbose exists to rule out. Only an interrupt (client
     * shutdown) ends it.
     */
    private fun drainLoop() {
        val batch = ArrayList<String>(DRAIN_BATCH)
        while (true) {
            emit(queue.take() ?: return)
            try {
                while (queue.drainTo(batch, DRAIN_BATCH) > 0) {
                    for (i in batch.indices) emit(batch[i])
                    batch.clear()
                }
            } catch (_: Throwable) {
                // fail-open: drop whatever this pass held rather than re-emitting it next pass,
                // then keep draining.
                batch.clear()
            }
        }
    }

    /** Write one line to the appender. Fail-open — a logging error must never kill the drainer. */
    private fun emit(message: String) {
        try { logger.info(message) } catch (_: Throwable) {}
    }
}

package dev.iustitia

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded, drop-on-full hand-off between a producer (the client tick thread) and a consumer
 * (the appender thread draining it). Extracted from [VerboseLog] so the overflow, ordering and
 * drain contract is unit-testable without a live client or a real appender.
 *
 * The producer side never blocks and never throws: a full queue drops the line and counts it,
 * because waiting for the appender is exactly the stall this exists to remove. Drops are not
 * silent — the caller reports them (`VerboseLog`'s heartbeat `dropped=N`), so a validation
 * capture can be told apart from a truncated one.
 *
 * Deliberately not a general-purpose queue: there is no unbounded growth path. A queue that
 * grows to fit any burst is a queue that eventually OOMs the client instead of dropping logs.
 */
internal class LogQueue(capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val queue = ArrayBlockingQueue<String>(capacity)
    private val dropped = AtomicLong(0)

    /** High-water mark of [dropped] already handed out by [resetDrops]. Never decreases. */
    private val reported = AtomicLong(0)

    /**
     * Non-blocking enqueue. Returns `true` when accepted, `false` when the queue was full and the
     * line was dropped (counted in [dropCount]).
     */
    fun offer(message: String): Boolean {
        // ArrayBlockingQueue.offer rejects only null; treat any refusal as a drop rather than
        // letting it propagate into the tick thread.
        val accepted = try { queue.offer(message) } catch (_: Throwable) { false }
        if (!accepted) dropped.incrementAndGet()
        return accepted
    }

    /** Blocking take for the consumer thread. Returns `null` if interrupted (and re-sets the flag). */
    fun take(): String? = try {
        queue.take()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    /** Move up to [max] queued lines into [out] in FIFO order; returns how many were moved. */
    fun drainTo(out: MutableList<String>, max: Int): Int = queue.drainTo(out, max)

    /** Non-blocking single poll; `null` when empty. Used by the bounded shutdown flush. */
    fun poll(): String? = queue.poll()

    /** Current backlog — lets a capture be judged still-in-flight or fully written. */
    fun size(): Int = queue.size

    /**
     * Total lines dropped over the queue's lifetime — what decides whether a whole capture is
     * complete. Distinct from [resetDrops], which reports only the delta: the heartbeat asks for
     * the delta (so `dropped=N` beside the other per-interval counters means "this window"), while
     * "is the transcript I just wrote usable?" needs the running total.
     */
    fun dropCount(): Long = dropped.get()

    /**
     * Read and clear the drop count, returning the lines dropped since the previous call — the
     * heartbeat's per-interval figure. Safe to call from one reporter; concurrent callers each get
     * a correct delta of the remainder.
     */
    fun resetDrops(): Long {
        val total = dropped.get()
        return total - reported.getAndSet(total)
    }
}

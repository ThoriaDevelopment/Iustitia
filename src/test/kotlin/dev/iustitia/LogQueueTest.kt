package dev.iustitia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-JVM tests for [LogQueue] — the bounded drop-on-full hand-off behind [VerboseLog].
 *
 * No Minecraft/Fabric objects and no threads under test: the queue's contract is what needs
 * pinning down, because it is the part that decides whether a verbose capture is complete and
 * whether the producer can ever be blocked. The async appender in [VerboseLog] is built on
 * exactly these semantics.
 */
class LogQueueTest {

    @Test
    fun `drains in fifo order`() {
        val q = LogQueue(4)
        for (i in 1..4) assertTrue(q.offer("line-$i"))

        val out = ArrayList<String>()
        assertEquals(4, q.drainTo(out, 4))
        assertEquals(listOf("line-1", "line-2", "line-3", "line-4"), out)
    }

    @Test
    fun `drainTo honours its batch limit and leaves the rest queued`() {
        val q = LogQueue(8)
        for (i in 1..6) q.offer("line-$i")

        val first = ArrayList<String>()
        assertEquals(3, q.drainTo(first, 3))
        assertEquals(listOf("line-1", "line-2", "line-3"), first)
        assertEquals(3, q.size())

        // The next pass picks up exactly where the last one stopped — this is what makes the
        // drain loop's batched catch-up lossless as well as ordered.
        val second = ArrayList<String>()
        assertEquals(3, q.drainTo(second, 3))
        assertEquals(listOf("line-4", "line-5", "line-6"), second)
        assertEquals(0, q.size())
    }

    @Test
    fun `overflow drops and counts instead of blocking`() {
        val q = LogQueue(2)
        assertTrue(q.offer("a"))
        assertTrue(q.offer("b"))
        // Capacity reached: the producer must be told no rather than made to wait.
        assertFalse(q.offer("c"))
        assertFalse(q.offer("d"))

        assertEquals(2, q.size())
        assertEquals(2L, q.dropCount())

        // The queue still holds exactly the accepted lines, in order.
        val out = ArrayList<String>()
        q.drainTo(out, 2)
        assertEquals(listOf("a", "b"), out)
    }

    @Test
    fun `resetDrops reports the delta while dropCount stays cumulative`() {
        val q = LogQueue(1)
        q.offer("a")
        q.offer("b")   // dropped
        q.offer("c")   // dropped

        // The heartbeat wants the delta for this window...
        assertEquals(2L, q.resetDrops())
        assertEquals(0L, q.resetDrops(), "a second read in the same window reports no new drops")
        // ...but "is this whole capture complete?" must not be answered by a windowed number:
        // a session that dropped lines 30s ago still has a partial transcript.
        assertEquals(2L, q.dropCount())

        // And a later drop is visible to both views.
        q.offer("d")
        assertEquals(3L, q.dropCount())
        assertEquals(1L, q.resetDrops())
        assertEquals(3L, q.dropCount())
    }

    @Test
    fun `poll returns null when empty and lines otherwise`() {
        val q = LogQueue(2)
        assertNull(q.poll())
        q.offer("only")
        assertEquals("only", q.poll())
        assertNull(q.poll())
    }

    @Test
    fun `take returns null when interrupted so the drain loop can exit`() {
        val q = LogQueue(1)
        val ready = CountDownLatch(1)
        val result = arrayOfNulls<String>(1)
        val t = Thread {
            ready.countDown()
            result[0] = q.take()
        }
        t.isDaemon = true
        t.start()
        try {
            ready.await()
            // Give the thread a moment to reach the blocking take, then interrupt it the way
            // client shutdown does.
            Thread.sleep(50)
            t.interrupt()
            t.join(2_000)
        } finally {
            t.interrupt()
        }
        assertNull(result[0], "interrupted take must yield null, not block or throw")
        assertFalse(t.isAlive, "interrupted take must let the drain thread exit")
    }

    @Test
    fun `concurrent producers never exceed capacity and conserve every line`() {
        val capacity = 64
        val producers = 8
        val perProducer = 500
        val q = LogQueue(capacity)

        val accepted = AtomicInteger(0)
        val go = CountDownLatch(1)
        val threads = (1..producers).map { p ->
            Thread {
                go.await()
                for (i in 1..perProducer) if (q.offer("p$p-$i")) accepted.incrementAndGet()
            }.apply { isDaemon = true; start() }
        }
        go.countDown()
        threads.forEach { it.join(10_000) }

        val attempts = producers * perProducer
        assertEquals(attempts.toLong(), accepted.get().toLong() + q.dropCount(),
            "every offered line is either queued or counted as dropped — never silently lost")
        assertTrue(q.size() <= capacity, "backlog must never exceed the bound")
        assertEquals(q.size().toLong(), accepted.get().toLong(),
            "with no consumer, everything accepted is still queued")
    }

    @Test
    fun `rejects a non-positive capacity`() {
        val threw = try { LogQueue(0); false } catch (_: IllegalArgumentException) { true }
        assertTrue(threw, "a zero-capacity queue would drop every line silently")
    }
}

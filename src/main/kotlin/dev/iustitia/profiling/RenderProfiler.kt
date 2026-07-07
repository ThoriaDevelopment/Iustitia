package dev.iustitia.profiling

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-mod render-thread sampling profiler — the live-measurement tool for the dense-player FPS
 * investigation (post pass #3: static analysis identified and fixed the most clearly wasteful
 * substrate, but the symptom persisted, so we now measure rather than guess).
 *
 * Gated on [dev.iustitia.VerboseLog] (the `/ius profile` command refuses to start unless verbose
 * is on, per the user's "implement that into verbose" framing) — a profiler is a verbose-mode
 * diagnostic, not a normal-play path. Sampling is opt-in and runs only between an explicit
 * `/ius profile` start and `/ius profile stop`; nothing runs otherwise.
 *
 * ## How it works
 *
 * The render thread (which is also the END_CLIENT_TICK thread — per-tick check work runs inline
 * between frames) is captured ONCE at [start] (the command handler runs on the render thread, so
 * `Thread.currentThread()` is it). A daemon sampler thread then calls `renderThread.stackTrace`
 * every ~5ms — the standard cross-thread sampling approach (the same one Spark uses). Each tick
 * of a slow check accumulates many samples on its frame, so the flat top-frame tally surfaces the
 * hot method directly.
 *
 * Three views are aggregated per sample:
 *  1. **Flat top-frame** — `stack[0]` (innermost method). Shows raw hotspots across vanilla AND
 *     ours (e.g. `BlockState.getCollisionShape`, vanilla entity render, a check's `process`).
 *     This is the ground truth for "what is the render thread actually doing".
 *  2. **Iustitia-attributed** — the first `dev.iustitia.*` frame from the top. Attributes the
 *     cost of vanilla calls made on Iustitia's behalf (e.g. `getCollisionShape` reached from
 *     `SpiderCheck.process`) to the Iustitia method that drove them. A sample with no
 *     `dev.iustitia.*` frame is pure vanilla (rendering, chunk meshing, GC) — NOT counted here.
 *  3. **Pairs** — `iustitia_method -> current top frame` (top 40). The most actionable view: it
 *     says e.g. "SpiderCheck.process -> BlockStateAbstract.getCollisionShape ×N" — which Iustitia
 *     method is paying for which vanilla call.
 *
 * GC / safepoint gaps are detected by the sampler's own `Thread.sleep(5)` overrunning >3× (a
 * safepoint stalls ALL threads, including the sampler, so a sampler gap is a proxy for a JVM-wide
 * pause). A slow check does NOT stall the sampler — the sampler keeps ticking and just samples the
 * render thread's stack repeatedly while it's stuck in the slow method (which is how the slow
 * method shows up as a tall bar in the flat tally).
 *
 * Writes a text report to `<dataDir>/profiles/iustitia-profile-<timestamp>.txt` (dataDir =
 * `%APPDATA%/.iustitia` on Windows — the same place the persistence store / clips live) so the
 * user can hand the file back for analysis. All paths fail-open; sampling never throws into the
 * render thread (a cross-thread `getStackTrace` failure is swallowed by the sampler).
 */
object RenderProfiler {

    /** Sampler interval (ms). 5ms = ~200 samples/s — enough resolution for a per-tick (50ms) cost. */
    private const val INTERVAL_MS = 5L
    /** A sampler sleep overrun beyond this (3× the interval) is treated as a GC/safepoint gap. */
    private const val GC_GAP_THRESHOLD_MS = 15L

    @Volatile private var running: Boolean = false
    @Volatile private var renderThread: Thread? = null
    private var samplerThread: Thread? = null

    // Per-sample tallies. ConcurrentHashMap + merge (no per-sample boxing beyond the Integer key).
    private val topFrames = ConcurrentHashMap<String, AtomicInteger>()
    private val iustitiaFrames = ConcurrentHashMap<String, AtomicInteger>()
    private val pairs = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile private var totalSamples: Int = 0
    @Volatile private var iustitiaSamples: Int = 0
    @Volatile private var gaps: Int = 0
    @Volatile private var maxGapMs: Long = 0
    @Volatile private var lastSampleMs: Long = 0
    @Volatile private var startMs: Long = 0

    fun isRunning(): Boolean = running

    /**
     * Start sampling. MUST be called on the render thread (the `/ius profile` command handler is),
     * so [renderThread] is captured correctly. Returns `null` on success, or an error string
     * (`"already running"`) if a session is already in progress.
     */
    fun start(): String? {
        if (running) return "already running"
        renderThread = Thread.currentThread()
        topFrames.clear(); iustitiaFrames.clear(); pairs.clear()
        totalSamples = 0; iustitiaSamples = 0; gaps = 0; maxGapMs = 0; lastSampleMs = 0
        startMs = System.currentTimeMillis()
        running = true
        samplerThread = Thread({ sampleLoop() }, "iustitia-profiler").apply {
            isDaemon = true
            start()
        }
        return null
    }

    /**
     * Stop sampling and write the text report. Returns the absolute report path on success, or
     * `"not running"` / `"failed: <msg>"` on failure. Idempotent-ish: a second stop returns
     * `"not running"`.
     */
    fun stop(): String {
        if (!running) return "not running"
        running = false
        samplerThread?.let { try { it.interrupt() } catch (_: Throwable) {} }
        samplerThread = null
        return try { writeReport() } catch (e: Throwable) { "failed: ${e.message ?: e.javaClass.simpleName}" }
    }

    private fun sampleLoop() {
        while (running) {
            try {
                val rt = renderThread
                if (rt != null) {
                    val stack = rt.stackTrace
                    if (stack.isNotEmpty()) record(stack)
                }
            } catch (_: Throwable) {
                // a cross-thread getStackTrace failure is swallowed — sampling is best-effort
            }
            val now = System.currentTimeMillis()
            if (lastSampleMs != 0L) {
                val gap = now - lastSampleMs - INTERVAL_MS
                if (gap > GC_GAP_THRESHOLD_MS) {
                    gaps++
                    if (gap > maxGapMs) maxGapMs = gap
                }
            }
            lastSampleMs = now
            try {
                Thread.sleep(INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun record(stack: Array<StackTraceElement>) {
        totalSamples++
        val top = stack[0]
        val topLabel = label(top)
        topFrames.computeIfAbsent(topLabel) { AtomicInteger() }.incrementAndGet()
        // First dev.iustitia.* frame from the top — the Iustitia method driving the stack.
        var iustitiaLabel: String? = null
        for (e in stack) {
            if (e.className.startsWith("dev.iustitia.")) { iustitiaLabel = label(e); break }
        }
        if (iustitiaLabel != null) {
            iustitiaSamples++
            iustitiaFrames.computeIfAbsent(iustitiaLabel) { AtomicInteger() }.incrementAndGet()
            pairs.computeIfAbsent("$iustitiaLabel -> $topLabel") { AtomicInteger() }.incrementAndGet()
        }
    }

    /** `ClassSimpleName.method` — short enough to read in a ranked list, distinct enough to act on. */
    private fun label(e: StackTraceElement): String {
        val simple = e.className.substringAfterLast('.')
        return "$simple.${e.methodName}"
    }

    private fun writeReport(): String {
        val dir = profileDir()
        Files.createDirectories(dir)
        val ts = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val path = dir.resolve("iustitia-profile-$ts.txt")
        val sb = StringBuilder()
        val durMs = System.currentTimeMillis() - startMs
        val iusPct = if (totalSamples > 0) iustitiaSamples * 100.0 / totalSamples else 0.0
        sb.appendLine("=== Iustitia render-thread profile ===")
        sb.appendLine("render thread: ${renderThread?.name ?: "?"}")
        sb.appendLine("duration: ${durMs} ms (${durMs / 1000}s)")
        sb.appendLine("sample interval: ${INTERVAL_MS} ms")
        sb.appendLine("total samples: $totalSamples")
        sb.appendLine("iustitia-attributed samples: $iustitiaSamples (${pct(iusPct)}% of total)")
        sb.appendLine("  (samples with NO dev.iustitia.* frame = pure vanilla: rendering / chunk mesh / GC)")
        sb.appendLine("GC/safepoint gaps (sampler sleep >${GC_GAP_THRESHOLD_MS}ms): $gaps  (max ${maxGapMs}ms)")
        sb.appendLine()
        sb.appendLine("--- Top 40 flat top-frames (vanilla + iustitia: raw hotspots) ---")
        appendTop(sb, topFrames, 40, totalSamples)
        sb.appendLine()
        sb.appendLine("--- Top 40 iustitia-attributed methods (first dev.iustitia.* frame) ---")
        appendTop(sb, iustitiaFrames, 40, iustitiaSamples)
        sb.appendLine()
        sb.appendLine("--- Top 40 pairs (iustitia_method -> current top frame) ---")
        appendTop(sb, pairs, 40, iustitiaSamples)
        Files.writeString(path, sb.toString())
        return path.toString()
    }

    private fun appendTop(sb: StringBuilder, map: Map<String, AtomicInteger>, n: Int, base: Int) {
        if (map.isEmpty()) { sb.appendLine("  (none — no samples in this view)"); return }
        map.entries.map { it.key to it.value.get() }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(n)
            .forEach { (k, v) ->
                val p = if (base > 0) v * 100.0 / base else 0.0
                sb.appendLine(String.format(java.util.Locale.US, "%6d  %5s%%  %s", v, pct(p), k))
            }
    }

    private fun pct(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

    /** `%APPDATA%/.iustitia/profiles` on Windows, `<gameDir>/.iustitia/profiles` elsewhere
     *  (matches [dev.iustitia.persistence.PersistenceManager]'s dataDir resolution). */
    private fun profileDir(): Path {
        val appdata = try { System.getenv("APPDATA") } catch (_: Throwable) { null }
        val base = if (appdata != null) Path.of(appdata).resolve(".iustitia")
            else FabricLoader.getInstance().gameDir.resolve(".iustitia")
        return base.resolve("profiles")
    }
}
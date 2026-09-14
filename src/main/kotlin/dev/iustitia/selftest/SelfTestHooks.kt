package dev.iustitia.selftest

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The recording tap used by the automated live-test harness. A tiny, dev-gated
 * singleton: it does nothing unless the JVM property `fabric.selftest` is set (only
 * the gametest run sets it), in which case `Check.flag` reports every flag here so
 * the harness can assert per-check VL and alert crossings.
 *
 * This is deliberately in the main source set (not the gametest source set) because
 * `Check.flag` is the one chokepoint every flag routes through — but the hook itself
 * must stay inert in normal play:
 *
 * - [isEnabled] reads a JVM system property once. Normal clients never set it, so
 *   the flag path costs one volatile boolean read.
 * - [recordFlag] only appends to maps keyed by the flagging player's uuid.
 * - [clear] wipes the recording between scenarios.
 *
 * No persistence, no I/O, no reflection — the recorded state is process-local and
 * dies with the test JVM. It never touches alerts, chat, tiering, or history; the
 * harness reads it instead of scraping chat, which keeps the assertions deterministic.
 */
object SelfTestHooks {

    @Volatile
    private var enabled: Boolean = try {
        System.getProperty("fabric.selftest") != null
    } catch (_: Throwable) { false }

    @Volatile
    private var recording: Boolean = false

    /** Peak VL per (player uuid, check id) since the last [clear]. */
    private val peakVl = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Double>>()

    /** Check ids whose VL crossed setbackVL per player since the last [clear]. */
    private val alerted = ConcurrentHashMap<UUID, MutableSet<String>>()

    fun isEnabled(): Boolean = enabled

    fun startRecording() {
        if (!enabled) return
        recording = true
    }

    fun stopRecording() {
        recording = false
    }

    fun clear() {
        peakVl.clear()
        alerted.clear()
    }

    /** Called from [dev.iustitia.checks.Check.flag] when self-test mode is active. */
    fun recordFlag(uuid: UUID, checkId: String, vl: Double, crossedAlert: Boolean) {
        if (!recording) return
        val perPlayer = peakVl.getOrPut(uuid) { ConcurrentHashMap() }
        perPlayer.merge(checkId, vl) { a, b -> maxOf(a, b) }
        if (crossedAlert) {
            alerted.getOrPut(uuid) { ConcurrentHashMap.newKeySet() }.add(checkId)
        }
    }

    fun peakVlFor(uuid: UUID): Map<String, Double> =
        peakVl[uuid]?.toMap() ?: emptyMap()

    fun alertedChecksFor(uuid: UUID): Set<String> =
        alerted[uuid]?.toSet() ?: emptySet()

    /** Union of every alerted check across all players — used by whole-scene assertions. */
    fun allAlertedChecks(): Set<String> =
        alerted.values.fold(mutableSetOf<String>()) { acc, s -> acc.addAll(s); acc }
}

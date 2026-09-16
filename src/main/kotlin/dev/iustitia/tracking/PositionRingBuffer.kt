package dev.iustitia.tracking

import net.minecraft.util.math.Vec3d

/**
 * Fixed-capacity ring buffer of (tick, position) samples per tracked player.
 * Ported from Nemesis `TrackedPosition` / ring-buffer pattern: lag-compensated
 * reach samples positions whose timestamp falls within the attacker's ping window.
 *
 * [getPositions] returns every stored position whose tick is newer than
 * `currentTick - maxAgeTicks`, newest-first. Fail-open: never throws.
 *
 * ## Two read paths, on purpose
 *
 * This is read on the per-`AttackEvent` hot path, so there are two ways out:
 *
 * - [forEachRecent] hands the raw components to a callback and allocates nothing. Use it whenever
 *   the consumer only aggregates — a spread, a min/max, a consecutive-distance test — which is
 *   most of them.
 * - [getPositions] materializes `Vec3d` objects for consumers that must hold or hand on individual
 *   positions (candidate hitbox lists, stored samples).
 *
 * Prefer [forEachRecent]: a `Vec3d` per sample per call adds up across a fight, and the aggregating
 * callers gained nothing from the objects.
 *
 * [forEachRecent] does NOT catch: the callback runs inline, so a throwing callback propagates and
 * the *caller* owns the fail-open decision (see `ReachCheck.isMotionless`, which treats a failed
 * walk exactly like a short ring — conservatively). [add] and [getPositions] never throw.
 */
class PositionRingBuffer(val capacity: Int = 24) {
    private val ticks = IntArray(capacity)
    private val xs = DoubleArray(capacity)
    private val ys = DoubleArray(capacity)
    private val zs = DoubleArray(capacity)
    private var head = 0
    private var size = 0

    fun add(tick: Int, pos: Vec3d) {
        try {
            ticks[head] = tick
            xs[head] = pos.x
            ys[head] = pos.y
            zs[head] = pos.z
            head = (head + 1) % capacity
            if (size < capacity) size++
        } catch (_: Throwable) {
            // ignore
        }
    }

    /** Positions sampled within the last [maxAgeTicks] (newest first). */
    fun getPositions(maxAgeTicks: Int, currentTick: Int): List<Vec3d> {
        if (maxAgeTicks <= 0 || size == 0) return emptyList()
        return try {
            val out = ArrayList<Vec3d>(size)
            val threshold = currentTick - maxAgeTicks
            for (i in 0 until size) {
                val idx = (head - 1 - i + capacity) % capacity
                if (ticks[idx] > threshold) {
                    out.add(Vec3d(xs[idx], ys[idx], zs[idx]))
                }
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Visit every sample newer than `currentTick - maxAgeTicks`, newest first, as raw components.
     * Allocation-free. The callback must not throw (see the class doc — the caller owns fail-open).
     */
    fun forEachRecent(maxAgeTicks: Int, currentTick: Int, action: (x: Double, y: Double, z: Double) -> Unit) {
        if (maxAgeTicks <= 0 || size == 0) return
        val threshold = currentTick - maxAgeTicks
        for (i in 0 until size) {
            val idx = (head - 1 - i + capacity) % capacity
            if (ticks[idx] > threshold) action(xs[idx], ys[idx], zs[idx])
        }
    }
}

package dev.iustitia.tracking

import net.minecraft.util.math.Vec3d

/**
 * Fixed-capacity ring buffer of (tick, position) samples per tracked player.
 * Ported from Nemesis `TrackedPosition` / ring-buffer pattern: lag-compensated
 * reach samples positions whose timestamp falls within the attacker's ping window.
 *
 * getPositions(maxAgeTicks, currentTick) returns every stored position whose tick is
 * newer than `currentTick - maxAgeTicks`, newest-first. Fail-open: never throws.
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

    fun clear() {
        head = 0
        size = 0
    }
}
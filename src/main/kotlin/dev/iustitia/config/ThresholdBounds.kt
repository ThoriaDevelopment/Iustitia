package dev.iustitia.config

/**
 * Per-check bounds for [IustitiaConfig.CheckConfig.threshold] where the generic `0..N` range
 * lets a value silently turn a check into always-flag (issue #32).
 *
 * Most thresholds are counts or magnitudes, so the generic range is harmless. The ones below
 * are RATIOS compared against a rate, and one end of the generic range makes the comparison
 * true on almost any window:
 *
 * - `throughWalls`: flags when `occluded/size >= threshold` — 0 flags every fight.
 * - `rotationTracking`: flags when `matched/size > threshold` — 0 flags any single aim match.
 * - `keepSprint`: flags when `retainedSpeed/preHitSpeed > threshold` — 0 flags any sprint hit.
 * - `noKnockback`: flags when `displacement < threshold * expected` — above 1 flags full,
 *   vanilla knockback.
 *
 * The floor ([RATIO_MIN]) only rejects the degenerate zero; it is not a calibration claim.
 * The rate checks are capped at 1 because their rate never exceeds 1, so a larger value would
 * only switch the check off without saying so.
 *
 * Every threshold writer reads these bounds — `/ius threshold`, the YACL field, and the
 * JSON/preset load path — so no editor can store a value the command would reject.
 * Pure Kotlin, no Minecraft imports, so it is unit-testable.
 */
object ThresholdBounds {
    /** Smallest accepted value for a ratio threshold whose zero means always-flag. */
    const val RATIO_MIN: Double = 0.01

    /** The range `/ius threshold` accepts for a non-ratio threshold. */
    val GENERIC: ClosedFloatingPointRange<Double> = 0.0..1000.0

    private val RATIO: Map<String, ClosedFloatingPointRange<Double>> = mapOf(
        "throughWalls" to RATIO_MIN..1.0,
        "rotationTracking" to RATIO_MIN..1.0,
        "keepSprint" to RATIO_MIN..GENERIC.endInclusive,
        "noKnockback" to 0.0..1.0,
    )

    /** The ratio-specific range for [id], or null when the check's threshold is not a ratio. */
    fun ratioRangeFor(id: String): ClosedFloatingPointRange<Double>? = RATIO[id]

    /** The full accepted range for [id]: its ratio range, else [GENERIC]. */
    fun rangeFor(id: String): ClosedFloatingPointRange<Double> = RATIO[id] ?: GENERIC

    /** True iff [value] is a finite number inside [rangeFor] for [id]. */
    fun accepts(id: String, value: Double): Boolean = value.isFinite() && value in rangeFor(id)
}

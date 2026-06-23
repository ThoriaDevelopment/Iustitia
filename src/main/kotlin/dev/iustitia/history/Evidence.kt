package dev.iustitia.history

import net.minecraft.util.math.Vec3d
import java.util.UUID

/**
 * The per-flag "why" payload carried on [FlagHistory.Flag] so the history screen, the profile
 * card, and the `/ius report` export can show *what triggered a flag* — the reach distance that
 * fired, the fly Δy, the blocked LOS rays, the triggerbot fast-hit ratio — instead of just the
 * check label + instantaneous VL.
 *
 * Every field is nullable: only the combat + fly subset of checks populate it (the ones whose
 * flag sites have a meaningful numeric "why" in local scope). All other checks pass `null`
 * (their existing `flag(...)` calls are unchanged) and the UI falls back to a label-only row.
 * This is **read-only capture** of values already computed at the flag site — it changes no
 * check logic, thresholds, or decay.
 *
 * Fields:
 *  - [subLabel] — the fine-grained sub-component, e.g. `"Ascend"`, `"in-box"`, `"silent(snap)"`.
 *    (FlyEnvelopeCheck already encodes this as a parenthesized suffix on the label; we split it
 *    out here so the UI can show `Fly · Ascend` cleanly.)
 *  - [measurement] — the triggering numeric value (reach dist, dy, ratio, victim count).
 *  - [threshold] — what it was compared against (`3.8`, `RATIO`, `2.0`…).
 *  - [pos] — the attacker/player world position at the flag tick (for the Phase B replay/viz).
 *  - [victim] — the combat victim uuid (combat checks only).
 *  - [extra] — a free-form human hint for things that don't fit the numeric fields
 *    (`"3 rays blocked"`, `"ratio 4/5"`, `"horiz=0.3 prevΔy=0.0"`).
 */
data class Evidence(
    val subLabel: String? = null,
    val measurement: Double? = null,
    val threshold: Double? = null,
    val pos: Vec3d? = null,
    val victim: UUID? = null,
    val extra: String? = null,
)
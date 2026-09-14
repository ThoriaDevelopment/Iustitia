package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.history.Evidence
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID

/**
 * JumpReset detector (JumpOnHurt). The cheat auto-jumps the instant the cheater is hit to
 * "reset" knockback. Observer signature: the cheater's Δy jumps (~0.4) within 1 tick of
 * being hit, repeatedly, with no obstacle. We record each hit (into the *victim's* context)
 * from [AttackEvent] and watch for a jump in [process] within ±1 tick. A jump-coincidence rate
 * of ≥90% over a sliding window of hits is the cheat; the alert is a one-shot per sustained
 * episode (see [Check.flagEpisode]) because the cadence is one verdict per hit — far below the
 * decay, so per-event accumulation could never reach setbackVL.
 *
 * FP guards over the original 3-hit/80% gate:
 *  - **Higher bar (5 hits / 90%).** Jump-resetting is also a legit anti-KB technique; a
 *    skilled player intentionally hops on damage and can sustain ~80% coincidence over a few
 *    hits. 90% across ≥5 hits is above the legit-technique ceiling while a real JumpReset
 *    cheat (~every hit) still clears it.
 *  - **KB-induced-hop exemption.** A knockback impulse with an upward component (airborne
 *    victim, special KB) produces a small Δy spike that isn't a self-jump. If an
 *    EntityVelocityUpdate arrived within 3 ticks, the Δy is server-induced — skip counting
 *    it as a coincident jump. On servers that don't broadcast other-player velocity packets
 *    this never exempts (we can't distinguish there); the higher bar absorbs that case.
 */
class JumpOnHurtCheck : Check() {

    override val id: String = "jumpOnHurt"

    init {
        try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = JumpOnHurtContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            val ctx = contextOf(ev.victim) as JumpOnHurtContext
            ctx.lastHitTick = ev.tick
            ctx.pendingHit = true
            ctx.totalHits++
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            if (tp.inVehicle || tp.gliding || tp.riptide) return
            if (tick - tp.lastTeleportTick < 5) return
            val ctx = contextOf(tp.uuid) as JumpOnHurtContext
            // totalHits/coincidentHits used to accumulate all-session, so the coincidence ratio
            // went stale: a cheater who toggled JumpReset on late could never climb back to
            // 0.9 against a huge all-time denominator (fail-negative), and a one-off early
            // coincidence was permanently baked in. Reset the per-fight counters once the
            // player has been out of combat (no hit) for SESSION_RESET_TICKS, so the ratio
            // reflects the current fight — the documented "≥5 hits / ≥90% coincidence" bar.
            if (ctx.lastHitTick != -10000 && tick - ctx.lastHitTick > SESSION_RESET_TICKS) {
                ctx.totalHits = 0
                ctx.episodeRing.clear()
                ctx.episodeActive = false
            }
            if (!ctx.pendingHit) return
            val since = tick - ctx.lastHitTick
            // KB-induced-hop exemption: a recent velocity update means the Δy is server knockback,
            // not a deliberate jump — the hit is skipped entirely (neither counted nor judged).
            val kbHop = tick - tp.velocityTick < 3
            if (since in 0..1 && !kbHop && tp.deltaY > cfg.threshold) {
                ctx.pendingHit = false
                ctx.totalHits++
                judge(ctx, tp, tick, jumped = true)
            } else if ((since in 0..1 && !kbHop && tp.deltaY < -0.05) || since > 1) {
                // The hit resolved without a self-jump: the victim was hit and did not hop (a
                // negative Δy is the knockback settling, a ~zero Δy is standing still). Counting
                // it is what keeps the ratio a real *rate* over hits rather than over jumps.
                ctx.pendingHit = false
                ctx.totalHits++
                judge(ctx, tp, tick, jumped = false)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Record one resolved hit's verdict and alert once if the pattern is sustained.
     *
     * The gate is the documented "≥90% jump-coincidence rate": [WINDOW] resolved hits with at
     * least [MIN_COINCIDENT] of them jumped on the hit tick. Expressed as a sliding window it is
     * the same bar, but it now *alerts* (one-shot per episode) instead of accumulating a level-1.0
     * flag at a one-per-hit cadence that the decay always outran.
     */
    private fun judge(ctx: JumpOnHurtContext, tp: TrackedPlayer, tick: Int, jumped: Boolean) {
        val sustainedNow = sustained(ctx, jumped, WINDOW, MIN_COINCIDENT)
        if (sustainedNow) {
            flagEpisode(tp, ctx, "JumpReset", tick, Evidence(
                subLabel = "jump-on-hit",
                measurement = MIN_COINCIDENT.toDouble(), threshold = WINDOW.toDouble(),
                pos = tp.pos,
                extra = "jumped on ≥$MIN_COINCIDENT of the last $WINDOW hits (a hand cannot jump on " +
                    "demand that consistently)"))
        } else {
            rearmEpisode(ctx, sustainedNow)
        }
    }

    private class JumpOnHurtContext : CheckContext() {
        var lastHitTick: Int = -10000
        var pendingHit: Boolean = false
        var totalHits: Int = 0
    }

    private companion object {
        /** Sliding window of resolved hits the coincidence rate is measured over. */
        const val WINDOW = 10
        /** Jumps required inside the window — the documented ≥90% coincidence bar. */
        const val MIN_COINCIDENT = 9
        /** Idle window (no hit taken) after which the latch releases, so the next fight starts fresh. */
        const val SESSION_RESET_TICKS = 140
    }
}
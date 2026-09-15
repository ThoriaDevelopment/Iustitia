package dev.iustitia.checks

import dev.iustitia.NumFmt
import dev.iustitia.VerboseLog
import dev.iustitia.alert.AlertManager
import dev.iustitia.config.ConfigManager
import dev.iustitia.selftest.SelfTestHooks
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.event.SwingSignal
import dev.iustitia.tracking.TrackedPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Base of all detection checks. Owns the per-player [CheckContext] map and the shared
 * violation-decay rhythm. Subclasses override either [process] (movement, every tick)
 * and/or the bus-driven combat hooks ([onAttack], [onSwing]).
 *
 * Violation lifecycle (Detection Plan C.4): a violation adds [level] to vl; each tick
 * vl decays by [decayPerTick] (clean ticks drift to 0); an alert fires only when
 * vl > setbackVL and the per-(player,check) throttle + join-grace allow it. All paths
 * fail-open — a thrown check is caught upstream and skipped, never propagated.
 *
 * [cfg] is resolved live from [ConfigManager] by [id] on every read, so `/iustitia`
 * edits and config reloads take effect without rebuilding the check registry.
 */
abstract class Check {

    abstract val id: String

    /** Per-check config slice — resolved live so config edits propagate immediately. */
    val cfg: IustitiaConfig.CheckConfig get() = ConfigManager.config.slice(id)

    /** Create a fresh per-player context for this check. */
    protected abstract fun newContext(uuid: UUID): CheckContext

    private val contexts = ConcurrentHashMap<UUID, CheckContext>()

    fun contextOf(uuid: UUID): CheckContext = contexts.getOrPut(uuid) { newContext(uuid) }

    val enabled: Boolean get() = cfg.enabled

    private val decayPerTick: Double get() = cfg.decay
    val setbackVL: Double get() = cfg.setbackVL

    /** Per-tick movement entrypoint (override for movement checks). */
    open fun process(tp: TrackedPlayer, tick: Int) {}

    /** Combat entrypoint: an inferred attack by [tp] (attacker) against [victim]. */
    open fun onAttack(tp: TrackedPlayer, victim: TrackedPlayer, ev: AttackEvent, tick: Int) {}

    /** Combat entrypoint: [tp] swung. */
    open fun onSwing(tp: TrackedPlayer, signal: SwingSignal) {}

    /** Decay every live context by one tick — call once per client tick before processing. */
    fun decayAll() {
        val d = decayPerTick
        if (d <= 0.0) return
        for (ctx in contexts.values) ctx.decay(d)
    }

    fun purge(uuid: UUID) { contexts.remove(uuid) }

    fun resetAll() { contexts.clear() }

    /**
     * Record a violation: add [level] to vl and alert if it crosses [setbackVL] (subject
     * to throttle + join-grace). [label] is the short check tag shown in the alert.
     *
     * [evidence] is the optional per-flag "why" payload (see [dev.iustitia.history.Evidence]):
     * reach distance, fly Δy, blocked LOS rays, etc. Default `null` — only the combat + fly
     * subset of checks populate it; every existing `flag(...)` call site compiles unchanged.
     * This is read-only capture of values already in scope at the flag site; no check logic
     * changes.
     */
    /**
     * Record this event's [violating] verdict into [ctx]'s rolling window and report whether the
     * window now describes a **sustained** pattern: at least [minViolations] of the last [window]
     * events violated. See [CheckContext.episodeRing] for why event-driven combat checks need
     * this instead of per-event accumulation.
     */
    protected fun sustained(
        ctx: CheckContext,
        violating: Boolean,
        window: Int,
        minViolations: Int,
    ): Boolean {
        ctx.episodeRing.addFirst(violating)
        while (ctx.episodeRing.size > window) ctx.episodeRing.pollLast()
        if (ctx.episodeRing.size < window) return false
        var n = 0
        for (v in ctx.episodeRing) if (v) n++
        return n >= minViolations
    }

    /**
     * Flag a **confirmed sustained episode** once.
     *
     * A combat check whose cheapest flag cadence is one per attack (≈1 per 12 ticks at vanilla
     * cooldown) cannot accumulate past the 0.5–1.0/tick decay no matter how blatant the cheat is —
     * measured live, `keepSprint`, `wTap`, `hitFlick`, `noKnockback`, `backtrack` and
     * `hitsWithoutSwing` all recorded in `/ius hist` and never alerted. Such a check pairs
     * [sustained] (does the *pattern* hold?) with this one-shot: the episode alerts at a level that
     * clears [setbackVL] in a single flag, and the latch re-arms only once the pattern breaks, so a
     * cheater who keeps doing it is reported once (not silenced, and not spammed) while one stray
     * event on a legitimate player never alerts.
     */
    protected fun flagEpisode(
        tp: TrackedPlayer, ctx: CheckContext, label: String, tick: Int,
        evidence: dev.iustitia.history.Evidence? = null,
    ) {
        // Disabled-check gate BEFORE the latch: flagEpisode sets ctx.episodeActive before
        // calling flag(), so if a check is disabled while its pattern is sustained the latch
        // would be consumed with no flag — and re-enabling the check later could never alert
        // (flagEpisode short-circuits on the latch until the pattern happens to break). A
        // disabled check must not consume episode state; the flag()-side gate remains the
        // chokepoint for the per-event flag sites.
        if (!cfg.enabled) return
        if (ctx.episodeActive) return
        ctx.episodeActive = true
        flag(tp, ctx, setbackVL + 1.0, label, tick, evidence)
    }

    /**
     * Release the [flagEpisode] latch once the sustained pattern has genuinely stopped, so a later
     * episode can alert again.
     */
    protected fun rearmEpisode(ctx: CheckContext, sustainedNow: Boolean) {
        if (!sustainedNow) ctx.episodeActive = false
    }

    protected fun flag(
        tp: TrackedPlayer, ctx: CheckContext, level: Double, label: String, tick: Int,
        evidence: dev.iustitia.history.Evidence? = null,
    ) {
        try {
            // Disabled-check gate: a check whose config slice is disabled (via /ius toggle, the
            // YACL screen, or a preset apply such as Lenient's disableSubtleChecks) produces NO
            // flags at all — no VL accumulation, no FlagHistory/tier update, no alert. This gate
            // lives at the flag chokepoint rather than at the dispatch sites because combat checks
            // are bus-driven (each subscribes to AttackEvent/SwingSignal in its own init), so the
            // tick loop's `if (!c.enabled) continue` never reaches them: without this gate a
            // toggled-off combat check kept flagging silently while /ius toggle reported OFF
            // (verified live). cfg resolves live from ConfigManager, so a preset apply mid-session
            // takes effect on the next flag site — no registry rebuild.
            if (!cfg.enabled) return
            // Exempt chokepoint: a player on the /ius exempt list is invisible to EVERY check
            // (movement + combat; all flags route through here). Skip before any VL/state write so
            // an exempt player stays clean. Tracking/replay/render still run (only detection is
            // suppressed). Forward-looking only — does NOT clear existing flags (use /ius clear).
            if (dev.iustitia.exempt.Exemptions.isExempt(tp.uuid)) return
            ctx.addVl(level)
            VerboseLog.countFlag()
            // Session flag history (drives /ius hist, status counts, alert hover, nametag tier).
            // Fail-open: a history error must never block a flag.
            try {
                dev.iustitia.history.FlagHistory.recordFlag(tp.uuid, tp.username(), id, label, ctx.vl, tick, evidence)
            } catch (_: Throwable) {}
            // Verbose: surface every flag (sub-threshold included) so a validation pass can
            // confirm a check is reacting even when nothing crosses setbackVL. The line below
            // the setback line in chat is the real alert; this is console-only diagnostics.
            // Guarded: the concatenation + "%.2f".format is evaluated before VerboseLog.log would
            // short-circuit, so we check isEnabled() once here to skip the whole build when verbose
            // is off (flag() is the hottest path in the pipeline — every flag, every tick).
            if (VerboseLog.isEnabled()) {
                // Include the evidence's numeric "why" when the flag site captured one. The
                // automated live-test calibration loop reads this line to tell a correctly-driven
                // scenario from a mis-driven one (e.g. "hit from 3.62 blocks" vs a real 2.2), so it
                // must carry the measurement, not just the label.
                val why = evidence?.let { e ->
                    buildString {
                        e.subLabel?.let { append(" sub=").append(it) }
                        e.measurement?.let { append(" m=").append(NumFmt.d(digits = 3, v = it)) }
                        e.threshold?.let { append(" t=").append(NumFmt.d(digits = 3, v = it)) }
                        e.extra?.let { append(" (").append(it).append(')') }
                    }
                } ?: ""
                VerboseLog.log(
                    "$id flag ${VerboseLog.nameOf(tp.username(), tp.uuid)} vl=${NumFmt.d(digits = 2, v = ctx.vl)} " +
                        "(setback $setbackVL) [$label] @tick $tick$why"
                )
            }
            // Self-test tap (dev-gated): record the peak VL + alert crossing for the automated
            // live-test harness (docs/automated-live-testing.md). Gated on the
            // fabric.selftest system property so this is a no-op read in normal play — the
            // property is only set by the gametest JVM. Fail-open, throws nothing, changes
            // no detection behavior.
            try {
                if (SelfTestHooks.isEnabled()) {
                    SelfTestHooks.recordFlag(tp.uuid, id, ctx.vl, ctx.vl > setbackVL)
                }
            } catch (_: Throwable) {}
            if (ctx.vl > setbackVL) {
                AlertManager.alert(
                    name = tp.username(),
                    check = label,
                    vl = ctx.vl,
                    player = tp.uuid,
                    checkId = id,
                    tick = tick,
                    joinTick = tp.joinTick,
                    setbackVL = setbackVL,
                )
            }
        } catch (e: Throwable) {
            // fail-open: a flag must never throw. But surface the failure when verbose so a check
            // that silently throws on real data is discoverable (otherwise it's indistinguishable
            // from a quiet server — the whole point of the verbose heartbeat). Self-gated.
            try { if (VerboseLog.isEnabled()) VerboseLog.log("$id flag threw: ${e.javaClass.simpleName}: ${e.message}") } catch (_: Throwable) {}
        }
    }
}
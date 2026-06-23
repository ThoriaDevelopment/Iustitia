package dev.iustitia.checks

import dev.iustitia.VerboseLog
import dev.iustitia.alert.AlertManager
import dev.iustitia.config.ConfigManager
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
    protected fun flag(
        tp: TrackedPlayer, ctx: CheckContext, level: Double, label: String, tick: Int,
        evidence: dev.iustitia.history.Evidence? = null,
    ) {
        try {
            ctx.vl += level
            VerboseLog.countFlag()
            // Session flag history (drives /ius hist, status counts, alert hover, nametag tier).
            // Fail-open: a history error must never block a flag.
            try {
                dev.iustitia.history.FlagHistory.recordFlag(tp.uuid, tp.username(), id, label, ctx.vl, tick, evidence)
            } catch (_: Throwable) {}
            // Verbose: surface every flag (sub-threshold included) so a validation pass can
            // confirm a check is reacting even when nothing crosses setbackVL. The line below
            // the setback line in chat is the real alert; this is console-only diagnostics.
            VerboseLog.log(
                "$id flag ${VerboseLog.nameOf(tp.username(), tp.uuid)} vl=${"%.2f".format(ctx.vl)} " +
                    "(setback $setbackVL) [$label] @tick $tick"
            )
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
        } catch (_: Throwable) {
            // fail-open: a flag must never throw
        }
    }
}
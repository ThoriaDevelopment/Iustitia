package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.AttackEvent
import dev.iustitia.event.VelocitySignal
import dev.iustitia.history.Evidence
import dev.iustitia.math.Vectors
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.LagCombatCorrelator
import dev.iustitia.tracking.TrackedPlayer
import dev.iustitia.world.WorldQueries
import net.minecraft.client.MinecraftClient
import java.util.UUID
import kotlin.math.hypot

/**
 * Anti-knockback / Velocity detector (Nemesis VelocityA/B proxy). When a sprinting
 * attacker lands a confirmed hit on the cheater, vanilla applies ~0.4 horizontal
 * knockback along the attacker's facing. A Velocity cheat cancels/scales that, so the
 * victim barely moves. We can't see the sent velocity packet (and on many servers it isn't
 * even broadcast for other players), so we use the vanilla expected magnitude and measure
 * the victim's actual ΔXZ over the knockback window.
 *
 * On [AttackEvent] we record (into the *victim's* context) the hit tick + attacker facing +
 * "attacker was sprinting". In [process] we accumulate the victim's horizontal displacement
 * over a [WINDOW] (3)-tick post-hit window (KB can land 1–2 ticks late and the impulse
 * spreads across ticks, so a single-tick gate misread both directions), then flag (Nemesis
 * VelocityC) when that displacement is under cfg.threshold (0.61) of the EXPECTED KB
 * displacement — impulse × friction-integral, captured from [VelocitySignal] on
 * broadcast-velocity servers or assumed vanilla sprint-KB (~0.4) where it isn't broadcast.
 * Gated on attacker sprinting (only sprint-KB is strong enough to require movement).
 * setbackVL 5, decay 1/tick.
 *
 * Exemptions (each closes a real FP class the single-tick/single-point form had):
 *  - **Dampening surface** at the feet — liquid / cobweb / soul sand / honey legitimately
 *    suppress horizontal KB below the threshold (vanilla KB in water is tiny). Tested via
 *    [WorldQueries.isKbDampeningAt].
 *  - **Wall in the KB direction** — sampled across several offsets and three body bands
 *    (not just one point straight ahead), so a wall beside/around the victim that stops them
 *    legitimately is recognized. Tested via [WorldQueries.isWallAhead]. This is the Nemesis
 *    `!collidedH` gate (a mid-window wall collision): a wall within the KB's ~1-block reach is
 *    caught by the swept start-sample, which is strictly stronger than a single-point
 *    collidedH flag, so a separate collidedH proxy is redundant.
 *  - **Server lag** — a server-wide freeze makes the victim legitimately not move; bail
 *    within the shared lag window.
 *
 * **Step-11 sub-signals (Nemesis VelocityB/C gates + KB-vector mismatch, plan §3/§8 step 11)
 *  — all share `noKnockback`'s VL pool (no new check id):**
 *  - **VelocityC attack-slowdown gate**: a hit landing within [ATTACK_SLOWDOWN_WINDOW] ticks
 *    of the previous hit on the victim is inside the vanilla 10-tick invulnerability window,
 *    so the second KB is reduced (~[ATTACK_SLOWDOWN]×). The expected-Displacement denominator
 *    is scaled down for such an i-frame hit — without this a legit victim's naturally-reduced
 *    i-frame displacement false-flags as anti-KB. (The 0.61 ratio itself is unchanged.)
 *  - **`NoKB(VelocityB)`** (Nemesis VelocityB vertical V-ratio): on broadcast-velocity servers
 *    we know the upward KB ([kbVy]); the victim's first-airborne-tick Δy should be ≈ kbVy. A
 *    Velocity cheat that cancels the upward KB shows Δy/kbVy < [VELOCITYB_RATIO] (the victim
 *    barely leaves the ground). The vanilla 0.42 jump band is exempt (a Δy there is a jump,
 *    ambiguous with the ~0.4 upward KB). Fail-open on non-broadcast servers (kbVy stays 0).
 *  - **`NoKB(Vector)`** (KB-vector vs attacker-yaw mismatch, Axis C): the server applies KB
 *    along the attacker's broadcast facing (Vape HitFlick / Slinky Knockback-Displace flick to
 *    redirect KB). For a victim ~stationary before the hit ([preHitH] < [PRE_HIT_STILL]) the
 *    post-hit displacement direction is KB-dominated and should match the attacker facing; a
 *    mismatch > [VECTOR_MISMATCH]° is a redirected KB — a full-magnitude redirect passes the
 *    VelocityC ratio, so this direction test catches what the magnitude test misses. Gated on
 *    low pre-hit momentum (else residual motion contaminates the direction) + a min displacement.
 */
class NoKnockbackCheck : Check() {

    override val id: String = "noKnockback"

    init {
        try {
            Iustitia.bus.subscribe<AttackEvent> { onAttack(it) }
            // Capture the KB impulse the server applies to each victim. tp.velocity is clobbered
            // each poll by EntityTrackerManager.updateSnapshot, so the impulse must come from the
            // bus (only broadcast-velocity servers emit EntityVelocityUpdate for other players;
            // on servers that don't, onVelocity never fires and evaluate() falls back to the
            // full-cancel path). This revives the check to catch partial anti-KB (Nemesis
            // VelocityC ratio), not just the full cancels the old MIN_MOVE floor caught.
            Iustitia.bus.subscribe<VelocitySignal> { onVelocity(it) }
        } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = NoKnockbackContext()

    private fun onAttack(ev: AttackEvent) {
        try {
            val attacker = EntityTrackerManager.get(ev.attacker) ?: return
            val victim = EntityTrackerManager.get(ev.victim) ?: return
            if (!attacker.sprinting) return // only sprint-KB is strong enough to require
            val ctx = contextOf(ev.victim) as NoKnockbackContext
            // VelocityC attack-slowdown gate (Nemesis, plan §3/§8 step 11): a hit landing within
            // ~2 ticks of the previous hit on this victim is inside the vanilla 10-tick
            // invulnerability window — the second hit's KB is reduced (~0.6×). Without this the
            // expected-Disp denominator is too high for an i-frame hit and a legit victim's
            // naturally-reduced displacement false-flags as anti-KB. (lastHitTick>0 guards the
            // fresh-context sentinel so the first hit never counts as a rapid double.)
            ctx.attackSlowdown = ctx.lastHitTick > 0 && ev.tick - ctx.lastHitTick in 1..ATTACK_SLOWDOWN_WINDOW
            // KB-vector vs attacker-yaw (Axis C, §3/§8 step 11): the victim's post-hit displacement
            // direction is only KB-dominated when the victim was ~stationary before the hit — a
            // charging victim's residual momentum would contaminate the direction. Snapshot the
            // pre-hit horizontal velocity (the tick the hit lands).
            ctx.preHitDx = victim.delta.x
            ctx.preHitDz = victim.delta.z
            ctx.lastHitTick = ev.tick
            ctx.kbYaw = attacker.yaw.toDouble()
            ctx.windowDisp = 0.0
            ctx.windowDx = 0.0
            ctx.windowDz = 0.0
            ctx.windowTicks = 0
            ctx.firstAirborneDy = Double.NaN
            ctx.firstAirborneCaptured = false
            ctx.evaluated = false
            // Reset the captured impulse too: a stale VelocitySignal from before this hit (a prior
            // hit's KB, an environmental launch) must NOT be used as this hit's expected-impulse
            // denominator — evaluate() falls back to ASSUMED_SPRINT_KB until a fresh velocity for
            // THIS hit arrives within its 3-tick window. (kbTick −10000 ⇒ the `tick - kbTick <= 3`
            // test is false ⇒ ASSUMED_SPRINT_KB fallback.)
            ctx.kbTick = -10000
            ctx.kbImpulseH = 0.0
            ctx.kbVy = 0.0
        } catch (_: Throwable) {}
    }

    private fun onVelocity(ev: VelocitySignal) {
        try {
            // Store the horizontal KB magnitude the server applied to this victim, so evaluate()
            // can compare the victim's actual post-hit displacement as a ratio of the expected KB
            // (Nemesis VelocityC). Ignore sub-0.1 impulses (zero-velocity resets / trivial noise).
            val h = hypot(ev.velocity.x, ev.velocity.z)
            if (h <= 0.1) return
            val ctx = contextOf(ev.entity) as NoKnockbackContext
            ctx.kbImpulseH = h
            ctx.kbTick = ev.tick
            // Vertical component for Nemesis VelocityB (plan §3/§8 step 11): a Velocity cheat that
            // cancels the upward KB shows a Δy/velocityY ratio < 0.995 on the first airborne tick.
            // Only meaningful on broadcast-velocity servers (this server doesn't rebroadcast other-
            // player EntityVelocityUpdate → onVelocity never fires → VelocityB stays fail-open).
            ctx.kbVy = ev.velocity.y
        } catch (_: Throwable) {}
    }

    override fun process(tp: TrackedPlayer, tick: Int) {
        try {
            val ctx = contextOf(tp.uuid) as NoKnockbackContext
            // No attack recorded yet (fresh context: lastHitTick=-10000). Without this guard the
            // first process() tick for every newly-tracked player computes `since = tick - (-10000)`
            // (huge) ≥ WINDOW with windowDisp still 0.0 (the 1..WINDOW accumulation never ran) and
            // evaluated=false → evaluate() runs → 0.0 < threshold*expectedDisp → a bogus NoKB flag
            // on EVERY player's first tick. (It never crossed setbackVL so no alert/tier corruption,
            // but it polluted the /ius hist flag timeline + VerboseLog counts. Real anti-KB detection
            // is unaffected: a real hit sets lastHitTick ≥ 0 first.) Fail-open.
            if (ctx.lastHitTick < 0) return
            val since = tick - ctx.lastHitTick
            if (since < 1) return // before the KB window
            // accumulate horizontal displacement over the post-hit window (KB spreads across
            // ticks; summing avoids the single-tick misread both ways).
            if (since in 1..WINDOW) {
                ctx.windowDisp += hypot(tp.delta.x, tp.delta.z)
                // vector sum of the victim's displacement (KB-vector vs attacker-yaw, Axis C) —
                // the magnitude sum above drives the VelocityC ratio; the vector sum drives the
                // direction mismatch. Sign-preserving so a redirect shows a different heading.
                ctx.windowDx += tp.delta.x
                ctx.windowDz += tp.delta.z
                // VelocityB: capture the Δy on the FIRST airborne tick after the hit (the upward
                // KB launches a grounded victim; that first-tick Δy is the vertical-KB response a
                // Velocity cheat cancels). NaN sentinel ⇒ not yet captured.
                if (!ctx.firstAirborneCaptured && !tp.groundedProxy && !tp.onGroundPacket) {
                    ctx.firstAirborneDy = tp.deltaY
                    ctx.firstAirborneCaptured = true
                }
                ctx.windowTicks++
            }
            // evaluate once when the window completes
            if (since >= WINDOW && !ctx.evaluated) {
                ctx.evaluated = true
                evaluate(tp, ctx, tick)
            }
        } catch (_: Throwable) {}
    }

    private fun evaluate(tp: TrackedPlayer, ctx: NoKnockbackContext, tick: Int) {
        if (tp.inVehicle || tp.gliding || tp.riptide) return
        if (tick - tp.lastTeleportTick < 5) return
        // Shield exemption: a player holding up a shield (UseAction.BLOCK on main/off hand) takes
        // NO knockback from a sprint hit — vanilla shields negate sprint-KB. Without this gate a
        // legit blocker's ~0 windowDisp trips the under-threshold NoKB test → false flag. (A
        // cheater toggling shield while hit is also exempted here, but shield use is visibly
        // animated — accepted tradeoff for removing a clean FP class.)
        if (tp.isBlocking) return
        // Server-lag exemption: a server-wide freeze makes the victim legitimately not move,
        // looking exactly like a Velocity cancel. Bail within the shared lag window.
        if (tick - EntityTrackerManager.lastServerLagTick <= 4 ||
            tick - EntityTrackerManager.lastLagBurstTick <= 2
        ) return
        val world = MinecraftClient.getInstance().world ?: return
        val bx = Math.floor(tp.pos.x).toInt()
        val bz = Math.floor(tp.pos.z).toInt()
        val footY = Math.floor(tp.pos.y).toInt()
        // dampening surface at the feet legitimately suppresses KB
        if (WorldQueries.isKbDampeningAt(world, bx, footY, bz) ||
            WorldQueries.isKbDampeningAt(world, bx, footY - 1, bz)
        ) return
        // a wall in the KB direction (swept, multi-band) legitimately stops them
        val look = Vectors.lookVector(ctx.kbYaw, 0.0)
        if (WorldQueries.isWallAhead(world, tp.pos.x, tp.pos.y, tp.pos.z, look.x, look.z, 1.0)) return
        // Detection (Nemesis VelocityC ratio). windowDisp is the victim's SUMMED horizontal
        // displacement over the 3-tick post-hit window; a full-KB taker moves ~impulse × friction-
        // integral (~1.0 for a sprint hit). The expected displacement is impulse × KB_FRICTION_INTEGRAL,
        // using the captured VelocitySignal impulse on broadcast-velocity servers, or the vanilla
        // ASSUMED_SPRINT_KB impulse (~0.4) on servers that don't broadcast other-player velocity
        // (this server — onVelocity never fires). Flag when the victim took under cfg.threshold
        // (0.61) of expected → partial anti-KB / Velocity. (The old form divided windowDisp by the
        // per-tick impulse directly — wrong units, ratio ~2.5 for full KB, so the <0.61 test only
        // caught ~24% cancels; combined with the 0.1 full-cancel fallback it fired on nobody.)
        val impulse = if (tick - ctx.kbTick <= 3 && ctx.kbImpulseH > 0.1) ctx.kbImpulseH else ASSUMED_SPRINT_KB
        // VelocityC attack-slowdown (Nemesis, §3/§8 step 11): an i-frame hit applies ~0.6× KB, so
        // the expected displacement is scaled down for a rapid consecutive hit — without this a
        // legit victim's naturally-reduced i-frame displacement false-flags.
        var expectedDisp = impulse * KB_FRICTION_INTEGRAL
        if (ctx.attackSlowdown) expectedDisp *= ATTACK_SLOWDOWN
        if (ctx.windowDisp < cfg.threshold * expectedDisp) {
            flag(tp, ctx, 1.0, "NoKB", tick)
            // Axis B amplifier (plan §2.2/§6): a KnockbackDelay self-blink freezes the cheater's
            // own outgoing stream through the knockback it just took — observable as an entity-
            // local freeze episode (not global-lag) around the hit. Zero-KB coincident with that
            // self-freeze amplifies toward KnockbackDelay. Server-dependent / partially visible:
            // when the cheat only buffers incoming (not outgoing) no local freeze is seen and the
            // amplifier simply doesn't fire (fail-open). Distinct label, shares `noKnockback`'s VL
            // pool (no new check id). The combat tick is the hit (ctx.lastHitTick).
            try {
                val lag = LagCombatCorrelator.combatCorrelatedLag(tp.uuid, ctx.lastHitTick, LAG_CORR_WINDOW)
                if (lag >= MIN_LAG_FREEZE) {
                    flag(tp, ctx, VL_LAG_CORR, "NoKB(LagCorr)", tick, Evidence(
                        subLabel = "lag-correlated", measurement = lag.toDouble(),
                        threshold = MIN_LAG_FREEZE.toDouble(), pos = tp.pos,
                        extra = "victim self-freeze around the hit"))
                }
            } catch (_: Throwable) {}
        }
        // Nemesis VelocityB — vertical-KB V-ratio (plan §3/§8 step 11): on broadcast-velocity
        // servers we know the upward KB (kbVy); the victim's first-airborne-tick Δy should be ≈
        // kbVy. A Velocity cheat that cancels the upward KB shows Δy/kbVy < 0.995 (the victim
        // doesn't leave the ground, or leaves with a far-smaller Δy). Exempt the vanilla 0.42 jump
        // band — a Δy there is a jump (ambiguous, the victim jumped rather than took the upward
        // KB), not a cancel. Fail-open on non-broadcast servers (kbVy stays 0 → no VelocityB).
        if (ctx.kbVy > 0.1 && ctx.firstAirborneCaptured && !ctx.firstAirborneDy.isNaN() &&
            (ctx.firstAirborneDy < JUMP_LO || ctx.firstAirborneDy > JUMP_HI)
        ) {
            val ratio = ctx.firstAirborneDy / ctx.kbVy
            if (ratio < VELOCITYB_RATIO) {
                flag(tp, ctx, VL_VELOCITYB, "NoKB(VelocityB)", tick, Evidence(
                    subLabel = "vertical-kb-ratio", measurement = ratio, threshold = VELOCITYB_RATIO,
                    pos = tp.pos, extra = "Δy=${"%.4f".format(ctx.firstAirborneDy)} kbVy=${"%.4f".format(ctx.kbVy)}"))
            }
        }
        // KB-vector vs attacker-yaw mismatch (Axis C, plan §3/§8 step 11): the server applies KB
        // along the attacker's broadcast facing (Vape HitFlick / Slinky Knockback-Displace flick
        // the attacker to redirect KB). For a victim ~stationary before the hit, the post-hit
        // displacement direction is KB-dominated and should match the attacker facing; a large
        // mismatch is a redirected KB (the cheat cancels the facing-aligned KB and the victim's
        // residual/redirected motion points elsewhere) — survives the magnitude ratio, which a
        // full-magnitude redirect passes. Gated on low pre-hit momentum (else residual motion
        // contaminates the direction) and a min displacement (else noise).
        val preHitH = hypot(ctx.preHitDx, ctx.preHitDz)
        val dispH = hypot(ctx.windowDx, ctx.windowDz)
        if (preHitH < PRE_HIT_STILL && dispH > MIN_VEC_MOVE) {
            val look = Vectors.lookVector(ctx.kbYaw, 0.0)
            val lh = hypot(look.x, look.z)
            if (lh > 1e-6) {
                val ux = ctx.windowDx / dispH
                val uz = ctx.windowDz / dispH
                val lx = look.x / lh
                val lz = look.z / lh
                val dot = (ux * lx + uz * lz).coerceIn(-1.0, 1.0)
                val mismatch = Math.toDegrees(Math.acos(dot))
                if (mismatch > VECTOR_MISMATCH) {
                    flag(tp, ctx, VL_VECTOR, "NoKB(Vector)", tick, Evidence(
                        subLabel = "kb-vector-mismatch", measurement = mismatch,
                        threshold = VECTOR_MISMATCH, pos = tp.pos,
                        extra = "preHitH=${"%.3f".format(preHitH)} dispH=${"%.3f".format(dispH)}"))
                }
            }
        }
    }

    private class NoKnockbackContext : CheckContext() {
        var lastHitTick: Int = -10000
        var kbYaw: Double = 0.0
        /** Summed horizontal displacement over the post-hit window. */
        var windowDisp: Double = 0.0
        /** Vector sum of the window displacement (KB-vector mismatch, Axis C). */
        var windowDx: Double = 0.0
        var windowDz: Double = 0.0
        /** Pre-hit victim horizontal velocity (KB-vector-mismatch stillness gate). */
        var preHitDx: Double = 0.0
        var preHitDz: Double = 0.0
        /** Ticks counted in the window (sanity — a teleport mid-window inflates disp, safe). */
        var windowTicks: Int = 0
        /** True once the window has been evaluated (reset by the next attack). */
        var evaluated: Boolean = false
        /** VelocityC attack-slowdown: this hit is inside the previous hit's i-frame window. */
        var attackSlowdown: Boolean = false
        /** Δy on the first airborne tick after the hit (VelocityB vertical-KB response); NaN until captured. */
        var firstAirborneDy: Double = Double.NaN
        /** True once [firstAirborneDy] has been captured for this hit's window. */
        var firstAirborneCaptured: Boolean = false
        /** Horizontal magnitude of the last captured KB impulse (from VelocitySignal); 0 if the
         *  server doesn't broadcast other-player velocity. */
        var kbImpulseH: Double = 0.0
        /** Vertical component of the last captured KB impulse (VelocityB); 0 on non-broadcast servers. */
        var kbVy: Double = 0.0
        /** Tick of the last captured KB impulse (−10000 ⇒ none captured / stale). */
        var kbTick: Int = -10000
    }

    private companion object {
        /** Post-hit window (ticks) over which KB displacement is summed. */
        const val WINDOW = 3
        /** Vanilla horizontal sprint-KB impulse (blocks/tick) assumed when the server doesn't
         *  broadcast other-player EntityVelocityUpdate (so onVelocity never captures one). */
        const val ASSUMED_SPRINT_KB = 0.4
        /** Sum of the per-tick ground-friction multipliers over the WINDOW (1 + 0.91 + 0.91² ≈
         *  2.73); the expected 3-tick KB displacement is impulse × this. Ground (0.91) is used
         *  rather than air (0.98) since most sprint-hits land on grounded victims. */
        const val KB_FRICTION_INTEGRAL = 2.7
        // -- Lag-correlation amplifier (Axis B, plan §3/§8 step 6) --
        /** Window (ticks) around the hit within which a victim self-freeze corroborates a
         *  KnockbackDelay self-blink. Tuned in step 14. */
        const val LAG_CORR_WINDOW = 6
        /** Min local-freeze ticks (coincident with combat) to amplify. */
        const val MIN_LAG_FREEZE = 2
        /** Amplifier sub-flag level — "weight up" on top of the NoKB flag. Tuned in step 14. */
        const val VL_LAG_CORR = 1.0

        // -- VelocityC attack-slowdown + VelocityB + KB-vector mismatch (Nemesis / Axis C, §3/§8 step 11) --
        /** A hit within this many ticks of the previous hit on the victim is inside the vanilla
         *  10-tick invulnerability window → the second KB is reduced (Nemesis `velocityH *= 0.6`). */
        const val ATTACK_SLOWDOWN_WINDOW = 2
        /** Expected-Displacement multiplier for an i-frame hit (Nemesis attack-slowdown factor). */
        const val ATTACK_SLOWDOWN = 0.6
        /** Nemesis VelocityB: flag when the first-airborne Δy / kbVy < this (victim took <99.5% of
         *  the upward KB). Tuned in step 14. */
        const val VELOCITYB_RATIO = 0.995
        /** Jump-exempt band: a first-airborne Δy in [JUMP_LO, JUMP_HI] is a vanilla 0.42 jump (ambiguous
         *  with the ~0.4 upward KB) — exempt from VelocityB rather than flag. Tuned in step 14. */
        const val JUMP_LO = 0.38
        const val JUMP_HI = 0.46
        /** KB-vector mismatch: max pre-hit victim horizontal speed (blocks/tick) for the direction
         *  test — above this, residual momentum contaminates the post-hit direction. */
        const val PRE_HIT_STILL = 0.05
        /** Min window displacement (blocks) to run the vector test — below this the direction is noise. */
        const val MIN_VEC_MOVE = 0.1
        /** Flag when the victim's displacement direction is off the attacker facing by more than this (°).
         *  A ~stationary victim's post-hit direction is KB-dominated; a redirect (HitFlick / Displace
         *  / a Velocity cheat canceling the facing-aligned KB) shows a larger mismatch. Tuned in step 14. */
        const val VECTOR_MISMATCH = 35.0
        /** VelocityB sub-flag level. Tuned in step 14. */
        const val VL_VELOCITYB = 1.0
        /** KB-vector-mismatch sub-flag level (Axis C). Tuned in step 14. */
        const val VL_VECTOR = 1.0
    }
}
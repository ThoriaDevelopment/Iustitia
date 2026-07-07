package dev.iustitia.tracking

import dev.iustitia.checks.movement.BlockLookupBudget
import dev.iustitia.history.FlagHistory
import dev.iustitia.world.WorldQueries
import net.minecraft.client.network.OtherClientPlayerEntity
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.Vec3d
import java.util.UUID

/**
 * Per-other-player snapshot polled each client tick from the live
 * [OtherClientPlayerEntity]. Holds the position ring buffer (lag-comp), per-tick
 * deltas, polled metadata flags (sprint/sneak/glide/etc.), and shared movement
 * accumulators (fall distance). Per-check numeric state (VL, swing windows, ...)
 * lives in each check's own [dev.iustitia.checks.CheckContext] keyed by this player's
 * uuid — TrackedPlayer stays the shared, side-effect-free snapshot.
 */
class TrackedPlayer(val uuid: UUID, var entityId: Int, val joinTick: Int) {

    /** Live entity handle, re-resolved each tick; null after despawn until purge. */
    var entity: OtherClientPlayerEntity? = null

    val ring = PositionRingBuffer(24)

    // --- position / deltas (server-space, polled) ---
    var pos: Vec3d = Vec3d.ZERO
    var lastPos: Vec3d = Vec3d.ZERO
    /** Full vec delta this tick (pos - lastPos). */
    var delta: Vec3d = Vec3d.ZERO
    /** Previous tick's vertical delta — used by FlyEnvelope physics: expectedY=(prevDeltaY-0.08)*0.98. */
    var prevDeltaY: Double = 0.0
    var deltaY: Double = 0.0

    var yaw: Float = 0f
    var lastYaw: Float = 0f
    var pitch: Float = 0f
    var lastPitch: Float = 0f

    /** Axis A substrate: per-player mouse-sensitivity estimate (MX SensitivityProcessor port).
     *  Converges to a 0..200 sensitivity + MCP table value from pitch-delta GCD fit. Fed by the
     *  tracker only on full-float-look protocols; no flags (consumed by the step-2 aimGcd check). */
    val sensitivity: SensitivityProcessor = SensitivityProcessor()

    // --- polled metadata flags ---
    var onGroundPacket: Boolean = false
    var groundedProxy: Boolean = false
    var sprinting: Boolean = false
    var sneaking: Boolean = false
    var gliding: Boolean = false
    var usingItem: Boolean = false
    /** True only when usingItem with an EAT/DRINK use action — distinguishes eating/drinking
     *  from shield-raising / bow-drawing (which also set usingItem). Drives KillAura consume
     *  so a legit shield-blocker isn't double-flagged (Consume + AutoBlock). */
    var isUsingConsumable: Boolean = false
    /** True only when usingItem with a BLOCK use action — i.e. a shield is actively raised (1.21)
     *  or a sword is block-raised (1.8). Bows (BOW), eating (EAT) and drinking (DRINK) also set
     *  usingItem but are NOT the autoblock fingerprint; gating AutoBlock on this instead of the
     *  bare usingItem stops a bow-draw (which holds usingItem for many ticks while charging) from
     *  tripping AutoBlock on any swing during the draw. Vanilla 1.21 cannot attack while a shield
     *  is raised, so BLOCK + swing overlap is precisely the cheat signature (no FP the other way). */
    var isBlocking: Boolean = false
    var riptide: Boolean = false
    var swimming: Boolean = false
    var inVehicle: Boolean = false

    var velocity: Vec3d = Vec3d.ZERO
    /** Tick of the last received EntityVelocityUpdateS2CPacket (velocity-exemption window). */
    var velocityTick: Int = -1000
    /** Tick of the last detected large position jump (>8b) — movement/reach teleport exemption. */
    var lastTeleportTick: Int = -1000
    /** Tick of the last HurtSignal for this player — knockback-exemption window (Speed/Fly).
     *  Substitutes for [velocityTick] on servers that don't broadcast other-player
     *  EntityVelocityUpdate packets (confirmed unobserved on 1.21.11 arch.mc): knockback
     *  follows a hit, so a recent hurt marks the knockback peak we must not flag. */
    var hurtTick: Int = -10000
    /** Tick of the last inferred [dev.iustitia.event.AttackEvent] where this player was the ATTACKER
     *  — the combat-relevance gate for the [sensitivity] substrate feed (see [EntityTrackerManager].
     *  updateSnapshot). Set centrally from the bus ([Iustitia] subscribes AttackEvent → markAttack),
     *  mirroring [hurtTick]. The substrate's only consumers (aimGcd / KillAura-GCD) flag the
     *  attacker, so feeding sensitivity only for recent attackers excludes the dense-crowd bystanders
     *  that were the FPS regression. Default -10000 so a player who never attacks is never fed. */
    var lastAttackTick: Int = -10000

    /** Last client-ticked hand-swing phase for this player (vanilla `LivingEntity.handSwingTicks`,
     *  0 when not mid-swing; advanced client-side in `OtherClientPlayerEntity.tickMovement`). Captured
     *  into the replay/clip buffer so a replay ghost's arm swings when the player attacked/mined. */
    var handSwingTicks: Int = 0

    /** Tick of the last tick this player actually moved (|delta|² >= 0.0001). Idle-since-join
     *  players keep the default -10000 so they never inflate the EntityTrackerManager mass-
     *  freeze (server-lag) signal — an AFK player frozen next to a Blinker must not exempt
     *  the Blinker's snap. */
    var lastMoveTick: Int = -10000

    // --- observed status effects ---
    /** Speed effect amplifier currently on the player (0=I, 1=II, ...); -1 = none. */
    var speedAmplifier: Int = -1

    // --- shared movement accumulators ---
    var fallAccum: Double = 0.0

    /** Cached cheat tier for this player — recomputed once per client tick by
     *  [dev.iustitia.tracking.EntityTrackerManager.updateSnapshot] and read by the nametag render
     *  mixins ([dev.iustitia.mixin.PlayerEntityRendererMixin] /
     *  [dev.iustitia.mixin.ArmorStandEntityRendererMixin]) so they don't call [FlagHistory.tierFor]
     *  every render frame (O(N×fps) map lookups + a synchronized scan for flagged players). At most
     *  1 tick stale — the tier only changes on a flag event or the slow (~minutes) decay, so
     *  render-frame staleness is imperceptible. */
    var tier: FlagHistory.Tier = FlagHistory.Tier.GREEN

    // --- per-tick world-query scratch (lazy, tick-stamped) ---
    // Dedupes the shared world queries across the block-lookup movement checks (Spider /
    // WallSprint / SprintHack): all three call isChunkLoaded on the player's chunk (3×→1) and
    // Spider + SprintHack both call isLiquidAt at the feet block (2×→1), so a tracked player
    // pays ONE chunk-loaded lookup + ONE feet-liquid lookup per tick, not 3 + 2. The expensive
    // block-presence verdicts (Spider's wall loop, WallSprint's isWallAhead) are NOT shared
    // (different geometry) — they are rate-limited per-check via verdict-caching instead.
    // Single-threaded client tick → no synchronization. Scratch only — not detection state.
    private var wqTick: Int = Int.MIN_VALUE
    private var wqChunkLoaded: Boolean = false
    private var wqFeetLiquid: Boolean = false
    private var wqFeetLiquidSet: Boolean = false

    /** True iff the chunk containing the player's current block is loaded. Cached per tick —
     *  the first caller per tick computes it, the rest reuse. Identical to [WorldQueries.isChunkLoaded]. */
    fun chunkLoadedCached(world: ClientWorld?, tick: Int, bx: Int, bz: Int): Boolean {
        if (wqTick != tick) {
            wqTick = tick
            wqChunkLoaded = WorldQueries.isChunkLoaded(world, bx, bz)
            wqFeetLiquidSet = false
        }
        return wqChunkLoaded
    }

    /** True iff the block at the player's feet (bx, by, bz) is liquid. Cached per tick — the
     *  first caller per tick computes it, the rest reuse. Returns false when the chunk is not
     *  loaded (matches [WorldQueries.isLiquidAt] fail-open). */
    fun feetLiquidCached(world: ClientWorld?, tick: Int, bx: Int, by: Int, bz: Int): Boolean {
        chunkLoadedCached(world, tick, bx, bz) // ensures the tick stamp + chunkLoaded are set
        if (!wqChunkLoaded) return false
        if (!wqFeetLiquidSet) {
            wqFeetLiquid = WorldQueries.isLiquidAt(world, bx, by, bz)
            wqFeetLiquidSet = true
        }
        return wqFeetLiquid
    }

    // --- groundedProxy isSolidBelow cache (rate-limit verdict-cache) ---
    // The onGround proxy recomputes [WorldQueries.isSolidBelow] (2× getCollisionShape) every tick
    // for any tracked player with |Δy| < 0.01 (a standing / horizontally-moving crowd = exactly the
    // dense-player case). The verdict is stable for a standing player (same ground block), so it is
    // recomputed at most every [BlockLookupBudget.RATE_LIMIT_N] ticks and reused in between — the
    // same verdict-cache pattern as the §8 block-lookup checks. The fresh |Δy| < 0.01 test still runs
    // every tick in [EntityTrackerManager.updateSnapshot], so a jump (Δy spikes) flips groundedProxy
    // to false INSTANTLY — no stale-grounded-while-airborne risk. Single-threaded client tick → no
    // synchronization. Scratch only — not detection state.
    private var solidBelowTick: Int = -10000
    private var solidBelow: Boolean = false

    /** [WorldQueries.isSolidBelow] verdict, recomputed at most every [BlockLookupBudget.RATE_LIMIT_N]
     *  ticks and reused in between (rate-limit verdict-cache). Identical return value to a direct
     *  [WorldQueries.isSolidBelow] call; only *when* the expensive lookup runs changes. */
    fun solidBelowCached(
        world: ClientWorld?, x: Double, y: Double, z: Double, offset: Double, tick: Int,
    ): Boolean {
        if (solidBelowTick == -10000 || tick - solidBelowTick >= BlockLookupBudget.RATE_LIMIT_N) {
            solidBelow = WorldQueries.isSolidBelow(world, x, y, z, offset)
            solidBelowTick = tick
        }
        return solidBelow
    }

    var lastUpdateTick: Int = joinTick

    /** Stable display name for alerts (falls back to truncated uuid if entity unloaded). */
    fun username(): String = try {
        entity?.gameProfile?.name ?: uuid.toString().take(8)
    } catch (_: Throwable) {
        uuid.toString().take(8)
    }

    /** Eye height given current pose (Grim HitboxSizes / entity-pose eye heights):
     *  1.62 standing, 1.54 sneaking, 0.4 for gliding (FALL_FLYING) / swimming / riptide
     *  (SPIN_ATTACK). Vanilla poses are mutually exclusive at the entity level. Feeds every
     *  raycast/LOS origin (Reach/ThroughWalls/Backtrack/RotationTracking/SnapBack) so an
     *  elytra/water attacker isn't measured from a 1.22b-too-high eye. */
    fun eyeHeight(): Double = when {
        gliding || swimming || riptide -> 0.4
        sneaking -> 1.54
        else -> 1.62
    }
}
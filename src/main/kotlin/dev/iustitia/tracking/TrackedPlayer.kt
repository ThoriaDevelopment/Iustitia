package dev.iustitia.tracking

import net.minecraft.client.network.OtherClientPlayerEntity
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
package dev.iustitia.tracking

import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.OtherClientPlayerEntity
import net.minecraft.client.world.ClientWorld
import net.minecraft.item.consume.UseAction
import net.minecraft.util.math.Vec3d
import dev.iustitia.world.WorldQueries
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the [TrackedPlayer] map and the per-tick poll loop. Each END_CLIENT_TICK it
 * re-resolves every other player from the live [ClientWorld], polls their pos/look/
 * metadata straight off the entity (no packet parsing), computes deltas, updates the
 * position ring buffer and the onGround proxy, and detects large teleports.
 *
 * This is the single place world state is read for tracking; checks consume the
 * resulting [TrackedPlayer] snapshots. Fail-open throughout: any per-player error is
 * swallowed so one bad entity never stops the tick.
 */
object EntityTrackerManager {

    private val byUuid = ConcurrentHashMap<UUID, TrackedPlayer>()

    @Volatile
    private var currentTick: Int = 0

    /**
     * Tick of the last detected *server-wide* lag spike — a tick where a majority of
     * tracked players had a near-zero position delta (everyone frozen simultaneously).
     * A single player freezing alone (Blink/FakeLag) does NOT set this. Checks that flag
     * freeze-then-snap or large-jump patterns exempt themselves within a short window of
     * this tick so a server hitch doesn't false-flag every player's catch-up movement.
     */
    @Volatile
    var lastServerLagTick: Int = -10000

    /**
     * Tick of the last *lag burst* — a tick where ≥3 tracked players jumped >2 blocks at
     * once (a batched catch-up after a hitch). A single-player clip does NOT set this.
     * Used by TeleportCheck to exempt the simultaneous-snap pattern of server lag.
     */
    @Volatile
    var lastLagBurstTick: Int = -10000

    fun tickCount(): Int = currentTick

    fun get(uuid: UUID): TrackedPlayer? = byUuid[uuid]

    fun all(): Collection<TrackedPlayer> = byUuid.values

    /** Mark a velocity packet received — opens the velocity-exemption window for [ticksAhead]. */
    fun markVelocity(uuid: UUID, tick: Int, velocity: Vec3d) {
        try {
            val tp = byUuid[uuid] ?: return
            tp.velocityTick = tick
            tp.velocity = velocity
        } catch (_: Throwable) {
            // ignore
        }
    }

    /** Mark a teleport detected by inference (e.g. chorus/pearl) — opens the reach/nofall
     *  exemption. Also clears [TrackedPlayer.fallAccum]: a void-fall + respawn leaves a
     *  huge stale fallAccum that would fire NoFall the moment the player lands at spawn,
     *  even though the fall was voided by the teleport. */
    fun markTeleport(uuid: UUID, tick: Int) {
        try {
            val tp = byUuid[uuid] ?: return
            tp.lastTeleportTick = tick
            tp.fallAccum = 0.0
        } catch (_: Throwable) {
            // ignore
        }
    }

    /** Mark a hurt signal received for this player — opens the knockback-exemption window
     *  (Speed/Fly). Knockback follows a hit, so a recent hurt flags the knockback peak we
     *  must not flag — this substitutes for the velocity window on servers that don't
     *  broadcast other-player EntityVelocityUpdate packets. */
    fun markHurt(uuid: UUID, tick: Int) {
        try {
            byUuid[uuid]?.hurtTick = tick
        } catch (_: Throwable) {
            // ignore
        }
    }

    /**
     * Apply a status-effect add/remove to the tracked player's effect state. Currently
     * only Speed is tracked (feeds the SpeedEnvelope cap raise). Other effects are
     * mitigated inside the checks (water exemption, NoFall threshold relaxation).
     */
    fun markEffect(uuid: UUID, isSpeed: Boolean, speedAmplifier: Int, added: Boolean) {
        try {
            val tp = byUuid[uuid] ?: return
            if (isSpeed) tp.speedAmplifier = if (added) speedAmplifier else -1
        } catch (_: Throwable) {
            // ignore
        }
    }

    /**
     * Poll all other players for the current tick. Returns the live tracked set
     * (snapshot list). Callers (the check driver) iterate and run checks against it.
     */
    fun poll(world: ClientWorld?, tick: Int): List<TrackedPlayer> {
        currentTick = tick
        if (world == null) return emptyList()
        val client = MinecraftClient.getInstance()
        return try {
            val seen = HashSet<UUID>()
            // per-tick lag tally: how many players were frozen (|Δ|≈0) vs jumped (>2b).
            // A majority frozen => server-wide lag; ≥3 simultaneous jumps => lag burst.
            var frozen = 0
            var burst = 0
            var total = 0
            for (e in world.players) {
                if (e === client.player) continue
                if (e !is OtherClientPlayerEntity) continue
                try {
                    val tp = byUuid.getOrPut(e.uuid) { TrackedPlayer(e.uuid, e.id, tick) }
                    tp.entity = e
                    if (tp.entityId != e.id) tp.entityId = e.id
                    seen.add(e.uuid)
                    updateSnapshot(tp, world, tick)
                    total++
                    val d = tp.delta
                    val mag = (d.x * d.x + d.y * d.y + d.z * d.z)
                    // Only count a player toward `frozen` if they actually moved recently, so a
                    // perpetually idle/AFK player never inflates the mass-freeze (server-lag)
                    // signal. Without this, an AFK player frozen next to a Blinker makes
                    // frozen==2 && total==2 fire every Blink-freeze tick, exempting the Blinker's
                    // snap from Speed/PacketGap. (Also defuses the ≥3-AFK majority branch.)
                    if (mag < 0.0001 && tick - tp.lastMoveTick <= 20) frozen++
                    else if (mag > 4.0) burst++
                } catch (_: Throwable) {
                    // skip this entity, keep going
                }
            }
            // purge despawned players (no longer in the world this tick)
            if (seen.isNotEmpty() || byUuid.isNotEmpty()) {
                byUuid.keys.removeAll { it !in seen }
            }
            // publish the shared lag signals for this tick (single source of truth).
            // A majority frozen, OR (for small lobbies of exactly 2 other players) BOTH frozen
            // — two independent players freezing together is still a server-wide signal, not a
            // lone Blink. (A strict 1v1, total == 1, is fundamentally unobservable client-side:
            // one player freezing then snapping is either server lag OR that player's Blink.)
            if (total > 0 && (frozen >= 3 && frozen * 2 >= total || total == 2 && frozen == 2)) {
                lastServerLagTick = tick
            }
            // batched catch-up: ≥3 jumped at once, OR (small lobby) both jumped together.
            if (burst >= 3 || (total == 2 && burst == 2)) lastLagBurstTick = tick
            byUuid.values.toList()
        } catch (_: Throwable) {
            byUuid.values.toList()
        }
    }

    private fun updateSnapshot(tp: TrackedPlayer, world: ClientWorld, tick: Int) {
        val e = tp.entity ?: return
        // capture server-space state from the live entity (Entity has no public getPos();
        // use getX/Y/Z which are public final).
        val newPos = Vec3d(e.getX(), e.getY(), e.getZ())
        val newYaw = e.yaw
        val newPitch = e.pitch

        // deltas
        val rawDelta = newPos.subtract(tp.pos)
        tp.prevDeltaY = tp.deltaY
        tp.deltaY = rawDelta.y
        tp.delta = rawDelta
        if (rawDelta.lengthSquared() >= 0.0001) tp.lastMoveTick = tick
        tp.lastPos = tp.pos
        tp.pos = newPos
        tp.lastYaw = tp.yaw; tp.yaw = newYaw
        tp.lastPitch = tp.pitch; tp.pitch = newPitch

        tp.ring.add(tick, newPos)

        // metadata flags
        tp.onGroundPacket = e.isOnGround
        tp.sprinting = e.isSprinting
        tp.sneaking = e.isSneaking
        tp.gliding = e.isGliding
        tp.usingItem = e.isUsingItem
        // Consumable (eat/drink) only — shields (BLOCK) and bows (BOW) also set usingItem but
        // are NOT the killaura-while-eating signal; gating KillAura consume on this avoids
        // double-flagging a legit shield-blocker (Consume + AutoBlock). Checked on both hands
        // since 1.9+ players eat with either hand.
        tp.isUsingConsumable = try {
            if (!e.isUsingItem) false
            else {
                val main = e.mainHandStack.useAction
                val off = e.offHandStack.useAction
                main == UseAction.EAT || main == UseAction.DRINK ||
                    off == UseAction.EAT || off == UseAction.DRINK
            }
        } catch (_: Throwable) {
            false
        }
        // Shield/sword-block only. Bows (BOW), eat (EAT), drink (DRINK) also set usingItem but are
        // NOT the autoblock fingerprint — gating AutoBlock on BLOCK stops bow-draw trips.
        tp.isBlocking = try {
            if (!e.isUsingItem) false
            else {
                val main = e.mainHandStack.useAction
                val off = e.offHandStack.useAction
                main == UseAction.BLOCK || off == UseAction.BLOCK
            }
        } catch (_: Throwable) {
            false
        }
        tp.riptide = e.isUsingRiptide
        tp.swimming = e.isSwimming
        tp.inVehicle = e.hasVehicle()
        tp.velocity = e.velocity

        // teleport heuristic: a single-tick jump > 8 blocks is a server teleport.
        // Also clears fallAccum (see markTeleport) so void-fall + respawn doesn't fire NoFall.
        if (rawDelta.lengthSquared() > 64.0) {
            tp.lastTeleportTick = tick
            tp.fallAccum = 0.0
        }

        // onGround proxy: small vertical movement + solid block just below feet.
        // Chunk not loaded → false (treat airborne → looser movement checks, per plan).
        tp.groundedProxy = try {
            kotlin.math.abs(tp.deltaY) < 0.01 &&
                WorldQueries.isSolidBelow(world, tp.pos.x, tp.pos.y, tp.pos.z, 0.05)
        } catch (_: Throwable) {
            false
        }

        tp.lastUpdateTick = tick
    }

    /** Clear all tracked state (e.g. on dimension change / game-join). */
    fun reset() {
        try {
            byUuid.clear()
        } catch (_: Throwable) {
            // ignore
        }
    }
}
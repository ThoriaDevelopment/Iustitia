package dev.iustitia.replay

import dev.iustitia.config.ConfigManager
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.tracking.TrackedPlayer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.world.World
import java.util.ArrayDeque
import java.util.UUID

/**
 * Rolling capture buffer of every OTHER player's server-space state (+ yaw/pitch/body-yaw/head-yaw/
 * pose/combat-sync/equipment), every nearby non-player entity, and every real alert event, kept for
 * the last [MAX_SECONDS] (60s) of ticks. The single data source for `/ius replay` (replay the last N
 * seconds in-world) and `/ius clip` (dump the last N seconds to a portable `.iusclip` file).
 *
 * ## Why this exists separately from [dev.iustitia.tracking.PositionRingBuffer]
 *
 * The reach-check ring buffer is 24 ticks (~1.2s) and per-player — far too short and not designed
 * for full-scene playback. Replay/clip need a continuous, scene-wide, multi-second buffer, so this
 * is its own bounded structure: a rolling [ArrayDeque] of [Frame]s (one per tick), a rolling deque of
 * [AlertRec]s, and a rolling deque of [TotemRec]s. All paths fail-open — a capture error is swallowed
 * so it never stalls the tick.
 *
 * ## Merge provenance
 *
 * This is Iustitia's original buffer (positions + alerts + totems + equipment + health) extended with
 * SnapClip's replay-engine additions: [EntitySnap] non-player entity capture, per-snap body/head yaw,
 * the [POSE_SIT]/[POSE_CRAWL] poses, per-frame [Frame.segmentId] and multi-segment [Segment]s. The
 * **alert timeline is preserved** (SnapClip dropped it) — see [AlertRec] / [Window.alerts].
 *
 * ## Memory bounds
 *
 * Frames cap at [MAX_FRAMES] (1200 = 60s @ 20 tps); adding past the cap drops the oldest. Each frame
 * holds at most [MAX_PLAYERS_PER_FRAME] player snaps and [MAX_ENTITIES_PER_FRAME] entity snaps (the
 * rest are dropped — a 64-player / 64-entity cap covers any realistic scene and bounds the worst case
 * at a few MB). Alerts cap at [MAX_ALERTS] (drop oldest). Recording is gated by
 * [dev.iustitia.config.IustitiaConfig.replayCapture] (default on) so a user who never uses
 * replay/clip can disable the per-tick work — and by [dev.iustitia.compat.CompanionMods.snapClip]
 * (SnapClip owns the buffer when installed).
 *
 * ## Threading
 *
 * [recordTick] runs on the client tick thread (from [dev.iustitia.Iustitia.onClientTick]); [recordAlert]
 * runs on the client thread too (from [dev.iustitia.alert.AlertManager.alert]). Reads ([snapshot],
 * [frameCount]) run on the client thread (command handlers). The deques are synchronized on the deque
 * object itself — coarse but correct; the per-tick work is tiny.
 */
object ReplayBuffer {

    /** Max replay/clip window in seconds. `/ius replay|clip` clamp their `seconds` arg to this. */
    const val MAX_SECONDS = 60
    private const val MAX_FRAMES = MAX_SECONDS * 20
    private const val MAX_PLAYERS_PER_FRAME = 64
    private const val MAX_ENTITIES_PER_FRAME = 64
    private const val MAX_ALERTS = 4000
    private const val MAX_TOTEMS = 1000

    /** Non-player entities are only captured within this radius (blocks) of the local player. */
    private const val ENTITY_CAPTURE_RADIUS = 64.0
    private const val ENTITY_CAPTURE_RADIUS_SQ = ENTITY_CAPTURE_RADIUS * ENTITY_CAPTURE_RADIUS

    /** Non-player entity capture toggle ([dev.iustitia.config.IustitiaConfig.clipEntities]). Read from
     *  the caller's per-tick config snapshot — see [recordTick]. Fail-open to the default (on). */
    private fun entityCaptureEnabled(cfg: IustitiaConfig): Boolean = cfg.clipEntities

    /** Per-frame non-player entity cap ([IustitiaConfig.clipEntityCap]), clamped 0..256. Fail-open to 64. */
    private fun entityCap(cfg: IustitiaConfig): Int = cfg.clipEntityCap.coerceIn(0, 256)

    /** Distance (blocks) the local player must move (or a world change) to begin a new capture segment
     *  ([IustitiaConfig.clipSegmentTeleportThreshold]), clamped 1..512. Fail-open to 64. */
    private fun segmentTeleportThreshold(cfg: IustitiaConfig): Double =
        cfg.clipSegmentTeleportThreshold.coerceIn(1.0, 512.0)

    /** Pose encoding for [PlayerSnap.pose] / [EntitySnap.pose] (1 byte). */
    const val POSE_STAND: Byte = 0
    const val POSE_SNEAK: Byte = 1
    const val POSE_GLIDE: Byte = 2
    const val POSE_SWIM: Byte = 3
    const val POSE_RIPTIDE: Byte = 4
    const val POSE_SIT: Byte = 5
    const val POSE_CRAWL: Byte = 6

    /**
     * One captured player at one tick. UUID stored as two longs (no [UUID] object retained per snap);
     * [name] is captured so a ghost/clip can be labeled even after the player logs off. [pitch] is the
     * look pitch; [bodyYaw]/[headYaw] are the vanilla body/head rotations (v13+; older clips default
     * them to the look yaw). [swingTicks] is the hand-swing phase. TrackedPlayer has no separate
     * head-yaw, so body yaw IS the look yaw there.
     */
    data class PlayerSnap(
        val uuidMost: Long, val uuidLeast: Long,
        val x: Float, val y: Float, val z: Float, val yaw: Float, val pitch: Float,
        val bodyYaw: Float = 0f, val headYaw: Float = 0f,
        val swingTicks: Int = 0, val pose: Byte = POSE_STAND,
        val name: String = "",
        val hurtTime: Byte = 0,
        val health: Float = 20f,
        val maxHealth: Float = 20f,
        val mainHand: String = "",
        val offHand: String = "",
        val head: String = "",
        val chest: String = "",
        val legs: String = "",
        val feet: String = "",
    ) {
        fun uuid(): UUID = UUID(uuidMost, uuidLeast)
    }

    /**
     * One captured **non-player** entity at one tick (v13+): mobs, animals, boats and minecarts. The
     * ghost renderer resolves [typeId] (`"minecraft:zombie"`) to a vanilla entity renderer so a
     * replay shows the actual scene, not just players. No equipment/inventory is captured (lossy by
     * design — a mob ghost is the entity model + pose + health).
     */
    data class EntitySnap(
        val typeId: String,
        val uuidMost: Long, val uuidLeast: Long,
        val x: Float, val y: Float, val z: Float,
        val bodyYaw: Float, val headYaw: Float, val pitch: Float,
        val pose: Byte,
        val hurtTime: Byte = 0,
        val health: Float = 20f,
        val maxHealth: Float = 20f,
    ) {
        fun uuid(): UUID = UUID(uuidMost, uuidLeast)
    }

    /** One captured tick: the tick number, the player snaps, the entity snaps, and the segment id. */
    data class Frame(
        val tick: Int, val snaps: List<PlayerSnap>,
        val entities: List<EntitySnap> = emptyList(),
        val segmentId: Int = 0,
    )

    /** One captured real alert event (post-throttle). UUID stored as two longs. */
    data class AlertRec(
        val tick: Int, val uuidMost: Long, val uuidLeast: Long,
        val name: String, val checkId: String, val label: String, val vl: Float,
    ) {
        fun uuid(): UUID = UUID(uuidMost, uuidLeast)
    }

    /** One captured Totem-of-Undying pop (v7+): the tick + the player who popped. UUID as two longs. */
    data class TotemRec(val tick: Int, val uuidMost: Long, val uuidLeast: Long) {
        fun uuid(): UUID = UUID(uuidMost, uuidLeast)
    }

    /**
     * One capture segment (v13+): a contiguous run of frames captured in one world without a
     * teleport-sized gap. [chunks] is the segment's world snapshot and [blockDeltas] the block
     * changes observed during it (both may be null/empty). [firstFrameIndex] indexes into
     * [Window.frames]. [snapshotIsStart] records whether the chunk snapshot was taken at the segment
     * start (so the delta overlay can rewind to it).
     */
    data class Segment(
        val dimensionKey: String,
        val firstFrameIndex: Int,
        val chunks: ChunkSnapshot? = null,
        val blockDeltas: List<BlockDeltaBuffer.BlockDelta> = emptyList(),
        val snapshotIsStart: Boolean = true,
    )

    private val frames: ArrayDeque<Frame> = ArrayDeque()
    private val alerts: ArrayDeque<AlertRec> = ArrayDeque()
    private val totems: ArrayDeque<TotemRec> = ArrayDeque()

    // --- segment tracking (v13+) ---
    private var currentSegmentId: Int = -1
    private var lastWorld: World? = null
    private var lastX: Double = 0.0
    private var lastY: Double = 0.0
    private var lastZ: Double = 0.0
    private val segmentDimensions: HashMap<Int, String> = HashMap()

    /**
     * Snapshot frames + alerts + totems + optional terrain/chunks/segments for a replay/clip, copied
     * out so live recording can't mutate playback. [terrain] (v5+) and [chunks] (v6+) are only set on
     * clip exports ([TerrainCapture]/[ChunkCapture] run in the export handler); a live `/ius replay`
     * snapshot leaves both null — replay never bundles the map (you're already on it). [segments]
     * (v13+) carries per-segment world snapshots + block deltas.
     */
    data class Window(
        val frames: List<Frame>, val alerts: List<AlertRec>,
        val terrain: TerrainSnapshot? = null, val chunks: ChunkSnapshot? = null,
        val totems: List<TotemRec> = emptyList(),
        val segments: List<Segment> = emptyList(),
    )

    /** True when the capture buffer is turned on AND Iustitia owns replay/clip capture. When SnapClip
     *  is installed it owns the rolling capture, so Iustitia stops buffering entirely (frames, alerts
     *  and totems all gate on this) — no duplicate per-tick work and no second `IUSC` writer. Fail-open. */
    private val enabled: Boolean get() = try {
        ConfigManager.config.replayCapture && !dev.iustitia.compat.CompanionMods.snapClip
    } catch (_: Throwable) { false }

    /** Record one tick of the scene. Call from the client tick thread after [dev.iustitia.tracking.EntityTrackerManager.poll]. */
    fun recordTick(tick: Int, tracked: Collection<TrackedPlayer>) {
        if (!enabled) return
        try {
            val mc = MinecraftClient.getInstance()
            val world = mc.world
            // One config snapshot for the whole tick (~8 [ConfigManager.config] getter reads collapsed
            // to one): the gates below can't disagree mid-tick if a config save swaps the snapshot
            // between them (e.g. entity capture on, entity cap from the new values).
            val cfg = ConfigManager.config
            updateSegment(mc, world, cfg)

            val snaps = ArrayList<PlayerSnap>(minOf(tracked.size, MAX_PLAYERS_PER_FRAME))
            for (tp in tracked) {
                if (snaps.size >= MAX_PLAYERS_PER_FRAME) break
                val snap = buildSnap(tp) ?: continue   // skip one bad player, keep going
                snaps.add(snap)
            }
            val entities = if (world != null) buildEntitySnaps(world, mc.player, cfg) else emptyList()
            synchronized(frames) {
                frames.addLast(Frame(tick, snaps, entities, currentSegmentId))
                while (frames.size > MAX_FRAMES) frames.removeFirst()
            }
        } catch (_: Throwable) {
            // fail-open
        }
    }

    /**
     * Segment bookkeeping (v13+): start a new segment when the world identity changes or the local
     * player moved more than [SEGMENT_TELEPORT_THRESHOLD] blocks since the last tick. A new segment
     * gets a fresh id + recorded dimension key. (Chunk rolling capture is Phase 2; this only tags the
     * frames so the codec/overlay can later group them.) Fail-open.
     */
    private fun updateSegment(mc: MinecraftClient, world: World?, cfg: IustitiaConfig) {
        val self = mc.player
        val px = self?.x ?: 0.0
        val py = self?.y ?: 0.0
        val pz = self?.z ?: 0.0
        val thr = segmentTeleportThreshold(cfg)
        val newSegment = when {
            world === null -> false
            world !== lastWorld -> true
            self == null -> false
            else -> {
                // Horizontal only: a fall (large dy, small dxz) is continuous motion the camera and
                // lerp can bridge — segmenting on it would snap the replay every few ticks of a
                // long drop. Genuine teleports move horizontally too.
                val dx = px - lastX; val dz = pz - lastZ
                dx * dx + dz * dz > thr * thr
            }
        }
        if (world != null) {
            if (newSegment) {
                currentSegmentId++
                segmentDimensions[currentSegmentId] = try { world.registryKey.value.toString() } catch (_: Throwable) { "?" }
                beginSegmentCapture(currentSegmentId, px, pz, cfg)
            }
            // Roll the segment's world in a little each tick (bounded per-tick cost), so an export has
            // the world already captured instead of sweeping ~300 chunks synchronously in the command.
            tickSegmentCapture(currentSegmentId, px, pz, cfg)
            lastWorld = world
            lastX = px; lastY = py; lastZ = pz
        }
    }

    /** Rolling chunk capture is gated on [dev.iustitia.config.IustitiaConfig.clipChunkWorld] (the same
     *  toggle the export honours). Fail-open to on. */
    private fun rollingCaptureEnabled(cfg: IustitiaConfig): Boolean = cfg.clipChunkWorld

    /** Capture radius in chunks ([IustitiaConfig.clipChunkRadius], clamped 1..32). Fail-open to 8. */
    private fun clipRadius(cfg: IustitiaConfig): Int = cfg.clipChunkRadius.coerceIn(1, 32)

    /** Open the rolling world capture for a fresh segment (nearest-first fill happens in
     *  [tickSegmentCapture]). Fail-open. */
    private fun beginSegmentCapture(segmentId: Int, px: Double, pz: Double, cfg: IustitiaConfig) {
        try {
            if (!rollingCaptureEnabled(cfg)) return
            ChunkRollingCapture.onSegmentStart(
                segmentId, Math.floorDiv(px.toInt(), 16), Math.floorDiv(pz.toInt(), 16), clipRadius(cfg),
            )
        } catch (_: Throwable) {
        }
    }

    /** Add up to [ChunkRollingCapture.PER_TICK] not-yet-captured chunks to this segment's world.
     *  Fail-open. */
    private fun tickSegmentCapture(segmentId: Int, px: Double, pz: Double, cfg: IustitiaConfig) {
        try {
            if (!rollingCaptureEnabled(cfg)) return
            if (segmentId < 0) return
            ChunkRollingCapture.tickCapture(
                segmentId, Math.floorDiv(px.toInt(), 16), Math.floorDiv(pz.toInt(), 16), clipRadius(cfg),
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * Group [frames] into capture segments (a run of frames sharing a [Frame.segmentId]) and attach the
     * world each segment's rolling capture holds plus the block deltas observed during it. This is what
     * makes an **export** carry a per-segment world (and replays of it the edits that happened) instead
     * of one export-time sweep — a clip that spans a teleport then replays both places.
     *
     * A segment whose rolling capture holds nothing (evicted under the section cap, `clipChunkWorld`
     * was off, or the window predates the capture) gets `chunks = null`; the export command then falls
     * back to a one-shot capture. Fail-open: empty list on any error (the caller then exports a
     * world-less window rather than failing).
     */
    internal fun segmentsFor(frames: List<Frame>): List<Segment> = try {
        if (frames.isEmpty()) emptyList()
        else {
            val dims = segmentDimensionsCopy()
            val out = ArrayList<Segment>()
            var i = 0
            while (i < frames.size) {
                val segId = frames[i].segmentId
                var j = i
                while (j < frames.size && frames[j].segmentId == segId) j++
                val firstTick = frames[i].tick
                val lastTick = frames[j - 1].tick
                val map = try { ChunkRollingCapture.snapshotForSegment(segId) } catch (_: Throwable) { null }
                if (map != null) try { map.dimension = dims[segId] } catch (_: Throwable) {}
                val keys = try { ChunkRollingCapture.chunkKeysFor(segId) } catch (_: Throwable) { emptySet<Long>() }
                val deltas = try {
                    BlockDeltaBuffer.snapshot(firstTick, lastTick, if (keys.isEmpty()) null else keys)
                } catch (_: Throwable) { emptyList() }
                out.add(Segment(dims[segId] ?: "?", i, map, deltas, snapshotIsStart = true))
                i = j
            }
            out
        }
    } catch (_: Throwable) { emptyList() }

    /**
     * [snapshot] **plus** the per-segment worlds + block deltas from [segmentsFor] — the export variant
     * used by `/ius clip` and `/ius replay save`.
     *
     * `/ius replay` deliberately keeps calling [snapshot] so it still renders ghosts over the **live**
     * world (instant, same server/dimension — it never bundles the map). Only an explicit export
     * switches to the captured-world path.
     */
    fun snapshotForExport(seconds: Int, tick: Int): Window {
        val base = snapshot(seconds, tick)
        return if (base.frames.isEmpty()) base else base.copy(segments = segmentsFor(base.frames))
    }

    /** Read-only copy of the segment-id → dimension-key map (consumed by the codec/segment builder). */
    internal fun segmentDimensionsCopy(): Map<Int, String> = segmentDimensions.toMap()

    /** Current capture segment id (starts at -1 before the first segment is opened). */
    internal fun currentSegmentId(): Int = currentSegmentId

    /**
     * Snapshot the last [seconds] of frames + alerts + totems for a replay or clip. [seconds] is
     * clamped to [MAX_SECONDS]; frames older than the window are excluded. Returns a defensive copy
     * (the deques keep advancing live without affecting the returned lists). Fail-open: empty on error.
     */
    fun snapshot(seconds: Int, tick: Int): Window = try {
        val secs = seconds.coerceIn(1, MAX_SECONDS)
        val frameBudget = secs * 20
        val tickFloor = tick - frameBudget
        val outFrames: List<Frame>
        synchronized(frames) {
            outFrames = if (frames.size <= frameBudget) frames.toList()
            else frames.toList().takeLast(frameBudget)
        }
        val outAlerts: List<AlertRec>
        synchronized(alerts) {
            outAlerts = alerts.filter { it.tick >= tickFloor }
        }
        val outTotems: List<TotemRec>
        synchronized(totems) {
            outTotems = totems.filter { it.tick >= tickFloor }
        }
        Window(outFrames, outAlerts, totems = outTotems)
    } catch (_: Throwable) {
        Window(emptyList(), emptyList())
    }

    /** Record a real alert event (called from [dev.iustitia.alert.AlertManager.alert] after throttle). */
    fun recordAlert(tick: Int, uuid: UUID, name: String, checkId: String, label: String, vl: Double) {
        if (!enabled) return
        try {
            synchronized(alerts) {
                alerts.addLast(
                    AlertRec(tick, uuid.mostSignificantBits, uuid.leastSignificantBits, name, checkId, label, vl.toFloat())
                )
                while (alerts.size > MAX_ALERTS) alerts.removeFirst()
            }
        } catch (_: Throwable) {
            // fail-open
        }
    }

    /** Record a Totem-of-Undying pop (called from the [dev.iustitia.mixin.ClientPlayNetworkHandlerMixin]
     *  onEntityStatus status-35 branch). Fail-open; bounded by [MAX_TOTEMS] (drop oldest). */
    fun recordTotemPop(tick: Int, uuid: UUID) {
        if (!enabled) return
        try {
            synchronized(totems) {
                totems.addLast(TotemRec(tick, uuid.mostSignificantBits, uuid.leastSignificantBits))
                while (totems.size > MAX_TOTEMS) totems.removeFirst()
            }
        } catch (_: Throwable) {
            // fail-open
        }
    }

    /** Currently buffered frame count (diagnostic). */
    fun frameCount(): Int = try { synchronized(frames) { frames.size } } catch (_: Throwable) { 0 }

    /** Clear the buffer (wired to [dev.iustitia.Iustitia.resetAll] on world/dimension change). */
    fun reset() {
        try { synchronized(frames) { frames.clear() } } catch (_: Throwable) {}
        try { synchronized(alerts) { alerts.clear() } } catch (_: Throwable) {}
        try { synchronized(totems) { totems.clear() } } catch (_: Throwable) {}
        try {
            currentSegmentId = -1
            lastWorld = null
            lastX = 0.0; lastY = 0.0; lastZ = 0.0
            segmentDimensions.clear()
            ChunkRollingCapture.reset()
            BlockDeltaBuffer.reset()
        } catch (_: Throwable) {}
    }

    private fun poseOf(tp: TrackedPlayer): Byte = when {
        tp.gliding -> POSE_GLIDE
        tp.swimming -> POSE_SWIM
        tp.riptide -> POSE_RIPTIDE
        tp.inVehicle -> POSE_SIT
        tp.sneaking -> POSE_SNEAK
        else -> POSE_STAND
    }

    private val itemIdCache = HashMap<net.minecraft.item.Item, String>()

    /** Map a live [ItemStack] to its registry-id string (`"minecraft:diamond_sword"`), or `""` for an
     *  empty/null stack. Fail-open: any read error → `""` (the ghost just shows no item in that slot). */
    private fun idOf(stack: ItemStack?): String = try {
        if (stack == null || stack.isEmpty) ""
        else itemIdCache.getOrPut(stack.item) { Registries.ITEM.getId(stack.item).toString() }
    } catch (_: Throwable) { "" }

    /**
     * Build one [PlayerSnap] from a tracked OTHER player, reading position/yaw/body-yaw/head-yaw/pose/
     * combat-sync + the v8 equipment slots straight off the live entity. The single shared snap-builder
     * so [recordTick] (the rolling 60s buffer) and [RecordManager.recordTick] (the manual long
     * recording) can't drift in what they capture. Fail-open: any per-player read error → null
     * (the caller skips that player, keeps the rest of the frame).
     */
    internal fun buildSnap(tp: TrackedPlayer): PlayerSnap? = try {
        val u = tp.uuid
        val nm = tp.username().ifEmpty { u.toString().take(8) }
        val e = tp.entity
        val hurtTime = try { (e?.hurtTime ?: 0).toByte() } catch (_: Throwable) { 0 }
        val health = try { e?.getHealth() ?: 20f } catch (_: Throwable) { 20f }
        val maxHealth = try { e?.getMaxHealth() ?: 20f } catch (_: Throwable) { 20f }
        val bodyYaw = try { e?.bodyYaw ?: tp.yaw } catch (_: Throwable) { tp.yaw }
        val headYaw = try { e?.headYaw ?: tp.yaw } catch (_: Throwable) { tp.yaw }
        val mainHand = idOf(e?.mainHandStack)
        val offHand = idOf(e?.offHandStack)
        val head = idOf(e?.getEquippedStack(EquipmentSlot.HEAD))
        val chest = idOf(e?.getEquippedStack(EquipmentSlot.CHEST))
        val legs = idOf(e?.getEquippedStack(EquipmentSlot.LEGS))
        val feet = idOf(e?.getEquippedStack(EquipmentSlot.FEET))
        PlayerSnap(
            u.mostSignificantBits, u.leastSignificantBits,
            tp.pos.x.toFloat(), tp.pos.y.toFloat(), tp.pos.z.toFloat(),
            tp.yaw, tp.pitch,
            bodyYaw = bodyYaw, headYaw = headYaw,
            swingTicks = tp.handSwingTicks, pose = poseOf(tp), name = nm,
            hurtTime = hurtTime, health = health, maxHealth = maxHealth,
            mainHand = mainHand, offHand = offHand, head = head, chest = chest, legs = legs, feet = feet,
        )
    } catch (_: Throwable) { null }

    /**
     * Capture nearby non-player entities (v13+): living entities (mobs/animals) plus boats and
     * minecarts, within [ENTITY_CAPTURE_RADIUS] of the local player, capped at [entityCap]
     * (`clipEntityCap`). Players are excluded (they are already in [Frame.snaps]). Fail-open: a
     * per-entity read error skips that entity.
     */
    internal fun buildEntitySnaps(world: ClientWorld, self: net.minecraft.client.network.ClientPlayerEntity?, cfg: IustitiaConfig): List<EntitySnap> {
        if (!entityCaptureEnabled(cfg)) return emptyList()
        val cap = entityCap(cfg)
        if (cap <= 0) return emptyList()
        val p = self ?: return emptyList()
        val sx = p.x; val sy = p.y; val sz = p.z
        val out = ArrayList<EntitySnap>(minOf(cap, 32))
        try {
            // Collect + sort by distance BEFORE the cap: `world.entities` iterates in hash order,
            // so taking the first `cap` in-range entities could keep far ones and drop the mobs
            // right next to the player when a crowd fills the budget.
            val candidates = ArrayList<Pair<Entity, Double>>(64)
            for (entity in world.entities) {
                if (entity === self) continue
                if (entity is PlayerEntity) continue
                if (entity.isRemoved) continue
                val dx = entity.x - sx; val dy = entity.y - sy; val dz = entity.z - sz
                val dSq = dx * dx + dy * dy + dz * dz
                if (dSq > ENTITY_CAPTURE_RADIUS_SQ) continue
                candidates.add(entity to dSq)
            }
            candidates.sortBy { it.second }
            for ((entity, _) in candidates) {
                if (out.size >= cap) break
                val snap = if (entity is LivingEntity) buildEntitySnap(entity) else buildNonLivingEntitySnap(entity)
                if (snap != null) out.add(snap)
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun buildEntitySnap(e: LivingEntity): EntitySnap? = try {
        val u = e.uuid
        val typeId = try { Registries.ENTITY_TYPE.getId(e.type).toString() } catch (_: Throwable) { return null }
        if (typeId.isEmpty()) return null
        EntitySnap(
            typeId,
            u.mostSignificantBits, u.leastSignificantBits,
            e.x.toFloat(), e.y.toFloat(), e.z.toFloat(),
            e.bodyYaw, e.headYaw, e.pitch,
            poseOfLiving(e),
            (try { e.hurtTime } catch (_: Throwable) { 0 }).toByte(),
            try { e.getHealth() } catch (_: Throwable) { 20f },
            try { e.getMaxHealth() } catch (_: Throwable) { 20f },
        )
    } catch (_: Throwable) { null }

    /** Non-living captures (boats / minecarts) have no pose/health — stand + full health. Only those
     *  two vehicle families are captured (a dropped item / arrow / painting is noise). */
    private fun buildNonLivingEntitySnap(e: net.minecraft.entity.Entity): EntitySnap? = try {
        if (e !is net.minecraft.entity.vehicle.BoatEntity &&
            e !is net.minecraft.entity.vehicle.ChestBoatEntity &&
            e !is net.minecraft.entity.vehicle.AbstractMinecartEntity) {
            return null
        }
        val u = e.uuid
        val typeId = try { Registries.ENTITY_TYPE.getId(e.type).toString() } catch (_: Throwable) { return null }
        if (typeId.isEmpty()) return null
        EntitySnap(
            typeId,
            u.mostSignificantBits, u.leastSignificantBits,
            e.x.toFloat(), e.y.toFloat(), e.z.toFloat(),
            e.yaw, e.yaw, e.pitch, POSE_STAND,
        )
    } catch (_: Throwable) { null }

    private fun poseOfLiving(e: LivingEntity): Byte = try {
        when {
            e.isGliding -> POSE_GLIDE
            e.isSwimming -> POSE_SWIM
            e.isCrawling -> POSE_CRAWL
            e.hasVehicle() -> POSE_SIT
            e.isSneaking -> POSE_SNEAK
            else -> POSE_STAND
        }
    } catch (_: Throwable) { POSE_STAND }
}

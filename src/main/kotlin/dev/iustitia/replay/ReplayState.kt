package dev.iustitia.replay

import java.util.UUID
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Active replay/clip-playback state (Phase 2 "instant replay"): holds an immutable snapshot of the
 * captured frames + alerts, advances a [playhead] through them at [speed] (0.5× / 0.25×), and is read
 * cross-thread by [dev.iustitia.render.ReplayRenderer] to draw ghosts at the playhead frame.
 *
 * ## Why the frames are an immutable snapshot
 *
 * [ReplayBuffer] keeps advancing every tick. A replay copies out its window once at [start], so the
 * live recording never mutates what's playing back — the playhead sweeps a fixed list. The list is
 * never mutated after [start], so the render thread can read it lock-free alongside the client-thread
 * [playhead] (a `@Volatile Float`).
 *
 * ## "Rewind feel" — hiding live players
 *
 * When [hideLive] is true (default; the chosen replay mode), [dev.iustitia.mixin.EntityRendererMixin]
 * cancels `shouldRender` for every OTHER player while [active], so only the buffered ghosts render —
 * a "rewind the world" feel. The live game + detection keep running under the hood; only rendering is
 * swapped, and it snaps back the instant [active] goes false. Toggle off in config to overlay ghosts
 * alongside live players instead.
 *
 * ## Safety
 *
 * There is no camera override here (unlike the watch follow-cam) — you keep your own view; the ghosts
 * render in-world around you. [active] is `@Volatile`; a stop (keybind re-run, world change, finished,
 * target-gone) flips it false and the very next frame renders live again. Fail-open throughout.
 */
object ReplayState {

    /** Replay camera mode. FREE keeps your own view (ghosts render around you); FOLLOW orbits the
     *  focus ghost (mouse-driven, like [dev.iustitia.render.WatchState]); POV puts the camera at the
     *  focus ghost's eye looking along its buffered yaw+pitch (see the world from their view); FREECAM
     *  detaches the camera entirely so you can fly it anywhere in the clip's solid world (including
     *  underground) with WASD + mouse — the Iustitia-native free-spectate, used when [chunks] != null. */
    enum class CameraMode { FREE, FOLLOW, POV, FREECAM }

    /** Supported playback speeds. Stored as "playhead-tick advance per real tick". */
    const val SPEED_FULL = 1.0f
    const val SPEED_HALF = 0.5f   // 0.5× → a 10s clip plays back over 20s
    const val SPEED_QUARTER = 0.25f

    /** How far the FREECAM pose seeds BEHIND the player's eye along the look vector on enter, so
     *  free-fly starts detached rather than inside the (hidden) player's face. Matches the Zergatul
     *  reference's 2-block back-off. */
    private const val FREECAM_BACKOFF: Double = 2.0

    /** Per-tick velocity added per unit input (blocks/tick²). Tuned for a spectator-like acceleration
     *  ramp; runtime-tunable feel. Scaled ×1.75 (from 0.06) on 2026-07-14 — steady-state speed ∝ accel
     *  (drag unchanged). The default (no-CTRL) speed uses [FC_FLY_FAST] on this accel → effective
     *  accel 0.21 → steady-state ≈ 0.525 b/t (the speed the user liked at CTRL and made the default). */
    private const val FC_FLY_ACCEL: Double = 0.105
    /** DEFAULT (no-CTRL) acceleration multiplier — the fast spectator speed is what you get by just
     *  moving, no key held (vanilla spectator sprint ≈ 2×). Steady-state ≈ 0.525 b/t. Runtime-tunable. */
    private const val FC_FLY_FAST: Double = 2.0
    /** CTRL-held BOOST multiplier — holding CTRL (the vanilla sprint key) goes ~2× FASTER than the
     *  default speed (4× the base accel → steady-state ≈ 1.05 b/t = 2× the native 0.525). The
     *  2026-07-14 rework: native is the fast speed, CTRL is a further boost (was briefly a slow-mode
     *  in an intermediate pass; the user preferred boost). Stays under [FC_FLY_MAX] (1.68). */
    private const val FC_FLY_BOOST: Double = 4.0
    /** Per-tick velocity retention (drag). 0.6 → release the key and the camera coasts to a stop over
     *  a few ticks (vanilla creative-flight deceleration feel). Steady-state speed ≈ accel/(1-drag). */
    private const val FC_FLY_DRAG: Double = 0.6
    /** Speed cap (blocks/tick) so accumulated velocity can't run away (≈ vanilla spectator sprint).
     *  Scaled ×1.75 (from 0.96) to keep the cap's ratio to steady-state; it still never bites at
     *  steady state (default ≈ 0.525 < 0.96 < 1.68), it's only a runaway guard. */
    private const val FC_FLY_MAX: Double = 1.68

    // Threading note: on 1.21.11 the "client thread" and the render thread are the same physical
    // thread, so these `@Volatile`s are not guarding true concurrent mutation — they guarantee the
    // *happens-before* visibility of a state change (a start/stop/seek from a command or keybind)
    // to the render callbacks that read the field later in the same tick/frame, without which the
    // JIT would be free to hoist the read out of the render loop. They also document the
    // cross-thread intent for anyone reordering this state. Kept deliberately — cheap, and the
    // honest contract for the fields a mixin reads directly.
    @Volatile var active: Boolean = false
        private set
    @Volatile var focusUuid: UUID? = null
        private set
    @Volatile var hideLive: Boolean = true
        private set
    /** Current camera mode (read by the render-thread camera mixin + the ghost renderer). Ephemeral
     *  — not persisted; defaults to FREE each replay. */
    @Volatile var cameraMode: CameraMode = CameraMode.FREE
        private set
    /** Paused freezes the playhead ([tick] does not advance); seek/step/speed still apply. */
    @Volatile var paused: Boolean = false
        private set

    /**
     * Relocation offset applied to every recorded coord at render + camera time, so a replay/clip
     * plays out around the **user's current position** instead of at raw recorded world coords (which
     * would float in air / sit in the ground / clip walls when you play it back somewhere else). Set
     * in [start] to `userPos - focusStartPos` (the focus player's recorded start maps to the user).
     * `null` ⇒ legacy absolute rendering (start couldn't compute it, or [IustitiaConfig.replayRelocate]
     * is off). Read cross-thread by [dev.iustitia.render.ReplayRenderer] + [dev.iustitia.mixin.CameraMixin].
     */
    @Volatile var relocOffset: Vec3d? = null
        private set

    /**
     * Terrain snapshot bundled with a clip (v5+), rendered as a face-culled shell by
     * [dev.iustitia.render.TerrainOverlay]. `null` for `/ius replay` (replay never bundles the map —
     * you're already on it) and for pre-v5 clips. Read cross-thread by the terrain overlay.
     */
    @Volatile var terrain: TerrainSnapshot? = null
        private set

    /**
     * Full-chunk world snapshot bundled with a clip (v6+), rendered as **solid, textured blocks** by
     * [dev.iustitia.render.ChunkWorldRenderer] (baked once via [dev.iustitia.render.ChunkMesher]) so the
     * user can free-spectate anywhere, including underground. `null` for `/ius replay`, pre-v6 clips,
     * and when `clipChunkWorld` is off — in those cases the [terrain] wireframe / ghosts render as
     * before. When non-null, [dev.iustitia.mixin.WorldRendererMixin] suppresses the live world's chunk
     * render so the clip's world replaces it. Read cross-thread by the chunk world renderer +
     * world-suppress mixin.
     */
    @Volatile var chunks: ChunkSnapshot? = null
        private set

    /** Capture segments bundled with this replay/clip (v13+): per-segment world snapshots + block
     *  deltas, so a clip that spans a teleport/world change replays each place with the world it had
     *  there. [tick] switches [chunks] to the segment under the playhead. Read cross-thread by the
     *  segment-aware renderer. */
    @Volatile var segments: List<ReplayBuffer.Segment> = emptyList()
        private set

    /** Block changes observed in the **active segment**, applied onto [chunks]'s overlay as the playhead
     *  advances (so blocks placed/broken during the clip appear/disappear through the replay). Null when
     *  the active segment has none (or the clip is pre-v13). Read cross-thread by the world renderer. */
    @Volatile var blockDeltas: List<BlockDeltaBuffer.BlockDelta>? = null
        private set

    /** Whether the active segment's chunk snapshot was taken at the segment START (so the delta overlay
     *  can rewind to it). False when the rolling capture began mid-segment. Display-only flag. */
    @Volatile var snapshotIsStart: Boolean = false
        private set

    /** Segment index whose world is currently active (`-1` when none / no segments). Client-thread only
     *  ([tick] writes it; nothing reads it cross-thread). */
    @Volatile private var activeSegmentIndex: Int = -1

    /** Playhead frame index → segment index (or `-1`), built once at [start] from the segments'
     *  [ReplayBuffer.Segment.firstFrameIndex]. Empty when the clip has no segments. */
    private var frameSegIndex: IntArray = IntArray(0)

    /** Whether this playback relocates segments to the user (true for `/ius playclip`). Re-derived per
     *  segment on [switchToSegment]. Client-thread only. */
    private var relocate: Boolean = false

    /** The user's position at [start] — the point a segment's [relocOffset] maps the focus start onto.
     *  Client-thread only. */
    private var userPos: Vec3d? = null

    /** Block-delta overlay cursor: the playhead tick the overlay is currently built to, the next delta
     *  index to apply, and the chunk keys the deltas touched (to invalidate their bakes). Client-thread
     *  only ([tick]); the overlay map itself is read cross-thread by the mesher. */
    private var deltaCursorTick: Int = -1
    private var nextDeltaIdx: Int = 0
    private var deltaChunkKeys: Set<Long> = emptySet()

    /**
     * Totem-of-Undying pop events captured within this replay/clip window (v7+). Each [ReplayBuffer.TotemRec]
     * is `(tick, uuid)` — the tick the pop happened + the player who popped. Set from [start] (copied from
     * the [ReplayBuffer.Window]) and cleared in [stop]; read cross-thread by [dev.iustitia.render.ReplayRenderer]
     * via [totemCountFor] to draw the `⚡<count>` badge on a ghost's nametag when
     * [dev.iustitia.config.IustitiaConfig.clipTotemPopCounter] is on. Empty for `/ius replay` and pre-v7
     * clips (no totems recorded → no badge). Fail-open.
     */
    @Volatile var totems: List<ReplayBuffer.TotemRec> = emptyList()
        private set

    /**
     * FREECAM free-spectate pose (v1.2.0 rework — pure camera-override, matching the Zergatul FreeCam
     * v26.2 reference). Six primitives stored in **relocated/screen space** (the space the camera
     * writes — [enterFreecam] seeds from the player's eye pos, [tickFreecam] advances in screen
     * space, [CameraMixin] writes them verbatim with NO [relocOffset] shift). Driven by WASD + mouse:
     * [tickFreecam] integrates held keys into the position (noclip, no gravity — spectator feel), and
     * [FreecamEntityMixin] redirects `Entity.changeLookDirection` onto [fcYaw]/[fcPitch] via
     * [applyFreecamLook]. The chunk world + ghosts render with the shared [relocOffset] translate, so
     * a screen-space freecam pos lines up with them. [freecamActive] is a render-thread-readable flag
     * distinct from `cameraMode == FREECAM` so the camera mixin needn't re-derive. Written
     * client-thread only; read cross-thread by the camera mixin.
     *
     * ## Torn-pose read (accepted)
     *
     * The six pose primitives are separate `@Volatile` fields (each individually visible to the render
     * thread, but NOT atomic across the group), so the render thread can read a torn pose mid-movement
     * — e.g. a new [fcX] with a stale [fcZ]. The worst case is a cosmetic one-frame camera jump of up
     * to ~one tick's sprint distance (~0.5 blocks at the FREECAM sprint speed). Accepted for a spectator
     * camera (no game-state consequences). Bundling the six into an atomic `FreecamPose` holder is a
     * documented future option if freecam ever interpolates between ticks.
     */
    @Volatile var fcX: Double = 0.0
        private set
    @Volatile var fcY: Double = 0.0
        private set
    @Volatile var fcZ: Double = 0.0
        private set
    @Volatile var fcYaw: Float = 0f
        private set
    @Volatile var fcPitch: Float = 0f
        private set
    /** Previous-tick FREECAM pose — snapshot of [fcX..fcPitch] taken at the start of each [tickFreecam]
     *  advance, so [dev.iustitia.mixin.CameraMixin] can lerp `prev → current` by `tickDelta` per frame
     *  (vanilla spectator interpolation). Written client-thread only; read cross-thread by the mixin. */
    @Volatile var prevFcX: Double = 0.0
        private set
    @Volatile var prevFcY: Double = 0.0
        private set
    @Volatile var prevFcZ: Double = 0.0
        private set
    @Volatile var prevFcYaw: Float = 0f
        private set
    @Volatile var prevFcPitch: Float = 0f
        private set
    /** Mouse-look deltas accumulated since the last [tickFreecam] (via [applyFreecamLook]), applied
     *  inside [tickFreecam] AFTER the prev-pose snapshot so look is smoothed across the tick boundary
     *  too (mirrors vanilla: prevYaw = end-of-last-tick, yaw = post-this-tick). Client-thread only. */
    @Volatile private var pendingYawDelta: Double = 0.0
    @Volatile private var pendingPitchDelta: Double = 0.0
    /** FREECAM velocity (blocks/tick) — integrated from input with acceleration + drag, so the camera
     *  has momentum (accelerate while a key is held, decelerate when released) like vanilla spectator
     *  flight, instead of the old instant-velocity step. Client-thread only. */
    @Volatile private var fcVX: Double = 0.0
    @Volatile private var fcVY: Double = 0.0
    @Volatile private var fcVZ: Double = 0.0
    @Volatile var freecamActive: Boolean = false
        private set
    /** True while a **playclip** is running in LEGACY mode (v1.1.0 behavior). Set from [start]'s
     *  [legacy] arg. Consumed by the input/packet-suppression mixins ([dev.iustitia.mixin.ClientPlayerEntityMixin],
     *  [dev.iustitia.mixin.ClientConnectionMixin]) + the chunk/terrain render + live-terrain gates to
     *  DISABLE those post-v1.1.0 behaviors for a Legacy playclip (player walks + acts normally, live
     *  world renders, no freecam). Always false for `/ius replay` (which passes `legacy=false`) so
     *  replay keeps its current spectator-like suppression unchanged. */
    @Volatile var legacyPlayclip: Boolean = false
        private set

    /** True once playback has reached the end and is being held (not advancing). Set in [tick] when
     *  the playhead reaches the last frame; cleared in [start]/[stop] and whenever a seek/step moves
     *  the playhead off the last frame. While true, [tick] does not advance and [stop] is NOT called
     *  — the mode stays active (freecam, ghosts, controls all live) until the user explicitly exits
     *  (/ius replay|playclip off, the numpad-0 exit keybind, or the replayToggle keybind). */
    @Volatile var held: Boolean = false
        private set

    /** Immutable frame list — set once at [start], read cross-thread. Empty when inactive. */
    private var frames: List<ReplayBuffer.Frame> = emptyList()
    private var alerts: List<ReplayBuffer.AlertRec> = emptyList()

    @Volatile private var playhead: Float = 0f
    @Volatile private var prevPlayhead: Float = 0f
    @Volatile private var speed: Float = SPEED_HALF
    @Volatile private var lastFrameTick: Int = 0

    /** Saved local-player perspective while POV forces first-person (mirrors [dev.iustitia.render.WatchState]).
     *  Null = nothing saved (not in POV). Client-thread-only writes. */
    private var savedPerspective: net.minecraft.client.option.Perspective? = null

    /**
     * Begin a replay/clip-playback of [window].frames at [speed]. [focus] is the highlighted player
     * (the `/ius replay <player>` subject; null for a generic clip). [relocate] controls whether the
     * scene is shifted to the user's current position: **`/ius replay` passes `false`** so ghosts
     * render at their exact recorded world coordinates (v1.1.0 behavior — replay is instant, same
     * server/dimension, no anchoring); **`/ius playclip` passes `true`** so a clip recorded elsewhere
     * plays around the user (the [IustitiaConfig.replayRelocate] toggle still gates it for clips).
     * Returns false if there are no frames. Idempotent: starting while active first stops the prior one.
     */
    fun start(window: ReplayBuffer.Window, focus: UUID?, speed: Float, hideLive: Boolean, relocate: Boolean, legacy: Boolean): Boolean {
        // Yield playback to SnapClip when it's installed: it owns replay/clip, so Iustitia must not
        // run a second camera/ghost pipeline or a second input-suppression path. Capture is already
        // off (ReplayBuffer.enabled), so no window would exist anyway; this closes the direct-start
        // paths (command, keybind, ClipPlayback) too.
        if (dev.iustitia.compat.CompanionMods.snapClip) return false
        try {
            if (window.frames.isEmpty()) return false
            // Idempotent: stop any prior replay first (restores perspective if it was in POV).
            if (active) stop("restarted")
            frames = window.frames
            alerts = window.alerts
            focusUuid = focus
            this.speed = speed
            this.hideLive = hideLive
            playhead = 0f
            prevPlayhead = 0f
            paused = false
            held = false
            cameraMode = CameraMode.FREE
            lastFrameTick = window.frames.last().tick
            // Legacy drops any terrain/chunks embedded in a v5/v6 clip so the render path + the
            // live-terrain suppression no-op (v1.1.0 was ghosts-over-live-world only). Modern keeps them.
            terrain = if (legacy) null else window.terrain
            segments = if (legacy) emptyList() else window.segments
            totems = if (legacy) emptyList() else window.totems
            this.relocate = relocate
            userPos = try {
                val p = net.minecraft.client.MinecraftClient.getInstance().player
                if (p != null) Vec3d(p.x, p.y, p.z) else null
            } catch (_: Throwable) { null }
            frameSegIndex = buildFrameSegIndex(window)
            activeSegmentIndex = -1
            deltaCursorTick = -1
            nextDeltaIdx = 0
            deltaChunkKeys = emptySet()
            if (!legacy && segments.isNotEmpty()) {
                // Segment-based clip (v13 record / rolling capture): the world lives per segment, so
                // start on the segment under frame 0. Falls back to a top-level snapshot if the first
                // segment carries none (a chunks-less segment shouldn't blank the world).
                switchToSegment(0)
                if (chunks == null) chunks = window.chunks
            } else {
                chunks = if (legacy) null else window.chunks
                blockDeltas = null
                snapshotIsStart = false
                relocOffset = if (relocate) computeRelocOffset(window, focus) else null
            }
            legacyPlayclip = legacy
            active = true
            return true
        } catch (_: Throwable) {
            active = false
            return false
        }
    }

    /**
     * The relocation offset: `userPos - origin`, where [origin] is the focus player's recorded start
     * (so the focus ghost begins at the user) or the first-frame centroid when there's no focus.
     * Returns null when relocation is disabled in config, no frames, or the local player is unavailable
     * — the renderer then falls back to absolute recorded coords. Client-thread only (reads the local
     * player); called from [start] only when [relocate] is true (i.e. `/ius playclip`). `/ius replay`
     * never calls this (it passes [relocate]=false) so replay stays at exact recorded coords.
     */
    private fun computeRelocOffset(window: ReplayBuffer.Window, focus: UUID?): Vec3d? = try {
        if (!try { dev.iustitia.config.ConfigManager.config.replayRelocate } catch (_: Throwable) { true }) return null
        val origin = focusStartPos(window, focus) ?: return null
        val player = net.minecraft.client.MinecraftClient.getInstance().player ?: return null
        Vec3d(player.x - origin.x, player.y - origin.y, player.z - origin.z)
    } catch (_: Throwable) { null }

    /** The focus player's recorded position in the first frame; the first snap's centroid if no focus
     *  (or no snaps). Null only when there are no frames at all. */
    private fun focusStartPos(window: ReplayBuffer.Window, focus: UUID?): Vec3d? = try {
        val first = window.frames.first().snaps
        if (first.isEmpty()) return null
        val s = (focus?.let { u -> first.firstOrNull { it.uuid() == u } } ?: first.first())
        Vec3d(s.x.toDouble(), s.y.toDouble(), s.z.toDouble())
    } catch (_: Throwable) { null }

    // ---- capture segments + block-delta overlay (SnapClip port) --------------------------------------

    /**
     * Build the playhead-frame → segment-index map from the segments' [ReplayBuffer.Segment.firstFrameIndex]
     * starts. A frame before the first segment start (shouldn't happen, but a malformed clip could)
     * maps to -1. Empty when the clip has no segments. Fail-open to an all--1 map.
     */
    private fun buildFrameSegIndex(window: ReplayBuffer.Window): IntArray {
        val segs = window.segments
        if (segs.isEmpty()) return IntArray(window.frames.size) { -1 }
        return try {
            val starts = IntArray(segs.size) { segs[it].firstFrameIndex }
            val out = IntArray(window.frames.size)
            var si = 0
            for (i in out.indices) {
                while (si + 1 < segs.size && starts[si + 1] <= i) si++
                out[i] = if (si < segs.size && starts[si] <= i) si else -1
            }
            out
        } catch (_: Throwable) {
            IntArray(window.frames.size) { -1 }
        }
    }

    /** The focus player's position in [seg]'s first frame (or the first snap's when no focus). Null when
     *  the frame/snap list is missing. Fail-open. */
    private fun segmentFirstFocusPos(seg: ReplayBuffer.Segment, focus: UUID?): Vec3d? = try {
        val idx = seg.firstFrameIndex
        if (idx < 0 || idx >= frames.size) return null
        val snaps = frames[idx].snaps
        if (snaps.isEmpty()) return null
        val s = (focus?.let { u -> snaps.firstOrNull { it.uuid() == u } } ?: snaps.first())
        Vec3d(s.x.toDouble(), s.y.toDouble(), s.z.toDouble())
    } catch (_: Throwable) { null }

    /** Per-segment relocation offset (`userPos - segmentFocusStart`), gated by the same
     *  [dev.iustitia.config.IustitiaConfig.replayRelocate] toggle as [computeRelocOffset]. Null when
     *  relocation is off / the segment or user pos is unavailable. */
    private fun computeSegmentOffset(seg: ReplayBuffer.Segment, focus: UUID?): Vec3d? = try {
        if (!try { dev.iustitia.config.ConfigManager.config.replayRelocate } catch (_: Throwable) { true }) return null
        val origin = segmentFirstFocusPos(seg, focus) ?: return null
        val up = userPos ?: return null
        Vec3d(up.x - origin.x, up.y - origin.y, up.z - origin.z)
    } catch (_: Throwable) { null }

    /**
     * Switch the active world to segment [index]: drop the outgoing segment's overlay, adopt its
     * snapshot + deltas, rewind the delta overlay to the segment start, and re-derive the relocation
     * offset for the new segment. Invalidates any chunks the previous segment's deltas had re-meshed so
     * the new snapshot bakes fresh. Fail-open. Client-thread only.
     */
    private fun switchToSegment(index: Int) {
        try {
            chunks?.dropOverlay()
            val seg = segments.getOrNull(index) ?: return
            activeSegmentIndex = index
            chunks = seg.chunks
            blockDeltas = seg.blockDeltas
            snapshotIsStart = seg.snapshotIsStart
            val segStartTick = try { frames.getOrNull(seg.firstFrameIndex)?.tick ?: 0 } catch (_: Throwable) { 0 }
            initBlockDeltaOverlay(segStartTick)
            relocOffset = if (relocate) computeSegmentOffset(seg, focusUuid) else null
            if (deltaChunkKeys.isNotEmpty()) {
                try { dev.iustitia.render.ChunkMesher.invalidateChunks(deltaChunkKeys) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
        }
    }

    /** Keep the segment world + block-delta overlay aligned with the current playhead. Called each
     *  [tick] (advancing, paused, or held). Fail-open. */
    private fun syncSegmentAndDeltas() {
        try {
            if (frames.isEmpty()) return
            if (segments.isNotEmpty()) {
                val idx = playhead.toInt().coerceIn(0, frames.size - 1)
                val newSeg = frameSegIndex.getOrNull(idx) ?: -1
                if (newSeg >= 0 && newSeg != activeSegmentIndex) switchToSegment(newSeg)
            }
            applyBlockDeltasForPlayhead()
        } catch (_: Throwable) {
        }
    }

    /**
     * Build the delta overlay for a segment start: every delta's **before** state (first-writer-wins) so
     * the world looks like the moment the segment began, then leave the cursor just before its start tick
     * so [applyBlockDeltasForPlayhead] replays the segment's own deltas forward. Collects the touched
     * chunk keys for later invalidation. Fail-open (no overlay on error).
     */
    private fun initBlockDeltaOverlay(segStartTick: Int) {
        val snap = chunks
        val list = blockDeltas
        if (snap == null || list.isNullOrEmpty()) {
            deltaCursorTick = -1
            nextDeltaIdx = 0
            deltaChunkKeys = emptySet()
            return
        }
        try {
            val keys = HashSet<Long>(list.size * 2)
            for (d in list) keys.addAll(dev.iustitia.render.ChunkMesher.invalidateAround(d.x, d.y, d.z))
            deltaChunkKeys = keys
            snap.initOverlay()
            for (d in list) snap.putOverlayIfAbsent(d.x, d.y, d.z, d.before)
            deltaCursorTick = segStartTick - 1
            nextDeltaIdx = 0
        } catch (_: Throwable) {
            deltaCursorTick = -1
            nextDeltaIdx = 0
            deltaChunkKeys = emptySet()
        }
    }

    /**
     * Advance the block-delta overlay to the playhead frame's tick: apply every delta at/before that
     * tick, invalidating each touched chunk. A backward seek (the playhead jumped before the cursor)
     * rebuilds from the segment start instead. No-op when the segment has no deltas/overlay. Fail-open.
     */
    private fun applyBlockDeltasForPlayhead() {
        val snap = chunks ?: return
        val list = blockDeltas ?: return
        if (list.isEmpty()) return
        if (snap.overlay == null) return
        try {
            val idx = playhead.toInt()
            val targetTick = frames.getOrNull(idx)?.tick ?: return
            if (targetTick < deltaCursorTick) {
                recomputeBlockDeltaOverlay(snap, list, targetTick)
                return
            }
            if (targetTick == deltaCursorTick) return
            val dirty = HashSet<Long>()
            while (nextDeltaIdx < list.size && list[nextDeltaIdx].tick <= targetTick) {
                val d = list[nextDeltaIdx]
                snap.putOverlay(d.x, d.y, d.z, d.after)
                dirty.addAll(dev.iustitia.render.ChunkMesher.invalidateAround(d.x, d.y, d.z))
                nextDeltaIdx++
            }
            deltaCursorTick = targetTick
            if (dirty.isNotEmpty()) dev.iustitia.render.ChunkMesher.invalidateChunks(dirty)
        } catch (_: Throwable) {
        }
    }

    /** Rewind the overlay to the segment start and re-play deltas up to [targetTick] (a backward seek).
     *  Invalidates every chunk any delta touched. Fail-open. */
    private fun recomputeBlockDeltaOverlay(snap: ChunkSnapshot, list: List<BlockDeltaBuffer.BlockDelta>, targetTick: Int) {
        try {
            snap.clearOverlay()
            for (d in list) snap.putOverlayIfAbsent(d.x, d.y, d.z, d.before)
            nextDeltaIdx = 0
            while (nextDeltaIdx < list.size && list[nextDeltaIdx].tick <= targetTick) {
                val d = list[nextDeltaIdx]
                snap.putOverlay(d.x, d.y, d.z, d.after)
                nextDeltaIdx++
            }
            deltaCursorTick = targetTick
            if (deltaChunkKeys.isNotEmpty()) dev.iustitia.render.ChunkMesher.invalidateChunks(deltaChunkKeys)
        } catch (_: Throwable) {
        }
    }

    /** Stop now and return the reason (for the caller to chat). Idempotent. */
    fun stop(reason: String): String {
        try {
            if (!active) return reason
            active = false
            frames = emptyList()
            alerts = emptyList()
            focusUuid = null
            playhead = 0f
            prevPlayhead = 0f
            paused = false
            held = false
            cameraMode = CameraMode.FREE
            relocOffset = null
            terrain = null
            // Drop the active segment's block overlay before clearing (the snapshot outlives the
            // replay in an export, so don't leave a delta overlay welded onto it).
            chunks?.dropOverlay()
            chunks = null
            segments = emptyList()
            blockDeltas = null
            snapshotIsStart = false
            activeSegmentIndex = -1
            frameSegIndex = IntArray(0)
            deltaCursorTick = -1
            nextDeltaIdx = 0
            deltaChunkKeys = emptySet()
            relocate = false
            userPos = null
            totems = emptyList()
            legacyPlayclip = false
            // Drop the FREECAM pose (pure camera-override — no camera entity to restore; flipping the
            // flag is enough; the next frame's camera mixin FREECAM branch returns false → vanilla view).
            try { exitFreecam() } catch (_: Throwable) {}
            try { dev.iustitia.render.ChunkWorldRenderer.free() } catch (_: Throwable) {}
            try { dev.iustitia.render.ReplayRenderer.clearGhostCaches() } catch (_: Throwable) {}
            restorePerspective()
        } catch (_: Throwable) {
            active = false
        }
        return reason
    }

    /**
     * Advance the playhead one real tick (called from [dev.iustitia.Iustitia.onClientTick], client
     * thread). Returns a non-null reason when the replay just finished (so the caller can chat it),
     * or null while still playing / inactive / paused.
     */
    fun tick(): String? {
        prevPlayhead = playhead   // snapshot every call: when held/paused the playhead doesn't
                                  // advance, so prev==playhead and currentFrameLerped's lerp is a
                                  // no-op (static frame). On advancing ticks prev lags by one tick,
                                  // giving the prev→current lerp its motion. Placing this AFTER the
                                  // held/paused guards would leave prev stale and cause a one-frame
                                  // oscillation at the held end / while paused.
        if (!active) return null
        try {
            if (paused || held) {
                // Playhead frozen (paused, or held at the end). Still sync the visible world to the
                // current playhead so a seek/step while paused scrubs the segment world + block-delta
                // overlay. Returns null (unchanged contract: paused/held never auto-stop).
                syncSegmentAndDeltas()
                return null
            }
            playhead += speed
            if (playhead >= frames.size) {
                // Hold on the last frame instead of exiting — the replay/clip stays active (freecam,
                // ghosts, controls) until an explicit stop. Returning null means onClientTick does
                // NOT chat "live view restored"; the still-rendered ghosts + freecam are the signal.
                playhead = (frames.size - 1).toFloat()
                held = true
            }
            syncSegmentAndDeltas()
            return null
        } catch (_: Throwable) {
            return stop("replay error")
        }
    }

    /** The frame at the current playhead (floor), or null when inactive / concurrently stopped. */
    fun currentFrame(): ReplayBuffer.Frame? = try {
        if (!active) return null
        val idx = playhead.toInt()
        if (idx < 0 || idx >= frames.size) null else frames[idx]
    } catch (_: Throwable) { null }

    // Reused scratch containers for currentFrameLerped (B2): a HashMap + HashSet + ArrayList were
    // allocated every render frame (sized to ghost count). clear()+reuse keeps the backing capacity
    // (no per-frame grow after warmup), dropping the per-frame allocation churn. Render-thread only:
    // currentFrameLerped is called only from ReplayRenderer.drawGhosts, single-threaded, and the
    // returned Frame's `out` list is consumed synchronously + discarded before the next frame reuses
    // it — so sharing the mutable list across frames is safe.
    private val lerpByUuid = HashMap<java.util.UUID, ReplayBuffer.PlayerSnap>()
    private val lerpAUuids = HashSet<java.util.UUID>()
    private val lerpOut = ArrayList<ReplayBuffer.PlayerSnap>()
    private val lerpEntityByUuid = HashMap<java.util.UUID, ReplayBuffer.EntitySnap>()
    private val lerpEntityAUuids = HashSet<java.util.UUID>()
    private val lerpEntityOut = ArrayList<ReplayBuffer.EntitySnap>()

    /** An interpolated frame at the playhead, lerped between [frames] floor and ceil by the
     *  tickDelta-interpolated playhead fraction — so ghosts move smoothly between recorded ticks
     *  (esp. at 0.5×/0.25× speed) AND between client ticks. At full speed (frac≈0 at each tick) this
     *  equals [currentFrame]. Read cross-thread by [dev.iustitia.render.ReplayRenderer]. Null when
     *  inactive. UUID from the floor frame; name/pose/swing from the ceil frame. */
    fun currentFrameLerped(tickDelta: Float): ReplayBuffer.Frame? = try {
        if (!active || frames.isEmpty()) return null
        val td = tickDelta.coerceIn(0f, 1f)
        val rh = MathHelper.lerp(td, prevPlayhead, playhead)
        val idx = rh.toInt().coerceIn(0, frames.size - 1)
        val frac = (rh - idx).coerceIn(0f, 1f)
        val a = frames[idx]
        if (idx + 1 >= frames.size || frac <= 0f) return a
        val b = frames[idx + 1]
        // Never interpolate across a segment boundary: the two frames' coords are in different
        // (teleported) world spaces, so lerping would streak a ghost across the map. Snap to [a].
        if (a.segmentId != b.segmentId) return a
        // Reuse scratch containers (B2): clear() keeps the backing arrays, so no per-frame HashMap/
        // HashSet/ArrayList allocation after warmup. `out` is returned in the Frame and consumed
        // synchronously by drawGhosts before the next frame reuses it.
        val byUuid = lerpByUuid.apply { clear() }
        for (s in b.snaps) byUuid[s.uuid()] = s
        val aUuids = lerpAUuids.apply { clear() }
        val out = lerpOut.apply { clear() }
        for (s in a.snaps) {
            aUuids.add(s.uuid())
            val t = byUuid[s.uuid()]
            out.add(if (t != null) lerpSnap(s, t, frac) else s)
        }
        for (s in b.snaps) if (s.uuid() !in aUuids) out.add(s)  // entered between ticks
        // v13 entity lerp: same merge, so a mob ghost moves smoothly instead of stepping per tick.
        val entOut = lerpEntityOut.apply { clear() }
        if (a.entities.isNotEmpty() || b.entities.isNotEmpty()) {
            val entByUuid = lerpEntityByUuid.apply { clear() }
            for (e in b.entities) entByUuid[e.uuid()] = e
            val entAUuids = lerpEntityAUuids.apply { clear() }
            for (e in a.entities) {
                entAUuids.add(e.uuid())
                val t = entByUuid[e.uuid()]
                entOut.add(if (t != null) lerpEntitySnap(e, t, frac) else e)
            }
            for (e in b.entities) if (e.uuid() !in entAUuids) entOut.add(e)
        }
        ReplayBuffer.Frame(a.tick, out, entOut, a.segmentId)
    } catch (_: Throwable) { null }

    /** The active replay's full window (frames + alerts + any bundled terrain/chunks), for
     *  `/ius replay save`. Empty when inactive. Client-thread only (command handler).
     *
     *  A segment-based window carries its world in [segments], so the top-level [chunks] is dropped in
     *  that case — otherwise the export would write the same snapshot twice (top-level + inside the
     *  segment) and double the clip size. Mirrors [dev.iustitia.replay.RecordManager.saveSegment]. */
    fun exportWindow(): ReplayBuffer.Window = try {
        if (!active) ReplayBuffer.Window(emptyList(), emptyList(), null, null)
        else ReplayBuffer.Window(frames, alerts, terrain, if (segments.isEmpty()) chunks else null, totems, segments)
    } catch (_: Throwable) { ReplayBuffer.Window(emptyList(), emptyList(), null, null) }

    /** Lerp the spatial fields of two snaps of the same player; UUID from [a] (floor frame),
     *  swingTicks/pose/name/hurtTime/health/maxHealth + the v8 equipment strings from [b] (the
     *  ceil/"current" frame — these are discrete/per-tick values, not spatial, so they snap to the
     *  current frame rather than lerp). Yaw uses angle-aware lerp. The v7 combat fields + v8 equipment
     *  MUST be carried here (not dropped) — the render path uses [currentFrameLerped], so dropping
     *  them would default every ghost to 20/20 health + no hurt flash + empty hands/armor (the
     *  "health indicator always shows 20/20" / "ghost holds nothing mid-lerp" bug). */
    private fun lerpSnap(a: ReplayBuffer.PlayerSnap, b: ReplayBuffer.PlayerSnap, frac: Float): ReplayBuffer.PlayerSnap {
        val f = frac.coerceIn(0f, 1f)
        return ReplayBuffer.PlayerSnap(
            uuidMost = a.uuidMost, uuidLeast = a.uuidLeast,
            x = MathHelper.lerp(f, a.x, b.x),
            y = MathHelper.lerp(f, a.y, b.y),
            z = MathHelper.lerp(f, a.z, b.z),
            yaw = MathHelper.lerpAngleDegrees(f, a.yaw, b.yaw),
            pitch = MathHelper.lerp(f, a.pitch, b.pitch),
            // v13 body/head yaw lerp; older snaps default both to the look yaw so it's a no-op there.
            bodyYaw = MathHelper.lerpAngleDegrees(f, a.bodyYaw, b.bodyYaw),
            headYaw = MathHelper.lerpAngleDegrees(f, a.headYaw, b.headYaw),
            swingTicks = b.swingTicks, pose = b.pose, name = b.name,
            hurtTime = b.hurtTime, health = b.health, maxHealth = b.maxHealth,
            mainHand = b.mainHand, offHand = b.offHand, head = b.head, chest = b.chest, legs = b.legs, feet = b.feet,
        )
    }

    /** Lerp the spatial fields of two entity snaps of the same entity; type/pose/name-ish fields from
     *  [b] (discrete per-tick values snap to the current frame). Yaws use angle-aware lerp. */
    private fun lerpEntitySnap(a: ReplayBuffer.EntitySnap, b: ReplayBuffer.EntitySnap, frac: Float): ReplayBuffer.EntitySnap {
        val f = frac.coerceIn(0f, 1f)
        return ReplayBuffer.EntitySnap(
            typeId = b.typeId,
            uuidMost = a.uuidMost, uuidLeast = a.uuidLeast,
            x = MathHelper.lerp(f, a.x, b.x),
            y = MathHelper.lerp(f, a.y, b.y),
            z = MathHelper.lerp(f, a.z, b.z),
            bodyYaw = MathHelper.lerpAngleDegrees(f, a.bodyYaw, b.bodyYaw),
            headYaw = MathHelper.lerpAngleDegrees(f, a.headYaw, b.headYaw),
            pitch = MathHelper.lerp(f, a.pitch, b.pitch),
            pose = b.pose, hurtTime = b.hurtTime, health = b.health, maxHealth = b.maxHealth,
        )
    }

    /** The buffered alerts in this replay (for the on-screen replay HUD / future overlay). */
    fun alerts(): List<ReplayBuffer.AlertRec> = if (active) alerts else emptyList()

    /** Count of Totem-of-Undying pops by [uuid] within this replay/clip window (v7+), for the `⚡<count>`
     *  nametag badge. 0 when inactive or the player popped no totems. Read cross-thread by the ghost
     *  renderer. Fail-open. */
    fun totemCountFor(uuid: UUID): Int = try {
        if (active) totems.count { it.uuid() == uuid } else 0
    } catch (_: Throwable) { 0 }

    /** Playhead progress 0..1 across the frame list (for a HUD progress bar). */
    fun progress(): Float = try {
        if (!active || frames.isEmpty()) 0f
        else (playhead / frames.size).coerceIn(0f, 1f)
    } catch (_: Throwable) { 0f }

    fun frameCount(): Int = if (active) frames.size else 0
    fun currentSpeed(): Float = speed
    fun isPaused(): Boolean = paused
    fun focusName(): String? = try {
        val u = focusUuid ?: return null
        dev.iustitia.history.FlagHistory.nameOrShort(u)
    } catch (_: Throwable) { null }

    /** The focus player's snap at the current playhead, lerped by [tickDelta] — the SAME frame +
     *  fraction the ghost renderer draws from ([currentFrameLerped]), so the POV/FOLLOW camera
     *  anchor and the ghost it films can't disagree by up to a full tick of motion at the tick
     *  boundary. Falls back to the floor frame when no lerp applies (single-frame window /
     *  segment-boundary snap). Null when no focus / inactive / not in frame. Render thread,
     *  consumed synchronously — the lerped frame's snap list is shared render scratch (see
     *  [currentFrameLerped]'s reuse note). */
    fun focusSnap(tickDelta: Float): ReplayBuffer.PlayerSnap? = try {
        if (!active) return null
        val u = focusUuid ?: return null
        (currentFrameLerped(tickDelta) ?: currentFrame())?.snaps?.firstOrNull { it.uuid() == u }
    } catch (_: Throwable) { null }

    /** The replay window's first..last captured tick (for mapping alert ticks to HUD progress). */
    fun windowTickRange(): IntRange? = try {
        if (!active || frames.isEmpty()) null else frames.first().tick..frames.last().tick
    } catch (_: Throwable) { null }

    // ---- playback controls (client thread; called from /ius replay subcommands + keybinds) ----

    /** Toggle pause. Returns the new paused state (for chat feedback). */
    fun togglePause(): Boolean { paused = !paused; return paused }

    /** Seek the playhead to [seconds] into the window (clamped 0..frames). Works while playing or paused. */
    fun seekTo(seconds: Float) {
        try {
            if (!active || frames.isEmpty()) return
            val target = (seconds * 20f).coerceIn(0f, (frames.size - 1).toFloat())
            playhead = target
            prevPlayhead = target
            held = target >= (frames.size - 1)
        } catch (_: Throwable) {}
    }

    /** Seek the playhead by [deltaSeconds] (signed; +forward / −back), clamped to the window. Works
     *  while playing OR paused — a live replay can be scrubbed without pausing first (used by the
     *  numpad +/- keybinds). */
    fun seekBy(deltaSeconds: Float) {
        try {
            if (!active || frames.isEmpty()) return
            val target = (playhead + deltaSeconds * 20f).coerceIn(0f, (frames.size - 1).toFloat())
            playhead = target
            prevPlayhead = target
            held = target >= (frames.size - 1)
        } catch (_: Throwable) {}
    }

    /** Step the playhead [frames] ticks (signed; +forward / −back). Only effective while paused so a
     *  live replay isn't fought by the step keys. */
    fun step(frames: Int) {
        try {
            if (!active || this.frames.isEmpty() || !paused) return
            val target = (playhead + frames).coerceIn(0f, (this.frames.size - 1).toFloat())
            playhead = target
            prevPlayhead = target
            held = target >= (this.frames.size - 1)
        } catch (_: Throwable) {}
    }

    /** Change playback speed mid-replay. Clamped to a supported speed. */
    fun setSpeed(s: Float) {
        try { speed = when (s) { SPEED_FULL, SPEED_HALF, SPEED_QUARTER -> s; else -> SPEED_HALF } } catch (_: Throwable) {}
    }

    /** Cycle playback speed FULL → HALF → QUARTER → FULL. Returns the new speed (for chat/keybind feedback). */
    fun cycleSpeed(): Float {
        speed = when (speed) { SPEED_FULL -> SPEED_HALF; SPEED_HALF -> SPEED_QUARTER; else -> SPEED_FULL }
        return speed
    }

    /** Switch camera mode. Entering POV forces first-person (so your own body doesn't float at your
     *  real position in the ghost's-eye view); leaving POV restores it. Entering FREECAM seeds the
     *  freecam pose from the user's current eye pos + look (so free-fly starts where you are, not at
     *  the origin) and lets [CameraMixin] override the camera each frame; leaving FREECAM flips the
     *  flag off (no camera entity to restore — pure camera-override). Client thread only. */
    fun setCameraMode(mode: CameraMode) {
        try {
            val prev = cameraMode
            cameraMode = mode
            if (prev == mode) return
            val mc = net.minecraft.client.MinecraftClient.getInstance()
            if (mode == CameraMode.POV && prev != CameraMode.POV) {
                if (savedPerspective == null) savedPerspective = mc.options.perspective
                mc.options.setPerspective(net.minecraft.client.option.Perspective.FIRST_PERSON)
            } else if (mode != CameraMode.POV && prev == CameraMode.POV) {
                restorePerspective()
            }
            // FREECAM enter/leave: seed the pose on enter; flip the flag off on leave. Re-entering
            // FREECAM re-seeds the pose at the current pos (the old pose was dropped on leave) —
            // simpler than preserving stale freecam state across modes.
            if (mode == CameraMode.FREECAM) {
                enterFreecam()
            } else if (prev == CameraMode.FREECAM) {
                exitFreecam()
            }
        } catch (_: Throwable) {}
    }

    /** Seed the FREECAM pose from the local player's eye pos + look, then back off [FREECAM_BACKOFF]
     *  blocks along the look vector (so free-fly starts detached, not inside the hidden player's face
     *  — matches the Zergatul reference). Sets [freecamActive]. Idempotent via the camera-mode guard
     *  in [setCameraMode] (only called on a FREECAM transition). Client thread only. Fail-open. */
    private fun enterFreecam() {
        try {
            val mc = net.minecraft.client.MinecraftClient.getInstance()
            val p = mc.player ?: return
            val eye = p.getEyePos()
            // MC yaw convention: yaw 0 → +Z (south); horizontal forward = (-sin yaw, cos yaw).
            val yawRad = p.yaw * (PI / 180.0)
            val fwdX = -sin(yawRad)
            val fwdZ = cos(yawRad)
            fcX = eye.x - fwdX * FREECAM_BACKOFF
            fcY = eye.y
            fcZ = eye.z - fwdZ * FREECAM_BACKOFF
            fcYaw = p.yaw
            fcPitch = p.pitch
            freecamActive = true
            prevFcX = fcX; prevFcY = fcY; prevFcZ = fcZ
            prevFcYaw = fcYaw; prevFcPitch = fcPitch
            pendingYawDelta = 0.0; pendingPitchDelta = 0.0
            fcVX = 0.0; fcVY = 0.0; fcVZ = 0.0
        } catch (_: Throwable) {
            // fail-open: if seeding fails, leave freecam off (camera stays on the player this run)
            freecamActive = false
        }
    }

    /** Drop the FREECAM pose. Idempotent. Client thread only. The camera entity is never swapped in
     *  the pure camera-override design (vanilla stays on the player; [CameraMixin] overrides the pose
     *  each frame while [freecamActive]), so there's no `setCameraEntity` to restore — flipping the
     *  flag is enough; the next frame's camera mixin FREECAM branch returns false → vanilla view. */
    private fun exitFreecam() {
        freecamActive = false
        pendingYawDelta = 0.0
        pendingPitchDelta = 0.0
        fcVX = 0.0; fcVY = 0.0; fcVZ = 0.0
    }

    /**
     * Advance the FREECAM pose one client tick: camera-relative WASD (forward along [fcYaw], strafe
     * right, jump=+Y, sneak=−Y), noclip (no collision/gravity — spectator feel). DEFAULT (no key) is
     *  the fast spectator speed ([FC_FLY_FAST]); holding CTRL (sprint) BOOSTS to [FC_FLY_BOOST] ≈ 2×
     *  the native speed. Reads the
     * held vanilla [net.minecraft.client.option.KeyBinding]s (public API, no mixin) — the player's own
     * walking is already suppressed by [dev.iustitia.mixin.ClientPlayerEntityMixin], so the keys are
     * free for the camera. No-op when FREECAM isn't active. Client thread only. Fail-open: a tick
     * error drops this tick's camera movement, never crashes.
     */
    fun tickFreecam() {
        try {
            if (!freecamActive || !active) { pendingYawDelta = 0.0; pendingPitchDelta = 0.0; return }
            // Snapshot prev = current pose BEFORE this tick's advance, so the render-thread camera
            // mixin can lerp prev→current by tickDelta (vanilla spectator interpolation).
            prevFcX = fcX; prevFcY = fcY; prevFcZ = fcZ
            prevFcYaw = fcYaw; prevFcPitch = fcPitch
            // Apply deferred mouse-look (accumulated since last tick via applyFreecamLook) AFTER the
            // snapshot, so look changes are smoothed across the tick boundary too.
            if (pendingYawDelta != 0.0 || pendingPitchDelta != 0.0) {
                fcYaw = fcYaw + (pendingYawDelta * 0.15).toFloat()
                fcPitch = (fcPitch + (pendingPitchDelta * 0.15).toFloat()).coerceIn(-90f, 90f)
                pendingYawDelta = 0.0; pendingPitchDelta = 0.0
            }
            val mc = net.minecraft.client.MinecraftClient.getInstance()
            val opts = mc.options
            val fwd = opts.forwardKey.isPressed
            val back = opts.backKey.isPressed
            val left = opts.leftKey.isPressed
            val right = opts.rightKey.isPressed
            val jump = opts.jumpKey.isPressed
            val sneak = opts.sneakKey.isPressed
            val sprint = try { opts.sprintKey.isPressed } catch (_: Throwable) { false }
            val yawRad = fcYaw * (PI / 180.0)
            val fwdX = -sin(yawRad)
            val fwdZ = cos(yawRad)
            // Player's RIGHT in MC's yaw convention (yaw 0 = south/+Z): right = (-cos, -sin).
            val rightX = -cos(yawRad)
            val rightZ = -sin(yawRad)
            // Build a unit wish-direction from the held keys (camera-relative).
            var dx = 0.0
            var dz = 0.0
            var dy = 0.0
            if (fwd) { dx += fwdX; dz += fwdZ }
            if (back) { dx -= fwdX; dz -= fwdZ }
            if (right) { dx += rightX; dz += rightZ }
            if (left) { dx -= rightX; dz -= rightZ }
            if (jump) dy += 1.0
            if (sneak) dy -= 1.0
            val hlen = dx * dx + dz * dz
            if (hlen > 1e-8) {
                val s = 1.0 / kotlin.math.sqrt(hlen)
                dx *= s; dz *= s
            } else {
                dx = 0.0; dz = 0.0
            }
            // Acceleration: wishDir * ACCEL * mul. DEFAULT (no CTRL) is the fast spectator speed
            // ([FC_FLY_FAST]); holding CTRL (sprint) BOOSTS to [FC_FLY_BOOST] ≈ 2× the native speed.
            // Vertical (jump/sneak) uses the same mul.
            val mul = if (sprint) FC_FLY_BOOST else FC_FLY_FAST
            val ax = dx * FC_FLY_ACCEL * mul
            val az = dz * FC_FLY_ACCEL * mul
            val ay = dy * FC_FLY_ACCEL * mul
            // Integrate with drag: v = v*DRAG + accel. Drag always applies so releasing keys decelerates.
            fcVX = fcVX * FC_FLY_DRAG + ax
            fcVY = fcVY * FC_FLY_DRAG + ay
            fcVZ = fcVZ * FC_FLY_DRAG + az
            // Cap horizontal + vertical speed so accumulated velocity can't run away.
            val vh = kotlin.math.sqrt(fcVX * fcVX + fcVZ * fcVZ)
            if (vh > FC_FLY_MAX) { val k = FC_FLY_MAX / vh; fcVX *= k; fcVZ *= k }
            if (kotlin.math.abs(fcVY) > FC_FLY_MAX) fcVY = fcVY.coerceIn(-FC_FLY_MAX, FC_FLY_MAX)
            // Integrate position (the prev/current pair above still drives per-frame interpolation).
            fcX = fcX + fcVX
            fcY = fcY + fcVY
            fcZ = fcZ + fcVZ
        } catch (_: Throwable) {}
    }

    /**
     * Apply a mouse delta to the FREECAM pose — called from [dev.iustitia.mixin.FreecamEntityMixin],
     * which cancels the player's own `changeLookDirection`. Defers the delta into
     * [pendingYawDelta]/[pendingPitchDelta]; the ×0.15 scaling and ±90 pitch clamp are applied in
     * [tickFreecam] (mirrors vanilla `Entity.changeLookDirection`'s scaling, verified against the
     * Zergatul FreeCam v26.2 reference's `onPlayerTurn`) so freecam look feels identical to vanilla
     * mouse-look. Client thread only (mouse-look runs on the client thread). Fail-open.
     */
    fun applyFreecamLook(yawDelta: Double, pitchDelta: Double) {
        try {
            if (!freecamActive) return
            // Defer into pending — applied inside tickFreecam after the prev-pose snapshot so the
            // render lerp smooths look across the tick boundary (same model as vanilla prevYaw/yaw).
            pendingYawDelta += yawDelta
            pendingPitchDelta += pitchDelta
        } catch (_: Throwable) {}
    }

    /** Restore the saved local-player perspective (called on stop / leaving POV). Idempotent. */
    private fun restorePerspective() {
        try {
            val mc = net.minecraft.client.MinecraftClient.getInstance()
            savedPerspective?.let { mc.options.setPerspective(it) }
            savedPerspective = null
        } catch (_: Throwable) {}
    }
}
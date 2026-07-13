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
     *  ramp; runtime-tunable feel. */
    private const val FC_FLY_ACCEL: Double = 0.06
    /** Sprint multiplier on the acceleration (vanilla spectator sprint ≈ 2×). */
    private const val FC_FLY_SPRINT: Double = 2.0
    /** Per-tick velocity retention (drag). 0.6 → release the key and the camera coasts to a stop over
     *  a few ticks (vanilla creative-flight deceleration feel). Steady-state speed ≈ accel/(1-drag). */
    private const val FC_FLY_DRAG: Double = 0.6
    /** Speed cap (blocks/tick) so accumulated velocity can't run away (≈ vanilla spectator sprint). */
    private const val FC_FLY_MAX: Double = 0.96

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
            chunks = if (legacy) null else window.chunks
            relocOffset = if (relocate) computeRelocOffset(window, focus) else null
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
            chunks = null
            legacyPlayclip = false
            // Drop the FREECAM pose (pure camera-override — no camera entity to restore; flipping the
            // flag is enough; the next frame's camera mixin FREECAM branch returns false → vanilla view).
            try { exitFreecam() } catch (_: Throwable) {}
            try { dev.iustitia.render.ChunkWorldRenderer.free() } catch (_: Throwable) {}
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
        if (paused) return null
        try {
            if (held) return null  // held at end — do not advance until the user seeks back / stops
            playhead += speed
            if (playhead >= frames.size) {
                // Hold on the last frame instead of exiting — the replay/clip stays active (freecam,
                // ghosts, controls) until an explicit stop. Returning null means onClientTick does
                // NOT chat "live view restored"; the still-rendered ghosts + freecam are the signal.
                playhead = (frames.size - 1).toFloat()
                held = true
            }
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

    /** An interpolated frame at the playhead, lerped between [frames] floor and ceil by the
     *  tickDelta-interpolated playhead fraction — so ghosts move smoothly between recorded ticks
     *  (esp. at 0.5×/0.25× speed) AND between client ticks. At full speed (frac≈0 at each tick) this
     *  equals [currentFrame]. Read cross-thread by [dev.iustitia.render.ReplayRenderer]. Null when
     *  inactive. Non-spatial fields (uuid/name/pose/swing) come from the ceil frame. */
    fun currentFrameLerped(tickDelta: Float): ReplayBuffer.Frame? = try {
        if (!active || frames.isEmpty()) return null
        val td = tickDelta.coerceIn(0f, 1f)
        val rh = MathHelper.lerp(td, prevPlayhead, playhead)
        val idx = rh.toInt().coerceIn(0, frames.size - 1)
        val frac = (rh - idx).coerceIn(0f, 1f)
        val a = frames[idx]
        if (idx + 1 >= frames.size || frac <= 0f) return a
        val b = frames[idx + 1]
        val byUuid = HashMap<java.util.UUID, ReplayBuffer.PlayerSnap>(b.snaps.size)
        for (s in b.snaps) byUuid[s.uuid()] = s
        val aUuids = HashSet<java.util.UUID>(a.snaps.size)
        val out = ArrayList<ReplayBuffer.PlayerSnap>(a.snaps.size + b.snaps.size)
        for (s in a.snaps) {
            aUuids.add(s.uuid())
            val t = byUuid[s.uuid()]
            out.add(if (t != null) lerpSnap(s, t, frac) else s)
        }
        for (s in b.snaps) if (s.uuid() !in aUuids) out.add(s)  // entered between ticks
        ReplayBuffer.Frame(a.tick, out)
    } catch (_: Throwable) { null }

    /** The active replay's full window (frames + alerts + any bundled terrain/chunks), for
     *  `/ius replay save`. Empty when inactive. Client-thread only (command handler). */
    fun exportWindow(): ReplayBuffer.Window = try {
        if (!active) ReplayBuffer.Window(emptyList(), emptyList(), null, null)
        else ReplayBuffer.Window(frames, alerts, terrain, chunks)
    } catch (_: Throwable) { ReplayBuffer.Window(emptyList(), emptyList(), null, null) }

    /** Lerp the spatial fields of two snaps of the same player; keep identity/discrete fields from [b]
     *  (the ceil/"current" frame). Yaw uses angle-aware lerp. */
    private fun lerpSnap(a: ReplayBuffer.PlayerSnap, b: ReplayBuffer.PlayerSnap, frac: Float): ReplayBuffer.PlayerSnap {
        val f = frac.coerceIn(0f, 1f)
        return ReplayBuffer.PlayerSnap(
            a.uuidMost, a.uuidLeast,
            MathHelper.lerp(f, a.x, b.x),
            MathHelper.lerp(f, a.y, b.y),
            MathHelper.lerp(f, a.z, b.z),
            MathHelper.lerpAngleDegrees(f, a.yaw, b.yaw),
            MathHelper.lerp(f, a.pitch, b.pitch),
            b.swingTicks, b.pose, b.name,
        )
    }

    /** The buffered alerts in this replay (for the on-screen replay HUD / future overlay). */
    fun alerts(): List<ReplayBuffer.AlertRec> = if (active) alerts else emptyList()

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

    /** The focus player's snap at the current playhead, or null (no focus / inactive / not in frame).
     *  Read cross-thread by the camera mixin (POV/FOLLOW) + the ghost renderer (skip-focus-in-POV). */
    fun focusSnap(): ReplayBuffer.PlayerSnap? = try {
        if (!active) return null
        val u = focusUuid ?: return null
        currentFrame()?.snaps?.firstOrNull { it.uuid() == u }
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
     * right, jump=+Y, sneak=−Y), sprint×1.2, noclip (no collision/gravity — spectator feel). Reads the
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
            // Acceleration: wishDir * ACCEL * (sprint ? SPRINT : 1). Vertical (jump/sneak) same accel.
            val mul = if (sprint) FC_FLY_SPRINT else 1.0
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
     * which cancels the player's own `changeLookDirection`. Mirrors vanilla
     * `Entity.changeLookDirection`'s scaling (×0.15, verified against the Zergatul FreeCam v26.2
     * reference's `onPlayerTurn`) so freecam look feels identical to vanilla mouse-look: yaw += yawDelta
     * ×0.15, pitch += pitchDelta ×0.15, pitch clamped to ±90. Client thread only (mouse-look runs on
     * the client thread). Fail-open.
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
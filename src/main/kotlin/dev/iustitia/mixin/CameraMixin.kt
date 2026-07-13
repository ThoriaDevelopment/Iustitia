package dev.iustitia.mixin

import dev.iustitia.Iustitia
import dev.iustitia.config.ConfigManager
import dev.iustitia.replay.ReplayBuffer
import dev.iustitia.replay.ReplayState
import dev.iustitia.render.OffenderCapture
import dev.iustitia.render.WatchState
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.Camera
import net.minecraft.entity.Entity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offender-selfie camera override (Phase B). For exactly ONE render frame, when
 * [OffenderCapture] has an armed request, this repoints the camera to a face-on ("selfie")
 * third-person view of the offender: positioned `SELFIE_DIST` blocks in FRONT of the offender
 * along their look direction, looking straight back at them (yaw+180, pitch 0) at eye height.
 * The framebuffer is then screenshotted at `WorldRenderEvents.END_MAIN` (see [SelfieRenderer]).
 *
 * ## Why this is safe to merge despite being a render-path WRITE
 *
 * - **Single-frame:** [OffenderCapture.pending] is consumed (nulled) here, so the override runs at
 *   most once per request. Vanilla `Camera.update` re-derives pos/rotation from the local player
 *   before our `@At("TAIL")` inject every frame; the next frame `pending` is null → this no-ops →
 *   the view reverts. The camera can never get stuck pointing at an offender.
 * - **Fail-open:** the whole body is try/caught; an exception before `setRotation`/`setPos` leaves
 *   the camera at its vanilla (player-derived) state — the worst case is a dropped selfie, never a
 *   broken render.
 * - **Read-only otherwise:** nothing else about the camera (frustum, projection, submersion,
 *   focused entity) is touched; we only call the two protected setters vanilla itself uses.
 *
 * Runtime-only-verifiable: a build cannot confirm the override lands before the world render's
 * frustum/entity culling, or that the offender ends up in-frame. If the offender is missing from
 * the shot, the likely cause is entity culling against a frustum computed before this TAIL inject.
 *
 * Target verified against yarn 1.21.11: `Camera.update(World, Entity, boolean, boolean, float)`,
 * `setRotation(float, float)` and `setPos(Vec3d)` are protected (callable here via `@Shadow`).
 */
@Mixin(Camera::class)
class CameraMixin {

    @Shadow
    protected fun setRotation(yaw: Float, pitch: Float) {}

    @Shadow
    protected fun setPos(pos: Vec3d) {}

    @Inject(method = ["update"], at = [At("TAIL")])
    private fun iustitia_selfieCamera(
        world: World,
        entity: Entity,
        thirdPerson: Boolean,
        other: Boolean,
        tickDelta: Float,
        ci: CallbackInfo,
    ) {
        try {
            // Replay camera (POV / FOLLOW) takes precedence over watch + selfie — a replay in a
            // look-driven mode owns the view while it runs. The ghosts render around the camera; the
            // hide-live mixin suppresses the live OTHER players so only ghosts show.
            if (tryReplay(tickDelta)) return
            // Watch follow-cam takes precedence over the single-frame selfie (they won't normally
            // co-occur; if they do, the sustained watch view is what the user wants on screen, and
            // the selfie's framebuffer grab — if armed — still fires at END_MAIN regardless).
            if (tryWatch(tickDelta)) return

            val pending = OffenderCapture.pending
            if (pending != null) {
                // Consume now — guarantees a single override frame even if END_MAIN never fires.
                OffenderCapture.pending = null
                if (Iustitia.tickCounter > pending.deadlineTick) {
                    // Offender never loaded within the deadline — give up, don't keep probing.
                    OffenderCapture.activeReq = null
                    OffenderCapture.frameActive = false
                    return
                }
                val mc = MinecraftClient.getInstance()
                val offender = mc.world?.getPlayerByUuid(pending.uuid)
                if (offender == null || offender.isRemoved) {
                    // Not loaded yet this frame — re-arm so we retry next frame (still single-frame
                    // per attempt; the deadline above bounds total retries).
                    OffenderCapture.pending = pending
                    return
                }
                positionSelfie(offender, tickDelta)
                OffenderCapture.activeReq = pending
                OffenderCapture.frameActive = true
            } else if (OffenderCapture.activeReq != null) {
                // Previous request's END_MAIN never fired (event missed / capture threw). The
                // camera has already reverted (pending is null, vanilla update ran first); just
                // clear the stale bookkeeping.
                OffenderCapture.activeReq = null
                OffenderCapture.frameActive = false
            }
        } catch (_: Throwable) {
            // Fail-open: leave the camera at its vanilla state.
        }
    }

    /**
     * Position the camera `SELFIE_DIST` blocks in front of [offender] (along their look yaw) at
     * eye height, looking straight back at them (yaw+180, pitch 0) for a face-on selfie. The
     * distance is clamped back toward the offender if the would-be camera position sits inside a
     * solid block (so a wall-facing offender doesn't yield a black/inside-block screenshot).
     */
    private fun positionSelfie(offender: Entity, tickDelta: Float) {
        val eye = offender.getCameraPosVec(tickDelta)
        val yawRad = offender.getYaw() * (PI / 180.0)
        // MC yaw convention: yaw 0 → +Z (south); forward = (-sin yaw, +cos yaw).
        val fwdX = -sin(yawRad)
        val fwdZ = cos(yawRad)

        val mc = MinecraftClient.getInstance()
        val w = mc.world
        var dist = SELFIE_DIST
        if (w != null) {
            // Step back toward the offender until the camera block is air (avoid wall-clipping).
            var d = SELFIE_DIST
            while (d > 0.6) {
                val bx = Math.floor(eye.x + fwdX * d).toInt()
                val by = Math.floor(eye.y).toInt()
                val bz = Math.floor(eye.z + fwdZ * d).toInt()
                if (w.getBlockState(BlockPos(bx, by, bz)).isAir) { dist = d; break }
                d -= 0.5
            }
        }
        val camPos = Vec3d(eye.x + fwdX * dist, eye.y, eye.z + fwdZ * dist)
        setRotation(offender.getYaw() + 180f, 0f)
        setPos(camPos)
    }

    /**
     * Replay camera: while [ReplayState] is active in a look-driven mode (POV / FOLLOW), drive the
     * camera from the focus ghost's buffered snap so the user sees the rewind from that player's
     * perspective (POV = their eyes) or orbiting them (FOLLOW). Returns true when it handled the
     * camera (so watch + selfie are skipped); false when replay is inactive / FREE / has no focus
     * snap this frame (→ the other branches run as before). Fail-open: a throw leaves the camera at
     * its vanilla (local-player-derived) state.
     *
     * Like watch + selfie this is a sustained TAIL override: vanilla re-derives the camera before our
     * inject every frame, so when the replay stops (or mode → FREE) the view reverts next frame —
     * the camera can never get stuck on a ghost. POV additionally forces first-person perspective
     * (saved/restored client-thread-side in [ReplayState]) so the local player's own body doesn't
     * float at its real position in the ghost's-eye view.
     */
    private fun tryReplay(tickDelta: Float): Boolean {
        try {
            if (!ReplayState.active) return false
            val mode = ReplayState.cameraMode
            if (mode == ReplayState.CameraMode.FREE) return false
            when (mode) {
                // FREECAM: pure camera-override (v1.2.0 rework, Zergatul FreeCam v26.2 reference). The
                // pose primitives on ReplayState (fcX/fcY/fcZ/fcYaw/fcPitch) are in relocated/screen
                // space, so write them verbatim with NO relocOffset shift (POV/FOLLOW shift the
                // *recorded* focus snap by relocOffset because their snaps are in recorded space;
                // FREECAM's pose is already in the space the camera writes). If freecam isn't actually
                // active (e.g. mode flipped mid-frame), return false so watch/selfie can run.
                ReplayState.CameraMode.FREECAM -> {
                    if (!ReplayState.freecamActive) return false
                    // Per-frame tickDelta interpolation prev→current (vanilla spectator camera math).
                    val td = tickDelta.coerceIn(0f, 1f)
                    val x = MathHelper.lerp(td.toDouble(), ReplayState.prevFcX, ReplayState.fcX)
                    val y = MathHelper.lerp(td.toDouble(), ReplayState.prevFcY, ReplayState.fcY)
                    val z = MathHelper.lerp(td.toDouble(), ReplayState.prevFcZ, ReplayState.fcZ)
                    val yaw = MathHelper.lerpAngleDegrees(td, ReplayState.prevFcYaw, ReplayState.fcYaw)
                    val pitch = MathHelper.lerp(td, ReplayState.prevFcPitch, ReplayState.fcPitch)
                    setRotation(yaw, pitch)
                    setPos(Vec3d(x, y, z))
                }
                ReplayState.CameraMode.POV -> {
                    val snap = ReplayState.focusSnap() ?: return false
                    positionReplayPov(snap)
                }
                ReplayState.CameraMode.FOLLOW -> {
                    val snap = ReplayState.focusSnap() ?: return false
                    positionReplayFollow(snap)
                }
            }
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    /** POV: camera at the focus ghost's eye, looking exactly where the player was looking (yaw+pitch).
     *  The eye is shifted by [ReplayState.relocOffset] so the camera follows the relocated scene (the
     *  focus ghost is at the user), not the original recorded coords. */
    private fun positionReplayPov(snap: ReplayBuffer.PlayerSnap) {
        val o = ReplayState.relocOffset ?: Vec3d.ZERO
        val eye = Vec3d(snap.x.toDouble() + o.x, snap.y.toDouble() + EYE_HEIGHT + o.y, snap.z.toDouble() + o.z)
        setRotation(snap.yaw, snap.pitch)
        setPos(eye)
    }

    /**
     * FOLLOW: orbit the focus ghost's eye (which moves through the timeline), mouse-controlled via
     * the LOCAL player's yaw (same orbit model as [positionWatch]). The camera sits [WATCH_DIST]
     * blocks behind the ghost along the player's look direction, raised [WATCH_HEIGHT] above the
     * ghost eye, always looking AT it. See [positionWatch] for the look-at math. The ghost eye is
     * shifted by [ReplayState.relocOffset] so the orbit tracks the relocated scene.
     */
    private fun positionReplayFollow(snap: ReplayBuffer.PlayerSnap) {
        val o = ReplayState.relocOffset ?: Vec3d.ZERO
        val eye = Vec3d(snap.x.toDouble() + o.x, snap.y.toDouble() + EYE_HEIGHT + o.y, snap.z.toDouble() + o.z)
        val player = MinecraftClient.getInstance().player ?: return
        val yawRad = player.getYaw() * (PI / 180.0)
        val fwdX = -sin(yawRad)
        val fwdZ = cos(yawRad)

        val camX = eye.x - fwdX * WATCH_DIST
        val camY = eye.y + WATCH_HEIGHT
        val camZ = eye.z - fwdZ * WATCH_DIST

        val dx = eye.x - camX
        val dy = eye.y - camY
        val dz = eye.z - camZ
        val horiz = sqrt(dx * dx + dz * dz)
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy.toDouble(), horiz.toDouble()))).toFloat()

        setRotation(yaw, pitch)
        setPos(Vec3d(camX, camY, camZ))
    }

    /**
     * Sustained watch follow-cam: if [WatchState] is active and the watched player is loaded,
     * position the camera on a slow auto-orbit around their eye (radius [WATCH_DIST], raised
     * [WATCH_HEIGHT] above the eye, always looking AT the offender) and return true so the
     * single-frame selfie branch is skipped. If the target is gone, auto-disable (the camera then
     * reverts to the local player next frame via vanilla's re-derive — the safe state).
     *
     * Returns false when watch is inactive (→ the selfie branch runs as before).
     */
    private fun tryWatch(tickDelta: Float): Boolean {
        try {
            if (!ConfigManager.config.watchFollowCam) return false
            if (!WatchState.active) return false
            val uuid = WatchState.targetUuid ?: return false
            val mc = MinecraftClient.getInstance()
            val offender = mc.world?.getPlayerByUuid(uuid)
            if (offender == null || offender.isRemoved) {
                // Target left the world — flag an exit for the client-thread tick loop to restore
                // the HUD/perspective state + chat (option writes stay off this render thread).
                // active goes false now → vanilla re-derives the local-player view next frame.
                WatchState.requestExit("target left — watch cancelled")
                return false
            }
            positionWatch(offender, tickDelta)
            return true
        } catch (_: Throwable) {
            // Fail-open: leave the camera at its vanilla state (→ reverts to local player).
            return false
        }
    }

    /**
     * Orbit the camera around [offender]'s eye, **mouse-controlled**: the orbit angle follows the
     * LOCAL player's yaw, so turning the mouse rotates the camera around the watched player (who
     * stays centered in frame). The camera sits `WATCH_DIST` blocks behind the offender along the
     * player's look direction, raised `WATCH_HEIGHT` above the offender's eye, always looking AT the
     * offender eye. There is NO auto-rotation — the orbit only moves when the user moves the mouse
     * (per the UX fix).
     *
     * **No wall-clamp:** the radius is fixed at [WATCH_DIST] regardless of blocks. A camera inside a
     * solid block renders nothing for that block (Minecraft culls the block the camera is in) unless
     * it is transparent, so letting the camera sit inside a wall is visually fine and, unlike a
     * radius clamp, never makes the view snap/glitch as the user orbits toward a wall. The user
     * controls the orbit angle, so a wall-side camera is their choice, not a bug to fix up.
     *
     * Look-at math (MC yaw 0 → +Z, forward = (-sin yaw, cos yaw); pitch + = down):
     *  - dir = eye − camPos; yaw = atan2(−dirX, dirZ); pitch = −atan2(dirY, horiz).
     */
    private fun positionWatch(offender: Entity, tickDelta: Float) {
        val eye = offender.getCameraPosVec(tickDelta)
        val mc = MinecraftClient.getInstance()
        val player = mc.player ?: return
        val yawRad = player.getYaw() * (PI / 180.0)
        // Behind-the-offender offset along the player's look direction (mouse-driven orbit).
        val fwdX = -sin(yawRad)
        val fwdZ = cos(yawRad)

        val camX = eye.x - fwdX * WATCH_DIST
        val camY = eye.y + WATCH_HEIGHT
        val camZ = eye.z - fwdZ * WATCH_DIST

        val dx = eye.x - camX
        val dy = eye.y - camY
        val dz = eye.z - camZ
        val horiz = sqrt(dx * dx + dz * dz)
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy.toDouble(), horiz.toDouble()))).toFloat()

        setRotation(yaw, pitch)
        setPos(Vec3d(camX, camY, camZ))
    }

    private companion object {
        /** Selfie distance in blocks (~F5 third-person). */
        private const val SELFIE_DIST = 2.5
        /** Watch orbit horizontal radius (blocks). */
        private const val WATCH_DIST = 3.5
        /** Watch camera height above the offender's eye (blocks) — slight downward look. */
        private const val WATCH_HEIGHT = 1.5
        /** Eye height above a ghost's feet (blocks) — vanilla standing eye height. */
        private const val EYE_HEIGHT = 1.62
    }
}
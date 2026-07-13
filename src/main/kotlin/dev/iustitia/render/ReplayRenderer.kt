package dev.iustitia.render

import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.replay.ReplayBuffer
import dev.iustitia.replay.ReplayState
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.VertexRendering
import net.minecraft.client.render.entity.PlayerEntityRenderer
import net.minecraft.client.render.entity.model.BipedEntityModel
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.EntityPose
import net.minecraft.text.Text
import net.minecraft.util.Arm
import net.minecraft.util.math.Box
import net.minecraft.util.math.RotationAxis
import net.minecraft.util.shape.VoxelShapes
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Instant-replay renderer (Phase 2): while [ReplayState] is active, draws a HUMANOID "ghost" of every
 * tracked player at the playhead frame's buffered position — head, torso, arms and legs as
 * pose-aware boxes (the vanilla player is itself a box model), advanced through the timeline at the
 * replay speed. One shared renderer serves `/ius replay` (a live-buffer window) and `/ius playclip`
 * (a `.iusclip` loaded into [ReplayState]).
 *
 * ## Why a humanoid model, not a single box
 *
 * The first cut drew one wireframe box per player; it read as an anonymous gray crate and you
 * couldn't tell which ghost was which user. This draws the player's actual limb breakdown so a ghost
 * reads as a person (and the pose — standing / sneaking / gliding / swimming / riptide — is visible),
 * colors each ghost by that player's cheat tier (green / yellow / red), highlights the replay focus
 * in cyan with a thicker outline + a `▶` name marker, and floats a name tag above every ghost so you
 * can read who is who. The model is drawn with the same runtime-proven
 * [VertexRendering.drawOutline] path as the target-highlight / ghost-trail overlays (depth-tested →
 * occluded by walls, no wallhack); only the boxes+labels are new.
 *
 * ## In-world ghosts
 *
 * `WorldRenderEvents.AFTER_ENTITIES` (the canonical world-space overlay phase). The view matrix
 * there is camera-rotation-only, so we translate by `-cameraPos` once and draw each ghost in world
 * coords. Color alpha is baked into the ARGB int's high byte (`drawOutline` passes its `int`
 * straight to `VertexConsumer.color(int)`; its `float` is line WIDTH, not alpha). Pose handling:
 * sneak lowers the model ~0.12 blocks; glide/swim/riptide lay it 90° forward (the body tips onto its
 * facing axis). The per-player facing nub is computed directly from the buffered yaw (guaranteed
 * correct regardless of any matrix-rotation sign ambiguity). Display-only — adds NO detection.
 *
 * ## Name tags
 *
 * A billboarded name floats above each ghost via [TextRenderer.draw] with `TextLayerType.SEE_THROUGH`
 * (always readable, even through terrain). The text is drawn with the view-rotation matrix (camera
 * rotation) after translating to the ghost's world point, which makes it screen-aligned/upright —
 * the same property that lets vanilla entity labels face the camera. Mild distance scaling keeps
 * near ghosts from blowing up and far ghosts readable. Fail-open per ghost: one bad snap never
 * kills the frame.
 *
 * ## "Rewind feel"
 *
 * [ReplayState.hideLive] (default on) hides every live OTHER player during a replay via
 * [dev.iustitia.mixin.EntityRendererMixin], so only the buffered ghosts render — a rewind-the-world
 * feel. The live game + detection keep running; rendering snaps back the instant the replay stops.
 *
 * ## Replay HUD
 *
 * A bottom-center banner (via the stable [HudRenderCallback]) shows the focus name + speed + a
 * progress bar, so a 20–40s replay has a visible "where am I in the rewind".
 *
 * Runtime-only-verifiable: a build confirms the model compiles + the matrices/colors match the
 * established overlay pattern; whether ghosts land exactly on the world, the lay-pose rotation
 * direction, and the name-tag orientation are confirmed only at runtime (all wrapped in fail-open).
 */
object ReplayRenderer {

    // Limb dimensions (blocks). Feet at origin, +Y up, +Z = body front.
    private const val LEG_H = 0.72
    private const val TORSO_H = 0.72
    private const val ARM_H = 0.72
    private const val HEAD = 0.4
    private const val HALF_W = 0.0625        // limb half-width
    private const val LIMB_D = 0.0625        // limb half-depth
    private const val TORSO_HALF = 0.25      // torso half-width (shoulders)
    private const val TORSO_HALF_D = 0.0625 // torso half-depth
    private const val LEG_OFF = 0.075        // leg center X offset from centerline
    private const val ARM_OFF = 0.3125      // arm center X offset (just outside torso)

    // Bright, full-alpha ARGB tier colors (drop the unreadable gray). Alpha in the high byte.
    private val GREEN_COLOR = 0xFF40E060.toInt()
    private val YELLOW_COLOR = 0xFFFFD020.toInt()
    private val RED_COLOR = 0xFFFF4040.toInt()
    private val FOCUS_COLOR = 0xFF40D0FF.toInt()   // cyan — distinct from all three tier colors

    private const val GHOST_WIDTH = 1.5f
    private const val FOCUS_WIDTH = 2.5f

    // Fullbright light coord for see-through text (LightmapTextureManager.MAX_LIGHT_COORDINATE).
    private const val FULL_LIGHT = 0xF000F0

    fun register() {
        try {
            WorldRenderEvents.AFTER_ENTITIES.register(WorldRenderEvents.AfterEntities { ctx ->
                try {
                    if (!ReplayState.active) return@AfterEntities
                    drawGhosts(ctx)
                } catch (_: Throwable) {
                    // fail-open: a render error never crashes the client
                }
            })
            HudRenderCallback.EVENT.register(HudRenderCallback { context, _ ->
                try { if (ReplayState.active) renderHud(context) } catch (_: Throwable) {}
            })
        } catch (_: Throwable) {}
    }

    private fun drawGhosts(ctx: WorldRenderContext) {
        val frame = ReplayState.currentFrameLerped(MinecraftClient.getInstance().renderTickCounter.getTickProgress(false)) ?: return
        if (frame.snaps.isEmpty()) return
        val mc = MinecraftClient.getInstance()
        val camera = ctx.gameRenderer().camera
        val camPos = camera.getCameraPos()
        // The AFTER_ENTITIES modelview is WORLD-space (identity rotation; the camera lives in the
        // projection), so a name tag drawn with no rotation faces a fixed compass direction and is
        // only visible from one azimuth. We billboard each tag by rotating it about world Y to face
        // the render camera. Sign: MC text quads wind clockwise viewed from +Z (signed area < 0) →
        // the readable FRONT face is −Z, so the tag faces the camera at rotation = −cameraYaw (the
        // canonical Fabric in-world text billboard). scale(-scale,-scale,scale): −Y flips glyphs
        // upright (world +Y is up, glyph +Y is down), −X mirrors glyphs so they read L→R toward the
        // camera. Text stays vertical (no pitch billboard — same as vanilla entity labels).
        val cameraYaw = try { camera.getYaw() } catch (_: Throwable) { 0f }
        val matrices = ctx.matrices()
        val vcp: VertexConsumerProvider = ctx.consumers()
        val lines = vcp.getBuffer(RenderLayers.lines())
        val tr = mc.textRenderer
        val focusUuid = ReplayState.focusUuid
        // In POV the camera sits at the focus ghost's eye — don't draw that ghost (you're inside its
        // head; rendering it would fill the view with a wireframe/model). Other ghosts still render.
        val skipFocus = ReplayState.cameraMode == ReplayState.CameraMode.POV && focusUuid != null
        // Active-alert labels for this frame: uuid → the offender's alert label, for any alert whose
        // captured tick is within ±1 of the playhead frame (so a ⚠ floats over a ghost exactly as the
        // playhead crosses the moment the cheat fired). Fail-open: an empty/missing map just draws none.
        val nowTick = frame.tick
        val alertLabelFor: Map<java.util.UUID, String> = try {
            ReplayState.alerts().filter { kotlin.math.abs(it.tick - nowTick) <= 1 }
                .associate { it.uuid() to it.label }
        } catch (_: Throwable) { emptyMap() }
        matrices.push()
        // Fold the relocation offset into the single shared origin translate: every per-snap
        // translate(s.x, s.y, s.z) below then lands the ghost at (recordedPos + offset) - camPos, i.e.
        // the whole scene shifts as a rigid block to the user (focus player's start → the user). The
        // terrain overlay draws in this same frame, so its absolute-coord quads relocate by the same
        // offset. null offset ⇒ legacy absolute rendering (relocate disabled / unavailable).
        val o = ReplayState.relocOffset ?: net.minecraft.util.math.Vec3d.ZERO
        matrices.translate(-camPos.x + o.x, -camPos.y + o.y, -camPos.z + o.z)
        try {
            // Chunk world (v6+ clip-only; null for /ius replay + pre-v6 clips). Solid, textured blocks
            // — the real captured map, face-culled to the surface shell — so the user can free-spectate
            // anywhere, including underground. Drawn first (under ghosts + the v5 wireframe terrain).
            // Skipped for a Legacy playclip (v1.1.0 = ghosts over the live world, no chunk world) —
            // belt-and-suspenders with ReplayState.start nulling chunks for Legacy.
            if (!ReplayState.legacyPlayclip) {
                try { ChunkWorldRenderer.render(matrices, vcp, camPos, o) } catch (_: Throwable) {}
            }
            // Terrain shell (clip-only; null for /ius replay). Face-culled, so only the visible
            // surface blocks draw — fully-enclosed blocks contribute nothing. Fail-open per frame.
            // Also skipped for a Legacy playclip (no terrain in v1.1.0).
            if (!ReplayState.legacyPlayclip) {
                try { TerrainOverlay.render(matrices, lines) } catch (_: Throwable) {}
            }
            for (s in frame.snaps) {
                try {
                    if (skipFocus && s.uuid() == focusUuid) continue
                    drawHumanoid(matrices, lines, vcp, tr, s, focusUuid, camPos, o, cameraYaw, alertLabelFor[s.uuid()], nowTick)
                } catch (_: Throwable) {
                    // skip one bad ghost, keep the rest
                }
            }
        } finally {
            matrices.pop()
        }
    }

    /**
     * Draw one ghost: a pose-aware humanoid wireframe + a facing nub (direct, correct yaw) + a
     * billboarded name tag. [camPos] is only used for the name-tag distance scaling. [alertLabel], when
     * non-null, floats a ⚠ marker above the ghost (the playhead just crossed an alert for this player).
     */
    private fun drawHumanoid(
        matrices: MatrixStack,
        lines: net.minecraft.client.render.VertexConsumer,
        vcp: VertexConsumerProvider,
        tr: TextRenderer,
        s: ReplayBuffer.PlayerSnap,
        focusUuid: java.util.UUID?,
        camPos: net.minecraft.util.math.Vec3d,
        offset: net.minecraft.util.math.Vec3d,
        cameraYaw: Float,
        alertLabel: String?,
        tick: Int,
    ) {
        // Offset floats for the name-tag / alert-marker distance scaling — the apparent distance must
        // be to the RELOCATED ghost (recorded + offset), not the original recorded spot (which on a
        // cross-server playclip could be far away and would blow the tag up to max scale).
        val oxF = offset.x.toFloat()
        val oyF = offset.y.toFloat()
        val ozF = offset.z.toFloat()
        val uuid = s.uuid()
        val isFocus = focusUuid != null && uuid == focusUuid
        val tier = try { FlagHistory.tierFor(uuid) } catch (_: Throwable) { FlagHistory.Tier.GREEN }
        val color = if (isFocus) FOCUS_COLOR else tierColor(tier)
        val width = if (isFocus) FOCUS_WIDTH else GHOST_WIDTH
        val laid = s.pose == ReplayBuffer.POSE_GLIDE || s.pose == ReplayBuffer.POSE_SWIM || s.pose == ReplayBuffer.POSE_RIPTIDE

        // --- real player model path (experimental, default off): drive vanilla's PlayerEntityRenderer
        //     model with a fabricated render state + the default Steve skin (no skin fetch / no
        //     network). If it draws, skip the box outline. If it returns false (toggle off, renderer
        //     unavailable, or any throw) the box outline below is the fail-open fallback. ---
        val drewModel = drawModel(matrices, vcp, s, tick)
        if (!drewModel) {
            // --- body (feet at the world point; pose applied to the local frame) ---
            matrices.push()
            try {
                matrices.translate(s.x.toDouble(), s.y.toDouble(), s.z.toDouble())
                if (s.pose == ReplayBuffer.POSE_SNEAK) matrices.translate(0.0, -0.12, 0.0)
                if (laid) matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90f))
                // legs
                box(matrices, lines, -LEG_OFF - HALF_W, 0.0, -LIMB_D, -LEG_OFF + HALF_W, LEG_H, LIMB_D, color, width)
                box(matrices, lines,  LEG_OFF - HALF_W, 0.0, -LIMB_D,  LEG_OFF + HALF_W, LEG_H, LIMB_D, color, width)
                // torso (sits on the legs)
                box(matrices, lines, -TORSO_HALF, LEG_H, -TORSO_HALF_D, TORSO_HALF, LEG_H + TORSO_H, TORSO_HALF_D, color, width)
                // arms (hang at the torso sides)
                box(matrices, lines, -ARM_OFF - HALF_W, LEG_H, -LIMB_D, -ARM_OFF + HALF_W, LEG_H + ARM_H, LIMB_D, color, width)
                box(matrices, lines,  ARM_OFF - HALF_W, LEG_H, -LIMB_D,  ARM_OFF + HALF_W, LEG_H + ARM_H, LIMB_D, color, width)
                // head (on top of the torso) — tilted by the buffered pitch around the neck pivot (top
                // of the torso) so the ghost nods to where the player was looking. Rotation sign is
                // runtime-verifiable; on any throw we draw it un-tilted (fail-open).
                val headY = LEG_H + TORSO_H
                val hh = HEAD / 2
                try {
                    matrices.push()
                    matrices.translate(0.0, headY.toDouble(), 0.0)
                    matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(s.pitch))
                    box(matrices, lines, -hh, 0.0, -hh, hh, HEAD.toDouble(), hh, color, width)
                } catch (_: Throwable) {
                    box(matrices, lines, -hh, headY, -hh, hh, headY + HEAD, hh, color, width)
                } finally {
                    matrices.pop()
                }
            } finally {
                matrices.pop()
            }

            // --- facing nub: a small cube ahead of the head along the buffered LOOK ray (yaw + pitch)
            //     so the indicator always points where the player was looking (independent of the body
            //     lay-rotation above). Computed directly from yaw+pitch in world coords. ---
            try {
                val yawRad = s.yaw * (PI / 180.0)
                val pitchRad = s.pitch * (PI / 180.0)
                val fx = -sin(yawRad)
                val fz = cos(yawRad)
                val cp = cos(pitchRad)
                val sp = sin(pitchRad)
                val nubY = if (laid) LEG_H + TORSO_H * 0.5 else LEG_H + TORSO_H + HEAD * 0.5
                val reach = 0.45
                val nx = s.x + fx * cp * reach
                val ny = s.y + nubY - sp * reach   // pitch+ (looking down) → nub drops
                val nz = s.z + fz * cp * reach
                val r = 0.07
                box(matrices, lines, nx - r, ny - r, nz - r, nx + r, ny + r, nz + r, color, width)
            } catch (_: Throwable) {}
        }

        // --- name tag (billboarded, see-through) ---
        try {
            val name = s.name
            val label = if (isFocus) Text.literal("§f▶ $name") else Text.literal(name)
            // labelY = the text's TOP in world space (after the −Y flip the glyphs extend DOWNWARD
            // from this point). The model + hat reach ~1.95 above the feet, so the baseline must sit
            // high enough that the text's bottom (labelY − ~0.25) clears the head — otherwise the tag
            // renders inside the model. Bumped up from 2.05/1.7/0.5 for that clearance.
            val labelY = if (laid) 0.95 else if (s.pose == ReplayBuffer.POSE_SNEAK) 2.15 else 2.5
            matrices.push()
            try {
                matrices.translate(s.x.toDouble(), (s.y + labelY).toDouble(), s.z.toDouble())
                val dx = (s.x + oxF) - camPos.x.toFloat()
                val dy = (s.y + labelY + oyF) - camPos.y.toFloat()
                val dz = (s.z + ozF) - camPos.z.toFloat()
                val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                val scale = (0.025f * (dist / 8f)).coerceIn(0.015f, 0.09f)
                // Billboard to face the render camera (see drawGhosts): rotate about world Y by
                // −cameraYaw so the text's −Z front faces the camera, then the canonical scale
                // (−X mirrors glyphs L→R, −Y flips them upright, +Z keeps the front toward camera).
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-cameraYaw))
                matrices.scale(-scale, -scale, scale)
                val w = tr.getWidth(label)
                val textColor = if (isFocus) FOCUS_COLOR else color
                tr.draw(
                    label, -w / 2f, 0f, textColor, true,
                    matrices.peek().getPositionMatrix(), vcp,
                    TextRenderer.TextLayerType.SEE_THROUGH, FULL_LIGHT, 0,
                )
            } finally {
                matrices.pop()
            }
        } catch (_: Throwable) {}

        // --- alert marker: when the playhead crosses an alert for this player, float a red ⚠ label
        //     above the name tag so the moment a cheat fired is visible on the ghost itself ---
        if (alertLabel != null) {
            try {
                val alert = Text.literal("§c⚠ §f$alertLabel")
                val baseY = if (laid) 0.95 else if (s.pose == ReplayBuffer.POSE_SNEAK) 2.15 else 2.5
                val ay = baseY + 0.35
                matrices.push()
                try {
                    matrices.translate(s.x.toDouble(), (s.y + ay).toDouble(), s.z.toDouble())
                    val dx = (s.x + oxF) - camPos.x.toFloat()
                    val dy = (s.y + ay + oyF) - camPos.y.toFloat()
                    val dz = (s.z + ozF) - camPos.z.toFloat()
                    val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                    val scale = (0.025f * (dist / 8f)).coerceIn(0.015f, 0.09f)
                    matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-cameraYaw))
                    matrices.scale(-scale, -scale, scale)
                    val w = tr.getWidth(alert)
                    tr.draw(
                        alert, -w / 2f, 0f, RED_COLOR, true,
                        matrices.peek().getPositionMatrix(), vcp,
                        TextRenderer.TextLayerType.SEE_THROUGH, FULL_LIGHT, 0,
                    )
                } finally {
                    matrices.pop()
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Real-player-model path (gated by [IustitiaConfig.replayPlayerModels], default on).
     *
     * Drives vanilla's [PlayerEntityRenderer] model with a hand-built [net.minecraft.client.render.entity.state.PlayerEntityRenderState]
     * and the player's REAL skin ([dev.iustitia.render.ReplaySkins.resolve] — a per-UUID cache that reads
     * the tab-list [net.minecraft.client.network.PlayerListEntry] vanilla already keeps warm; Steve/Alex
     * fallback for players not in the tab list), drawn via [net.minecraft.client.model.Model.render]
     * straight onto the overlay's [VertexConsumerProvider]. This bypasses the 1.21.11
     * `OrderedRenderCommandQueue` entity pipeline (which the world overlay context can't supply) by
     * going one level lower — the base `Model.render` still takes a plain `VertexConsumer`, exactly what
     * `ctx.consumers()` exposes (the same path the GUI skin renderer uses).
     *
     * The renderer is resolved from the local player (`mc.getEntityRenderDispatcher().getRenderer(mc.player)`)
     * — same renderer vanilla uses for every other client player, so no fabricated entity is needed. The
     * state is populated minimally for a posed, facing player at the buffered yaw + pitch (head tilts to
     * the look direction); the model's `setAngles` poses the limbs and we apply the sneak/glide body
     * transforms on the matrix (mirroring the box path).
     *
     * Returns true if the model was drawn (caller skips the box outline); false (toggle off, renderer
     * unavailable, or any throw) → caller falls back to the box outline. The matrix sequence mirrors
     * vanilla `LivingEntityRenderer.render` exactly (`scale(-1,-1,1)` + `translate(0,-1.501,0)`, no
     * `/16` — entity-model vertices are pre-divided to block units at build); pose comes from the
     * state flags + `model.setAngles`. **Runtime-only-verifiable:** the skin-layer buffer flush at
     * `AFTER_ENTITIES` can only be confirmed in a live game — if a ghost renders as just the box
     * outline with this on, the skin/layer fetch is the path to recheck. Display-only: no detection.
     */
    @Volatile
    private var playerRenderer: PlayerEntityRenderer<*>? = null

    /**
     * Per-ghost rolling walk-animation state. Vanilla drives a player's legs from a `LimbAnimator`
     * that the entity ticks; a replay ghost has no entity, so we derive the equivalent amplitude +
     * phase from the horizontal position delta between consecutive snaps. `lastX/lastZ` track the
     * previous snap (NaN sentinel = first frame for this uuid); `lastTickDist` is the per-tick
     * horizontal distance (held between ticks so the stride doesn't collapse when the snap is static
     * for the 3 frames between playhead ticks); `amp` lerps toward the clamped-speed target each
     * frame (smooth ramp up/down); `phase` advances every frame scaled by amplitude (a smooth, not
     * 20-tps-stepped, leg swing). Dist is capped so a seek/teleport never flings the stride. The
     * map survives across the replay session; cap-bounded in practice by player count.
     */
    private class WalkState {
        var lastX: Float = Float.NaN
        var lastZ: Float = Float.NaN
        var lastTick: Int = Int.MIN_VALUE
        var lastTickDist: Float = 0f
        var amp: Float = 0f
        var phase: Float = 0f
    }
    private val walkState = ConcurrentHashMap<java.util.UUID, WalkState>()

    private fun drawModel(
        matrices: MatrixStack,
        vcp: VertexConsumerProvider,
        s: ReplayBuffer.PlayerSnap,
        tick: Int,
    ): Boolean {
        if (!try { ConfigManager.config.replayPlayerModels } catch (_: Throwable) { false }) return false
        val mc = MinecraftClient.getInstance()
        val player = mc.player ?: return false
        // resolve + cache the player renderer (stable per entity type; doesn't change with world)
        val renderer = playerRenderer ?: try {
            @Suppress("UNCHECKED_CAST")
            (mc.getEntityRenderDispatcher().getRenderer(player) as? PlayerEntityRenderer<*>).also { playerRenderer = it }
        } catch (_: Throwable) { null }
        if (renderer == null) return false
        val model = try { renderer.model } catch (_: Throwable) { return false }
        // Real skin via the per-UUID cache (async-loaded by vanilla; Steve/Alex fallback). Fail-open → box.
        val skin = try { ReplaySkins.resolve(s.uuid()) } catch (_: Throwable) { return false }
        val state = try { renderer.createRenderState() } catch (_: Throwable) { return false }

        // --- populate the render state so the ghost poses, faces, walks and swings like the live
        //     player did. Field-by-field rationale (verified by disassembling vanilla 1.21.11):
        //       skin/baseScale/ageScale/visibility/light/invisible — basic posed-skin setup.
        //       hurt=false / deathTime=0 / shaking=false — explicitly suppress every damage channel.
        //         The overlay passed to Model.render is OverlayTexture.DEFAULT_UV (the transparent
        //         no-overlay row), NOT 0 — see the inline note at the render call below for why 0 was
        //         the constant-red bug. Pinning hurt/deathTime/shaking false is belt-and-suspenders.
        //       bodyYaw — setAngles does NOT read bodyYaw; the body-facing rotation lives in
        //         LivingEntityRenderer.setupTransforms (multiply POSITIVE_Y by 180-bodyYaw), which we
        //         bypass, so we apply that rotation on the matrix below. The field is set for any
        //         feature renderer that reads it.
        //       relativeHeadYaw=0 + pitch=s.pitch — the head nods to the look pitch (setAngles reads
        //         pitch); head doesn't turn independently (no separate head-yaw captured — body yaw
        //         IS the look yaw, so the whole ghost faces where the player looked).
        //       age — running time for the subtle idle arm/body sway (setAngles reads age).
        //       limbSwingAmplitude / limbSwingAnimationProgress — walk animation, derived below from
        //         the per-uuid position delta (limbAmplitudeInverse defaults to 1 in the state ctor,
        //         so the base setAngles leg math animates with no extra setup).
        //       handSwingProgress / leftArmPose / rightArmPose / mainArm / preferredArm — arm-swing
        //         (attack) animation; handSwingTicks captured per snap, arm poses EMPTY = natural hang. ---
        state.skinTextures = skin
        state.baseScale = 1f
        state.ageScale = 1f
        state.hatVisible = true; state.jacketVisible = true
        state.leftPantsLegVisible = true; state.rightPantsLegVisible = true
        state.leftSleeveVisible = true; state.rightSleeveVisible = true
        state.capeVisible = true
        state.light = FULL_LIGHT
        state.invisible = false
        state.hurt = false
        state.deathTime = 0f
        state.shaking = false
        state.bodyYaw = s.yaw
        state.relativeHeadYaw = 0f
        state.pitch = s.pitch
        state.age = tick.toFloat()
        // Walk animation from the per-uuid rolling position delta (see WalkState). The advance is
        // gated on the PLAYHEAD FRAME advancing (tick - lastTick == 1), NOT on every render frame:
        // the playhead moves one frame per replay tick at 1× (slower at 0.5/0.25×), so the legs swing
        // at the replay's tick rate. The old code did `phase += amp * 0.45` every AFTER_ENTITIES
        // frame, pumping the legs at FPS-rate (~3× too fast at 60fps, worse uncapped) — the "too
        // fast / jittery" bug — and the same limbSwingAmplitude drives the arm pump, so sprint made
        // the hands flail. Tick-gated advance is FPS-independent and scales with replay speed.
        // Dead-zone: < 0.05 b/tick = standing still / server position noise → amp→0, legs freeze
        // (kills the idle fidget). Capped dist + a seek/gap reset stop teleport/seek from flinging.
        val ws = walkState.computeIfAbsent(s.uuid()) { WalkState() }
        val tickJump = tick - ws.lastTick
        when {
            tickJump == 1 -> {
                if (!ws.lastX.isNaN()) {
                    val dx = s.x - ws.lastX
                    val dz = s.z - ws.lastZ
                    ws.lastTickDist = sqrt((dx * dx + dz * dz).toDouble()).toFloat().coerceAtMost(1.5f)
                }
                ws.lastX = s.x; ws.lastZ = s.z; ws.lastTick = tick
                val walkTarget = if (ws.lastTickDist > 0.05f) (ws.lastTickDist / 0.3f).coerceIn(0f, 1f) else 0f
                ws.amp += (walkTarget - ws.amp) * 0.3f
                // ~0.94/tick at full amp → ~0.5s stride cycle sprinting, ~1.5s strolling; cadence scales
                // with distance like vanilla. Capped dist above stops a teleport/gap fling. (0.94 = 2×
                // the original 0.47 — the tick-gated advance read as too slow, so the stride was doubled.)
                ws.phase += ws.amp * 0.94f
            }
            tickJump != 0 -> {
                // seek / gap / first frame: resync position + drop amp to 0 so the stride settles
                // instead of flinging. Phase keeps its value (resumes from mid-stride).
                ws.lastX = s.x; ws.lastZ = s.z; ws.lastTick = tick; ws.lastTickDist = 0f; ws.amp = 0f
            }
            // tickJump == 0: same playhead frame redrawn → hold the pose (no per-frame leg pump).
        }
        state.limbSwingAmplitude = ws.amp
        state.limbSwingAnimationProgress = ws.phase
        // Arm swing (attack): handSwingTicks polled from the live entity + captured per snap.
        // progress = ticks/6 ≈ vanilla getHandSwingProgress over the ~6-tick swing. Arm poses EMPTY
        // so the arms hang at the sides and swing via handSwingProgress when the player attacked.
        state.handSwingProgress = (s.swingTicks / 6f).coerceIn(0f, 1f)
        state.leftArmPose = BipedEntityModel.ArmPose.EMPTY
        state.rightArmPose = BipedEntityModel.ArmPose.EMPTY
        state.mainArm = Arm.RIGHT
        state.preferredArm = Arm.RIGHT
        when (s.pose) {
            ReplayBuffer.POSE_SNEAK -> { state.isInSneakingPose = true; state.pose = EntityPose.CROUCHING }
            ReplayBuffer.POSE_GLIDE -> { state.isGliding = true; state.pose = EntityPose.GLIDING }
            ReplayBuffer.POSE_SWIM -> { state.isSwimming = true; state.pose = EntityPose.SWIMMING }
            ReplayBuffer.POSE_RIPTIDE -> { state.usingRiptide = true; state.pose = EntityPose.SPIN_ATTACK }
            else -> state.pose = EntityPose.STANDING
        }
        // pose the model limbs from the state (head pitch, body/limb walk, arm swing, sneak/glide).
        try { model.setAngles(state) } catch (_: Throwable) { return false }

        val texture = try { skin.body().texturePath() } catch (_: Throwable) { return false }
        val layer = try { model.getLayer(texture) } catch (_: Throwable) { return false } ?: return false
        val vc = try { vcp.getBuffer(layer) } catch (_: Throwable) { return false }

        matrices.push()
        try {
            matrices.translate(s.x.toDouble(), s.y.toDouble(), s.z.toDouble())
            // Body-facing rotation — replicates vanilla LivingEntityRenderer.setupTransforms for a
            // non-sleeping entity: multiply POSITIVE_Y by (180 - bodyYaw). setupTransforms runs
            // BEFORE the -1,-1,1 flip in vanilla's render sequence, so we insert it here before the
            // flip. Without this the body never rotates to the player's yaw (setAngles doesn't read
            // bodyYaw) → the ghost always faced south regardless of where the player looked (the
            // "camera not logged" bug). Runtime-verifiable sign; fail-open per ghost.
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - s.yaw))
            // Replicate vanilla LivingEntityRenderer.render EXACTLY from here: entity-model vertices
            // are ALREADY in block units (ModelPart$Vertex divides pixel coords by 16 at build time),
            // so there is NO /16 scale; scale(-1,-1,1) flips the model out of its +Y-down definition
            // space; translate(0,-1.501,0) seats the feet at the snap origin (vanilla's constants).
            matrices.scale(-1f, -1f, 1f)
            matrices.translate(0f, -1.501f, 0f)
            // OverlayTexture: pass DEFAULT_UV (packUv(0,10) = the transparent no-overlay row).
            // Passing 0 here samples V=0 = the translucent-RED hurt row (rows 0-7 are red 0xB2FF0000),
            // which tinted EVERY ghost ~70% red all the time — the "every player always has the red" bug.
            // DEFAULT_UV is exactly what vanilla's LivingEntityRenderer uses for a non-hurt entity.
            model.render(matrices, vc, FULL_LIGHT, OverlayTexture.DEFAULT_UV)
        } catch (_: Throwable) {
            return false
        } finally {
            matrices.pop()
        }
        return true
    }

    /** Draw an axis-aligned box outline in the CURRENT matrix frame (offset 0; caller positions). */
    private fun box(
        matrices: MatrixStack,
        lines: net.minecraft.client.render.VertexConsumer,
        minx: Double, miny: Double, minz: Double,
        maxx: Double, maxy: Double, maxz: Double,
        color: Int, width: Float,
    ) {
        val shape = VoxelShapes.cuboid(Box(minx, miny, minz, maxx, maxy, maxz))
        VertexRendering.drawOutline(matrices, lines, shape, 0.0, 0.0, 0.0, color, width)
    }

    private fun tierColor(tier: FlagHistory.Tier): Int = when (tier) {
        FlagHistory.Tier.RED -> RED_COLOR
        FlagHistory.Tier.YELLOW -> YELLOW_COLOR
        FlagHistory.Tier.GREEN -> GREEN_COLOR
    }

    /** Bottom-center replay banner: focus name + speed + a progress bar. */
    private fun renderHud(context: DrawContext) {
        val mc = MinecraftClient.getInstance()
        if (mc.currentScreen != null) return
        val tr = mc.textRenderer ?: return
        val speed = ReplayState.currentSpeed()
        val paused = ReplayState.isPaused()
        val focus = ReplayState.focusName() ?: "scene"
        val pauseTxt = if (paused) "§e⏸ " else ""
        val line = Text.literal("§8[§diustitia§8] §6▶ §fReplay §e$focus §7$pauseTxt§7${"%.2f".format(speed)}×")
        val padX = 6
        val sw = mc.getWindow().scaledWidth
        val sh = mc.getWindow().scaledHeight
        val lineW = tr.getWidth(line)
        val barW = 160
        val panelW = maxOf(lineW, barW) + padX * 2
        val x = (sw - panelW) / 2
        val y = sh - 30
        context.fill(x, y, x + panelW, y + 22, BG)
        context.drawTextWithShadow(tr, line, x + padX, y + 3, WHITE)
        // Progress bar.
        val bx = x + padX
        val by = y + 15
        context.fill(bx, by, bx + barW, by + 4, BAR_BG)
        val prog = ReplayState.progress()
        if (prog > 0f) context.fill(bx, by, bx + (barW * prog).toInt().coerceIn(0, barW), by + 4, BAR_FG)
        // Alert markers on the bar: one tick per captured alert, colored by the offender's tier,
        // positioned where the alert sits in the window. Capped so a busy replay doesn't paint a
        // solid bar. Fail-open: a bad marker never breaks the HUD.
        try {
            val range = ReplayState.windowTickRange()
            val alerts = ReplayState.alerts()
            if (range != null && alerts.isNotEmpty()) {
                val span = (range.last - range.first).coerceAtLeast(1)
                val cap = 40
                var drawn = 0
                for (a in alerts) {
                    if (drawn >= cap) break
                    val frac = ((a.tick - range.first).toFloat() / span).coerceIn(0f, 1f)
                    val mx = bx + (barW * frac).toInt().coerceIn(0, barW)
                    val tier = try { FlagHistory.tierFor(a.uuid()) } catch (_: Throwable) { FlagHistory.Tier.GREEN }
                    val col = when (tier) { FlagHistory.Tier.RED -> RED_COLOR; FlagHistory.Tier.YELLOW -> YELLOW_COLOR; FlagHistory.Tier.GREEN -> GREEN_COLOR }
                    context.fill(mx, by - 2, mx + 1, by + 6, col)
                    drawn++
                }
            }
        } catch (_: Throwable) {}
    }

    private const val WHITE = -1
    private val BG = 0x90000000.toInt()
    private val BAR_BG = 0x50000000.toInt()
    private val BAR_FG = 0xC0FFE070.toInt()
}
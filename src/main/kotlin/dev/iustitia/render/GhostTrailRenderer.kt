package dev.iustitia.render

import dev.iustitia.Iustitia
import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.OtherClientPlayerEntity
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.VertexRendering
import net.minecraft.entity.Entity
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.util.shape.VoxelShapes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Ghost trail (Phase B): a fading breadcrumb trail of recent positions for suspect OTHER
 * players, drawn on the world. Render-only, fail-open, depth-tested (the lines layer writes
 * depth, so trails are occluded by intervening walls — no wallhack).
 *
 * Trails only YELLOW/RED suspects — clean (GREEN) players are never trailed, consistent with
 * the target-highlight + nametag philosophy. A player that drops back to GREEN has its trail
 * cleared on the next sample tick (no stale breadcrumbs linger).
 *
 * ## Sampling
 *
 * Each [Iustitia.SAMPLE_EVERY] ticks (client tick thread) we snapshot each present suspect's
 * feet position into a per-uuid deque capped at [MAX_POINTS]. Sampling is throttled (not every
 * tick) so consecutive samples are far enough apart to read as a trail rather than a clump, and
 * it bounds the deque length. A player not in the world (logged out / out of render) is pruned.
 *
 * ## Drawing
 *
 * `WorldRenderEvents.AFTER_ENTITIES` (render thread, after entities drawn — the canonical
 * world-space overlay phase). `WorldRenderContext.matrices()` at this phase is the
 * camera-rotation-only view matrix (vanilla applies camera TRANSLATION per-vertex via the
 * camera-pos offset it passes to its own outline draws), so we translate by `-cameraPos` once
 * then draw each world-coord breadcrumb with its position as the `drawOutline` offset — the
 * identical, runtime-proven path used by [TargetHighlightRenderer].
 *
 * ## Color / fade
 *
 * `VertexRendering.drawOutline` passes its `int color` straight to `VertexConsumer.color(int)`
 * as ARGB, and its `float` param is line WIDTH (not alpha). So the fade is baked into the ARGB
 * int's high byte: newest point ≈ full alpha, oldest ≈ 0x26. The float width is held at 1.0.
 *
 * Runtime-only-verifiable: a build cannot confirm the trail lands on the world / fades as
 * expected (depends on the matrices-rotation-only + ARGB-color assumptions, established by the
 * highlight's live success). All fail-open: a render error just skips the frame.
 */
object GhostTrailRenderer {

    private const val MAX_POINTS = 12
    private const val MAX_TRAILED = 12

    /** Feet-anchored breadcrumb box: 0.2³ sitting on the ground at the sample position. */
    private val BOX = VoxelShapes.cuboid(Box(-0.1, 0.0, -0.1, 0.1, 0.2, 0.1))

    /** uuid → recent positions, newest last. */
    private val trails: ConcurrentHashMap<java.util.UUID, ConcurrentLinkedDeque<Vec3d>> =
        ConcurrentHashMap()

    fun register() {
        try {
            ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick {
                try {
                    if (!ConfigManager.config.ghostTrail) return@EndTick
                    sample()
                } catch (_: Throwable) {
                    // fail-open: a sampling error never crashes the tick loop
                }
            })

            WorldRenderEvents.AFTER_ENTITIES.register(WorldRenderEvents.AfterEntities { ctx ->
                try {
                    if (!ConfigManager.config.ghostTrail) return@AfterEntities
                    draw(ctx)
                } catch (_: Throwable) {
                    // fail-open: a render error never crashes the client
                }
            })
        } catch (_: Throwable) {}
    }

    /** Clears all trails — wired to [dev.iustitia.Iustitia.resetAll] so a session reset empties them. */
    fun reset() {
        try { trails.clear() } catch (_: Throwable) {}
    }

    private fun sample() {
        val mc = MinecraftClient.getInstance()
        val world = mc.world ?: return
        val self = mc.player
        val selfUuid = self?.uuid

        // Prune trails for players no longer present (logged out / out of render) OR now clean
        // (GREEN) — stale breadcrumbs must not linger after a player stops being a suspect.
        val pruned = mutableSetOf<java.util.UUID>()
        for (uuid in trails.keys) {
            val present = try { world.getPlayerByUuid(uuid) != null } catch (_: Throwable) { false }
            if (!present) {
                pruned.add(uuid); continue
            }
            if (FlagHistory.tierFor(uuid) == FlagHistory.Tier.GREEN) pruned.add(uuid)
        }
        for (uuid in pruned) trails.remove(uuid)

        if (Iustitia.tickCounter % Iustitia.SAMPLE_EVERY != 0) return

        // Soft-cap total trailed suspects to bound memory on a crowded server: once at the cap,
        // only extend EXISTING trails (don't start new ones) so a fresh cheater on a full server
        // doesn't unbounded the map. Existing trails naturally free as players leave/go-green.
        val atCap = trails.size >= MAX_TRAILED
        for (entity in world.players) {
            if (entity !is OtherClientPlayerEntity) continue
            if (entity.uuid == selfUuid) continue
            if (entity.isRemoved) continue
            if (FlagHistory.tierFor(entity.uuid) == FlagHistory.Tier.GREEN) continue
            sampleOne(entity, atCap)
        }
    }

    private fun sampleOne(entity: Entity, onlyExisting: Boolean) {
        val uuid = entity.uuid
        val pos = feetOf(entity)
        val deque = trails[uuid]
        if (deque != null) {
            deque.addLast(pos)
            while (deque.size > MAX_POINTS) deque.pollFirst()
            return
        }
        if (onlyExisting) return
        val fresh = ConcurrentLinkedDeque<Vec3d>()
        fresh.addLast(pos)
        val race = trails.putIfAbsent(uuid, fresh)
        // If a concurrent sampler won the race, extend the winner instead of duplicating.
        if (race != null) {
            race.addLast(pos)
            while (race.size > MAX_POINTS) race.pollFirst()
        }
    }

    /**
     * Feet position of an entity. 1.21.11 dropped `Entity.getPos()` (only a private `pos` field
     * remains), so we derive x/z center + y floor from the public bounding box — which is also
     * what the target-highlight uses, and is render-tick independent (no tickDelta needed).
     */
    private fun feetOf(entity: Entity): Vec3d {
        val b = entity.getBoundingBox()
        return Vec3d((b.minX + b.maxX) * 0.5, b.minY, (b.minZ + b.maxZ) * 0.5)
    }

    private fun draw(ctx: WorldRenderContext) {
        if (trails.isEmpty()) return
        val mc = MinecraftClient.getInstance()
        val camPos = ctx.gameRenderer().camera.getCameraPos()
        val matrices = ctx.matrices()
        val lines = ctx.consumers().getBuffer(RenderLayers.lines())
        matrices.push()
        matrices.translate(-camPos.x, -camPos.y, -camPos.z)
        try {
            for ((uuid, deque) in trails) {
                val tier = FlagHistory.tierFor(uuid)
                // Safety: skip if the player reverted to green mid-frame (a stale entry not yet pruned).
                if (tier == FlagHistory.Tier.GREEN) continue
                val rgb = if (tier == FlagHistory.Tier.RED) RED_RGB else YELLOW_RGB
                val pts = deque.toList()
                val n = pts.size
                if (n == 0) continue
                for (i in pts.indices) {
                    // newest (i = n-1) → alpha 0xFF; oldest (i = 0) → 0x26.
                    val frac = if (n <= 1) 1.0f else i.toFloat() / (n - 1)
                    val alpha = (0x26 + (0xFF - 0x26) * frac).toInt().coerceIn(0x00, 0xFF)
                    val argb = (alpha shl 24) or rgb
                    val p = pts[i]
                    VertexRendering.drawOutline(matrices, lines, BOX, p.x, p.y, p.z, argb, 1.0f)
                }
            }
        } finally {
            matrices.pop()
        }
    }
}

// Tier RGB (24-bit) — combined with a per-point alpha byte at draw time → ARGB.
private const val RED_RGB = 0x00FF3030
private const val YELLOW_RGB = 0x00FFFF30
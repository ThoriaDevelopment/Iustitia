package dev.iustitia.mixin

import dev.iustitia.Iustitia
import dev.iustitia.compat.CompanionMods
import dev.iustitia.config.ConfigManager
import dev.iustitia.replay.BlockDeltaBuffer
import dev.iustitia.replay.ChunkCapture
import dev.iustitia.replay.RecordManager
import net.minecraft.block.BlockState
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.BlockPos
import net.minecraft.world.chunk.WorldChunk
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/**
 * Block-change observer (read-only) for replay/clip world edits. Ported from SnapClip.
 *
 * `WorldChunk.setBlockState` is the single funnel for every block change this client applies — an
 * incoming block-update packet from the server, a chunk load, or a local prediction. Injecting at
 * `HEAD` and diffing the old vs new state records `(tick, pos, before, after)` into
 * [BlockDeltaBuffer] (and into an active `/ius record` via [RecordManager.recordDelta]), so a replay
 * or clip can play back the world **as it changed** — a wall broken mid-fight, a temporary block —
 * on top of the captured chunk snapshot.
 *
 * ## Not a send path, not a mutation
 *
 * The inject does not cancel or modify the vanilla call; it only reads `world.getBlockState(pos)`
 * (the old state) and the incoming `state` argument. No packet is sent, no block is placed by us —
 * we observe the client's own block updates. Everything is fail-open (a throw leaves vanilla
 * untouched).
 *
 * ## Gates
 *
 * Skipped entirely while SnapClip is installed (it owns replay/clip and has its own equivalent hook —
 * see [CompanionMods.snapClip]) and while replay capture is off, so the per-change work is paid only
 * when Iustitia actually owns and records replay. The bridge is the line before vanilla's own write:
 * `getBlockState` still returns the pre-change state at `HEAD`.
 */
@Mixin(WorldChunk::class)
class WorldChunkMixin {

    @Inject(method = ["setBlockState"], at = [At("HEAD")])
    private fun iustitia_onSetBlockState(
        pos: BlockPos, state: BlockState, flags: Int, cir: CallbackInfoReturnable<BlockState>,
    ) {
        try {
            if (CompanionMods.snapClip) return
            if (!ConfigManager.config.replayCapture) return
            val world = MinecraftClient.getInstance().world ?: return
            val tick = Iustitia.tickCounter
            val before = try { ChunkCapture.stateKey(world.getBlockState(pos)) } catch (_: Throwable) { "minecraft:air" }
            val after = try { ChunkCapture.stateKey(state) } catch (_: Throwable) { "minecraft:air" }
            BlockDeltaBuffer.record(tick, pos.x, pos.y, pos.z, before, after)
            try { RecordManager.recordDelta(tick, pos.x, pos.y, pos.z, before, after) } catch (_: Throwable) {}
        } catch (_: Throwable) {
            // fail-open: observation must never affect the block update
        }
    }
}

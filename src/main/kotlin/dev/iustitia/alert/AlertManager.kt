package dev.iustitia.alert

import dev.iustitia.Iustitia
import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.tracking.EntityTrackerManager
import net.minecraft.client.MinecraftClient
import net.minecraft.sound.SoundEvents
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes violations to the local chat hud with per-(player, check) throttling and a join-grace
 * suppression. **Local-only**: writes straight to [net.minecraft.client.gui.hud.ChatHud.addMessage]
 * — no packet is ever sent to the server. Fail-open: any error here must not propagate.
 *
 * Phase 2 pipeline: alerts are buffered per player and flushed either immediately (when batching is
 * off — the legacy one-line-per-alert behavior) or after a quiet window ([IustitiaConfig.alertBatchWindowTicks],
 * driven from [Iustitia.onClientTick]). On flush, the batch is (1) lag-softened (a `[lag]` prefix
 * and, under the quiet preset, a non-red drop during a server-lag burst), (2) preset-gated by
 * [IustitiaConfig.alertLevel] (quiet=red only / normal=orange+red / verbose=all — display-only), (3)
 * formatted (collapsed "Reach ×4, Backtrack ×2 (last 5s)" or single, compact when enabled), (4) sent
 * to chat, and (5) optionally an audio cue plays (yellow/red/nuclear). Mute (`/ius alerts`) and the
 * global chat-alerts switch suppress only the chat send — detection, [FlagHistory] tier/history and
 * the nametag prefix keep reflecting the player. So [FlagHistory.recordAlert] is called AFTER the
 * throttle passes (a real alert event) but BEFORE the mute/preset checks return, meaning a muted or
 * preset-dropped check still tiers a player up; only the chat/audio is silenced.
 */
object AlertManager {

    private val lastAlertTick = ConcurrentHashMap<String, Int>()

    private class CountEntry(var count: Int, var vl: Double, var setbackVL: Double, var label: String)
    private class AlertBatch(
        val name: String, var firstTick: Int, var lastTick: Int,
        val checks: java.util.LinkedHashMap<String, CountEntry> = java.util.LinkedHashMap(),
        val primaries: java.util.HashSet<String> = java.util.HashSet(),
    )

    private val batches = ConcurrentHashMap<UUID, AlertBatch>()

    /**
     * Emit an alert for [player] / [check] unless throttled or within join-grace. [setbackVL] drives
     * the severity color band. Buffers the alert into the player's batch; flushes immediately when
     * batching is off.
     */
    fun alert(
        name: String,
        check: String,
        vl: Double,
        player: UUID,
        checkId: String,
        tick: Int,
        joinTick: Int,
        setbackVL: Double,
    ) {
        try {
            val cfg = ConfigManager.config
            if (!cfg.enabled) return
            // join-grace: no alerts for the first joinGraceTicks of a player's session
            if (tick - joinTick < cfg.joinGraceTicks) return
            // throttle per (player, check) — a real alert event
            val key = "$player|$checkId"
            val last = lastAlertTick[key] ?: -100000
            if (tick - last < cfg.alertThrottleTicks) return
            lastAlertTick[key] = tick

            // Tier/history always update (mute/preset are chat-suppression only, not detection).
            FlagHistory.recordAlert(player, checkId, name, tick)

            // Phase B burst-spark: on a fresh tier-relevant alert (the ones that move the tier),
            // spawn a tier-colored particle burst at the offender's eye. Tier-neutral alerts
            // (noFall / speedEnvelope / rotationTracking / minor signals) don't burst — they don't
            // mark a player suspect and a spark would imply they do. Fail-open inside.
            if (checkId in FlagHistory.DEFINITIVE || checkId in FlagHistory.CORROBORATOR) {
                try {
                    dev.iustitia.render.BurstSparks.fire(player, FlagHistory.tierFor(player))
                } catch (_: Throwable) {}
            }

            // Buffer into the per-player batch.
            val batch = batches.computeIfAbsent(player) { AlertBatch(name, tick, tick) }
            synchronized(batch) {
                batch.lastTick = tick
                val e = batch.checks.getOrPut(checkId) { CountEntry(0, vl, setbackVL, check) }
                e.count += 1
                if (vl > e.vl) e.vl = vl
                e.setbackVL = setbackVL
                e.label = check
                if (checkId in FlagHistory.DEFINITIVE) batch.primaries.add(checkId)
            }

            if (!cfg.alertBatching) flushBatch(player, tick)
        } catch (_: Throwable) {
            // fail-open: alerting must never crash
        }
    }

    /**
     * Flush a player's batch to chat + audio. Called immediately when batching is off, and from
     * [tickFlush] when a batch's quiet window (or max age) elapses. Fail-open.
     */
    fun flushBatch(uuid: UUID, tick: Int) {
        try {
            val cfg = ConfigManager.config
            val batch = batches.remove(uuid) ?: return
            synchronized(batch) {
                if (batch.checks.isEmpty()) return
                // 1. global mute / per-player mute → no chat (detection/tier already recorded).
                if (!cfg.alertsEnabled) return
                if (uuid.toString() in cfg.mutedPlayers) return
                // per-check mute: drop muted checks from the batch line; if all muted, no line.
                val visible = batch.checks.filterKeys { it !in cfg.mutedChecks }
                if (visible.isEmpty()) return

                // 2. worst severity band across the visible checks (the worst drives gate + color).
                var worstBand = 0
                for ((_, e) in visible) {
                    val b = AlertFormatter.band(e.vl, e.setbackVL)
                    if (b > worstBand) worstBand = b
                }

                // 3. lag-soften: during a server-lag burst, prefix [lag]; under quiet, drop non-red.
                val lagging = cfg.lagSuppressAlerts && isLagging(tick)
                if (lagging && cfg.alertLevel == 0 && worstBand < 2) return

                // 4. preset gate (display-only): quiet=red only, normal=orange+red, verbose=all.
                if (cfg.alertLevel == 0 && worstBand < 2) return
                if (cfg.alertLevel == 1 && worstBand < 1) return

                // 5. format + send.
                val parts = visible.entries.map { (cid, e) ->
                    if (e.count <= 1) e.label else "${e.label} ×${e.count}"
                }
                val windowSec = ((tick - batch.firstTick).coerceAtLeast(0) / 20).coerceAtLeast(1)
                val single = visible.size == 1
                val text = if (single && !lagging) {
                    if (cfg.compactMode) {
                        val e = visible.values.first()
                        AlertFormatter.formatCompact(batch.name, e.label, e.vl, e.setbackVL, uuid, visible.keys.first())
                    } else {
                        val e = visible.values.first()
                        AlertFormatter.format(batch.name, e.label, e.vl, e.setbackVL, uuid, visible.keys.first())
                    }
                } else {
                    AlertFormatter.formatBatch(batch.name, parts, worstBand, windowSec, lagging, cfg.compactMode, uuid)
                }
                val client = MinecraftClient.getInstance()
                client.execute { client.inGameHud?.chatHud?.addMessage(text) }

                // 6. audio cue (gated by the same mute/preset rules — a muted/preset-dropped alert
                //    is silent too).
                if (cfg.audioCues) playCue(uuid, batch.primaries.size)
            }
        } catch (_: Throwable) {
            // fail-open
        }
    }

    /** Driven from [Iustitia.onClientTick]: flush batches whose quiet window OR max age elapsed. */
    fun tickFlush(tick: Int) {
        try {
            val cfg = ConfigManager.config
            if (!cfg.alertBatching) return
            val window = cfg.alertBatchWindowTicks
            val toFlush = ArrayList<UUID>()
            for ((uuid, batch) in batches) {
                synchronized(batch) {
                    if (tick - batch.lastTick >= window || tick - batch.firstTick >= window) toFlush += uuid
                }
            }
            for (uuid in toFlush) flushBatch(uuid, tick)
        } catch (_: Throwable) {
            // fail-open
        }
    }

    private fun isLagging(tick: Int): Boolean = try {
        tick - EntityTrackerManager.lastServerLagTick <= 8 ||
            tick - EntityTrackerManager.lastLagBurstTick <= 3
    } catch (_: Throwable) { false }

    private fun playCue(uuid: UUID, primaryCount: Int) {
        try {
            val cfg = ConfigManager.config
            val tier = FlagHistory.tierFor(uuid)
            val nuclear = cfg.audioNuclear && tier == FlagHistory.Tier.RED && primaryCount >= 2
            val vol = cfg.audioVolume.toFloat()
            val client = MinecraftClient.getInstance()
            client.execute {
                try {
                    val player = client.player ?: return@execute
                    if (nuclear) {
                        player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), vol, 0.6f)
                        player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), vol, 0.8f)
                    } else when (tier) {
                        FlagHistory.Tier.RED -> player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), vol, 0.8f)
                        FlagHistory.Tier.YELLOW -> player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), vol, 1.2f)
                        FlagHistory.Tier.GREEN -> player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_HARP.value(), vol * 0.6f, 1.0f)
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun reset() {
        try { lastAlertTick.clear(); batches.clear() } catch (_: Throwable) {}
    }
}
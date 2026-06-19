package dev.iustitia.alert

import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import net.minecraft.client.MinecraftClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes violations to the local chat hud with per-(player, check) throttling and a
 * join-grace suppression. **Local-only**: writes straight to [ChatHud.addMessage] —
 * no packet is ever sent to the server. Fail-open: any error here must not propagate.
 *
 * Mute (`/ius alerts`) suppresses only the chat send — detection, [FlagHistory] tier/history
 * and the nametag prefix keep reflecting the player. So [FlagHistory.recordAlert] is called
 * AFTER the throttle passes (a real alert event) but BEFORE the mute check returns, meaning a
 * muted check still tiers a player up; only the chat noise is silenced.
 */
object AlertManager {

    private val lastAlertTick = ConcurrentHashMap<String, Int>()

    /**
     * Emit an alert for [player] / [check] unless throttled or within join-grace.
     * [setbackVL] drives the severity color band.
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

            // Tier/history always update (mute is chat-suppression only, not detection-suppression).
            FlagHistory.recordAlert(player, checkId, name, tick)

            // global chat-alerts mute (`/ius alerts` with no arg): silence all chat lines, keep
            // detection + tiering + nametag running.
            if (!cfg.alertsEnabled) return

            // mute: per-check or per-player — suppress the chat line only
            if (checkId in cfg.mutedChecks || player.toString() in cfg.mutedPlayers) return

            val text = AlertFormatter.format(name, check, vl, setbackVL, player, checkId)
            val client = MinecraftClient.getInstance()
            client.execute { client.inGameHud?.chatHud?.addMessage(text) }
        } catch (_: Throwable) {
            // fail-open: alerting must never crash
        }
    }

    fun reset() {
        try { lastAlertTick.clear() } catch (_: Throwable) {}
    }
}
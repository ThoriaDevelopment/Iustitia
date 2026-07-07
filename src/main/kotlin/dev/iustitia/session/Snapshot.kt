package dev.iustitia.session

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.iustitia.Iustitia
import dev.iustitia.history.FlagHistory
import dev.iustitia.persistence.PersistenceManager
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.Framebuffer
import net.minecraft.client.util.ScreenshotRecorder
import net.minecraft.text.Text
import java.util.UUID
import java.util.function.Consumer

/**
 * The one-click evidence snapshot (Phase 2 #3, non-render half): posts a one-line summary to chat,
 * copies it to the clipboard, and — when persistence is on — writes a small `.json` to
 * `%APPDATA%/.iustitia/snapshots/`. The screenshot + target-highlight overlay is a deferred Phase B
 * render piece. Fail-open, local-only (clipboard + chat are client-side; nothing is sent upstream).
 */
object Snapshot {

    private val GSON = GsonBuilder().setPrettyPrinting().create()

    /** Build the one-line chat/clipboard summary: `[Iustitia] PlayerX: Reach 4.2 | Tier: RED [87]`. */
    fun buildSummary(uuid: UUID, name: String): String = try {
        val tier = FlagHistory.tierFor(uuid)
        val tierName = tier.label
        val score = FlagHistory.confidenceScore(uuid)
        val counts = FlagHistory.flagCounts(uuid)
        val top = if (counts.isEmpty()) "no flags" else counts.entries.take(3).joinToString(", ") {
            (if (it.value > 1) "${it.key} ×${it.value}" else it.key)
        }
        val maxVl = (FlagHistory.maxVlByCheck(uuid).values.maxOrNull() ?: 0.0)
        "[Iustitia] $name: $top | max vl ${String.format(java.util.Locale.US, "%.1f", maxVl)} | Tier: $tierName [$score]"
    } catch (_: Throwable) { "[Iustitia] $name: (snapshot unavailable)" }

    /** Build the small JSON snapshot (tier, score, confidence, last flags with evidence). Built with
     *  Gson so every string field (name, confidence, checkId, label) is fully escaped — the old
     *  hand-rolled StringBuilder only escaped `"` and `\` (and not at all for checkId), so a player
     *  name / label containing a newline or control char produced invalid JSON. */
    fun buildJson(uuid: UUID, name: String): String = try {
        val tier = FlagHistory.tierFor(uuid)
        val tierName = tier.label
        val obj = JsonObject()
        obj.addProperty("name", name)
        obj.addProperty("uuid", uuid.toString())
        obj.addProperty("tick", Iustitia.tickCounter)
        obj.addProperty("tier", tierName)
        obj.addProperty("score", FlagHistory.confidenceScore(uuid))
        obj.addProperty("confidence", FlagHistory.confidenceExplanation(uuid))
        obj.addProperty("alerts", FlagHistory.sessionAlertCount(uuid))
        val flags = FlagHistory.flags(uuid).take(20)
        val arr = JsonArray()
        for (f in flags) {
            val fo = JsonObject()
            fo.addProperty("tick", f.tick)
            fo.addProperty("check", f.checkId)
            fo.addProperty("label", f.label)
            fo.addProperty("vl", String.format(java.util.Locale.US, "%.2f", f.vl).toDouble())
            arr.add(fo)
        }
        obj.add("flags", arr)
        GSON.toJson(obj)
    } catch (_: Throwable) { "{}" }

    /** Full capture: chat line + clipboard + (if persistence on) json file + PNG screenshot.
     *  Returns the summary. */
    fun capture(uuid: UUID, name: String): String {
        val summary = buildSummary(uuid, name)
        try {
            val client = MinecraftClient.getInstance()
            client.execute {
                try {
                    client.inGameHud?.chatHud?.addMessage(Text.literal(summary))
                    try { client.keyboard.setClipboard(summary) } catch (_: Throwable) {}
                } catch (_: Throwable) {}
            }
            try { PersistenceManager.saveSnapshot(name, buildJson(uuid, name)) } catch (_: Throwable) {}
            try { captureScreenshot(name, uuid) } catch (_: Throwable) {}
        } catch (_: Throwable) {}
        return summary
    }

    /**
     * Capture a screenshot to `snapshots/<tick>_<name>.png` (the Phase B render half of the
     * evidence snapshot). The `int` arg to [ScreenshotRecorder] is the resolution multiplier
     * (1 = full-res, matching vanilla F2). Vanilla uses the filename verbatim (no auto-extension)
     * when it's non-null, so the `.png` MUST be included or Windows won't treat it as an image.
     *
     * **Offender selfie:** when [offenderUuid] is given (the normal case — [capture] passes the
     * snapshotted player), this does NOT grab the local framebuffer immediately. Instead it arms a
     * one-frame camera override ([dev.iustitia.render.OffenderCapture]) that repoints the camera
     * to a face-on view of the offender; the actual PNG is grabbed at `WorldRenderEvents.END_MAIN`
     * the next frame by [dev.iustitia.render.SelfieRenderer]. With no target it falls back to an
     * immediate local-POV capture (vanilla F2 angle) on the render thread.
     *
     * The save's [Consumer] callback receives the "saved <file>" / error [Text]; we echo it to chat
     * so the user gets a clickable file link. Fail-open: any GL/IO/camera failure is swallowed —
     * the chat, clipboard, and `.json` summary from [capture] still land. A build cannot verify the
     * framebuffer/camera behavior (GL-state-dependent); runtime-test only. No-op when persistence
     * is off.
     */
    fun captureScreenshot(name: String, offenderUuid: UUID? = null) {
        try {
            val client = MinecraftClient.getInstance()
            val dir = PersistenceManager.screenshotsDirFile() ?: return
            val tick = Iustitia.tickCounter
            val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val filename = "${tick}_${safe}.png"

            if (offenderUuid != null) {
                // Selfie: arm a one-frame camera override; capture happens at END_MAIN next frame.
                dev.iustitia.render.OffenderCapture.arm(dev.iustitia.render.OffenderCapture.Req(
                    uuid = offenderUuid, name = name, dir = dir, file = filename,
                    deadlineTick = tick + 40,
                ))
                return
            }
            // No target → immediate local-POV capture (vanilla F2 angle) on the render thread.
            val fb: Framebuffer? = client.framebuffer
            if (fb == null) return
            client.execute {
                try {
                    ScreenshotRecorder.saveScreenshot(
                        dir, filename, fb, 1,
                        Consumer { msg -> try { client.inGameHud?.chatHud?.addMessage(msg) } catch (_: Throwable) {} }
                    )
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }
}
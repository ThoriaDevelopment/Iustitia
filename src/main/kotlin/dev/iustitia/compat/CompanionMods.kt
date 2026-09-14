package dev.iustitia.compat

import net.fabricmc.loader.api.FabricLoader

/**
 * Companion-mod detection + feature yielding.
 *
 * SnapClip (`snapclip`), Scrollback (`scrollback`), and FollowCam (`followcam`) are single-feature
 * extracts of Iustitia. When one is installed alongside Iustitia the two would otherwise run the
 * SAME machinery twice — two rolling capture buffers, two replay cameras fighting over
 * `Camera.update`, two input-suppression paths, two chat-history stores — and would also fight over
 * the same default keybinds. Iustitia therefore YIELDS the overlapping feature to the standalone
 * mod: it stops capturing / stops starting its own playback / stops capturing chat, and lets the
 * companion own it.
 *
 * Core detection, alerts, overlays, evidence tooling, and every non-overlapping feature keep running
 * unchanged — only the duplicated moderation feature yields.
 *
 * ## Mixin-level conflict
 *
 * Yielding at the feature level is not enough for SnapClip: Iustitia's [dev.iustitia.mixin.GameRendererMixin]
 * and [dev.iustitia.mixin.WorldRendererMixin] `@Redirect` the **exact same** call sites SnapClip
 * redirects, and two `@Redirect`s on one instruction is a hard mixin-apply failure. Those two mixins
 * are therefore skipped entirely when SnapClip is present — see
 * [dev.iustitia.mixin.IustitiaMixinPlugin], which consults [snapClip] at mixin-config load time.
 *
 * Detection is a one-shot `isModLoaded` lookup, cached after first read. Fail-open: any error reads
 * as "not present", which leaves Iustitia's own feature enabled — always the safe default.
 */
object CompanionMods {

    /** SnapClip owns replay / clip / playclip / record. */
    val snapClip: Boolean by lazy { loaded("snapclip") }

    /** Scrollback owns per-player chat history (`/ius chathist`). */
    val scrollback: Boolean by lazy { loaded("scrollback") }

    /** FollowCam owns the watch follow-cam (`/ius spectate`). */
    val followCam: Boolean by lazy { loaded("followcam") }

    /** True when at least one companion owns an Iustitia feature. */
    val any: Boolean get() = snapClip || scrollback || followCam

    /**
     * Human-readable list of the features Iustitia is yielding, or null when nothing is installed.
     * Used for the one-line startup notice so a user who installed both knows why a command deferred.
     */
    fun yieldSummary(): String? {
        val yielded = ArrayList<String>(3)
        if (snapClip) yielded += "replay/clip/record → SnapClip"
        if (scrollback) yielded += "chat history → Scrollback"
        if (followCam) yielded += "follow follow-cam → FollowCam"
        return if (yielded.isEmpty()) null else yielded.joinToString(", ")
    }

    private fun loaded(id: String): Boolean = try {
        FabricLoader.getInstance().isModLoaded(id)
    } catch (_: Throwable) {
        false
    }
}

package dev.iustitia.info

/**
 * One-paragraph explanations for the Phase 2 commands + screens, surfaced by `/ius help <feature>`
 * (mirrors [CheckInfo] for checks). Keeps users out of the docs — the command explains itself.
 */
object FeatureInfo {

    fun describe(feature: String): String? = DESCRIPTIONS[feature.lowercase()]

    private val DESCRIPTIONS: Map<String, String> = mapOf(
        "transcript" to
            "/ius transcript <name> prints a Discord-copyable timeline of a player's session — swings, inferred hits, reach samples, velocity received, and which checks fired — straight to chat. It's the chat-print form of `/ius report <name> text` (same builder, different output: transcript → chat + export file, report → clipboard). Add `panel` to toggle the live side panel for your crosshair target (also a keybind + a config toggle).",
        "evidence" to
            "/ius evidence <name> collapses the last few seconds of a player's flags (window set by the Evidence window config) into ONE chat line you can paste straight into a report.",
        "note" to
            "/ius note <name> <category> <text...> tags a player as closet / blatant / needsReview / legit. The note follows them for the session and, with persistence on, across restarts. /ius note <name> (no category) re-reads it.",
        "session" to
            "/ius session prints a session summary: total players tracked, how many hit each tier, who peaked highest (confidence score), and total alerts.",
        "snapshot" to
            "The snapshot keybind posts a one-line evidence summary to chat ([Iustitia] PlayerX: Reach 4.2 | Tier: RED [87]), copies it to your clipboard, and (with persistence on) saves a .json snapshot. The screenshot + target highlight is a Phase B render feature.",
        "wizard" to
            "The first-launch setup wizard asks how you use Iustitia (General / Moderation / Ranked Player) and pre-sets alert level, nametag, audio, batching, compact mode and persistence to sensible defaults you can tweak later in /ius config. Only appears once.",
        "keybinds" to
            "The keybind hub screen lists every Iustitia bind with its current key and a one-line description, and highlights any that conflict with another bind in red. Open it from the `keybinds` keybind.",
        "compact" to
            "Compact mode shrinks alert lines and the history/session/transcript screens to one-line summaries for users who hate clutter. Toggle it with the `compact` keybind or the config screen.",
        "hist" to
            "/ius hist opens the searchable player list; /ius hist <name> opens that player's profile card (tier, confidence, max-VL-per-check bar) + filtered flag timeline with evidence rows. Click an alert to jump there.",
        "report" to
            "/ius report <name> [markdown|json|text] builds a full report card from the same data as the history screen and copies it to your clipboard — paste it into a Discord report. `text` is the chat-friendly transcript form (session stats + moderator note + timeline) — the same builder `/ius transcript <name>` prints to chat.",
        "alerts" to
            "/ius alerts toggles all chat alerts on/off. /ius alerts <name|check> [on|off] mutes one player or check's chat (detection + tiering keep running).",
        "watch" to
            "The watch keybind (default F9) toggles the follow-cam on your crosshair target — same as /ius spectate. It forces F1 (HUD hidden) and a third-party view of the target with all entities — including yourself — visible; move the mouse to orbit (the target stays centered), walk or get hit to stop, press the bind again to exit. Auto-reverts if the target leaves render range.",
        "spectate" to
            "/ius spectate [name] starts the watch follow-cam on the named player (or your crosshair target when no name is given) — same as the watch keybind (default F9) but works by name without needing them under your crosshair. /ius spectate off (or bare /ius spectate while already watching) stops it. Forces F1, third-party view of the target with all entities (including yourself) visible; mouse to orbit, move or get hit to stop.",
        "replay" to
            "/ius replay <player> <seconds> [0.5|0.25] reconstructs an instant replay from the rolling 60s capture buffer: translucent ghost copies of every tracked player at their buffered positions, played back at half or quarter speed, with the live world hidden (rewind feel). The named player is the highlighted focus ghost. Auto-stops at the end; /ius replay off stops early. Needs the Replay capture buffer on in config.",
        "clip" to
            "/ius clip <seconds> [name] exports the last N seconds of every tracked player's positions + every alert to a portable .iusclip file under %APPDATA%/.iustitia/clips — an evidence clip you can play back later with /ius playclip, not just a screenshot. [name] tags the focus player into the clip. Always writes (explicit export, independent of the persist-across-sessions toggle).",
        "playclip" to
            "/ius playclip [name] plays a saved .iusclip back in-world as ghost positions at 0.5×, exactly like /ius replay but from a file. No name = list your saved clips. /ius playclip off stops a playing clip early.",
        "sonar" to
            "/ius sonar [on|off] toggles directional audio alerts: on a flushed alert, a note plays at the offender's last-known position so the PAN tells you the direction and the PITCH tells you the distance (closer = higher). Eyes-free alerting — keep fighting and listen for cheats. Additive to chat; volume in /ius config.",
    )
}
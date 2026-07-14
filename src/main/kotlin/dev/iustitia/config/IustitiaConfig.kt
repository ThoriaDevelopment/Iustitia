package dev.iustitia.config

/**
 * On-disk configuration for Iustitia. Persisted as `config/iustitia.json` (see
 * [ConfigManager]) and editable live via YACL + `/iustitia`. Serialized by hand
 * through Gson's JsonObject (see [ConfigManager]) so we avoid pulling in the
 * kotlinx.serialization compiler plugin and its Kotlin-constructor pitfalls.
 *
 * Each check owns a [CheckConfig] slice: enabled, setbackVL (alert threshold),
 * decay (per-tick VL reduction), and a generic [threshold] whose meaning is
 * check-specific (Reach→maxReach, MultiTarget→min victims, ClickStatistics→cps cap,
 * SpeedEnvelope→bps cap, FlyEnvelope→Δy tolerance, NoFall→fallAccum limit, …).
 *
 * CheckConfig(enabled, setbackVL, decay, threshold).
 */
data class IustitiaConfig(
    var enabled: Boolean = true,
    var verbose: Boolean = false,
    /** Schema version of the calibration defaults. Bump [CONFIG_VERSION] whenever a check's
     *  default setbackVL/decay/threshold is recalibrated. On load ([ConfigManager]), if the
     *  persisted config predates this version, every check's CALIBRATION fields are reset to the
     *  code defaults while user preferences (per-check enabled, mutes, alerts, nametag) are
     *  preserved. Without this, a `config/iustitia.json` saved before a recalibration silently
     *  overrides the tuned defaults — the round-1/2 decay/VL edits were being clobbered this way
     *  (flyEnvelope decay stayed 1.0, throughWalls setbackVL stayed 5.0, etc.), so the config
     *  tuning had zero effect on the running game. */
    var configVersion: Int = CONFIG_VERSION,
    /** Min ticks between repeated alerts for the same (player, check). */
    var alertThrottleTicks: Int = 40,
    /** Ticks after a player joins before any alert fires (30s default). */
    var joinGraceTicks: Int = 600,
    /** Optional strict gates for ScaffoldRotation's LegitScaffold path (motion + cadence +
     *  5-short-crouch). Default off — the baseline Rain path is the true-positive detector;
     *  flip on only to A/B-test a hypothetical stationary-builder FP once one is observed. */
    var legitScaffoldStrictGates: Boolean = false,
    /** Mouse-sensitivity substrate (Axis A, plan §1.1/§8 step 1) — converges each tracked player's
     *  mouse sensitivity from the GCD structure of their pitch deltas, feeding two GCD sub-flags:
     *  `KillAura.heuristic(gcd)` (constant-rotation aimbot) and `RotationTracking.gcd` (too-clean
     *  pitch step while locking). Default **OFF** because the convergence (40 GCD samples per
     *  player, fed every tick while combat-relevant) is the dense-server FPS hog profiled in the
     *  v1.2.0 render-thread investigation — on a high-turnover server (Stray.gg) the continuous
     *  stream of newly-loaded combatants each paying the ~2s convergence phase kept the substrate
     *  at ~32% of render time. With it off the substrate is never fed, `sensitivity.valid` stays
     *  false, and both `gcdComponent` methods fail-open (self-disable) — no GCD sub-flags, but
     *  every OTHER KillAura / RotationTracking heuristic keeps running. Flip on to re-enable the
     *  two GCD sub-flags (accepting the dense-crowd FPS cost). Additive field — no CONFIG_VERSION
     *  bump; a config saved before this field simply keeps the default (off). */
    var sensitivitySubstrate: Boolean = false,

    // --- usability / display ---
    /** Global chat-alerts switch (toggled by bare `/ius alerts`). When false, NO chat alert lines
     *  are shown, but detection + [FlagHistory] tiering/history + the nametag prefix keep running —
     *  this is a "silence the chat noise" mute, not a "stop detecting" switch. To stop detection
     *  entirely, flip [enabled] off (via `/ius config`). */
    var alertsEnabled: Boolean = true,
    /** Master switch for the nametag cheat-tier prefix (green [+] / yellow [!] / red [X]). */
    var nametagPrefixes: Boolean = true,
    /** Show the green [+] on clean/low-flag players. Flip off to only mark yellow/red when a
     *  lobby full of green ticks feels noisy. No effect on yellow/red. */
    var nametagGreenEnabled: Boolean = true,
    /** Check ids whose chat alerts are muted (`/ius alerts <check>`). Detection, history and
     *  the nametag tier keep running — only the chat line is suppressed. */
    var mutedChecks: MutableList<String> = mutableListOf(),
    /** Player uuids (as strings) whose chat alerts are muted (`/ius alerts <name>`). Muting by
     *  uuid survives rejoin. As with mutedChecks, only chat is silenced. */
    var mutedPlayers: MutableList<String> = mutableListOf(),

    // --- Phase 2 UX / operability ---
    /** Master toggle for the roaming persistence store (`%APPDATA%/.iustitia` on Windows, game-dir
     *  `.iustitia` elsewhere). When on, moderator notes, the tier+flag history, evidence snapshots,
     *  and transcript/evidence exports are saved across sessions; when off, everything is
     *  in-memory session-only as before. Detection/tiering run regardless. */
    var persistenceEnabled: Boolean = false,
    /** One-shot gate: the first-launch setup wizard opens until the user picks a preset (or Skip),
     *  then this is stamped true so it never reappears. */
    var wizardCompleted: Boolean = false,
    /** Alert severity preset (display-only; no check logic changes). 0 = quiet (red-severity band
     *  only), 1 = normal (orange + red), 2 = verbose (all, including the low yellow band). Driven
     *  by the vl/setbackVL ratio, not the player tier. */
    var alertLevel: Int = 1,
    /** Collapse rapid same-player flags into one chat line after [alertBatchWindowTicks] of quiet.
     *  Off = the legacy one-line-per-alert behavior. */
    var alertBatching: Boolean = true,
    /** Quiet window (ticks) before a pending alert batch flushes. 100 = 5s. */
    var alertBatchWindowTicks: Int = 100,
    /** Play a note-block cue on a flushed alert batch. Off = silent. */
    var audioCues: Boolean = false,
    /** Audio cue volume (0..1). */
    var audioVolume: Double = 0.6,
    /** Distinct "nuclear" cue when a flush reaches RED tier from ≥2 primary checks at once. */
    var audioNuclear: Boolean = true,
    /** During a server-lag burst (per [dev.iustitia.tracking.EntityTrackerManager.lastServerLagTick])
     *  prefix flushed alerts with `[lag]` and, under the quiet preset, drop the non-red ones so a
     *  noisy read doesn't spam chat. Display-only. */
    var lagSuppressAlerts: Boolean = true,
    /** Color-pulse the nametag tier prefix for ~3s after a fresh yellow/red flag. Pure text-color
     *  modulation (no render API) — the spark particle is a deferred Phase B render piece. */
    var nametagBurstPulse: Boolean = true,
    /** Reduce alert lines + history/session/transcript rows to one-line summaries. */
    var compactMode: Boolean = false,
    /** `/ius evidence <name>` lookback window in ticks. 200 = 10s. */
    var evidenceWindowTicks: Int = 200,
    /** Auto-show the transcript side panel for the crosshair target. Off = keybind/`/ius transcript` only. */
    var transcriptPanel: Boolean = false,
    /** Phase B HUD: draw a small top-left `⚠ lag` indicator while a server-lag burst is recent, so a
     *  moderator sees WHY alerts are being softened (or distance flagged) without opening a screen.
     *  Reuses [dev.iustitia.tracking.EntityTrackerManager.lastServerLagTick]/lastLagBurstTick — no TPS
     *  estimator. Pure HUD text (HudRenderCallback), no world render. Display-only. */
    var lagHudIcon: Boolean = true,
    /** Phase B HUD: draw a compact panel near the crosshair showing the looked-at player's tier glyph +
     *  confidence score + the one-line "why this tier" explanation + the FP hint for their top check.
     *  This is the deferred nametag-hover tooltip delivered as a robust HUD overlay (the world-hover
     *  tooltip render path is fragile on 1.21.11; a HUD that reads the crosshair target is equivalent
     *  for the user and build-verifiable). Display-only. */
    var confidenceHud: Boolean = true,

    /** Draw a tier-colored wireframe box around the OTHER player your crosshair is on (Phase B
     *  on-world target highlight). Render-only, depth-tested (no wallhack — hidden behind walls,
     *  consistent with the nametag-visibility-respecting philosophy). Yellow/red always drawn; green
     *  only when [nametagGreenEnabled] (so clean players aren't boxed unless you opted into green). */
    var targetHighlight: Boolean = true,

    /** Ghost trail: draw a fading breadcrumb trail of recent positions for suspect (yellow/red)
     *  OTHER players, so you can see where a cheater came from / is heading. Render-only,
     *  depth-tested (no wallhack — occluded behind walls). Clean (green) players are never
     *  trailed; a player that drops back to green has its trail cleared. */
    var ghostTrail: Boolean = true,

    /** Watch follow-cam: pressing the `watch` keybind on a crosshair-targeted OTHER player starts a
     *  sustained slow auto-orbit third-person camera around them (a "spectate the cheater" view);
     *  press again to stop. Render-only — the camera auto-reverts to your view the instant you stop /
     *  the target leaves / the world changes (vanilla re-derives the camera each frame, so it can
     *  never get stuck on the offender). Gated by this toggle. */
    var watchFollowCam: Boolean = true,

    /** Burst sparks: spawn a brief tier-colored (red/yellow) particle burst at a player's eye when a
     *  fresh tier-relevant alert fires — a visual "flag" cue mid-fight. Client-only particles (no
     *  packet). Render-only; fires only on alerts that move the tier (not tier-neutral spam). */
    var burstSparks: Boolean = true,

    /** Hover tooltip: after the crosshair rests on one player for ~1.5s, show an expanded top-center
     *  banner (tier glyph + score, "why this tier", the FP hint for their top check, and their most-
     *  flagged checks) — the deferred nametag-hover tooltip delivered as a robust HUD overlay (the
     *  world-space hover path is fragile on 1.21.11). Suppresses the compact crosshair panel while
     *  up. Display-only. */
    var hoverTooltip: Boolean = true,

    /** Tab-list badge: prepend the tier glyph to each OTHER player's row in the Tab (player list)
     *  HUD, so the tier is visible without looking at them in-world. Follows the same nametag
     *  settings (nametagPrefixes / nametagGreenEnabled). Read-only mixin on the tab name; never
     *  touches your own row. */
    var tabListBadge: Boolean = true,

    // --- Phase 2 instant-replay / sonar / clip ---
    /** Master toggle for the rolling replay capture buffer ([dev.iustitia.replay.ReplayBuffer]). On by
     *  default — the per-tick work is tiny (≤64 players × ≤1200 frames), and turning it off only
     *  disables `/ius replay` + `/ius clip` (no in-world ghost playback, no `.iusclip` export). The
     *  live game + detection run regardless. Flip off if you never use replay/clip and want the
     *  per-tick capture skipped entirely. */
    var replayCapture: Boolean = true,
    /** "Rewind feel" for `/ius replay` + `/ius playclip`: while a replay is active, hide every live
     *  OTHER player so only the buffered ghost copies render (a rewind-the-world look). Off = overlay
     *  the ghosts on top of the live players instead. Render-only — the live game keeps running. */
    var replayHideLive: Boolean = true,
    /** Render replay/clip ghosts as the actual Minecraft player MODEL with each player's REAL skin
     *  (resolved per-UUID from the tab-list [net.minecraft.client.network.PlayerListEntry] vanilla keeps
     *  warm; Steve/Alex fallback for players not in the tab list — see [dev.iustitia.render.ReplaySkins]),
     *  driven via the vanilla [net.minecraft.client.render.entity.PlayerEntityRenderer] model, instead
     *  of the tier-colored humanoid box outline. ON by default. This path drives a render-state model
     *  outside the vanilla entity pipeline, whose exact block scale + pose transforms are
     *  runtime-only-verifiable (see [dev.iustitia.render.ReplayRenderer]). The box outline remains the
     *  fail-open fallback per ghost, so a render error never blanks a replay. Display-only — adds NO detection. */
    var replayPlayerModels: Boolean = true,
    /** Relocate a `/ius playclip` scene to YOUR current position so the ghosts (and the bundled chunk
     *  world) render around you instead of at the recorded absolute coords — which would float in air /
     *  sit in the ground / clip walls when you play a clip back somewhere else. The focus player's
     *  recorded start maps to where you stand. ON by default; flip off to render a clip where it was
     *  actually recorded. **Applies to `/ius playclip` only** — `/ius replay` always renders ghosts at
     *  their exact recorded coordinates (it's instant, same server/dimension — no relocation needed).
     *  Display-only. */
    var replayRelocate: Boolean = true,
    /** Bundle the loaded terrain around the action into a `.iusclip` when `/ius clip` runs, so
     *  `/ius playclip` can render the map (relocated to you) alongside the ghosts — a clip recorded on
     *  server A is then watchable in full on server B. The capture is bounded to the action bbox + a
     *  margin (the client only has loaded chunks in render distance, not the whole server map) and is
     *  volume-capped. Flip off to save clips without terrain (smaller file, ghosts-only playback). */
    var clipTerrain: Boolean = true,
    /** Capture every **loaded chunk** (full 16×16 columns, all Y sections — includes underground) around
     *  the player into a `.iusclip` when `/ius clip` runs, so `/ius playclip` can render the clip's world
     *  as **solid, textured blocks** (the real map, relocated to you) and you can free-spectate anywhere
     *  — including underground — like ReplayMod. The live world is hidden during playback and restored on
     *  `/ius playclip off`. The capture is one-shot at save time (only the chunks loaded then are kept;
     *  a chunk that unloaded earlier in the window isn't captured) and bounded by [clipChunkRadius] +
     *  a section budget. Flip off to save clips without the chunk world (the v5 wireframe terrain / ghosts
     *  path). On by default. */
    var clipChunkWorld: Boolean = true,
    /** Capture radius in chunks for [clipChunkWorld] (a `clipChunkRadius`-chunk square centred on the
     *  player; 8 → 17×17 = 289 chunks). Larger = more of the map captured + a bigger `.iusclip` file +
     *  a heavier bake at playclip start. Display/UX only — the radius is clamped 1..32 at capture. */
    var clipChunkRadius: Int = 8,
    /** Render distance (in chunks) for the chunk world during `/ius playclip` — only chunks within this
     *  radius of the camera are drawn each frame, bounding per-frame cost (the captured area can be much
     *  larger via [clipChunkRadius]; this is how much of it you actually render at once). Default 6,
     *  clamped 4..12 at render. Display/UX only. */
    var clipChunkRenderDistance: Int = 6,
    /** `/ius playclip` generation: **LEGACY** = the v1.1.0 playclip experience (ghosts over the live
     *  world, no chunk/terrain capture, no relocation, no auto-freecam, no spectator input/packet
     *  suppression — the player walks + acts normally while watching). **MODERN** = the current feature
     *  set (solid captured chunk world + free-spectate freecam + relocated scene + spectator-like
     *  input/packet suppression). Default MODERN (since v1.2.0 — the C2 static-GPU-mesh fix made Modern's
     *  FPS match the 120 cap, so the richer mode is the better out-of-box experience). Existing users'
     *  persisted `playclipMode` is preserved by the [ConfigManager] `if (o.has("playclipMode"))` guard —
     *  only a fresh install (no persisted field) gets MODERN. The chunk/terrain/relocate toggles only
     *  take effect in Modern; in Legacy they're ignored (forced off). User-controlled: NOT overridden by
     *  preset applies. */
    var playclipMode: PlayclipMode = PlayclipMode.MODERN,
    /** Sonar alerting: on a flushed alert batch, play a DIRECTIONAL note positioned at the offender's
     *  last-known world position (pan = direction, pitch = distance) so you can keep fighting and
     *  listen for cheats. Additive to chat alerts; gated by the same mute/preset rules. */
    var sonarAlerts: Boolean = true,
    /** Sonar cue volume (0..1). Independent of the chat audio-cue volume — sonar pings are quieter by
     *  design (positional, frequent). */
    var sonarVolume: Double = 0.7,
    /** Seconds of buffered scene to replay when the replay-toggle keybind (numpad * by default) is
     *  pressed. 1..60 (clamped to the 60s replay buffer). Default 30. Additive — no CONFIG_VERSION
     *  bump; a pre-field config keeps the default. */
    var replayKeybindSeconds: Int = 30,
    /** Show a numeric **health indicator** (`§c<hp>§r/§f<max>`, e.g. `14/20`) above each ghost's
     *  nametag during `/ius playclip`, plus a transient `§c-<dmg>` popup for ~40 ticks when a recorded
     *  player's health drops (damage amount = health-diff; attack SOURCE is not available client-side
     *  for other players). Natively OFF so a default playclip looks like vanilla; turn on in `/ius
     *  config`. Additive — no CONFIG_VERSION bump; a pre-field config keeps the default (off). */
    var clipHealthIndicator: Boolean = false,
    /** Show a **totem-pop counter** (`§6⚡<count>` badge) on each ghost's nametag during `/ius playclip`,
     *  counting that recorded player's Totem-of-Undying pops within the clip window (captured via the
     *  EntityStatusS2C status-35 broadcast). Natively OFF; turn on in `/ius config`. Additive — no
     *  CONFIG_VERSION bump. */
    var clipTotemPopCounter: Boolean = false,

    // --- combat ---
    var reach: CheckConfig = CheckConfig(true, 10.0, 0.25, 3.0),
    var multiTarget: CheckConfig = CheckConfig(true, 2.0, 1.0, 2.0),
    var clickStatistics: CheckConfig = CheckConfig(true, 5.0, 0.05, 20.0),
    var throughWalls: CheckConfig = CheckConfig(true, 8.0, 0.5, 0.5), // threshold = occlusion match-rate ratio that flags (NCM-style rolling window). A hit is "occluded" when the attacker's eye→{eye,torso,feet} LOS is blocked for ALL three victim body points; we keep a rolling window of the last 12 occlusion verdicts per attacker and flag when occluded/size ≥ threshold (0.5) with ≥3 samples. Default 0.5: a legit player's occasional occluded hit reads low and never flags; a through-walls aura's repeated full-body-occluded hits read ≥0.5 and flag in <1s of combat. Lower = stricter (fewer through-wall hits required to flag); raise to 1.0 to flag only all-occluded sprees.
    var criticals: CheckConfig = CheckConfig(true, 5.0, 0.1, 0.05),
    var noKnockback: CheckConfig = CheckConfig(true, 5.0, 1.0, 0.61),
    var keepSprint: CheckConfig = CheckConfig(true, 5.0, 0.5, 0.8), // threshold = retained-speed ratio flagging point (was hardcoded 0.8; now wired)
    var wTap: CheckConfig = CheckConfig(true, 5.0, 0.5, 2.0),
    var jumpOnHurt: CheckConfig = CheckConfig(true, 5.0, 0.2, 0.3), // threshold = min Δy within ±1 tick of a hit (was hardcoded 0.3; now wired)
    var backtrack: CheckConfig = CheckConfig(true, 10.0, 0.25, 3.0),
    /** Rain-Anticheat killaura/silent-aim suite (7 sub-components, one VL pool). threshold unused.
     *  decay 0.10 (was 0.05): the 0.05 break-even (~1 flag/sec) was low enough that normal PvP
     *  cadence over-accumulated to alert on Polar-clean players (cosYT vl 10.8, devilseeker 9.5).
     *  0.10 raises the break-even to ~2 flags/sec — spread-out FP accumulation decays between
     *  bursts, sustained silent-aim bursts still climb to alert. */
    var killAura: CheckConfig = CheckConfig(true, 5.0, 0.1, 0.0),
    /** Rain-Anticheat AutoBlock — sustained swing+use overlap tick limit. */
    var autoBlock: CheckConfig = CheckConfig(true, 5.0, 0.5, 10.0),
    /** HitFlick / KnockbackDisplace — min yaw-off-hitbox degrees at the attack tick. */
    var hitFlick: CheckConfig = CheckConfig(true, 5.0, 0.5, 30.0),
    /** Triggerbot — min fast-hit count (attacks within MAX_REACTION_TICKS of the crosshair first
     *  reaching a victim's hitbox) required in the rolling window, gated by a ≥0.75 ratio over
     *  ≥5 hits. Default 4 = "really blatant" (4 of 5 hits are sub-reaction). Raise to tune stricter. */
    var triggerbot: CheckConfig = CheckConfig(true, 5.0, 0.05, 4.0),
    /** MaceSmash (MaceKill family) — |Δy| floor around a mace-holder's attack (plan §7.1).
     *  decay 0.05 (slow): a per-attack burst signal — a blatant every-hit MaceKill climbs to
     *  alert, a single residual rubberband never does. threshold 1.5 = the warp-spike floor;
     *  the check deliberately ignores lastTeleportTick so the >8b heuristic doesn't exempt it. */
    var maceSmash: CheckConfig = CheckConfig(true, 5.0, 0.05, 1.5),
    /** HitsWithoutSwing (Slinky Hit Select / Grim PacketOrderB) — min no-swing hurts attributed
     *  to one inferred attacker in an episode to flag. CORROBORATOR-tier weak signal (some
     *  disablers legit skip swing): decay 0.5, low per-flag level, transition-gated. */
    var hitsWithoutSwing: CheckConfig = CheckConfig(true, 5.0, 0.5, 3.0),

    // --- movement ---
    var speedEnvelope: CheckConfig = CheckConfig(true, 5.0, 1.0, 10.0),
    var flyEnvelope: CheckConfig = CheckConfig(true, 5.0, 0.5, 0.1),
    /** Spider (AvA wall-climb) — consecutive ascending-ticks against a non-climbable wall to
     *  flag the Spider sub-flag (ConstantClimb uses fixed bands). decay 0.5. Tuned in step 14. */
    var spider: CheckConfig = CheckConfig(true, 5.0, 0.5, 10.0),
    var noFallDamage: CheckConfig = CheckConfig(true, 4.0, 1.0, 8.0),
    var stepHeight: CheckConfig = CheckConfig(true, 5.0, 0.5, 0.6),
    var teleport: CheckConfig = CheckConfig(true, 5.0, 0.5, 1.5),
    var longJump: CheckConfig = CheckConfig(true, 5.0, 0.5, 0.6),
    var noSlow: CheckConfig = CheckConfig(true, 5.0, 0.5, 4.0),
    var backwardSprint: CheckConfig = CheckConfig(true, 5.0, 0.5, 1.0),
    /** WallSprint (Grim SprintE) — consecutive sprint-into-wall ticks to flag. decay 0.5. */
    var wallSprint: CheckConfig = CheckConfig(true, 5.0, 0.5, 5.0),
    /** SprintHack (Grim SprintG/B/D) — sustained sprint-while-water/sneak/blind ticks to flag. decay 0.5. */
    var sprintHack: CheckConfig = CheckConfig(true, 5.0, 0.5, 3.0),
    var waterWalk: CheckConfig = CheckConfig(true, 5.0, 0.5, 1.0),
    var elytraSpeed: CheckConfig = CheckConfig(true, 5.0, 1.0, 40.0),

    // --- rotation / packet-flow / minor ---
    var rotationTracking: CheckConfig = CheckConfig(true, 5.0, 0.1, 0.92),
    var rotationSnapBack: CheckConfig = CheckConfig(true, 5.0, 0.5, 30.0),
    var phaseClip: CheckConfig = CheckConfig(true, 5.0, 0.5, 1.0),
    var packetGap: CheckConfig = CheckConfig(true, 5.0, 0.5, 2.0),
    var aimWrap: CheckConfig = CheckConfig(true, 5.0, 0.5, 165.0),
    var pitchBound: CheckConfig = CheckConfig(true, 5.0, 0.0, 90.0),
    var scaffoldRotation: CheckConfig = CheckConfig(true, 5.0, 0.05, 78.0),
) {
    companion object {
        /** Current calibration schema version. Bump on each recalibration round so
         *  [ConfigManager] resets stale persisted calibration fields to these defaults.
         *  v4: wired the previously-dead keepSprint (0.5→0.8) and jumpOnHurt (0.4→0.3)
         *  threshold sliders to match the values the checks always hardcoded, so existing
         *  persisted configs don't shift detection stricter/looser when the slider goes live.
         *  v5: repurposed the reserved `throughWalls` threshold (1.0, unused) into the NCM
         *  occlusion match-rate ratio (0.5). A persisted config saved at v4 has threshold=1.0,
         *  which under the new meaning would only flag all-occluded sprees (stricter than
         *  intended) — the bump resets it to the 0.5 default. */
        const val CONFIG_VERSION = 5
    }

    /** `/ius playclip` generation selector — see [IustitiaConfig.playclipMode]. */
    enum class PlayclipMode { LEGACY, MODERN }

    data class CheckConfig(
        var enabled: Boolean = true,
        var setbackVL: Double = 5.0,
        var decay: Double = 1.0,
        var threshold: Double = 0.0,
    )

    /** All check slices in a stable order (used by the registry + YACL screen). */
    fun checks(): List<Pair<String, CheckConfig>> = listOf(
        "reach" to reach,
        "multiTarget" to multiTarget,
        "clickStatistics" to clickStatistics,
        "throughWalls" to throughWalls,
        "criticals" to criticals,
        "noKnockback" to noKnockback,
        "keepSprint" to keepSprint,
        "wTap" to wTap,
        "jumpOnHurt" to jumpOnHurt,
        "backtrack" to backtrack,
        "killAura" to killAura,
        "autoBlock" to autoBlock,
        "hitFlick" to hitFlick,
        "triggerbot" to triggerbot,
        "maceSmash" to maceSmash,
        "hitsWithoutSwing" to hitsWithoutSwing,
        "speedEnvelope" to speedEnvelope,
        "flyEnvelope" to flyEnvelope,
        "spider" to spider,
        "noFallDamage" to noFallDamage,
        "stepHeight" to stepHeight,
        "teleport" to teleport,
        "longJump" to longJump,
        "noSlow" to noSlow,
        "backwardSprint" to backwardSprint,
        "wallSprint" to wallSprint,
        "sprintHack" to sprintHack,
        "waterWalk" to waterWalk,
        "elytraSpeed" to elytraSpeed,
        "rotationTracking" to rotationTracking,
        "rotationSnapBack" to rotationSnapBack,
        "phaseClip" to phaseClip,
        "packetGap" to packetGap,
        "aimWrap" to aimWrap,
        "pitchBound" to pitchBound,
        "scaffoldRotation" to scaffoldRotation,
    )

    /** Look up a check's slice by id (so checks always read the live config). */
    fun slice(id: String): CheckConfig = when (id) {
        "reach" -> reach
        "multiTarget" -> multiTarget
        "clickStatistics" -> clickStatistics
        "throughWalls" -> throughWalls
        "criticals" -> criticals
        "noKnockback" -> noKnockback
        "keepSprint" -> keepSprint
        "wTap" -> wTap
        "jumpOnHurt" -> jumpOnHurt
        "backtrack" -> backtrack
        "killAura" -> killAura
        "autoBlock" -> autoBlock
        "hitFlick" -> hitFlick
        "triggerbot" -> triggerbot
        "maceSmash" -> maceSmash
        "hitsWithoutSwing" -> hitsWithoutSwing
        "speedEnvelope" -> speedEnvelope
        "flyEnvelope" -> flyEnvelope
        "spider" -> spider
        "noFallDamage" -> noFallDamage
        "stepHeight" -> stepHeight
        "teleport" -> teleport
        "longJump" -> longJump
        "noSlow" -> noSlow
        "backwardSprint" -> backwardSprint
        "wallSprint" -> wallSprint
        "sprintHack" -> sprintHack
        "waterWalk" -> waterWalk
        "elytraSpeed" -> elytraSpeed
        "rotationTracking" -> rotationTracking
        "rotationSnapBack" -> rotationSnapBack
        "phaseClip" -> phaseClip
        "packetGap" -> packetGap
        "aimWrap" -> aimWrap
        "pitchBound" -> pitchBound
        "scaffoldRotation" -> scaffoldRotation
        else -> {
            // Unknown check id — a stale persisted config key (e.g. timerRate after upgrade) or a
            // check registered without a slice() branch. [Iustitia.verifyCheckRegistry] logs the
            // drift at startup; until then return a SAFE default: disabled + max setback/threshold
            // so the check can't fire (most movement checks flag on `value > threshold`, so
            // MAX_VALUE means never — a `<`-comparison orphan like NoKnockback is the known gap the
            // self-check catches instead). NB: checks do NOT gate on `enabled`, so the threshold
            // is the real guard, not the enabled flag.
            CheckConfig(enabled = false, setbackVL = Double.MAX_VALUE, decay = 1.0, threshold = Double.MAX_VALUE)
        }
    }
}
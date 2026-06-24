<div align="center">

<img src="iustitia.png" alt="Iustitia" width="128" height="128">

# Iustitia

A purely client-sided anticheat for Minecraft Java **1.21.11** (Fabric).

Detects **both 1.8-era and 1.21.11-era cheats** by passively observing *other* players through the packets the server already rebroadcasts to you — no server component, no network transmission, no outgoing packets.

**Local-only alerts and overlays. No bans. No interference. No outgoing packets.**

</div>

---

## What it is

Iustitia is a Fabric client mod that watches every *other* player on your server and flags impossible world/combat interactions — the kind of thing a reach hack, a killaura, a fly hack, or a timer cheat produces. It does this entirely on your client:

- **Read-only on incoming packets.** A single mixin (`ClientPlayNetworkHandlerMixin`) observes server packets and feeds them into a tracking pipeline. It never sends anything to the server and never mutates the local player. The **watch follow-cam** (`/ius spectate`, see below) is the one deliberate exception: it overrides the *camera only*, while you're actively spectating, and auto-reverts the instant you stop, move, get hit, or the target leaves — vanilla re-derives the camera every frame so it can never get stuck.
- **Other players only.** It builds a server-space model of every other client player (position, yaw/pitch, sprint/sneak, hurt ticks, vehicle, deltas) from rebroadcast state, then runs 32 detection checks against that model each tick.
- **Fail-open everywhere.** Every check and mixin body is wrapped so a thrown exception is swallowed and skipped — a detection error never crashes your client and never produces a false positive. A chunk-unloaded player is a false *negative*, never a false *positive*.
- **Two streams, kept separate.** *Chat alerts* fire only when a check's violation level crosses its setback threshold. *Verbose console logging* (every flag, a pipeline heartbeat) is opt-in via `/ius verbose` for validation/debugging and is **off by default** — the release build is silent in `latest.log` unless you turn it on.

It is a detection/inspection tool, not an enforcement tool: it tells *you* who looks like a cheater. It does not kick, ban, or report anyone, and it sends nothing anywhere.

## Requirements

| Dependency | Version |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | 0.19.3+ |
| Fabric API | 0.141.3+1.21.11 (any compatible) |
| fabric-language-kotlin | 1.13.9+kotlin.2.3.10 (any compatible) |
| Yet Another Config Lib (YACL) | 3.8.2+1.21.11 (any compatible) |
| Java | 21 |
| **Recommended:** ViaFabricPlus | for playing on 1.8-era servers via protocol translation |

All three library mods (Fabric API, fabric-language-kotlin, YACL) are standard and can be installed alongside any other modpack. Iustitia is written to fail-open and not crash when other mods are present.

## Installation

1. Install Fabric Loader 0.19.3+ for Minecraft 1.21.11.
2. Drop **Fabric API**, **fabric-language-kotlin**, and **YACL** into your `mods/` folder.
3. Drop `iustitia-1.1.0.jar` into your `mods/` folder.
4. (To detect cheats on 1.8-era servers) Install **ViaFabricPlus** so your 1.21.11 client can join them.
5. Launch. A one-time **first-launch wizard** asks how you use Iustitia (General / Moderation / Ranked Player) and pre-sets sensible defaults. Join any server with other players.

That's it. Alerts appear in chat; other players get a colored tier prefix (with a confidence score) on their nametag (where the server allows it — see [Nametag prefixes](#nametag-prefixes)).

## Quick start

```
/ius                 # list checks + enabled state (alias of /iustitia)
/ius status          # health panel: master, tracked players, protocol, alerts
/ius help            # in-game command help
/ius help reach      # describe a check + its live config (or /ius help spectate, transcript, …)
/ius hist            # session top offenders (searchable screen)
/ius hist <name>     # a player's profile card + recent flags
/ius spectate <name> # watch follow-cam on a player (or your crosshair target; /ius spectate off to stop)
/ius transcript <name>  # copyable session timeline (or /ius transcript panel <name> for the side panel)
/ius evidence <name>    # one-line summary of their last few seconds of flags
/ius note <name> <cat> <text…>  # tag a player (closet/blatant/needsReview/legit); /ius note <name> to read
/ius session         # session summary: tracked players, tier counts, who peaked highest
/ius report <name>   # full report card → clipboard (markdown or json)
/ius snapshot [name] # one-line evidence snapshot of your crosshair target → clipboard
/ius replay <name> <sec> [1|0.5|0.25]  # rewind the last N seconds in-world as ghost models (1× by default; 0.5/0.25 = slow-mo)
/ius replay pause|resume|seek <s>|step +|-|speed 1|0.5|0.25|cam free|follow|pov|off  # playback controls while a replay runs
/ius clip <sec> [name]  # export the last N seconds of positions + alerts to a portable .iusclip file
/ius playclip [name] [1|0.5|0.25]  # play a saved clip back in-world (/ius playclip off to stop; bare = list clips)
/ius clips           # open the clip manager screen (list / play / delete saved .iusclip files)
/ius sonar [on|off]   # directional audio alerts — pan = direction, pitch = distance (additive to chat)
/ius clear <name|all> # reset one player's flags (tier→green) or everyone's (exemptions untouched)
/ius exempt [name [on|off]]  # exempt a player from all checks (bare = list exempted); persists across sessions
/ius alerts          # mute/unmute all chat alerts (detection keeps running)
/ius keybinds        # open the keybind hub screen
/ius config          # open the YACL config screen
```

There are also **twelve keybinds** (snapshot, transcript, session, keybinds, config, note, compact, watch, plus four **numpad replay controls**: pause/resume, seek +5s, seek −5s, exit) — configurable in vanilla Controls → Miscellaneous, and listed with conflict-detection in the keybind hub (`/ius keybinds`).

See **[USERMANUAL.md](USERMANUAL.md)** for a non-developer walkthrough.

## Architecture

```
                 ┌─────────────────────────────┐
   server packets │  ClientPlayNetworkHandler   │  (read-only mixin — no send path)
   ──────────────►│  Mixin → PacketSignals       │
                  └──────────────┬──────────────┘
                                 │  swing / hurt / position / metadata
                                 ▼
                      ┌──────────────────────┐
                      │   EntityTrackerManager │  server-space model of every other player
                      │   + ProtocolDetector    │  (1.8-era vs modern combat timing)
                      └──────────┬───────────┘
                                 │  TrackedPlayer (pos, yaw/pitch, deltas, sprint, hurtTick…)
                                 ▼
                      ┌──────────────────────┐   per tick:  ClientTickEvents.END_CLIENT_TICK
                      │   32 Checks (combat +  │   ──► Check.process() / onAttack() / onSwing()
                      │   movement/rotation/   │   ──► vl += level ; decay each clean tick
                      │   packet)              │
                      └──────────┬───────────┘
                                 │  flag() when vl > setbackVL
                                 ▼
                      ┌──────────────────────┐
                      │  AlertManager +       │  ──► chat alert (throttled, join-grace)
                      │  FlagHistory + CheckInfo│ ──► /ius hist, status, nametag tier
                      └──────────────────────┘

                 ┌─────────────────────────────┐
   render thread  │  PlayerEntityRendererMixin  │  nametag tier prefix + confidence badge + burst pulse
                  │  ArmorStandEntityRendererMx │  nametag fallback for armor-stand holograms
                  │  PlayerListHudMixin          │  tab-list tier badge
                  │  CameraMixin                │  offender-selfie + watch follow-cam (auto-reverting)
                  │  EntityRendererMixin         │  hides live players during an instant replay
                  └─────────────────────────────┘  (all render-only, no visibility hack, no send path)
```

### Mixin set (the only bytecode touched)

- `ClientPlayNetworkHandlerMixin` — the read-only packet observer. `defaultRequire: 1` (loud-fail if it ever drifts on a future MC build, because a silent packet miss means silent false negatives).
- `PlayerEntityRendererMixin` — the nametag tier prefix + confidence badge + burst-pulse renderer. Cosmetic, render-only, fail-open (a future-build signature drift would at worst make prefixes silently not appear).
- `ArmorStandEntityRendererMixin` — extends the nametag fallback to armor-stand holograms (servers that ride the nametag on an armor stand). Render-only, fail-open.
- `PlayerListHudMixin` — prepends the tier glyph (+ score) to each OTHER player's row in the Tab list. `@Inject` on `getPlayerName` at RETURN, fail-open.
- `EntityRenderStateAccessor` — `@Accessor` for `displayName` / `playerName` on `EntityRenderState`.
- `CameraMixin` — the offender-selfie (single-frame) and watch follow-cam (sustained) camera overrides. Both rely on vanilla re-deriving the camera before the `@At("TAIL")` inject each frame, so the override only persists while actively re-applied — the instant it stops, the view reverts to the local player (the safe state).
- `EntityRendererMixin` — the "rewind feel" hide-live for instant replay: cancels `shouldRender` for every OTHER player while a replay is active with hide-live on (the base `EntityRenderer` is the target because `PlayerEntityRenderer` inherits `shouldRender` and doesn't override it). Render-only, fail-open; a cheap volatile-read early-out when no replay is running.

No `@Redirect` or `@Overwrite` is used anywhere. No send-path mixins. No local-player mutation (the watch follow-cam overrides the *camera* only, and is the sole deliberate exception — see above).

### Protocol awareness

`ProtocolDetector` distinguishes 1.8-era combat (via ViaFabricPlus) from modern 1.21.11 combat and adjusts the hurt-confirmation lookback (3 ticks on 1.8 vs 2 on modern), so attack-inference timing is correct on both. Checks that depend on server ticking (timer/blinker, teleport) carry a server-lag exemption so a lagging server doesn't manufacture false positives.

## The 32 checks

Each check has its own config slice (`enabled`, `setbackVL`, `decay`, `threshold`) editable live via `/ius` or YACL. Checks marked **definitive** can prove cheating and drive the red nametag tier; the rest are inferential and drive yellow.

### Combat (14)

| id | detects | definitive |
|---|---|:--:|
| `reach` | Hit a victim beyond vanilla melee reach (lag-compensated). | ✓ |
| `multiTarget` | Struck ≥2 distinct victims in one tick (multi-aura). | ✓ |
| `clickStatistics` | Click cadence too uniform / too fast (autoclicker). | |
| `throughWalls` | Attacked a victim with no line-of-sight to the torso. | ✓ |
| `criticals` | Spoofed a grounded crit hop to crit while on the ground. | ✓ |
| `noKnockback` | Took a hit without the expected knockback (anti-KB). | |
| `keepSprint` | Kept sprinting through an attack instead of the legit slowdown. | |
| `wTap` | Reset sprint KB pattern mismatch (W-Tap / SuperKB cheat). | |
| `jumpOnHurt` | Jumped instantly on taking damage (anti-KB hop). | |
| `backtrack` | Hit a victim from a stale (backtracked) position. | |
| `killAura` | Silent-aim / aim-snap suite (7 sub-components, one VL pool). | ✓ |
| `autoBlock` | Swung while a shield was raised (auto-block / block-hit). | ✓ |
| `hitFlick` | Redirected aim off the hitbox at the attack tick (HitFlick). | ✓ |
| `triggerbot` | Auto-attacked the instant the crosshair reached a hitbox (sub-reaction). | |

`killAura` is a port of Rain-Anticheat's 1.8.9 silent-aim suite; `hitFlick` is a Vape/Slinky-style knockback-redirect detector; `triggerbot` is a lax, blatant-only rising-edge reaction-timing detector (deliberately **not** definitive — yellow tier — pending live validation).

### Movement / rotation / packet (18)

| id | detects | definitive |
|---|---|:--:|
| `speedEnvelope` | Moved horizontally faster than the vanilla speed envelope. | |
| `flyEnvelope` | Vertical motion broke vanilla physics (fly / hover / ascend). | ✓ |
| `noFallDamage` | Spoofed on-ground to avoid fall damage. | ✓ |
| `stepHeight` | Stepped up a block higher than the vanilla step height. | |
| `teleport` | Position jumped in a way that isn't a vanilla teleport/pearl. | ✓ |
| `longJump` | Covered too much horizontal distance in one air tick. | ✓ |
| `noSlow` | Moved at full speed while using an item that should slow you. | |
| `backwardSprint` | Sprinted backward (OmniSprint) — blatant-only, KB-exempt. | |
| `waterWalk` | Walked on water (Jesus / water-walk). | ✓ |
| `elytraSpeed` | Elytra glide exceeded the vanilla speed cap. | |
| `rotationTracking` | Aim rotated too uniformly while tracking a target. | |
| `rotationSnapBack` | Aim snapped back after an attack (aim-snap). | |
| `phaseClip` | Moved through a solid block (phase / no-clip). | ✓ |
| `packetGap` | Packet timing gap inconsistent with vanilla ticking (timer/blinker). | |
| `timerRate` | Game-tick rate inconsistent with vanilla (timer cheat). | ✓ |
| `aimWrap` | Aim rotated faster than a legit flick (>threshold°/tick). | |
| `pitchBound` | Pitch outside the vanilla [-90, 90] bound. | |
| `scaffoldRotation` | Scaffold placement rotation inconsistent with legit bridging. | ✓ |

## Alerts

The fixed alert layout:

```
§8[§diustitia§8] §f(Name) §<sev>(Check) §<sev>(VL)
```

Severity color scales with the violation ratio `vl / setbackVL`: **<2× yellow** (`§e`), **<3× orange** (`§6`), **≥3× red** (`§c`). The trailing number is the violation count (ceiling of VL).

The line is self-documenting and interactive (both are local chat-component events — no packet is sent):
- **Hover** → the check's one-line description, this player's session alert count, and the severity legend.
- **Click** → runs `/ius hist <name>` to open that player's flag history.

Alerts are throttled per (player, check) and suppressed during a join-grace window (default 30s) so a player who just rendered in doesn't burst-fire.

## Nametag prefixes

Other players get a tier prefix drawn on their nametag (vanilla visibility is respected — no wallhack; the prefix only appears when vanilla would show the nametag):

| prefix | tier | meaning |
|---|---|---|
| `§a[+]§r` | green | no chat alerts this session (clean / low-flag) |
| `§e[!]§r` | yellow | ≥1 primary red-capable alert has fired (suspect) |
| `§c[X]§r` | red | ≥2 distinct red-capable checks have proven cheating (sticky for the session, decays one tier per ~10 min idle) |

When **nametag confidence badge** is on (default), the 0-99 confidence score is appended inside the bracket, e.g. `[X 87]` or `[! 55]`, so two suspects are comparable at a glance. When **nametag burst pulse** is on (default), the prefix briefly pulses white/tier-color for ~3 s after a fresh yellow/red alert. Both are display-only (no check logic changed).

The prefix is written at the HEAD of `PlayerEntityRenderer.renderLabelIfPresent` (the draw method), so it survives label batching, and is mirrored into the Tab list by `PlayerListHudMixin`.

**Server coverage caveat:** the prefix only appears on servers that populate the vanilla nametag field (`displayName`). This works on most servers, including **minemen.club** and 1.8-era servers. Some servers suppress the vanilla nametag and render their own server-side name hologram instead — on those, Iustitia has no `displayName` to attach to and the prefix will not appear. This is by design (the alternative would be a wallhack-style visibility hack, which Iustitia refuses to do). Confirmed-affected: **stray.gg** and **mcpvp.club** (the latter shows a black-background label that is actually the server's BELOW_NAME health indicator, not the vanilla name).

## Observer tooling & render overlays

v1.1.0 adds a control surface and a visual layer that turn raw detections into a moderation workflow — all still read-only and client-sided.

### Evidence commands
- `/ius transcript <name>` — a Discord-copyable session timeline (swings, inferred hits, reach samples, velocity received, checks fired). `/ius transcript panel <name>` toggles a live side panel.
- `/ius evidence <name>` — collapses the last few seconds of a player's flags into one chat line.
- `/ius note <name> <category> <text…>` — moderator tag (closet / blatant / needsReview / legit). `/ius note <name>` re-reads it.
- `/ius session` — session summary: players tracked, tier counts, who peaked highest (confidence score). `/ius session screen` opens a dense one-screen version.
- `/ius report <name> [markdown|json]` — a full report card copied to your clipboard.
- `/ius snapshot [name]` — a one-line evidence snapshot of your crosshair target, copied to clipboard.

### Keybinds
Twelve configurable binds registered in vanilla Controls → Miscellaneous: `snapshot`, `transcript`, `session`, `keybinds`, `config`, `note`, `compact`, `watch` (default F9), plus four **numpad replay controls** — `replayPause` (numpad 5), `replaySeekFwd` (numpad +, +5s, works while playing), `replaySeekBack` (numpad −, −5s), and `replayExit` (numpad 0). `/ius keybinds` opens a hub screen that lists them all and highlights any that conflict with another bind in red.

### Watch follow-cam
`/ius spectate [name]` (or the `watch` keybind, default F9) starts a sustained follow-cam on a player: it forces F1, shows a third-party view of the target (all entities — including yourself — still rendered), and lets you orbit with the mouse (the target stays centered). It auto-stops when you move >0.5 blocks, get hit, or the target leaves render range; `/ius spectate off` (or the bind again) stops it manually. The camera auto-reverts to your view the instant it stops (vanilla re-derives it each frame, so it can never get stuck).

### Instant replay, sonar & evidence clips
A moderator-style "instant replay" of the scene, an eyes-free directional audio alert, and a portable evidence-clip format — all client-side, all render/sound-only (no detection logic touched):

- **`/ius replay [<player>|<seconds>] [<seconds>] [1|0.5|0.25]`** — reconstructs the last N seconds (≤60) from a rolling 60 s capture buffer and plays it back in-world as translucent **humanoid ghost models** of every tracked player at their buffered positions, at **full (1×) speed by default** (add `0.5` or `0.25` for slow-mo). The named player is the highlighted focus (cyan + `▶` name marker). Ghosts are colored by that player's cheat tier (green/yellow/red) with a floating name tag so you can read who is who, and a facing nub shows each one's buffered yaw. By default **the live players are hidden** during a replay ("rewind feel" — only the ghosts render); the live game + detection keep running underneath and rendering snaps back the instant the replay stops. The `<player>` arg is **optional and overloaded**: a number = the duration with no focus (`/ius replay 60`); a name = the focus player, optionally followed by `<seconds> [speed]` (`/ius replay thoria 60 0.5`); bare `/ius replay` = the default 30 s window, no focus. **Playback controls** while a replay runs: `/ius replay pause` / `resume`, `seek <s>`, `step +|−` (frame-step, while paused), `speed 1|0.5|0.25`, `cam free|follow|pov` (free = your view; follow = orbit the focus ghost; pov = the focus ghost's eyes), and `off`. Four **numpad keybinds** mirror the controls without leaving the game: numpad 5 = pause/resume, numpad +/− = seek ±5 s (works while playing), numpad 0 = exit. Needs the **Replay capture buffer** toggle on (default on).
- **`/ius sonar [on|off]`** — on a flushed alert, plays a *directional* note positioned at the offender's last-known world position: the **pan tells you the direction** and the **pitch tells you the distance** (closer = higher). Eyes-free alerting — keep fighting and just listen for cheats. Additive to chat (not a replacement), gated by the same mute/preset rules, with its own volume in `/ius config`.
- **`/ius clip <seconds> [name]`** — exports the last N seconds of every tracked player's positions + every alert to a portable binary **`.iusclip`** file under `%APPDATA%/.iustitia/clips`. An evidence clip you can play back later, not just a screenshot. `[name]` is the clip's filename (verbatim, so `/ius playclip <name>` round-trips) and also sets the focus player when it matches someone online; omit for `scene_<tick>`. Always writes (explicit export — independent of the Persist-across-sessions toggle).
- **`/ius playclip [name] [1|0.5|0.25]`** — loads a saved `.iusclip` and plays it back in-world as ghost models, at **full (1×) speed by default** (pass `0.5` or `0.25` for slow-mo), exactly like `/ius replay` but from a file. Bare `/ius playclip` lists your saved clips; `/ius playclip off` stops a playing clip.
- **`/ius clips`** — opens a **clip manager screen** listing every saved `.iusclip` with its focus + frame/alert counts; left-click to play, right-click to delete. Same data as bare `/ius playclip`, but browsable.

### Player management
- **`/ius clear <name|all>`** — wipes one player's flags (detection vl, flag timeline, tier, and alert routing → nametag back to green) or, with `all`, everyone's. Tracking and replay keep running; **exemptions are untouched**. A bare `/ius clear` prints usage (a bare clear is too easy to fat-finger into a wipe).
- **`/ius exempt [name [on|off]]`** — exempts a player from **every** check at the `Check.flag` chokepoint (the very first line, before vl is incremented), so they stop flagging entirely. Bare `/ius exempt` lists the currently-exempted players; a bare name toggles; `on`/`off` set explicitly. Exemptions persist to `exemptions.json` (under `%APPDATA%/.iustitia` when persistence is on) and are **not** cleared on world change, so a trusted regular stays exempt across sessions and server hops. Exempting does **not** clear existing flags — pair with `/ius clear <name>` to reset the tier.

All four instant-replay tools have toggles in `/ius config` (Replay capture buffer / Replay hides live players / Sonar alerts / Sonar volume).

### World/HUD overlays (all render-only, depth-tested — no wallhack)
- **Target highlight** — a tier-colored wireframe box around the player your crosshair is on.
- **Ghost trail** — fading breadcrumb trail of recent positions for suspect (yellow/red) players.
- **Burst sparks** — a brief tier-colored particle burst at a player's eye on a fresh tier-relevant alert.
- **Hover tooltip** — after the crosshair rests on one player for ~1.5 s, an expanded top-center banner (tier + score + why-this-tier + FP hint + most-flagged checks). Suppresses the compact crosshair panel while up.
- **Crosshair confidence HUD** — a compact panel near the crosshair with the looked-at player's tier glyph + score + why-this-tier + FP hint.
- **Server-lag HUD indicator** — a top-left ⚠ marker while a server-lag burst is recent (shows *why* alerts are being softened).
- **Tab-list badge** — the tier glyph (+ score) prepended to each other player's row in the Tab list.
- **Offender selfie** — a single-frame third-person screenshot of a freshly-red player, saved to `%APPDATA%/.iustitia/snapshots` (when persistence is on).

Each overlay has its own toggle in `/ius config` and is off-able independently.

## Configuration

- **In-game:** `/ius config` opens a YACL screen with a toggle per check plus all the global switches — master, chat alerts, nametag prefixes / confidence badge / burst pulse / green-tick, alert presets, smart batching, audio cues, lag-soften, compact mode, the render/HUD overlays, persistence, and the first-launch wizard.
- **Commands:** `/ius toggle <check>`, `/ius threshold <check> <value>`, `/ius alerts <check|name> [on|off]`, `/ius verbose`, `/ius reload`, `/ius reset`, `/ius wizard` (re-run the setup wizard).
- **On disk:** `config/iustitia.json` (hand-rolled JSON via Gson; no extra serialization dependency). Edited live values are saved automatically (debounced, off the render thread).
- **Optional persistence:** when **Persist across sessions** is on, moderator notes, tier/flag history, evidence snapshots, transcript/evidence exports, and the player exemption list are saved to `%APPDATA%/.iustitia` (roaming) and reappear after a restart. Off by default — everything is in-memory session-only otherwise. (Evidence clips under `.iustitia/clips` and exemptions under `exemptions.json` always write, since exporting a clip or exempting a player is an explicit action.)
- **Alert presets (`alertLevel`):** 0 = quiet (red-severity band only), 1 = normal (orange + red), 2 = verbose (all). Display-only — no check logic changes. **Smart batching** collapses rapid same-player flags into one line after a quiet window; **audio cues** play a note-block chime per flushed batch (distinct "nuclear" cue for RED from ≥2 primary checks); **lag-soften** prefixes `[lag]` and (under quiet) drops non-red alerts during a server-lag burst. **Compact mode** shortens alert lines and screen rows to one-liners.

Each check's `threshold` is check-specific (Reach→max reach, MultiTarget→min victims, ClickStatistics→CPS cap, SpeedEnvelope→bps cap, Triggerbot→min fast-hits, etc.). `/ius help <check>` prints the live config + description for any check.

## Building from source

```bash
git clone https://github.com/ThoriaDevelopment/Iustitia.git
cd Iustitia
./gradlew build
```

The built mod jar is at `build/libs/iustitia-<version>.jar`. A sources jar is also produced. Requires JDK 21 and internet access on first build (Loom downloads Minecraft + mappings).

```bash
./gradlew runClient   # launch a dev client with Iustitia loaded
```

## Privacy

Iustitia is **purely client-sided**. It reads incoming server packets that your client already receives, runs detection locally, and writes to your local chat and your local config file. It does **not** transmit, upload, or report anything to any server, endpoint, or third party. There is no telemetry, no analytics, no network code beyond reading what the server sends you.

By default, muting, tiering, flag history, moderator notes, and evidence data are all **in-memory** and cleared on restart (or `/ius reset`). The optional **Persist across sessions** toggle (`persistenceEnabled`) writes moderator notes, tier/flag history, evidence snapshots, transcript/evidence exports, and the player exemption list to `%APPDATA%/.iustitia` on your own machine so they survive a restart — still local, still never uploaded. Evidence clips (`.iustitia/clips`) and the exemption list (`exemptions.json`) always write when you explicitly create them. Nothing else is written to disk unless you turn persistence on.

## Known limitations

- **False positives are possible.** Iustitia infers cheating from rebroadcast state, which is lossy. Every borderline (yellow) check is a heuristic. Treat yellow as "worth watching," not "definitely cheating." Red (definitive) checks are tuned to be high-confidence but should still be corroborated via `/ius hist` before acting.
- **Server-side nametags** (stray.gg, mcpvp.club, and any server that hides the vanilla nametag) will not show the prefix — see [Nametag prefixes](#nametag-prefixes).
- **Chunk-unloaded players** are false negatives (we can't observe what the server stops telling us), never false positives.
- **It does not stop cheaters.** It only tells you who looks like one. There is no enforcement.

## License

MIT — see [LICENSE](LICENSE).
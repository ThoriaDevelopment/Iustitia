# Iustitia — User Manual

This is the plain-language guide to using Iustitia. You don't need to be a developer to follow it. If you want the technical internals, see [README.md](README.md).

## What Iustitia does, in one sentence

Iustitia is a Minecraft mod that watches the **other players** on your server and tells **you**, in chat, when one of them is doing something a normal player can't do — hitting from too far away, hitting through walls, flying, sprinting backward, and so on.

### Important: it only tells *you*

- It does **not** kick or ban anyone.
- It does **not** message the server, admins, or anyone else.
- It does **not** send any data anywhere. Everything happens on your own computer.
- It does **not** change how *you* move, look, or play. It only watches others.

Think of it as a radar that whispers to you in chat. Nothing more.

## Installing it

You need Minecraft **Java Edition 1.21.11** with **Fabric**.

1. **Install Fabric Loader** for 1.21.11 (from fabricmc.net).
2. Open your Minecraft launcher, edit the Fabric profile, and make sure these three helper mods are in your `mods` folder (they're free and standard):
   - **Fabric API**
   - **fabric-language-kotlin**
   - **Yet Another Config Lib** (YACL)
3. Copy **`iustitia-1.2.0.jar`** into the same `mods` folder. (Download it from the [latest release](https://github.com/ThoriaDevelopment/Iustitia/releases/latest).)
4. *(Optional but useful)* If you want Iustitia to also catch cheaters on **old 1.8-era servers** (like classic PvP servers), install **ViaFabricPlus** too. Without it, Iustitia still works perfectly on 1.21.11 servers.
5. Launch the game with your Fabric profile and join any server with other players.

You're done. You should start seeing alerts in chat when someone cheats.

## What's new in 1.2.0

The 1.2.0 release adds four moderator-focused features on top of the v1.1.0 instant-replay suite. The detection pipeline, config version, and clip file format are unchanged — v1.1.0 settings and `.iusclip` files carry straight over.

1. **Delete a saved clip by name** — `/ius deleteclip <name>` (alias `/ius delclip <name>`).
2. **Presets** — `/ius preset <name>` applies a named config preset (5 built-ins + your own custom presets).
3. **Spectator-like input suppression** during any active replay or playclip — your movement, look, and interactions stop reaching the server while you're reviewing, and restore the instant the replay/clip stops.
4. **Spectator-quality freecam** — `/ius replay cam freecam` (chunk-bearing playclip only) is a true detached free-fly camera: WASD + mouse, no collision, underground, and the first-person hand is hidden; nosing the camera into a solid block does not black out that block's face.

Details on each follow. The full command reference is in [The `/ius` command](#the-ius-command) below.

### Delete a saved clip — `/ius deleteclip`

```
/ius deleteclip Steve   → delete the saved clip named "Steve"
/ius delclip Steve      → same thing (alias)
/ius delclip nope       → "no clip named nope" if there isn't one
```

You can also still delete clips from the clip manager screen (`/ius clips`, right-click). Bare `/ius playclip` lists your saved clips (so you don't have to remember the names).

### Presets — `/ius preset`

A **preset** is a named, ready-to-load config. Apply one with `/ius preset <name>`; it overwrites the live config in place (every check's `enabled`/`setbackVL`/`threshold`/`decay` plus the display fields — verbose, alertLevel, visuals, sonar, replay, nametags). The five **built-in** presets:

| preset | what it gives you |
|---|---|
| `strict` | Every check on. Alerts sooner (`setbackVL` ×0.5 — flags at half the usual VL). Verbose + `alertLevel 2`. No visuals. Sonar off. Replay on. Nametags on. |
| `standard` | The default tuned 36-check config. No visuals. Sonar off. Replay on. Nametags on. (This is what the first-launch wizard gives you unless you picked another.) |
| `lenient` | Only blatant-tier checks — the subtle/corroboration checks are off: `hitsWithoutSwing`, `hitFlick`, `triggerbot`, `packetGap`, `rotationSnapBack`, `wTap`, `keepSprint`, `jumpOnHurt`, `aimWrap`, `scaffoldRotation`. Alerts only on sustained blatant (`setbackVL` ×2.0). No visuals. Sonar off. Replay on. Nametags on. The disabled list is tunable — tell us which subtle checks you want kept. |
| `debug` | Every visual + feature on (overlays, sonar, audio cues, transcript panel). Verbose + `alertLevel 2`. For validation/troubleshooting, not normal play. |
| `moderation` | Standard detection + hover tooltip on, verbose off, compact mode on, crosshair confidence HUD off, nametags on, sonar off, replay on. Tuned for live moderation: clean chat, the hover banner when you rest on someone, no crosshair clutter. |

**Custom presets** — save your current config as a preset you can re-apply any time:

```
/ius createpreset mystrict   → save the current config as "mystrict"
                               (writes to .iustitia/presets/mystrict.json;
                               survives restart like a built-in)
/ius preset mystrict         → apply it later
/ius deletepreset mystrict   → delete the custom preset "mystrict"
/ius deletepreset strict     → refuses — built-ins can't be deleted
/ius presets                 → list every preset (built-in + custom)
```

**What an apply does NOT touch:** `configVersion`, `mutedChecks`, `mutedPlayers`, `wizardCompleted`, and `persistenceEnabled` are left as-is — a preset only changes detection + display, not your mute list or persistence setting. So applying a preset never silently un-mutes a check you muted, never turns persistence on/off, and never re-runs the wizard.

### Spectator-like input suppression during replays & playclips

While **any** `/ius replay` or `/ius playclip` is active — in every cam mode (free, follow, pov, freecam) — Iustitia treats you as a spectator, not a player. Your inputs stop reaching the live server:

- **Suppressed** — walking / jumping / sprinting / sneaking, mouse look, block-break, block-place, use (right-click), interact (e.g. opening a chest, pressing a button), attack (left-click), and swing. None of these leave your client. To a second account or the server console, you're standing still.
- **Still work** — chat (so you can `/ius status`, `/ius replay pause`, etc.), commands, inventory (E + click), and the hotbar (1–9 / scroll). You can still inspect your inventory mid-replay.
- **Restores instantly** — the moment the replay/clip stops (`/ius replay off`, `/ius playclip off`, end-of-clip, or a numpad-0 exit), every input routes back to the live server. No rubberband, no leftover state — you're playing again the same tick.

This is a deliberate widening of the v1.1.0 freecam-only input freeze: it now covers *every* cam mode and *every* replay/playclip, so you can scrub and inspect without accidentally walking off a ledge or punching a ghost.

### Spectator-quality freecam — `/ius replay cam freecam`

The freecam cam mode (chunk-bearing `/ius playclip` only — it needs the clip's solid world to fly through) is a true detached spectator camera, not a port of the old v1.1.0 free-fly:

- **WASD + mouse** — move on all three axes (Space / Shift for up / down by default) and look with the mouse. The local player's own walking is suppressed while freecam runs, so WASD flies the camera, not the player.
- **No collision** — fly through walls, the ground, the ceiling. Follow a player who went underground right through the stone.
- **Underground** — the clip's captured chunks include every Y section (the full-world capture from `/ius clip`), so the world below the surface is solid and textured all the way down.
- **Sprint flies faster** — hold sprint to move faster through the world, same as creative spectator flight.
- **No black-out inside blocks** — nosing the camera into a solid block does **not** black out that block's face (the v1.2.0 render fix). The world stays readable even when you're clipping through stone.
- **First-person hand hidden** — the vanilla first-person hand model is suppressed while freecam is active, so your arm doesn't float in front of the detached camera.
- **Stop with** `/ius replay cam free` (return to your view), `/ius replay cam follow` / `pov`, or `/ius playclip off` (end the clip → live world restored, no crash, no blocks actually placed).

### Your first launch: the setup wizard

The very first time you launch with Iustitia installed, a small **setup wizard** appears once. It asks how you intend to use the mod and picks sensible starting settings for you:

- **General** — balanced alerts, audio off, nametag on. Good default for most people.
- **Moderation** — verbose alerts (every flag), audio on, full individual alert lines, persistence on (your notes + history survive restarts), live transcript panel.
- **Ranked Player** — quiet alerts (only the high-confidence reds), compact one-line alerts, audio on. Minimal noise while you're trying to play.

You can change any of these later in `/ius config`, and re-run the wizard any time with `/ius wizard`. It only pops up on its own once.

## What you'll see

### 1. Chat alerts

When a player does something suspicious, you get a line in chat that looks like this:

```
[§diustitia§8] §fSteve §e(Reach) §e(3)
```

Reading it left to right:

| part | meaning |
|---|---|
| `[iustitia]` | the mod's tag (dark gray brackets) |
| `Steve` | the player's name |
| `(Reach)` | which check caught them |
| `(3)` | how many times it's happened (the "violation level") |

The **color of `(Reach)` and `(3)`** tells you how confident the detection is:

- **Yellow** — low confidence. Could be a fluke. Worth watching, not worth accusing.
- **Orange** — medium confidence. Repeated behavior.
- **Red** — high confidence. This player is almost certainly cheating at this specific thing.

The color scales up the more times the same check fires on the same player.

**Two handy features built into every alert line:**
- **Hover your mouse over the alert** → a tooltip explains what the check detects, how many alerts that player has this session, the color legend, and a one-line hint about what *legit* thing can cause that check to fire (so you don't over-react to lag-driven flags).
- **Click the alert line** → it runs `/ius hist <name>` for you, opening that player's full flag history (see below).

**Alert behavior you can tune (all in `/ius config` or set by the wizard):**

- **Alert level** — *Quiet* (only the high-confidence reds), *Normal* (orange + red), or *Verbose* (everything). Display-only — detection always runs.
- **Smart batching** — when one player sets off a burst of flags, Iustitia collapses them into a single line after a few seconds of quiet (e.g. `Steve Reach ×4, Backtrack ×2 (last 5s)`). It re-flushes if they keep going. Turn it off if you'd rather see every flag individually.
- **Audio cues** — plays a soft note-block chime when a batch flushes: a higher pling for yellow, a low bass for red, and a distinct two-tone "nuclear" chime for a red flag backed by two or more different checks.
- **Lag-soften** — during a server-lag spike, alert lines get a `[lag]` prefix and (in Quiet mode) non-red alerts are dropped entirely. Watch for the top-left ⚠ indicator — that's your sign that current alerts are being softened.
- **Compact mode** — shrinks alert lines and the history/session/transcript screens to one-liners if you find the default style cluttered.

### 2. Nametag prefixes (above players' heads)

Other players get a small colored tag in front of their name so you can spot cheaters at a glance:

| tag | color | meaning |
|---|---|---|
| `[+]` | green | clean — no alerts this session |
| `[!]` | yellow | suspect — some alerts, but nothing proven |
| `[X]` | red | proven — a high-confidence check has flagged them |

A red tag means **two or more different high-confidence checks** have flagged that player (one alone stays yellow). The tier also **decays** one level per ~10 minutes of quiet, so a tag you saw last match fades if the player goes clean — it latches at the peak, then relaxes.

**Burst pulse.** When a player gets a fresh yellow/red flag, their tag briefly pulses white-and-back for about 3 seconds — a little "look here now" nudge even if you weren't watching chat.

> Want the number behind a tier? The 0–99 confidence score (tier + recency + corroboration) is shown in `/ius hist`, `/ius session`, the snapshot, and the crosshair confidence HUD — not on the nametag itself, to keep the tag a clean at-a-glance glyph.

**Tab list.** The same tag + score is mirrored into the player list (Tab) so you can scan the whole lobby without looking away from the fight.

The prefix only shows when Minecraft would normally show that player's nametag (so it disappears when they sneak behind a wall or go out of range — it's **not** a wallhack). It also never appears on **you**.

> **Note on some servers:** a few servers hide Minecraft's normal nametag and draw their own custom name instead. On those servers the prefix won't show up — there's nothing for Iustitia to attach to. This is expected and not a bug. Known examples: **stray.gg** and **mcpvp.club**. On most servers (including minemen.club and 1.8-era servers) it works fine.

### 3. World & HUD overlays

These are visual helpers drawn on top of the game. They're all **render-only** — they show information, they never change anything, and they're depth-tested so they never become a wallhack (you can't see through walls). Each one has its own toggle in `/ius config` if you don't want it.

- **Crosshair confidence panel** — a small panel near your crosshair showing the tier glyph, confidence score, *why* that tier, and a one-line false-positive hint for the player you're currently looking at.
- **Hover tooltip** — if your crosshair rests on one player for about 1.5 seconds, a bigger banner appears at the top-center with the full breakdown (tier, score, why-this-tier, FP hint, most-flagged checks). It takes over from the compact panel while it's up.
- **Target highlight** — a tier-colored wireframe box appears around the player your crosshair is on, so you don't lose track of who you're reading.
- **Ghost trail** — suspect (yellow/red) players leave a fading breadcrumb trail of their recent positions, so you can see where they came from.
- **Burst sparks** — a brief colored particle puff at a player's eye the instant they get a fresh tier-relevant flag.
- **Server-lag indicator** — a small ⚠ at the top-left appears for a moment after the server lags. It's there to tell you *why* alerts are being softened right now (see "lag-soften" below) — and it's a good reminder that lag itself can cause false flags, so don't over-trust alerts during a lag spike.
- **Offender selfie** — when a player hits red, Iustitia can grab a single-frame third-person screenshot of them and (if persistence is on) save it to `%APPDATA%/.iustitia/snapshots`. It's a one-frame flicker by design — the camera reverts instantly.

### 4. Watch follow-cam

Sometimes a nametag or alert isn't enough and you want to actually **watch** a player. You can:

```
/ius spectate Steve   → start a follow-cam on Steve
/ius spectate         → follow-cam on whoever your crosshair is on
/ius spectate off     → stop
```

Or press the **watch keybind** (default F9) on the player under your crosshair.

While watching, Iustitia:
- **forces F1** (hides the HUD so you can see clearly),
- shows a **third-party view of the target** — your own player model stays rendered in the scene (so it's not confusing), all other entities stay visible,
- lets you **orbit with the mouse** — the target stays centered; turn the mouse to swing the camera around them,
- **auto-stops** the moment you walk more than half a block, get hit, or the target leaves your render range,
- reverts to your normal view the instant it stops (it can never get "stuck" on the follow-cam — the camera resets every frame underneath it).

It's a tool for a closer look, not a permanent spectator mode. Move or get hit and you're right back to playing.

### 5. Instant replay, sonar & evidence clips

Three more moderator tools that go beyond a nametag or a chat line — a rewind of the scene, an eyes-free audio cue, and a portable evidence file. All three are **render/sound-only**: they show or play information, they never change detection, and they send nothing anywhere.

#### Instant replay — `/ius replay`

After something suspicious happens (a kill that looked snapped, a hit that looked too far), you can **rewind the last few seconds and watch it again**:

```
/ius replay                  → replay the last 30s (default), no focus, full speed
/ius replay 60               → 60s, no focus, full speed (a number = the duration)
/ius replay Steve            → default 30s, focus on Steve, full speed
/ius replay Steve 10         → 10s around Steve, full speed
/ius replay Steve 10 0.25    → same, but quarter speed (slower, more detail)
/ius replay off              → stop the replay early
```

What you see:
- **Translucent "ghost" models** of every tracked player at where they *were* during those seconds — drawn as little humanoid figures (head, body, arms, legs) so you can tell a person from a crate, not just a box.
- Each ghost is **colored by that player's tier** (green / yellow / red, same colors as the nametags) and has their **name floating above them**, so you can read who is who. The player you named (Steve, above) is highlighted in **cyan** with a `▶` marker.
- A small **facing nub** on each ghost's head points the way they were facing at that tick.
- By default, the **live players are hidden** during the replay, so only the ghosts show — a "rewind the world" feel. (You can turn that off in `/ius config` if you'd rather see the ghosts overlaid on the live scene.)
- A small banner at the bottom-center shows the focus name, the speed, and a **progress bar** so you know where you are in the rewind.

**Playback controls** while a replay runs — so you can scrub and inspect without re-running it:

```
/ius replay pause        → freeze the replay (and /ius replay resume to play on)
/ius replay seek 5       → jump to 5s into the window
/ius replay step +       → step one frame forward (only while paused; use - for back)
/ius replay speed 0.5    → change speed on the fly (1 / 0.5 / 0.25)
/ius replay cam follow   → camera mode: free (your view) / follow (orbit the focus) / pov (the focus's eyes) / freecam (detached free-fly — WASD + mouse, no collision; chunk-bearing playclip only)
```

There are also **four numpad keybinds** so you don't have to open chat mid-replay:
- **Numpad 5** — pause / resume
- **Numpad +** — jump 5s forward (works while playing)
- **Numpad −** — jump 5s back
- **Numpad 0** — stop the replay and restore the live view

The replay plays through the buffered frames at the speed you picked, then stops on its own and the live view snaps straight back. The live game and detection keep running underneath the whole time — only rendering is swapped. It needs the **Replay capture buffer** toggle on (it's on by default; turn it off in `/ius config` if you never use replays and want to skip the per-tick recording).

> **No relocation for replay:** `/ius replay` plays the ghosts at their **exact recorded world coordinates** — it's instant, meant for the same server + same dimension you're already on, so the recorded coords still line up with the live world around you (v1.1.0 behavior). `/ius replay` never includes the map. **`/ius playclip` is the one that relocates** the scene to you (the focus player starts at your spot), because a clip is meant to be portable — recorded on server A, played back on server B — so raw coords would float the ghosts in mid-air or bury them in the ground. That relocation is gated by **Relocate scene to me** in `/ius config`; turn it off to play a clip at its original coordinates too.

#### Sonar — `/ius sonar`

```
/ius sonar        → toggle directional audio alerts on/off
/ius sonar on     → explicitly on
/ius sonar off    → explicitly off
```

When sonar is on, every time a player's alert batch flushes, Iustitia plays a short **note positioned at that player's last-known location**. Your ears do the work:

- **Which side it comes from** = the direction to the cheater (stereo pan).
- **The pitch** = how far away they are (closer = higher pitch, farther = lower pitch).

So you can keep your eyes on the fight and just *listen* for cheats — a high ping to your right means someone close on your right just flagged. It's **additive to chat**, not a replacement: you still get chat lines unless you mute them. There's a separate **Sonar volume** slider in `/ius config`. The note's sound matches the tier (same vocabulary as the audio cues: bass = red, pling = yellow, harp = green).

#### Evidence clips — `/ius clip` & `/ius playclip`

A screenshot only captures one frame. A **clip** captures the last N seconds of *movement + alerts* as a portable file you can play back later:

```
/ius clip 15                → save the last 15s of everyone's positions + alerts
/ius clip 15 Steve          → same, but the clip is saved as "Steve" (and focuses Steve if he's online)
/ius playclip               → list your saved clips
/ius playclip Steve         → play the "Steve" clip back in-world as ghost models (full speed)
/ius playclip Steve 0.5     → same, but half speed
/ius playclip off           → stop a playing clip early
/ius clips                  → open the clip manager screen (browse / play / delete)
```

The name you give `/ius clip` is the clip's **filename** (so `/ius playclip <same name>` round-trips), and it doubles as the focus player when it matches someone online. If you leave the name off, Iustitia names it `scene_<tick>` for you.

Clips are saved as `.iusclip` files in `%APPDATA%/.iustitia/clips` (or the game-folder `.iustitia/clips` on other platforms). They always write when you ask — **not** gated by the "Persist across sessions" toggle, since exporting a clip is an explicit action. Playing one back looks exactly like a `/ius replay` (ghost models + names + focus highlight + progress bar, plus the same playback controls and numpad keybinds), just from a file instead of the live buffer. Handy for reviewing a clip after the fact, or sharing the file with another moderator who also runs Iustitia. The **clip manager** (`/ius clips`) lists every saved clip with its focus + frame/alert counts (and a chunk-section count when the clip carries a captured world, or a terrain-block count for older v5 clips) — left-click to play, right-click to delete.

> **Full-world capture + solid render (clips only):** with **Clip captures full world** on (default), `/ius clip` snapshots **all loaded chunks** around the action — full 16×16 columns, every Y section **including underground** — bounded by the **Chunk capture radius** (`/ius config`, default 8 → 17×17, capped 4..16) and a total-section budget. The captured world is stored block-by-block (block names + per-section palettes) in the `.iusclip`, so a clip recorded on server A is watchable in full — map included, underground included — on server B. The client only has the chunks still loaded within render distance *when you run `/ius clip`*, and the capture is radius-bounded to keep the file size sane; a bigger radius means a bigger file.
>
> `/ius playclip` then **relocates the scene to you including the map**: the focus player starts at your spot and the captured chunks render around you as **solid, textured blocks** (real block models via vanilla `BlockRenderManager`, face-culled, fullbright — the actual world, not a wireframe), and the **live world is hidden** for the duration so the clip's world replaces it. It's pure render substitution — **no blocks are placed, no packets sent, no server edit** — and the live world is restored the instant the clip stops (`/ius playclip off` or end-of-clip). Then `/ius replay cam freecam` detaches the camera so you can **fly anywhere in the clip's world with WASD + mouse, including underground** (no collision — you can follow a player who went underground right through the stone); the local player's own walking is suppressed while freecam runs, so WASD flies the camera, not the player. `/ius replay` never carries the map (same-server, same-map use). A v5 clip (wireframe-terrain) plays with ghosts relocated + the v5 wireframe shell; a v2–v4 clip plays ghosts-only — both back-compat, no solid world.
>
> **Render distance for FPS:** the **Clip chunk render distance** slider in `/ius config` (default 6, range 4..12) bounds how many chunks of the captured world draw around the camera each frame — the per-chunk block draw is the main cost of a playclip, so if `/ius playclip` drops your FPS, lower this. 4 renders a tight ring around you (best FPS), 12 shows nearly the whole captured world at once. The wireframe shell from v5 clips is no longer drawn once the solid chunk world is present (no double-render).
>
> **Lazy chunk loading (no load spike):** the clip's world **streams in** instead of appearing in one hit. Only the chunks within your render distance are ever built, and they're built **nearest-first, a few per frame** — so the chunk right around your camera appears instantly and the rest fills in over the next second as you look/fly around. Chunks far from the action are never built at all unless you fly toward them. This is what removes the big FPS hitch you'd otherwise get the moment the clip's world loads.

`/ius help replay`, `/ius help sonar`, `/ius help clip`, `/ius help playclip`, and `/ius help clips` each explain their command in one paragraph.

### 6. Managing players — `/ius clear` & `/ius exempt`

Two tools for keeping your tier history honest when you *know* a flag was bogus, or a player is trusted:

**Reset a player's flags** — when you've decided a red/yellow tag was a false alarm (lag, arena drop, a legit PvP exchange) and you don't want it sitting on their record:
```
/ius clear Steve    → wipe Steve's flags: detection vl, timeline, tier, alert routing → green
/ius clear all      → same for everyone
```
Their nametag drops back to green right away. Tracking and replay keep running underneath, and **exemptions are not touched**. A bare `/ius clear` just prints the usage (so you can't fat-finger a wipe).

**Exempt a trusted player** — stops a player from flagging *at all*. The check that would flag them stops before it even counts a violation, so a regular you trust (or a test account) stays green forever:
```
/ius exempt Steve    → exempt Steve (he stops flagging); toggle again to un-exempt
/ius exempt Steve on → explicitly exempt
/ius exempt Steve off → checks run normally again on Steve
/ius exempt          → list everyone who's currently exempt
```
Things to know:
- Exempting does **not** clear existing flags — pair it with `/ius clear Steve` if you want a clean slate.
- Exemptions are saved to `exemptions.json` and survive restarts (when persistence is on) and **server hops / world changes** — the trusted player stays exempt across sessions. (Clips and exemptions always save, since exempting a player is an explicit action.)
- It's per-player, not per-check: an exempt player is skipped by *every* check.


## The `/ius` command

Everything is controlled with the `/ius` command (or `/iustitia` — same thing). Type it and you'll get a list of checks. Here are the ones you'll actually use:

### Seeing what's going on

```
/ius status      → a one-screen health panel:
                   master on/off, how many checks are on, how many players
                   are being watched, the server protocol, and how many
                   alerts have fired this session.

/ius hist        → the top offenders this session (who's been flagged most).

/ius hist Steve  → Steve's recent flags, with the check name and a count
                   for each. This is what clicking an alert line does.

/ius list        → every check, whether it's on, and its settings.
```

### Gathering evidence (the moderation commands)

When you want to actually write up a report or keep track of a suspect, these pull everything Iustitia knows about a player into one place:

```
/ius transcript Steve   → a copyable timeline of Steve's session: swings,
                          inferred hits, reach samples, velocity received,
                          and which checks fired and when. Paste it into
                          Discord. Add `panel` (/ius transcript panel Steve)
                          for a live side panel you can leave open.

/ius evidence Steve     → collapses the last ~10 seconds of Steve's flags
                          into ONE chat line, ready to paste.

/ius note Steve blatant "snap-aim, watch crosshair snap"
                        → tags Steve with a category (closet / blatant /
                          needsReview / legit) and a note. With persistence
                          on, the note follows Steve across restarts.
/ius note Steve         → re-reads the note you left.

/ius session            → a session summary: how many players you tracked,
                          how many hit each tier, who peaked highest
                          (confidence score), total alerts.
/ius session screen     → the same, as a dense one-screen view.

/ius report Steve       → a full report card copied to your clipboard
                          (markdown by default; add `json` for JSON). Or `text` for the chat-friendly transcript form.

/ius snapshot           → a one-line evidence snapshot of your crosshair
                          target, also copied to clipboard.
```

`/ius help <command>` (e.g. `/ius help transcript`) explains any of these in one paragraph.

### Keybinds

There are twelve configurable keybinds, all settable under Minecraft's **Options → Controls → Miscellaneous**:

| keybind | default | what it does |
|---|---|---|
| `snapshot` | K | evidence snapshot of your crosshair target (chat + clipboard) |
| `transcript` | J | toggle the live transcript side panel on your crosshair target |
| `session` | Home | open the session screen |
| `keybinds` | End | open the keybind hub screen |
| `config` | F8 | open the settings screen |
| `note` | N | add a note to your crosshair target |
| `compact` | F7 | toggle compact mode |
| `watch` | F9 | toggle the watch follow-cam |
| `replayPause` | Numpad 5 | pause / resume an active instant replay |
| `replaySeekFwd` | Numpad + | jump an active replay 5s forward (works while playing) |
| `replaySeekBack` | Numpad − | jump an active replay 5s back |
| `replayExit` | Numpad 0 | stop the active replay and restore the live view |

`/ius keybinds` opens a **keybind hub screen** that lists all of them with their current key and a description, and highlights in red any bind that conflicts with another binding so you can fix it.

### Learning what a check means

```
/ius help        → list all commands.
/ius help reach  → explains what the "reach" check detects and shows its
                   current settings. Works for any check name.
```

You can also **hover any alert line** for the same explanation.

### Turning down the noise (false positives)

Sometimes a check fires on a legit player (lag, weird server behavior, edge cases). You have three levels of control:

**Mute one check's chat alerts** (it keeps detecting, just stops printing):
```
/ius alerts reach        → toggles reach alerts on/off
/ius alerts reach off    → explicitly mute them
/ius alerts reach on     → unmute them
```
The player's nametag still turns yellow/red — only the chat line is silenced.

**Mute everything** (a global "shut up" switch):
```
/ius alerts              → toggles ALL chat alerts on/off
```
Detection and nametags keep running; only chat goes quiet.

**Mute one specific player** (by name):
```
/ius alerts Steve        → stops printing Steve's alerts (by uuid, so it
                           survives if Steve leaves and rejoins)
```

**Turn a check off entirely** (stops it from detecting at all):
```
/ius toggle reach        → disables the reach check completely
/ius toggle reach        → again to re-enable
```

**Make a check stricter or looser** (advanced):
```
/ius threshold reach 3.2 → changes the reach check's threshold
```
What the number means depends on the check (reach = max distance, autoclicker = clicks/sec, etc.). Use `/ius help <check>` to see what it does. If in doubt, leave it at the default.

### Other commands

```
/ius config      → opens a settings screen (YACL) with a toggle for every
                   check and all the global options — alert level, batching,
                   audio, nametag/badge/burst, compact mode, every render
                   overlay, persistence, and the first-launch wizard.
                   Easiest for browsing.
/ius wizard      → re-run the first-launch setup wizard (picks a preset).
/ius verbose     → toggles detailed console logging (off by default).
                   Only useful if you're debugging; prints a pipeline
                   summary to latest.log. Leave off for normal play.
/ius reload      → reloads the config file from disk.
/ius reset       → clears all session history and resets everyone's
                   nametag back to green. Use if things feel stale.
/ius spectate    → watch follow-cam on your crosshair target (see above).
/ius replay      → instant-replay the scene (see section 5). Also:
                   /ius replay pause|resume|seek <s>|step +|-
                   |speed 1|0.5|0.25|cam free|follow|pov|freecam|off
                   (numpad 5/+/-/0 mirror these without opening chat).
/ius clip        → export the last N seconds to a .iusclip file (section 5).
/ius playclip     → play a saved .iusclip back in-world (section 5).
/ius clips       → open the clip manager screen (browse / play / delete).
/ius deleteclip  → delete a saved .iusclip by name (alias /ius delclip). See [What's new in 1.2.0].
/ius preset      → apply a named config preset (built-in or custom). See [What's new in 1.2.0].
/ius createpreset → save the current config as a custom preset.
/ius deletepreset → delete a custom preset (built-ins can't be deleted).
/ius presets     → list all presets (built-in + custom).
/ius sonar       → toggle directional audio alerts (section 5).
/ius clear       → reset one player's flags (→green) or everyone's (section 6).
/ius exempt      → exempt a trusted player from all checks (section 6).
```

## Recommended workflow

1. **Install, pick a wizard preset, join a server, play normally.** Let it run.
2. **Glance at nametags + the tab list.** Green `[+]` everywhere = clean lobby. A yellow `[!]` or red `[X]` = look closer. A burst pulse tells you it just happened; `/ius hist <name>` gives the confidence score and the why.
3. **When someone flags in chat**, **hover** the line to see what they did (and what legit cause could mimic it), then **click** it (or `/ius hist <name>`) to see their history. One flag is noise; a pattern across many ticks is signal.
4. **Want a closer look?** `/ius spectate <name>` to follow-cam them, or rest your crosshair on them to bring up the hover tooltip.
5. **Building a case?** `/ius transcript <name>` for the timeline, `/ius note <name> blatant "…"` to tag them, `/ius report <name>` to copy a report card to your clipboard.
6. **If a check is noisy on a legit player**, mute that check (`/ius alerts <check>`) or raise its threshold — don't ignore the whole mod. Most checks are quiet by design. During a lag spike, watch for the `[lag]` prefix / the ⚠ indicator and give alerts extra skepticism.
7. **If a player's nametag is red**, that means **two or more** high-confidence checks flagged them. Those are tuned to be high-confidence. Still worth corroborating with `/ius hist` before you act on it.

## FAQ

**Is this allowed on servers?**
Iustitia is read-only and sends nothing to the server. It's no more "detectable" or "cheating" than reading your own chat. That said, server rules vary; if a server bans third-party mods entirely, don't use it there.

**Will it false-accuse innocent people?**
Possibly, on the yellow tier — those are heuristic checks. That's why yellow means "watch," not "guilty." Red checks are high-confidence. Always check `/ius hist` before judging.

**Why does no prefix show on server X?**
That server hides Minecraft's nametag and draws its own. Iustitia can't prefix a name that isn't there. See the note above.

**Can the watch follow-cam get stuck / change my view permanently?**
No. The camera resets to your normal view every single frame underneath the follow-cam; the follow-cam only persists while it's actively re-applied. The instant you move, get hit, the target leaves range, or you stop it, it stops — you're back to your own view. It also auto-stops if you walk, so you can't forget it's on.

**What does "Persist across sessions" actually save?**
Only your moderator notes, tier/flag history, evidence snapshots, and transcript/evidence exports — to `%APPDATA%/.iustitia` on your own PC. Nothing about *you* or your account is sent anywhere; it's just so your notes and history survive a restart. Off by default; turn it on in `/ius config` or pick the Moderation wizard preset.

**It's not flagging anyone. Is it working?**
Run `/ius status` — if "players tracked" is > 0 and "checks" shows most enabled, it's working; the lobby may just be clean. You can temporarily `/ius verbose` and check `latest.log` for the pipeline heartbeat, then turn it back off.

**Does it work on 1.8 servers?**
Yes, if you also install ViaFabricPlus (so your 1.21.11 client can join them). Iustitia auto-detects the protocol and adjusts combat timing.

**I turned verbose on and my log is huge.**
That's what verbose is for — it logs everything. `/ius verbose` to turn it off. It's off by default.

## Privacy (short version)

Iustitia reads the packets your client already gets from the server, thinks about them locally, and writes to your chat and config file. **Nothing is ever sent anywhere.** No telemetry, no uploads, no reporting.

By default, everything — tier data, flag history, moderator notes, evidence — lives **in memory** and is cleared when you close the game (or `/ius reset`). If you turn on **Persist across sessions** in `/ius config` (the Moderation wizard preset does this for you), then your notes, tier/flag history, evidence snapshots, transcript/evidence exports, and the player exemption list are saved to `%APPDATA%/.iustitia` on your own machine so they survive a restart. Evidence clips (`.iustitia/clips`) and the exemption list (`exemptions.json`) always save when you create them, even with persistence off. That's still local — it's never uploaded anywhere.

## Need the technical details?

See [README.md](README.md) for the architecture, the full check list with technical descriptions, mixin set, and build-from-source instructions.
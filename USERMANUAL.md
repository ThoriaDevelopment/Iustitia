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
3. Copy **`iustitia-v0.1.0.jar`** into the same `mods` folder.
4. *(Optional but useful)* If you want Iustitia to also catch cheaters on **old 1.8-era servers** (like classic PvP servers), install **ViaFabricPlus** too. Without it, Iustitia still works perfectly on 1.21.11 servers.
5. Launch the game with your Fabric profile and join any server with other players.

You're done. You should start seeing alerts in chat when someone cheats.

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
- **Hover your mouse over the alert** → a tooltip explains what the check detects, how many alerts that player has this session, and the color legend.
- **Click the alert line** → it runs `/ius hist <name>` for you, opening that player's full flag history (see below).

### 2. Nametag prefixes (above players' heads)

Other players get a small colored tag in front of their name so you can spot cheaters at a glance:

| tag | color | meaning |
|---|---|---|
| `[+]` | green | clean — no alerts this session |
| `[!]` | yellow | suspect — some alerts, but nothing proven |
| `[X]` | red | proven — a high-confidence check has flagged them |

The prefix only shows when Minecraft would normally show that player's nametag (so it disappears when they sneak behind a wall or go out of range — it's **not** a wallhack). It also never appears on **you**.

> **Note on some servers:** a few servers hide Minecraft's normal nametag and draw their own custom name instead. On those servers the prefix won't show up — there's nothing for Iustitia to attach to. This is expected and not a bug. Known examples: **stray.gg** and **mcpvp.club**. On most servers (including minemen.club and 1.8-era servers) it works fine.

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
                   check and all the global options. Easiest for browsing.
/ius verbose     → toggles detailed console logging (off by default).
                   Only useful if you're debugging; prints a pipeline
                   summary to latest.log. Leave off for normal play.
/ius reload      → reloads the config file from disk.
/ius reset       → clears all session history and resets everyone's
                   nametag back to green. Use if things feel stale.
```

## Recommended workflow

1. **Install, join a server, play normally.** Let it run.
2. **Glance at nametags.** Green `[+]` everywhere = clean lobby. A yellow `[!]` or red `[X]` = look closer.
3. **When someone flags in chat**, **hover** the line to see what they did, then **click** it (or `/ius hist <name>`) to see their history. One flag is noise; a pattern across many ticks is signal.
4. **If a check is noisy on a legit player**, mute that check (`/ius alerts <check>`) or raise its threshold — don't ignore the whole mod. Most checks are quiet by design.
5. **If a player's nametag is red**, that means a *definitive* check (reach, killaura, fly, phase, etc.) fired. Those are tuned to be high-confidence. Still worth corroborating with `/ius hist` before you act on it.

## FAQ

**Is this allowed on servers?**
Iustitia is read-only and sends nothing to the server. It's no more "detectable" or "cheating" than reading your own chat. That said, server rules vary; if a server bans third-party mods entirely, don't use it there.

**Will it false-accuse innocent people?**
Possibly, on the yellow tier — those are heuristic checks. That's why yellow means "watch," not "guilty." Red checks are high-confidence. Always check `/ius hist` before judging.

**Why does no prefix show on server X?**
That server hides Minecraft's nametag and draws its own. Iustitia can't prefix a name that isn't there. See the note above.

**It's not flagging anyone. Is it working?**
Run `/ius status` — if "players tracked" is > 0 and "checks" shows most enabled, it's working; the lobby may just be clean. You can temporarily `/ius verbose` and check `latest.log` for the pipeline heartbeat, then turn it back off.

**Does it work on 1.8 servers?**
Yes, if you also install ViaFabricPlus (so your 1.21.11 client can join them). Iustitia auto-detects the protocol and adjusts combat timing.

**I turned verbose on and my log is huge.**
That's what verbose is for — it logs everything. `/ius verbose` to turn it off. It's off by default.

## Privacy (short version)

Iustitia reads the packets your client already gets from the server, thinks about them locally, and writes to your chat and config file. **Nothing is ever sent anywhere.** No telemetry, no uploads, no reporting. History and tier data live in memory and are cleared when you close the game (or `/ius reset`).

## Need the technical details?

See [README.md](README.md) for the architecture, the full check list with technical descriptions, mixin set, and build-from-source instructions.
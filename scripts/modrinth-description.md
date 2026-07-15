# Iustitia

A client-sided anticheat for Minecraft 1.21.11 (Fabric) that watches *other* players from your own client. No server install, no permissions needed, no outgoing packets, no telemetry. It just watches what the server already sends you and turns it into clear, trustworthy cheat detection.

## Why client sided?

Most anticheats run on the server. Iustitia runs on *your* client, so it works anywhere you can join: vanilla servers, minigame networks, realms, even servers you do not own or moderate. You see every other player's cheat tier right on their nametag, and you decide what to do with it.

## 36 read only checks

Combat, movement, rotation, and packet checks, running per tracked player per client tick. From lag compensated reach and a full KillAura suite to blink, timer, and silent aim tracking. Every check is tuned to avoid false positives first, because an anticheat that cries wolf is worse than having none.

- **16 definitive checks** whose alert on its own proves cheating
- **Lag aware guards** that exempt movement checks during lag spikes, the number one source of false positives
- **Pose aware hitboxes** so crouching, prone, and elytra targets do not trip reach alerts
- **Conservative tiers** that need cross check corroboration before a player ever goes red

## A full moderator toolkit

Iustitia is not just flags. It is a whole workflow for catching and documenting cheaters:

- **Instant replay** (`/ius replay`) rewinds the last 60 seconds and replays the scene in world as ghost models of every tracked player at their real positions. Scrub, slow it down, step frame by frame, switch cameras.
- **Chat history** (`/ius chathist`) keeps a per player chat log, and it actually works on the servers people play. Hypixel, ArchMC, Minemen, and more all decorate chat with ranks, stars, and suffixes, and Iustitia parses past that to recover the real sender and message. Search by player, by phrase, or by both. It is stored per server and it persists.
- **Live side panels** keep a player's transcript or chat history open in a corner overlay while the game keeps running underneath, so you never have to leave the scene to read up on someone.
- **Evidence clips** (`/ius clip`, `/ius playclip`) export the last few seconds of positions and alerts to a portable `.iusclip` file you can replay later or hand to another moderator.
- **Directional sonar** plays a note on every alert. Pan tells you the direction, pitch tells you the distance, so you can hear cheats without taking your eyes off the fight.
- **Long recordings** (`/ius record`) for when the 60 second replay window is not enough.
- **Player exemptions** (`/ius exempt`) so a trusted regular never flags, and session stats, transcripts, and moderator notes that follow players across sessions.

## Honest about what it is

- 0 outgoing packets
- 0 telemetry
- 100% client side
- Free and open source, full source on GitHub

## Requirements

Fabric Loader, Fabric API, Fabric Language Kotlin, and Minecraft 1.21.11.

Run `/ius help` in game for the full command list, or `/ius config` to tune every check, set alert presets, and turn features on or off.

---

*Not affiliated with or endorsed by Mojang or Microsoft. Minecraft is a trademark of Mojang Synergies AB.*
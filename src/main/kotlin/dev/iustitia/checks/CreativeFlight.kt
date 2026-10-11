package dev.iustitia.checks

import net.minecraft.client.MinecraftClient
import net.minecraft.world.GameMode
import java.util.UUID

/**
 * Narrow creative/spectator flight exemption for the [Check.flag] chokepoint.
 *
 * A player the server reports as CREATIVE (an admin flying around, a creative test
 * account) moves in ways that lawfully trip the physics-based movement checks, and a
 * SPECTATOR does the same plus noclip. Exempting happens per-check-id — never blanket —
 * so combat coverage (killaura, reach, …) stays intact on exactly the players the old
 * blanket proposal would have blinded us to, which matters on minigame servers where a
 * creative can still fight.
 *
 * Included checks, each with its lawful-flight trigger:
 * - `flyEnvelope` — flight itself (hover/ascend).
 * - `speedEnvelope` — creative fly speed (~10.9 bps) vs the 10.0 blatancy floor.
 * - `longJump` — sustained airborne horizontal travel.
 * - `noFallDamage` — a descending flight lands hurtless with fallAccum armed.
 * - `backwardSprint` — fast backward/strafe flight with sprint held.
 * - `wallSprint` — sprint held while hovering against a wall (e.g. inspecting a build).
 * - `stepHeight` — the vertical takeoff spike off a grounded tick.
 *
 * Deliberately NOT included: `elytraSpeed` (gliding-gated — fliers don't glide),
 * `spider`/`phaseClip` for creatives (flight collides; creative can't climb or noclip),
 * `teleport`, `noSlow`, `sprintHack` (sneak cancels sprint in every mode, flight
 * included), `waterWalk` (needs surface contact), `packetGap`, all rotation/aim checks
 * and `scaffoldRotation` (aim/packet signals are mode-independent). Spectators
 * additionally skip `phaseClip` ([SPECTATOR_EXTRA_IDS]) because noclip is lawful
 * spectator locomotion — every wall pass would otherwise flag.
 *
 * Residual trade-off, stated plainly: a creative running a movement cheat (step, speed)
 * is also exempt from that check. Movement cheats in creative are near-meaningless as
 * an advantage (they can already fly anywhere), and combat stays watched. Servers that
 * run creative PvP testing flip the `creativeFlightExempt` toggle off.
 *
 * Game mode comes from the server-sent tab list ([net.minecraft.client.network.PlayerListEntry]).
 * Fail-open everywhere: no network handler / no tab entry / unknown mode → not exempt →
 * checks run as before. A minigame server that spoofs or hides game modes can therefore
 * under-exempt (creative still flags — the pre-fix behavior) or over-exempt (a survival
 * shown as creative skips movement checks); both are bounded, visible in `/ius hist`
 * (no flags where you'd expect them), and curable with the toggle.
 */
object CreativeFlight {

    /** Movement checks lawful creative/spectator flight trips (see class doc). */
    val FLIGHT_EXEMPT_IDS: Set<String> = setOf(
        "flyEnvelope",
        "speedEnvelope",
        "longJump",
        "noFallDamage",
        "backwardSprint",
        "wallSprint",
        "stepHeight",
    )

    /** Spectator-only extra: lawful noclip trips phase. */
    val SPECTATOR_EXTRA_IDS: Set<String> = setOf("phaseClip")

    /** Server-sent tab-list game mode, or null when unknown (fail-open to checking). */
    fun gameModeOf(uuid: UUID): GameMode? = try {
        MinecraftClient.getInstance().networkHandler?.getPlayerListEntry(uuid)?.gameMode
    } catch (_: Throwable) {
        null
    }

    /** Narrow gate for [Check.flag]: true only for an exempt (mode, check) pair. */
    fun isExempt(checkId: String, uuid: UUID): Boolean = try {
        when (gameModeOf(uuid)) {
            GameMode.CREATIVE -> checkId in FLIGHT_EXEMPT_IDS
            GameMode.SPECTATOR -> checkId in FLIGHT_EXEMPT_IDS || checkId in SPECTATOR_EXTRA_IDS
            else -> false
        }
    } catch (_: Throwable) {
        false
    }
}

package dev.iustitia.selftest

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/**
 * The `fabric-client-gametest` entrypoint. Loom's `runClientGameTest` boots a real client,
 * creates the deterministic test world, runs every scenario listed here, and exits nonzero on
 * failure -- that exit code is what `scripts/live_selftest.py` and CI consume.
 *
 * Scenario order is deliberate:
 *
 * 1. the pipeline smoke test, because tracking is the precondition that makes every other
 *    result meaningful;
 * 2. the **legit** pass (false-positive gate);
 * 3. the **cheat** pass (bypass gate);
 * 4. the **replay** pass (observer tooling).
 *
 * The inventory is grouped in one place so the coverage question ("is check X driven?") is
 * answered by reading this file next to the check registry, and the manifest printed before
 * the run lets `scripts/live_selftest.py --list` and `--coverage` work offline.
 *
 * ## Filtering
 *
 * Four optional system properties (set by `-Pselftest.*`, which `live_selftest.py` fills in):
 *
 * - `iustitia.selftest.pass` -- `LEGIT` / `CHEAT` / `REPLAY`
 * - `iustitia.selftest.filter` -- comma-separated scenario-name substrings
 * - `iustitia.selftest.source` -- reference client (`Meteor`, `LiquidBounce`, ...)
 * - `iustitia.selftest.tag` -- behaviour tag (`world`, `packet`, ...)
 * - `iustitia.selftest.shard` -- `I/N`, keeps every Nth scenario by sorted name (splits a
 *   slow suite across clients/CI jobs; every shard still reports its own verdict)
 *
 * A filter that selects nothing THROWS, because a typo must never be indistinguishable from a
 * green run. Whatever the filter selects, the smoke test is prepended when it is not already
 * included, so every run still proves the tracker saw its bots.
 */
class SelfTestEntrypoint : FabricClientGameTest {

    override fun runTest(ctx: ClientGameTestContext) {
        // FIRST, before any scenario inventory or filtering work: mute the client. A run that dies
        // on a bad filter, a missing scenario or a world-creation timeout should still be silent,
        // and muting after `filter()` (which is allowed to throw) would skip exactly those runs.
        SelfTest.silenceClientAudio(ctx)

        val all = allScenarios()
        val scenarios = shard(filter(all))
        SelfTest.printManifest(scenarios)

        val reports = SelfTest.runAll(ctx, scenarios)
        SelfTest.printReport(reports)

        val failed = reports.filter { !it.passed }
        if (failed.isNotEmpty()) {
            val lines = failed.joinToString("\n") { r ->
                "- ${r.pass}/${r.scenario} [${r.source}]: " + (r.error
                    ?: "missed=${r.missedChecks} falsePositives=${r.falsePositives} vl=${r.vl}")
            }
            throw AssertionError(
                "Iustitia self-test: ${failed.size}/${reports.size} scenario(s) FAILED:\n$lines\n" +
                    "See docs/automated-live-testing.md for the scenario definitions and tuning knobs."
            )
        }
    }

    /** Every scenario, in run order. */
    fun allScenarios(): List<SelfTest.Scenario> = buildList {
        add(Scenarios.pipelineSmoke())
        addAll(legitPass())
        addAll(cheatPass())
        addAll(replayPass())
    }

    /** The first pass: vanilla-accurate behavior, zero alerts expected. */
    fun legitPass(): List<SelfTest.Scenario> = listOf(
        Scenarios.legitLocomotion(),
        Scenarios.legitBunnyHop(),
        Scenarios.legitCombat(),
        Scenarios.legitKnockback(),
        Scenarios.legitFall(),
        Scenarios.legitWindCharge(),
        Scenarios.legitMaceSmash(),
        Scenarios.legitSpeedPotion(),
        Scenarios.legitWater(),
        Scenarios.legitLadder(),
        Scenarios.legitElytra(),
        Scenarios.legitShieldEat(),
        // FP-direction coverage for the four detectors hardened in the v1.4.0 audit pass. Each
        // presents the *legitimate* shape the detector used to read as a cheat; the assertions and
        // the discriminating observable per scenario are documented in the scenario KDoc and in the
        // §5 coverage table (docs/automated-live-testing.md).
        Scenarios.legitFlyRamp(),
        Scenarios.legitTriggerbotStrafe(),
        Scenarios.legitNoKbAirborne(),
        Scenarios.legitMultiTargetSweep(),
        // FP-direction coverage for the swing-source audit. A player who digs continuously emits arm
        // swings on the server's fixed relay clock, which is the same input an autoclicker produces;
        // this is the legitimate shape `clickStatistics` reads as a fixed-delay clicker.
        Scenarios.legitMiningCadence(),
        // The second half of the swing-source audit: a digging player is a permanently eligible
        // attacker for attack inference, so a teammate's unattributed damage nearby lands on them.
        Scenarios.legitMiningNearHurt(),
        // The attribution follow-up. Each of these three isolates one branch of the rule that a hurt
        // is attributed only to the player the server named: an unnamed hurt beside a swinger who is
        // not digging, a named cause that resolves to nobody plus the knockback that comes with it,
        // and the same non-player cause seen by `hitsWithoutSwing`'s own resolver.
        Scenarios.legitHurtIdlessBystander(),
        Scenarios.legitHurtMobKnockback(),
        Scenarios.legitHitsWithoutSwingBystander(),
    )

    /**
     * The second pass: unfair advantage, alerts expected (or a documented finding where the
     * detector's own VL economy cannot reach its setback -- see the scenario KDoc).
     */
    fun cheatPass(): List<SelfTest.Scenario> = listOf(
        // --- combat: reach / multi-target / clicking ---
        CheatCombat.reachLiquidBounce(),
        CheatCombat.reachVape(),
        CheatCombat.reachGhost(),
        // The dig exemption's bypass proof: a reach module that also holds a fake dig open must
        // still be attributed and still alert.
        CheatCombat.reachDigging(),
        CheatCombat.multiAura(),
        CheatCombat.multiAuraPair(),
        CheatCombat.autoClickerUniform(),
        CheatCombat.autoClickerFast(),
        CheatCombat.autoClickerRecorded(),
        // --- combat: no-knockback ---
        CheatCombat.noKnockback(),
        // --- combat: aim ---
        CheatCombat.killAuraSnap(),
        CheatCombat.killAuraTrack(),
        CheatCombat.killAuraDrift(),
        CheatCombat.killAuraDriftRearm(),
        CheatMovement.rotationTracking(),
        CheatMovement.snapBack(),
        CheatMovement.aimWrap(),
        // --- combat: line of sight / timing / crits ---
        CheatCombat.throughWalls(),
        CheatCombat.criticalsMicroY(),
        CheatCombat.criticalsFixedPhase(),
        CheatCombat.maceSmash(),
        CheatCombat.keepSprint(),
        CheatCombat.wTap(),
        CheatCombat.jumpOnHurt(),
        CheatCombat.backtrack(),
        CheatCombat.hitsWithoutSwing(),
        CheatCombat.hitsWithoutSwingRearm(),
        CheatCombat.autoBlockShield(),
        CheatCombat.autoBlockConsume(),
        CheatCombat.hitFlick(),
        CheatCombat.triggerBot(),
        // --- movement: speed / flight ---
        CheatMovement.speedLiquidBounce(),
        CheatMovement.speedBlatant(),
        CheatMovement.speedSubCap(),
        CheatMovement.flyHover(),
        CheatMovement.flyAscend(),
        CheatMovement.flyStrafeHop(),
        CheatMovement.flyAntiKick(),
        // --- movement: packet flow / fall ---
        CheatMovement.verticalClip(),
        CheatMovement.horizontalClipper(),
        CheatMovement.blink(),
        CheatMovement.noFall(),
        CheatMovement.noFallSpoof(),
        CheatMovement.noFallBurstSpoof(),
        CheatMovement.noFallMaceEvade(),
        // --- movement: world interaction ---
        CheatMovement.spider(),
        CheatMovement.step(),
        CheatMovement.longJump(),
        CheatMovement.noSlow(),
        CheatMovement.omniBackward(),
        CheatMovement.omniStrafe(),
        CheatMovement.wallSprint(),
        CheatMovement.sprintHackWater(),
        CheatMovement.sprintHackSneak(),
        CheatMovement.sprintHackBlind(),
        CheatMovement.waterWalk(),
        CheatMovement.elytraFly(),
        CheatMovement.phase(),
        // --- movement: rotation / pitch / scaffold ---
        CheatMovement.pitchBound(),
        CheatMovement.scaffold(),
    )

    /** The observer-tooling pass: replay + clip + preset/config mechanics. */
    fun replayPass(): List<SelfTest.Scenario> = listOf(
        ReplayScenarios.BufferCapture(),
        ReplayScenarios.PlaybackLifecycle(),
        ReplayScenarios.PlaybackControls(),
        ReplayScenarios.CameraModes(),
        ReplayScenarios.ClipRoundTrip(),
        ReplayScenarios.ShowSelf(),
        ReplayScenarios.PlayclipRelocation(),
        PresetScenarios.PresetDisabledGate(),
        PresetScenarios.PresetApplyCoverage(),
        PresetScenarios.PresetBuiltInCoverage(),
    )

    /**
     * Narrow the run to the scenarios a contributor is working on. A filter that selects nothing
     * throws (see the class doc); the smoke test is always kept so tracking is verified even in a
     * one-scenario run.
     */
    fun filter(scenarios: List<SelfTest.Scenario>): List<SelfTest.Scenario> {
        val pass = prop("pass")
        val filter = prop("filter")
        val source = prop("source")
        val tag = prop("tag")
        if (pass.isEmpty() && filter.isEmpty() && source.isEmpty() && tag.isEmpty()) return scenarios

        val needles = filter.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        fun matches(s: SelfTest.Scenario): Boolean =
            (needles.isEmpty() || needles.any { s.name.contains(it, ignoreCase = true) }) &&
                (source.isEmpty() || s.source.equals(source, ignoreCase = true)) &&
                (tag.isEmpty() || s.tags.any { it.equals(tag, ignoreCase = true) })

        // The pass filter is applied first so `--pass cheat --source Meteor` means what it says.
        // The smoke test is normally excluded from the body and re-prepended below, so it is always
        // run without being counted as a filter match -- but when a filter names it EXPLICITLY it
        // joins the body like any other scenario, because `--check pipeline-smoke` (the cheapest
        // available smoke run, and a name `--list` prints) must not fail as "matched nothing".
        val smoke = scenarios.firstOrNull { it.name == SMOKE_NAME }
        val smokeNamed = smoke != null && matches(smoke)
        val body = scenarios.filter { s ->
            (s.name != SMOKE_NAME || smokeNamed) &&
                (pass.isEmpty() || s.pass.equals(pass, ignoreCase = true)) && matches(s)
        }
        if (body.isEmpty()) {
            val available = scenarios.joinToString("\n  ") { "${it.pass}/${it.name} [${it.source}]" }
            throw AssertionError(
                "Iustitia self-test filter matched no scenarios " +
                    "(pass='$pass', filter='$filter', source='$source', tag='$tag').\n" +
                    "Available:\n  $available"
            )
        }
        val selected = if (smoke != null && body.none { it.name == smoke.name }) listOf(smoke) + body else body
        println(
            "[iustitia-selftest] running ${selected.size}/${scenarios.size} scenario(s) -- " +
                "pass='$pass' filter='$filter' source='$source' tag='$tag'"
        )
        return selected
    }

    /**
     * Keep every Nth scenario of a stable sort, so `--shard 1/3`, `2/3`, `3/3` partition the
     * suite exactly and reproducibly (no overlap, nothing dropped). Splitting matters as the
     * library grows: the full three-pass run boots a client once but exercises every drive, and
     * shards let CI fan that across jobs without any scenario going unrun.
     *
     * A malformed shard spec or an out-of-range index THROWS for the same reason an empty
     * filter does: a typo must never be mistakable for a green run.
     */
    fun shard(scenarios: List<SelfTest.Scenario>): List<SelfTest.Scenario> {
        val spec = prop("shard")
        if (spec.isEmpty()) return scenarios
        val parts = spec.split('/')
        val index = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val count = parts.getOrNull(1)?.trim()?.toIntOrNull()
        if (parts.size != 2 || index == null || count == null || count < 1 || index < 1 || index > count) {
            throw AssertionError("Invalid --shard spec '$spec'; expected I/N with 1 <= I <= N (e.g. 1/3).")
        }
        val ordered = scenarios.sortedBy { it.name }
        val selected = ordered.filterIndexed { i, _ -> i % count == index - 1 }
        if (selected.isEmpty()) {
            throw AssertionError("Iustitia self-test shard $spec selected no scenarios out of ${scenarios.size}.")
        }
        println("[iustitia-selftest] shard $index/$count: ${selected.size}/${scenarios.size} scenario(s)")
        return selected
    }

    private fun prop(name: String): String =
        System.getProperty("iustitia.selftest.$name")?.trim().orEmpty()

    private companion object {
        /** The tracking precondition, kept in every filtered run. */
        const val SMOKE_NAME = "pipeline-smoke"
    }
}

package dev.iustitia.selftest

import dev.iustitia.config.ConfigManager
import dev.iustitia.replay.ClipPlayback
import dev.iustitia.replay.ClipStore
import dev.iustitia.replay.ReplayBuffer
import dev.iustitia.replay.ReplayState

/**
 * Replay / clip verification scenarios. These exercise the observer tooling end to
 * end in the same gametest world: capture a scripted scene into the rolling buffer,
 * start/pause/seek/stop a replay, and round-trip a `.iusclip` export.
 *
 * Pass membership is LEGIT (these run in the first pass group): they assert pipeline
 * mechanics, not detector verdicts, and must never flag their own bot.
 */
object ReplayScenarios {

    /** Buffer capture fills to >=N frames for a moving bot. */
    class BufferCapture : SelfTest.Scenario("replay-buffer-capture", "REPLAY") {
        override fun run(b: SelfTest.ScenarioBuilder) {
            val bot = b.bot("Actor", 0.0, 0.0)
            var t = 0
            b.everyTick {
                bot.teleportTo(0.0, b.groundY, -t * 0.2)
                t++
            }
            b.runFor(80)
            val frames = ClientThread.computeOnClient { _ -> ReplayBuffer.frameCount() }
            if (frames < 60) {
                throw ScenarioFailed(
                    "REPLAY capture failure: buffer held $frames frames after 80 moving ticks " +
                        "(expected >=60 -- replayCapture is on by default; a capture regression empties every replay)."
                )
            }
        }
    }

    /** Full playback lifecycle: start -> frame progression -> pause -> seek -> stop -> restored. */
    class PlaybackLifecycle : SelfTest.Scenario("replay-playback-lifecycle", "REPLAY") {
        override fun run(b: SelfTest.ScenarioBuilder) {
            val bot = b.bot("Actor", 0.0, 0.0)
            var t = 0
            b.everyTick {
                bot.teleportTo(0.0, b.groundY, -t * 0.2)
                t++
            }
            b.runFor(80)

            ClientThread.runOnClient { _ ->
                val window = ReplayBuffer.snapshot(4, dev.iustitia.Iustitia.tickCounter)
                val started = ReplayState.start(
                    window, focus = null, speed = ReplayState.SPEED_FULL,
                    hideLive = false, relocate = false, legacy = true,
                )
                check(started) { "ReplayState.start refused a non-empty window (frames=${window.frames.size})" }
            }
            b.runFor(20)
            val afterRun = ClientThread.computeOnClient { _ -> ReplayState.frameCount() > 0 }
            check(afterRun) { "replay frame count was 0 while active" }

            ClientThread.runOnClient { _ -> check(ReplayState.togglePause()) }
            b.runFor(5)
            ClientThread.runOnClient { _ -> ReplayState.seekBy(1f) }
            b.runFor(5)
            ClientThread.runOnClient { _ -> ReplayState.stop("selftest") }
            b.runFor(2)
            val stillActive = ClientThread.computeOnClient { _ -> ReplayState.active }
            check(!stillActive) { "ReplayState.stop() left the replay active -- live view would stay hidden" }
        }
    }

    /**
     * Camera modes: the observer's view switching. Entering POV must force first-person (so the
     * hidden player's own body doesn't float in the ghost's eye view), leaving POV must restore
     * the user's chosen perspective, and entering/leaving FREECAM must flip the freecam pose flag
     * the camera mixin reads. A camera mode that fails to restore leaves a player stuck in
     * first-person after a replay, which is the kind of thing only this suite would catch.
     */
    class CameraModes : SelfTest.Scenario(
        "replay-camera-modes", "REPLAY", "vanilla", setOf(Tags.REPLAY),
    ) {
        override fun run(b: SelfTest.ScenarioBuilder) {
            val bot = b.bot("Actor", 0.0, 0.0)
            var t = 0
            b.everyTick {
                bot.teleportTo(0.0, b.groundY, -t * 0.2)
                t++
            }
            b.runFor(80)

            val before = ClientThread.computeOnClient { mc -> mc.options.perspective }
            ClientThread.runOnClient { _ ->
                val window = ReplayBuffer.snapshot(4, dev.iustitia.Iustitia.tickCounter)
                check(ReplayState.start(window, bot.uuid, ReplayState.SPEED_FULL, true, false, true)) {
                    "ReplayState.start refused a non-empty window"
                }
            }
            b.runFor(4)

            ClientThread.runOnClient { _ -> ReplayState.setCameraMode(ReplayState.CameraMode.POV) }
            val pov = ClientThread.computeOnClient { mc -> mc.options.perspective }
            check(pov == net.minecraft.client.option.Perspective.FIRST_PERSON) {
                "POV camera mode did not force first-person (perspective=$pov)"
            }

            ClientThread.runOnClient { _ -> ReplayState.setCameraMode(ReplayState.CameraMode.FREE) }
            val restored = ClientThread.computeOnClient { mc -> mc.options.perspective }
            check(restored == before) {
                "leaving POV left the perspective at $restored instead of restoring $before"
            }

            ClientThread.runOnClient { _ -> ReplayState.setCameraMode(ReplayState.CameraMode.FREECAM) }
            b.runFor(2)
            check(ClientThread.computeOnClient { _ -> ReplayState.freecamActive }) {
                "FREECAM mode did not arm the freecam pose (freecamActive=false)"
            }
            ClientThread.runOnClient { _ -> ReplayState.setCameraMode(ReplayState.CameraMode.FREE) }
            check(!ClientThread.computeOnClient { _ -> ReplayState.freecamActive }) {
                "leaving FREECAM left freecamActive=true -- the live view would stay overridden"
            }

            ClientThread.runOnClient { _ -> ReplayState.stop("selftest") }
            b.runFor(2)
        }
    }

    /**
     * Playback controls an observer actually uses: pause, frame-step, seek, percentage seek and
     * speed cycling. Each must move or hold the playhead as documented -- a step that only works
     * while playing, or a speed cycle that lands on an unsupported tier, is a tooling regression
     * that no static check can see.
     */
    class PlaybackControls : SelfTest.Scenario(
        "replay-playback-controls", "REPLAY", "vanilla", setOf(Tags.REPLAY),
    ) {
        override fun run(b: SelfTest.ScenarioBuilder) {
            val bot = b.bot("Actor", 0.0, 0.0)
            var t = 0
            b.everyTick {
                bot.teleportTo(0.0, b.groundY, -t * 0.2)
                t++
            }
            b.runFor(100)

            ClientThread.runOnClient { _ ->
                val window = ReplayBuffer.snapshot(4, dev.iustitia.Iustitia.tickCounter)
                check(ReplayState.start(window, null, ReplayState.SPEED_FULL, true, false, true)) {
                    "ReplayState.start refused a non-empty window"
                }
            }
            b.runFor(10)

            ClientThread.runOnClient { _ -> check(ReplayState.togglePause()) { "togglePause did not pause" } }
            val pausedProgress = ClientThread.computeOnClient { _ -> ReplayState.progress() }
            b.runFor(10)
            val stillProgress = ClientThread.computeOnClient { _ -> ReplayState.progress() }
            check(pausedProgress == stillProgress) {
                "a paused replay advanced ($pausedProgress -> $stillProgress) -- pause is not holding the playhead"
            }

            ClientThread.runOnClient { _ -> ReplayState.step(5) }
            val stepped = ClientThread.computeOnClient { _ -> ReplayState.progress() }
            check(stepped > stillProgress) { "frame-step did not advance the playhead ($stillProgress -> $stepped)" }

            ClientThread.runOnClient { _ -> ReplayState.seekTo(0f) }
            val seeked = ClientThread.computeOnClient { _ -> ReplayState.progress() }
            check(seeked == 0f) { "seekTo(0) left the playhead at $seeked" }

            val s0 = ClientThread.computeOnClient { _ -> ReplayState.currentSpeed() }
            val s1 = ClientThread.computeOnClient { _ -> ReplayState.cycleSpeed() }
            check(s1 != s0) { "cycleSpeed returned the same speed ($s0)" }

            ClientThread.runOnClient { _ -> ReplayState.togglePause() }
            b.runFor(4)
            ClientThread.runOnClient { _ -> ReplayState.stop("selftest") }
            b.runFor(2)
        }
    }

    /**
     * Relocated playback of a clip whose window carries captured world state: the observer's
     * `/ius playclip` path. Asserting the relocation offset and the captured-terrain snapshot are
     * populated is what separates "the clip loaded" from "the clip can actually be shown where the
     * observer is standing" -- a clip that loads without its world renders as ghosts in the wrong
     * place.
     */
    class PlayclipRelocation : SelfTest.Scenario(
        "replay-playclip-relocation", "REPLAY", "vanilla", setOf(Tags.REPLAY),
    ) {
        override fun run(b: SelfTest.ScenarioBuilder) {
            val bot = b.bot("Actor", 0.0, 0.0)
            // build a little scene so the captured world state has something in it
            val g = b.groundY.toInt()
            b.fill(0, g, 2, 2, g + 1, 3, net.minecraft.block.Blocks.STONE)
            var t = 0
            b.everyTick {
                bot.teleportTo(0.0, b.groundY, -t * 0.2)
                t++
            }
            b.runFor(80)

            ClientThread.runOnClient { _ ->
                val window = ReplayBuffer.snapshotForExport(4, dev.iustitia.Iustitia.tickCounter)
                check(window.frames.isNotEmpty()) { "snapshotForExport produced an empty window" }
                check(ReplayState.start(window, bot.uuid, ReplayState.SPEED_FULL, true, true, false)) {
                    "RelayState.start refused a relocatable window"
                }
            }
            b.runFor(6)

            val relocation = ClientThread.computeOnClient { _ -> ReplayState.relocOffset != null }
            check(relocation) {
                "relocated replay has no relocOffset -- the ghosts would render at the recorded \n" +
                    "(distant) coordinates instead of around the observer"
            }
            check(ClientThread.computeOnClient { _ -> ReplayState.segments.isNotEmpty() }) {
                "the exported window carries no segments -- multi-world clips cannot be relocated"
            }

            ClientThread.runOnClient { _ -> ReplayState.stop("selftest") }
            b.runFor(2)
        }
    }

    /** `.iusclip` round trip: export -> file exists -> load -> replayable -> delete. */
    class ClipRoundTrip : SelfTest.Scenario("replay-clip-roundtrip", "REPLAY") {
        override fun run(b: SelfTest.ScenarioBuilder) {
            val bot = b.bot("Actor", 0.0, 0.0)
            var t = 0
            b.everyTick {
                bot.teleportTo(0.0, b.groundY, -t * 0.2)
                t++
            }
            b.runFor(80)

            val name = "selftest_clip"
            // `computeOnClient` carries a non-null Java type parameter, so each hop returns a
            // non-null sentinel ("" / -1) and the assertion reports the failure in harness words.
            val saved = ClientThread.computeOnClient { _ ->
                val window = ReplayBuffer.snapshotForExport(4, dev.iustitia.Iustitia.tickCounter)
                ClipStore.save(name, window, null) ?: ""
            }
            check(saved.isNotEmpty()) { "ClipStore.save returned null -- export path is broken" }

            try {
                val frames = ClientThread.computeOnClient { _ -> ClipStore.metadata(saved)?.frameCount ?: -1 }
                check(frames > 0) { "clip metadata reports $frames frames for a fresh clip" }

                val started = ClientThread.computeOnClient { _ -> ClipPlayback.start(saved, ReplayState.SPEED_FULL) }
                check(started is ClipPlayback.Result.Started) { "ClipPlayback.start failed: $started" }
                b.runFor(10)
                ClientThread.runOnClient { _ -> ReplayState.stop("selftest") }

                val deleted = ClientThread.computeOnClient { _ -> ClipStore.delete(saved) }
                check(deleted) { "ClipStore.delete could not remove the clip it just wrote" }
            } finally {
                // Belt-and-braces cleanup so a failed assertion never leaves a stray file.
                ClientThread.runOnClient { _ -> ClipStore.delete(name) }
            }
        }
    }
}

# Live verification guide

Static compilation cannot prove that a Minecraft mixin applied at runtime, that a camera restores correctly, or that a replay does not leave the live world hidden. Use this guide for changes that affect client behavior.

> **Run the automated suite first.** Detector and observer logic is verified without a
> human by `python scripts/live_selftest.py` — a real client, scripted legitimate and
> cheating bot players, and a false-positive/bypass verdict per change. See
> [automated-live-testing.md](automated-live-testing.md). This checklist remains
> authoritative for what that suite cannot observe: mixin packet decode, rendering and
> screenshots, ViaFabricPlus/1.8 behavior, and server-side interaction.

## Before launching

Run the static checks first:

```bash
python scripts/verify_contribution.py --static
./gradlew test
./gradlew build
```

Then launch the development client:

```bash
./gradlew runClient
```

Use a test world or a server where you are allowed to test. Do not test packet suppression, movement, or detection behavior on a server if its rules prohibit the mod.

## Baseline smoke test

For every runtime change:

1. Confirm the client reaches the title screen without a mixin application error.
2. Create or join a test world.
3. Confirm `/ius status` opens and reports the expected check count.
4. Confirm the config screen opens.
5. Confirm the client can disconnect and return to the title screen.
6. Confirm the feature is inert or unchanged when its toggle is off.
7. Enable verbose logging only when needed, then turn it off after the test.

## Detection changes

For a changed check:

1. Confirm the check appears in `/ius list`.
2. Confirm its config slice is present and editable.
3. Confirm a normal movement/combat trace does not immediately produce an alert.
4. Exercise the intended suspicious-like trace in a controlled test.
5. Confirm flags appear in `/ius hist` when expected.
6. Confirm alert throttling, join grace, decay, and tier behavior.
7. Confirm disabling the check stops new flags while preserving expected history behavior.
8. Confirm `/ius clear <name>` resets the detection record without removing tracking.
9. Confirm world change and disconnect reset transient state safely.

A synthetic or controlled trace is evidence of code behavior, not proof that the detector is correct on every server.

## Packet and inference changes

1. Launch with the development client and inspect the log for mixin errors.
2. Join a test world with at least one other player if possible.
3. Confirm `/ius status` reports tracked players.
4. Exercise the relevant packet event: swing, hurt, velocity, effect, chat, join, respawn, or disconnect.
5. Confirm verbose logs show the expected signal or inferred event.
6. Test duplicate packet channels where applicable.
7. Test a player leaving render range and reappearing.
8. Test a death-respawn separately from a dimension/world change.
9. Confirm no outgoing gameplay behavior was added unintentionally.

## Mixin changes

For any new or changed mixin:

1. Start the client from a clean `run/` state if the issue may involve stale generated data.
2. Confirm there is no `InvalidMixinException`, target-not-found error, or injection failure.
3. Exercise the target method in-game.
4. Exercise the inverse path where the mixin should not run.
5. Stop the feature and confirm vanilla behavior returns on the next frame/tick.
6. Test disconnect, world change, and reload paths if state is involved.

## Rendering and HUD changes

1. Test with the feature off.
2. Test with the feature on in first person.
3. Test third person if player rendering is involved.
4. Test a target behind terrain where depth testing should apply.
5. Test an unloaded or removed entity.
6. Test a crowded scene if performance is relevant.
7. Check that the overlay does not appear over configuration or history screens.
8. Capture a screenshot or short recording for visual changes when practical.
9. Verify that an exception in one rendered object does not remove the live scene.

## Replay, clips, and freecam

1. Fill the replay buffer with a few seconds of tracked movement.
2. Start `/ius replay`.
3. Test pause, resume, seek, step, speed, and stop.
4. Confirm live players are restored immediately after stop.
5. Test `/ius clip` and inspect that the file is written locally.
6. Load it with `/ius playclip`.
7. Test legacy and modern playclip modes if either was changed.
8. Test freecam movement and mouse look if affected.
9. Confirm chat, commands, inventory, and hotbar behavior matches the documented mode.
10. Confirm movement/interactions are restored after playback stops.
11. Test world/dimension change during playback.
12. Test disconnect during playback.
13. Test a missing/corrupt/old clip and confirm it fails without crashing.

### Replay merge (v13 segments, entity ghosts, block edits)

1. Capture a window that contains non-player entities; confirm the ghosts match kind and position, and that **Clip captures entities** off hides them all.
2. Place and break blocks during a window, then replay/clip it; confirm the world shows the edits, and that seeking **backwards** restores the earlier state.
3. Teleport (or change dimension) mid-window, export a clip, and confirm `/ius clip` reports more than one segment and replays each place instead of only the last.
4. Confirm `/ius clip` returns without a multi-second freeze, and that turning **Clip captures full world** off stops the rolling capture.
5. Confirm the segment's baked world is re-meshed after edits (no stale chunks or leaked buffers across segment switches).
6. Confirm `/ius replay` still renders ghosts over the **live** world (it must not pull in a captured world).
7. Confirm an older clip (v2–v12) still loads and that a SnapClip-era clip loads without a fabricated alert timeline.

## Persistence and configuration

1. Change a setting in YACL and confirm it takes effect immediately.
2. Restart or reload as appropriate.
3. Inspect only the documented local file and confirm the field round-trips.
4. Test a missing field to verify additive defaults.
5. Test an older config version when migration behavior changed.
6. Test malformed JSON and confirm the client remains usable.
7. Confirm persistence-off mode does not write passive evidence data.
8. Confirm explicit clip export behavior remains documented.

## Verification evidence for a PR

Record:

- The exact commands run.
- The client launch result.
- The scenario tested.
- The expected result.
- The observed result.
- Any limitations, skipped tests, or baseline failures.

A useful report is concise and factual:

```text
Live verification:
- runClient: launched successfully; no mixin errors.
- Replay: pause/seek/stop tested; live player rendering restored immediately.
- Clip: exported and reloaded locally; corrupt-file path returned a user error without a crash.
- Not tested: multiplayer attack inference, because no second test account was available.
```
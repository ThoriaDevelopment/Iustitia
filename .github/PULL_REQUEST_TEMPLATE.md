## Summary

<!-- What problem does this PR solve? Keep this focused. -->

## Changes

- 

## Affected areas

- [ ] Detection/check logic
- [ ] Packet observation or inference
- [ ] Config or presets
- [ ] Commands or UI
- [ ] Rendering or HUD
- [ ] Replay, clips, camera, or input
- [ ] Persistence or local data
- [ ] Documentation/tests/tooling

## Verification

Commands run:

```text
python scripts/verify_contribution.py --static
./gradlew test
./gradlew build
```

Results:

- Static verifier:
- Tests:
- Build:

### Live client verification

<!-- Required for mixins, packet handling, detection, rendering, replay, camera, input, or other runtime behavior. State exact scenarios or write NOT RUN with a reason. -->

- Client launch/mixin application:
- Scenario(s):
- Expected result:
- Observed result:
- Not tested:

## Safety and privacy review

- [ ] No telemetry, uploads, analytics, or remote reporting were added.
- [ ] No unintended outgoing gameplay packets were added.
- [ ] Fail-open behavior is preserved where appropriate.
- [ ] Local-world, camera, input, and persistence state restore after stop/error/world change where relevant.
- [ ] Logs, screenshots, clips, and test data contain no secrets or private information.

## Documentation and migration

- [ ] User-facing documentation updated, or no update is needed.
- [ ] Config/clip migration reviewed, or no migration is needed.
- [ ] Generated documentation source data updated if applicable.

## AI assistance

- AI tool(s) used: <!-- none / tool name and version -->
- What the tool contributed:
- What I reviewed manually:

## Known limitations and follow-up work

- 

# Maintainer contribution checklist

Use this checklist during review. Not every item applies to every PR.

## Scope and design

- [ ] The PR solves one focused problem.
- [ ] The affected source-of-truth layers are correct.
- [ ] The implementation does not quietly include unrelated worktree changes.
- [ ] Detection claims match the evidence available to a client observer.

## Safety and privacy

- [ ] No telemetry, uploads, remote reporting, or undocumented network calls.
- [ ] No unintended outgoing gameplay packets.
- [ ] No unsafe local-player/live-world mutation.
- [ ] Replay/camera/input state restores on stop, error, world change, and disconnect.
- [ ] File paths, counts, lengths, and imported data are bounded/sanitized.
- [ ] Logs and fixtures do not contain sensitive data.

## Code quality

- [ ] Static verifier passes.
- [ ] Relevant tests pass.
- [ ] Build passes, or the baseline failure is documented and unrelated.
- [ ] New config fields are serialized, migrated, exposed, and documented as appropriate.
- [ ] Registered checks and config slices remain in parity.
- [ ] Mixin targets and descriptors are reviewed.
- [ ] Runtime-sensitive behavior has live-client evidence.

## Documentation

- [ ] README/user manual claims are accurate.
- [ ] Commands, keybind counts, check counts, and version references are current.
- [ ] Compatibility and limitations are documented.
- [ ] Generated documentation source data is updated where needed.

## AI-assisted work

- [ ] AI use is disclosed.
- [ ] The contributor reviewed the final diff.
- [ ] No AI-generated test or benchmark claims are accepted without evidence.
- [ ] The PR does not contain unexplained broad rewrites.
- [ ] Human contributor can explain the changed behavior and verification results.
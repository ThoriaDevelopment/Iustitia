# Contributing to Iustitia

Thank you for helping improve Iustitia. This project is a client-side Fabric mod with anticheat detection, packet observation, replay tooling, and render-heavy moderator features. Contributions are welcome, but changes must preserve the project's safety, privacy, and fail-open guarantees.

## Before you start

1. Read the [README](README.md) for the product behavior and architecture.
2. Read the [user manual](USERMANUAL.md) before changing user-facing commands or workflows.
3. Read [SUPPORT.md](SUPPORT.md) to choose the right issue type.
4. Read [SECURITY.md](SECURITY.md) before reporting a security or privacy concern.
5. For AI-assisted work, read [docs/ai-assisted-development.md](docs/ai-assisted-development.md) and use the [Iustitia Claude Code skill](.claude/skills/iustitia-contributor/SKILL.md).
6. Search existing issues and pull requests before opening a duplicate.

## What belongs where

| Need | Use |
|---|---|
| A reproducible crash, build failure, broken command, or compatibility problem | Bug report |
| A false positive or false negative | Detection/accuracy report |
| A missing Minecraft/Fabric version or server compatibility issue | Compatibility report |
| A new detector or moderator workflow | Feature request or design discussion |
| A privacy, packet, telemetry, or unsafe behavior concern | Private security report |
| A documentation correction | Documentation issue or pull request |
| A question about using Iustitia | Discussion or support issue, not a bug report |

Do not put private account information, server credentials, IP addresses, access tokens, or unpublished exploit details in a public issue.

## Development environment

Iustitia currently targets:

- Minecraft 1.21.11
- Java 21
- Fabric Loader 0.19.3+
- Fabric API 0.141.3+1.21.11
- Fabric Language Kotlin 1.13.9+kotlin.2.3.10
- YACL 3.8.2+1.21.11
- Gradle through the included wrapper

Use the repository's wrapper and do not substitute a different package manager or build system.

```bash
./gradlew build
./gradlew test
./gradlew runClient
```

On Windows, use `gradlew.bat` if preferred. Run these commands from the repository root (the directory containing `build.gradle.kts`).

## Branches and commits

- Create a focused branch from the current default branch.
- Keep one conceptual change per pull request.
- Do not mix an unrelated cleanup, version upgrade, detector retuning, and feature addition in one PR.
- Use clear commit messages. A useful form is `area: short imperative description`, for example `replay: preserve equipment in clip interpolation`.
- Do not commit generated Gradle output, runtime files, logs, local assistant state, private research material, or user data.
- Do not commit `.minecraft`, `run/`, `build/`, `.gradle/`, or local `%APPDATA%/.iustitia` data.

## Change boundaries

### Detection changes

Detection changes require more evidence than ordinary UI changes.

Include:

- The observable signal being used.
- Why the signal is available to a client observer.
- The expected legitimate behavior.
- False-positive guards.
- False-negative limitations.
- Protocol/version assumptions.
- The expected violation-level and decay behavior.
- A plan for validating the detector against both clean and cheating-like traces.

Do not describe a detector as proof unless the project has evidence that supports that claim. Iustitia is inference-based and cannot see another player's outgoing packets.

### Mixin changes

Mixin changes are Minecraft-version-sensitive. Explain in the PR:

- The target class and method.
- The injection point and why it is stable enough.
- Whether the mixin observes, renders, or mutates behavior.
- What happens if the target drifts.
- How the change was live-tested in a development client.

Prefer Fabric events and accessors over new mixins when they provide the needed behavior. Never add a send-path or local-player mutation without an explicit design discussion. Replay/playclip input suppression is a documented exception, not a general license to change player behavior.

### Rendering and replay changes

Rendering code must remain fail-open. A broken ghost, chunk, label, or overlay should not crash the client or prevent the live view from being restored.

For render/replay changes, test:

- Normal gameplay with the feature disabled.
- Feature activation and deactivation.
- World/dimension change.
- Disconnect/reconnect.
- Missing or unloaded entities.
- The lowest supported player count and a crowded scene.
- Replay stop/reset paths.
- FPS impact when the feature is inactive.

### Persistence and privacy

All stored data must remain local. Do not add telemetry, analytics, uploads, remote reporting, or server-facing detection messages.

Document:

- What is stored.
- Where it is stored.
- Whether it is opt-in or explicit-action storage.
- How it is bounded or deleted.
- What happens when parsing or writing fails.

Never log full server credentials, tokens, private messages, or unnecessary personal data.

## Required verification

Run the repository verifier before opening a PR:

```bash
python scripts/verify_contribution.py --static --run-build
```

Run the normal Gradle checks when available:

```bash
./gradlew test
./gradlew build
```

If the project baseline is broken, do not hide that fact. State the baseline failure in the PR and explain whether your change is related. A PR that fixes the baseline should include the smallest reproducer and the verification output.

Then run the automated live tests. They boot a real client in a deterministic flat
world, drive legitimate and cheating bot players, and report false positives (first
pass) and bypasses (second pass) — no account, server, or human input required:

```bash
python scripts/live_selftest.py                  # full two-pass verification
python scripts/live_selftest.py --check reach    # only one check's scenarios
```

A detection or replay/clip change must come with (or extend) its scenarios; see
[Automated live testing](docs/automated-live-testing.md) for the standard recipe and the
coverage table.

Use the changed-file matrix in [docs/live-verification.md](docs/live-verification.md) to
decide which manual client checks are still required — the automated suite covers detector
and observer logic, but not mixin packet decode, rendering, or ViaFabricPlus behavior.
Detection, packet, mixin, rendering, replay, camera, and input changes require live testing
in `runClient` unless a maintainer explicitly approves an exception.

## Pull requests

A good PR includes:

- A short explanation of the problem.
- The chosen approach and important alternatives considered.
- A list of changed areas.
- Verification commands and their results.
- Live-test evidence for runtime-sensitive changes.
- Screenshots or recordings for UI/rendering changes when useful.
- Compatibility and migration notes.
- A note stating whether AI tools were used.
- A list of known limitations or follow-up work.

Keep the PR title specific. Avoid titles such as `update stuff`, `fix anticheat`, or `AI changes`.

Maintainers may ask for changes even when the code builds. Build success is necessary, not sufficient: detection quality, client safety, privacy, runtime behavior, and maintainability all matter.

## AI-assisted contributions

AI tools are allowed. They do not transfer responsibility away from the contributor.

AI-assisted contributors must:

- Review every generated or modified line.
- Provide the agent with the repository instructions and relevant source context.
- Run the static verifier and appropriate Gradle tasks.
- Run the required live client checks for runtime-sensitive changes.
- Inspect the final diff for unrelated edits, secrets, generated files, and unsafe behavior.
- Disclose which AI tool or agent was used and what it contributed.
- Never allow an agent to claim a live test happened when it only ran a compiler or static check.
- Never let an agent push directly to the default branch.

The recommended workflow is documented in [docs/ai-assisted-development.md](docs/ai-assisted-development.md). The Claude Code skill is the operational version for Claude Code agents.

## Review principles

Reviewers will prioritize:

1. Client safety and crash resistance.
2. Privacy and no unintended network behavior.
3. Detection correctness and false-positive control.
4. Runtime compatibility with Minecraft 1.21.11.
5. Testability and verification evidence.
6. Clear scope and maintainable code.
7. Documentation and migration quality.

Thanks for contributing responsibly. Iustitia works best when its evidence is honest, its limitations are visible, and its tools do not interfere with the player or server.
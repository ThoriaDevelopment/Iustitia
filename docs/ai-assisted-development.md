# AI-assisted development for Iustitia

AI coding tools are welcome in Iustitia, including Claude Code and other repository-aware agents. The project uses a verification-first policy: an agent may help produce a change, but a human contributor owns the result and must be able to explain, review, test, and support it.

## The rule in one sentence

Do not submit what an agent claims to have done; submit what you personally reviewed and what the repository's checks and live verification actually demonstrate.

## Recommended workflow

### 1. Start from a clean branch

```bash
git status --short
git switch -c <focused-topic>
```

Do not let an agent silently absorb unrelated worktree changes. If the worktree is already dirty, record which files were dirty before asking the agent to edit anything.

### 2. Give the agent the project context

Point the agent to:

- `README.md`
- `CONTRIBUTING.md`
- `SUPPORT.md`
- `SECURITY.md`
- `.claude/skills/iustitia-contributor/SKILL.md`
- The relevant package and documentation files

Ask it to inspect before editing. For Iustitia, broad changes often cross config, command, persistence, render, mixin, and documentation layers.

### 3. Ask for a plan before implementation

The plan should identify:

- The user-visible behavior.
- The source-of-truth files.
- The compatibility and privacy implications.
- The tests/static checks needed.
- The live client checks needed.
- Any migration or documentation updates.

### 4. Keep the change focused

Agents are good at making broad edits quickly, which is also a risk. One pull request should normally have one coherent purpose. Do not combine an upgrade, detector retuning, UI redesign, and cleanup merely because the agent noticed them together.

### 5. Verify in layers

Run the static verifier first:

```bash
python scripts/verify_contribution.py --static
```

Then run the relevant Gradle tasks:

```bash
./gradlew test
./gradlew build
```

If the change affects the runtime client, run:

```bash
./gradlew runClient
```

Follow the required checklist in [live-verification.md](live-verification.md). The agent must not mark a live test complete based only on compilation.

### 6. Inspect the final diff

Before creating a PR, inspect:

```bash
git diff --check
git diff --stat
git diff
```

Look specifically for:

- Unrelated edits.
- Generated files.
- Secrets or local paths.
- New network calls.
- New outgoing packet behavior.
- Local-player mutation.
- Missing fail-open guards.
- Config fields not serialized or migrated.
- Checks registered without config slices.
- Config slices without registered checks.
- Documentation that claims a feature is live when it is not.

### 7. Write an honest PR

Disclose:

- The AI tool or tools used.
- What the agent did.
- What you reviewed manually.
- Commands that passed and failed.
- Live tests performed.
- Known limitations.
- Any baseline failure that predates your change.

## What agents may and may not do

Agents may:

- Inspect repository structure and history.
- Propose implementation plans.
- Edit source, tests, and documentation.
- Run repository verification and build/test commands.
- Prepare a branch and PR description.

Agents must not:

- Invent test results.
- Claim a live client test without launching and observing it.
- Hide a failing baseline build.
- Add telemetry, uploads, remote reporting, or hidden network behavior.
- Add a mixin without explaining its target and live verification plan.
- Change detection thresholds without describing false-positive impact.
- Push directly to the default branch.
- Commit secrets, user data, runtime logs, or generated build output.
- Treat an alert as proof that a player is cheating.

Whether an agent can create a commit or open a draft PR is a project-owner decision. Human contributors remain responsible for the final branch and credentials.

## Change-to-verification matrix

| Changed area | Minimum verification |
|---|---|
| Pure math or data structure | Static verifier, tests, build |
| Config or presets | Static verifier, config migration tests, build, manual `/ius config` if UI changed |
| Commands or screens | Build, `runClient`, execute changed command/screen path |
| Detection check | Tests where possible, build, clean/edge-case trace, live client with verbose logs |
| Packet signals or inference | Build, live client packet/inference scenario, disconnect/world-change test |
| Mixin | Build, dev-client launch, confirm no mixin application error, exercise target behavior |
| Rendering/HUD/nametags | Build, dev client, feature on/off, screenshot or recording when useful |
| Replay/clip/freecam/input | Build, dev client, start/pause/seek/stop/world-change/disconnect restoration |
| Persistence/clip codec | Tests, malformed-input test, build, inspect generated local files |
| Documentation only | Link/heading checks and diff review |

## If the agent gets stuck

Stop and ask it to report:

- What it knows.
- What it has verified.
- What it only inferred.
- The exact failing command.
- The smallest next experiment.

Do not let the agent compensate for uncertainty by making a larger unrelated rewrite.

## Suggested PR disclosure

```text
AI assistance: Claude Code was used to inspect the repository, draft the implementation, and suggest tests. I reviewed the final diff and verified the change with:

- python scripts/verify_contribution.py --static --run-build
- ./gradlew test
- ./gradlew build
- [live checks, if applicable]

Known limitations: [list them, or say none].
```

## Prompt starter

```text
You are contributing to Iustitia. Read README.md, CONTRIBUTING.md, SUPPORT.md, docs/ai-assisted-development.md, and .claude/skills/iustitia-contributor/SKILL.md before editing. Inspect the current architecture and worktree first. Propose a focused plan, identify required static and live verification, preserve local-only behavior and fail-open guarantees, then implement only after the plan is clear. Never claim a live test without running it.
```
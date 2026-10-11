# Adding a detection check

This page is the order of work for adding a new check to Iustitia, from the first line of Kotlin to
a green suite. A check is wired into more places than its class file, and each place fails in its
own way when it is missed: some loudly (a verifier error, a generator assert), some only as a log
line, and some not at all. Each step below says which.

It does not repeat the rules a check must follow. Those live in one place each, and this page links
to them:

- What a detection change has to explain in its pull request: [CONTRIBUTING.md, "Detection changes"](../CONTRIBUTING.md#detection-changes).
- The detector design checklist, the flag economy and the config-field procedure: [the contributor skill](../.claude/skills/iustitia-contributor/SKILL.md).
- How to write, calibrate and register a scenario: [Automated live testing, section 4](automated-live-testing.md#4-adding-a-scenario-the-standard-recipe).
- What the automated suite cannot see and has to be checked by hand: [Live verification](live-verification.md).

Throughout, `<id>` is the check id (lower camel case, for example `pitchBound`) and `<Name>Check` is
its class. The id is the key in every one of the places below, and it has to be spelled the same in
all of them.

## 0. Before you write code

Write down the signal first: what the observer can see, the legitimate behaviour that looks like it,
the guards, and how the violation level should accumulate and decay. The list of questions is in
CONTRIBUTING.md and the skill (linked above). A check whose signal cannot be explained there will not
get through review however clean its code is.

Then pick a check that already works the way yours will, and read it end to end before copying its
shape:

| Your check is driven by | Read | Notes |
|---|---|---|
| the per-tick player snapshot (movement, rotation) | `checks/movement/PitchBoundCheck.kt` (smallest), `checks/movement/WaterWalkCheck.kt` (lag guard) | override `process(tp, tick)` |
| inferred attacks | `checks/combat/MaceSmashCheck.kt`, `checks/combat/ReachCheck.kt` | subscribe in `init`, see below |
| a per-event pattern that needs a sustained window | any check that calls `flagEpisode` (`KeepSprintCheck`, `HitsWithoutSwingCheck`, `AimWrapCheck`) | see the skill's "Flag economy" section |

## 1. The check class

Create `src/main/kotlin/dev/iustitia/checks/<combat|movement>/<Name>Check.kt`, extending
[`Check`](../src/main/kotlin/dev/iustitia/checks/Check.kt):

- `override val id = "<id>"`.
- `override fun newContext(uuid)` returns a private `CheckContext` subclass that holds any
  per-player state the check needs. The base class already keeps the violation level and the
  `episodeRing` / `episodeActive` pair that `sustained` and `flagEpisode` use.
- Movement checks override `process(tp, tick)`. The tick driver in `Iustitia.kt` calls it once
  per tracked player per tick, and skips the call when the check is disabled.
- Combat checks subscribe to the event bus in `init`, for example
  `try { Iustitia.bus.subscribe<AttackEvent> { onAttack(it) } } catch (_: Throwable) {}`. The
  `open fun onAttack` / `onSwing` hooks declared on `Check` are not called by anything, so
  overriding them on their own does nothing. Copy the subscription from an existing combat check.
- Read thresholds through `cfg` (`cfg.threshold`, `cfg.setbackVL`), never from a constant. `cfg`
  is looked up live by id, which is what makes `/ius threshold`, YACL and presets reach the check.
- Every flag goes through `flag(...)` or `flagEpisode(...)`. That one call already applies the
  disabled gate, the `/ius exempt` list, FlagHistory, verbose logging, the self-test tap and the
  alert, so a check never calls any of those itself.
- Wrap the body in `try { … } catch (_: Throwable) {}`. Detection is fail-open: a throwing check
  must cost one sample, not a crash.
- Lag guards use the shared windows in `checks/LagWindows.kt` rather than private constants.

**If you get this wrong:** nothing fails to compile. A check that never flags looks the same as a
check that has never been asked, which is why step 7 exists.

If any part of the logic can run without Minecraft objects, put it in a small helper and add a JVM
test for it under `src/test/kotlin` (`./gradlew test`).

## 2. The config slice

[`config/IustitiaConfig.kt`](../src/main/kotlin/dev/iustitia/config/IustitiaConfig.kt) needs the
check in three places, all keyed by the same id:

1. A field with the defaults, written exactly in this shape:
   `var <id>: CheckConfig = CheckConfig(true, 5.0, 1.0, <threshold>),`
   (`enabled, setbackVL, decay, threshold`, positional). The static verifier scrapes this shape to
   compare the defaults against `scripts/checks.json`. Named arguments or a multi-line call will not
   match, and the verifier then reports that it could not parse the defaults.
2. `"<id>" to <id>,` in `checks()`.
3. `"<id>" -> <id>` in `slice(id)`.

`checks()` is what everything else iterates: JSON save and load in `ConfigManager`, the YACL
screen, `/ius` completion and status, preset application and persistence. Adding the id there is
the whole of the config wiring. There is no separate serializer, YACL entry or preset copy list to
update.

**If you get this wrong:**

- Missing from `slice()`: `slice()` falls through to a disabled slice with `Double.MAX_VALUE`
  setback and threshold, so the check is silently off. The static verifier errors (`checks()
  entries without a slice() branch`), and at startup `Iustitia.verifyCheckRegistry()` logs a
  warning. Nothing shows in game.
- Missing from `checks()` too: the verifier errors on the count (`registered checks (N) do not
  match config checks() entries (M)`). The check is also missing from the config file and the YACL
  screen.

**Migration:** a new check is an additive field. `ConfigManager` reads each slice only when the
key is present in the saved file, so an older config keeps the code defaults and
`CONFIG_VERSION` does **not** change. Bump it only when you change an *existing* check's defaults
(see "Config and presets" and "Detection checks" in the skill).

**Presets:** every built-in preset writes `enabled` for all checks. `standard` turns off exactly the
ids in `PresetManager.standardOffChecks` and turns every other check on, so a new check is on in
`standard` unless you add it to that set. Decide which one you want and say why in the pull request.
`PresetApplyTest` and `PresetBuiltInsTest` enforce the set.

## 3. Registration

Add `Iustitia.register(<Name>Check())` to the block of `register` calls in
[`IustitiaClientMod.kt`](../src/main/kotlin/dev/iustitia/IustitiaClientMod.kt), before the
`Iustitia.verifyCheckRegistry()` call that follows them. Import the class and register it by its
simple name, as the existing calls do. The static verifier counts registrations with a pattern that
only matches `Iustitia.register(<SimpleName>())`, so a fully qualified
`Iustitia.register(dev.iustitia.checks.movement.<Name>Check())` compiles and runs but is not
counted, and the verifier reports a count mismatch for a check that is correctly registered.

**If you get this wrong:** the check never runs. The static verifier compares the number of
`Iustitia.register(...)` calls with the number of `checks()` entries and errors when they differ.
At startup, `verifyCheckRegistry()` diffs registered ids against config ids in both directions and
logs a warning only. A check that is registered but returns a different `id` from its config key
passes the verifier's count check and is caught only by that startup warning, so read the log once.

## 4. Tier

[`history/FlagHistory.kt`](../src/main/kotlin/dev/iustitia/history/FlagHistory.kt) decides how an
alert from the check moves the player's nametag tier:

| Tier | Where it is declared | Effect |
|---|---|---|
| primary (definitive) | `FlagHistory.DEFINITIVE` | one alert marks the player yellow; two distinct red-capable checks mark red |
| corroborator | `FlagHistory.CORROBORATOR` | counts toward red only alongside a primary; never starts a tier alone |
| neutral | *not declared anywhere* | in neither set; recorded in `/ius hist`, never moves the tier |

Neutral is the default, so a new check that is added to neither set is neutral without anyone
deciding it. Make the choice explicitly and give the reason in the pull request. A check belongs in
`DEFINITIVE` only when an alert really is close to proof (see "Detection changes" in CONTRIBUTING.md).

`CheckInfo.isDefinitive` reads `DEFINITIVE`, so the help text follows automatically. The
`definitive` boolean in `scripts/checks.json` (step 6) does **not**. Set it by hand to match. Nothing
checks the two against each other yet; that is
[issue #15](https://github.com/ThoriaDevelopment/Iustitia/issues/15). A corroborator is
`"definitive": false` in the JSON.

## 5. In-game descriptions

Two maps in `info/` hold the text a user sees in game, separate from the generated docs:

- [`info/CheckInfo.kt`](../src/main/kotlin/dev/iustitia/info/CheckInfo.kt) `DESCRIPTIONS`: the one-line
  "what it detects", shown in the alert hover tooltip and in `/ius help <id>`.
- [`info/FpHint.kt`](../src/main/kotlin/dev/iustitia/info/FpHint.kt) `HINTS`: the benign cause most
  likely to trip the check ("lag can inflate distance"), shown in the alert tooltip.

**If you get this wrong:** nothing fails. A missing description falls back to
`Iustitia check '<id>'.` and a missing hint is simply not shown.

The README's check table uses the `CheckInfo` sentence as its "detects" column (step 7).

## 6. Generated documentation source

[`scripts/checks.json`](../scripts/checks.json) is the source for the generated per-check pages.
Add one object, copying an existing entry for the field set. `scripts/gen_docs.py` asserts that
`id`, `class`, `name`, `group`, `definitive`, `what`, `signature`, `fpGuards`, `defaults` and
`source` are present. `constants` is optional. Add the entry by hand. Loading and re-dumping the
file with a JSON library rewrites the formatting of every other entry and buries your change in a
diff of several hundred lines.

- `defaults` must equal the Kotlin defaults from step 2 exactly. The static verifier compares every
  field and errors on any difference, as it does when the set of ids differs from `checks()`.
- `group` must be one of `Combat`, `Movement`, `Rotation`, `Packet`. The generator only renders the
  groups in its `GROUP_ORDER` list, so a check with any other group still gets its own page but is
  left out of the catalog and the sidebar without an error.
- `definitive`: see step 4.

**The check count is hard-coded.** `gen_docs.py` asserts `len(checks) == 36` and that there are 36
unique ids, so adding a check stops the generator until you change both asserts. The same number
appears in its module docstring and in the catalog page it emits (the `<meta name="description">`
and the heading), and the static verifier warns when `checks.json` does not have 36 entries. Update
all of them to the new count in the same change. Step 7 lists the other places the number appears.

Then regenerate:

```bash
python scripts/gen_docs.py
```

The generated HTML under `docs/` is git-ignored (see `.gitignore`) and is not committed. Running the
generator proves the JSON is well formed and the asserts pass.

## 7. Scenarios, check inventory and the hand-maintained lists

**Scenarios.** Follow [Automated live testing, section 4](automated-live-testing.md#4-adding-a-scenario-the-standard-recipe):
a `legit-<id>` scenario in the first pass and a `cheat-<id>` scenario, with a named reference client,
in the second. Register both in `SelfTestEntrypoint` (`legitPass()` / `cheatPass()`), and add the
check's row to the [section 5 coverage tables](automated-live-testing.md#5-coverage-matrix) and the
summary counts above them. If the first run shows the drive cannot reach the check yet, record that
as section 4 says (`expectDriveGap`, or `expectKnownOpen` for a detector gap) and do not leave an
assertion you know is false.

**`CheckIds`.** Add the id to `COMBAT` or `MOVEMENT` in
[`src/gametest/kotlin/dev/iustitia/selftest/Spec.kt`](../src/gametest/kotlin/dev/iustitia/selftest/Spec.kt).
Scenarios use these lists for whole-family assertions such as `expectQuiet(bot, CheckIds.MOVEMENT)`,
so a check missing from them is left out of every legit scenario's "nothing in this family alerted"
gate. That is a false-positive gate the check silently never gets. The comment on `CheckIds` says
`--coverage` compares this list with the runtime registry. It does not do that today: `--coverage`
builds its rows from the registry the harness publishes (`Iustitia.allChecks`), so a new check shows
up there as `no LEGIT scenario` / `no CHEAT scenario` until it has scenarios, but nothing reports
that the id is missing from `CheckIds`. Check it by hand.

**Counts and tables written in prose.** These do not fail anything when they are stale, and they
drift with every new check. Search for the current count (`git grep -n "36"`) and update:

- `README.md`: the overview bullet, the architecture diagram label, the "The N checks" heading, the
  group heading count, and a row in that group's table (id, the `CheckInfo` sentence, ✓ if definitive).
- `scripts/modrinth-description.md`: the heading.
- `scripts/gen_docs.py` and `scripts/verify_contribution.py`: see step 6.
- `config/PresetManager.kt`: comments that say "all N checks".
- `docs/automated-live-testing.md` section 5: the `N / M` gate count.

## 8. Verify

From the repository root:

```bash
python scripts/verify_contribution.py --static
python scripts/gen_docs.py
./gradlew test
./gradlew build
./gradlew compileGametestKotlin
python scripts/live_selftest.py --check <id>
python scripts/live_selftest.py --coverage
```

Read the `<id>` row in the `--coverage` output rather than assuming the check is covered because a
scenario mentions it. Once the pull request is finished, run the full suite once and paste its summary
into the description. When it is required and how to read a failure are covered in the skill's
"Automated live verification" section and in [section 7 of the live-testing guide](automated-live-testing.md#7-evidence-for-a-pull-request).
Then do the manual checks [Live verification](live-verification.md) asks for, for anything the suite
cannot see.

## Checklist

| # | Where | Missed means |
|---|---|---|
| 1 | `checks/<area>/<Name>Check.kt` | n/a |
| 2 | `IustitiaConfig.kt`: field, `checks()`, `slice()` | verifier error; the check is silently disabled at runtime |
| 2 | `PresetManager.standardOffChecks`, if it should be off in `standard` | on in `standard` by default |
| 3 | `IustitiaClientMod.kt`: `Iustitia.register(...)` | verifier error on the count; the check never runs |
| 4 | `FlagHistory.DEFINITIVE` / `CORROBORATOR`, or neither | neutral tier, without anyone having decided it |
| 5 | `CheckInfo.DESCRIPTIONS`, `FpHint.HINTS` | generic tooltip text; no hint |
| 6 | `scripts/checks.json` + `gen_docs.py` count asserts | verifier error (ids or defaults); generator assert |
| 7 | scenarios in `SelfTestEntrypoint` + live-testing section 5 | `--coverage` reports no gate |
| 7 | `CheckIds` in `Spec.kt` | left out of the family-wide legit gates, unreported |
| 7 | README, Modrinth description, count comments | stale numbers |
| 8 | verifier, generator, tests, build, suite | n/a |

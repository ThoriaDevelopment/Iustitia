#!/usr/bin/env python3
"""Verify an Iustitia contribution without requiring a Minecraft server.

The verifier is intentionally stdlib-only so contributors and CI can run it on a
fresh checkout. It checks repository invariants that are easy for a human or AI
agent to miss, then optionally runs the Gradle build.

Examples:
    python scripts/verify_contribution.py --static
    python scripts/verify_contribution.py --static --run-build
    python scripts/verify_contribution.py --changed --run-build

The tool does not claim that a live client test happened. Runtime-sensitive
changes are reported as required live verification for the contributor to run.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Iterable


def configure_stdio() -> None:
    """Make stdout/stderr UTF-8-tolerant before anything can print.

    Windows consoles default to cp1252, where a single non-ASCII glyph in a message raises
    UnicodeEncodeError and kills the run. That would be worst exactly when it matters most --
    while reporting an error -- so this is a safety net, and all emitted text is kept ASCII.
    """
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):  # non-reconfigurable stream (piped/test)
            pass


configure_stdio()


ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "kotlin" / "dev" / "iustitia"
RESOURCES = ROOT / "src" / "main" / "resources"
# The automated live-test harness lives in its own source set (Fabric client gametest).
HARNESS = ROOT / "src" / "gametest"

REQUIRED_FILES = (
    "build.gradle.kts",
    "gradle.properties",
    "settings.gradle.kts",
    "README.md",
    "CONTRIBUTING.md",
    "SUPPORT.md",
    "SECURITY.md",
    "CODE_OF_CONDUCT.md",
    "docs/ai-assisted-development.md",
    "docs/live-verification.md",
    "docs/automated-live-testing.md",
    "scripts/live_selftest.py",
    "src/main/resources/fabric.mod.json",
    "src/main/resources/iustitia.mixins.json",
    "src/gametest/resources/fabric.mod.json",
)

RUNTIME_PREFIXES = (
    "src/main/kotlin/dev/iustitia/mixin/",
    "src/main/kotlin/dev/iustitia/render/",
    "src/main/kotlin/dev/iustitia/replay/",
    "src/main/kotlin/dev/iustitia/tracking/",
    "src/main/kotlin/dev/iustitia/inference/",
)

DETECTION_PREFIXES = (
    "src/main/kotlin/dev/iustitia/checks/",
    "src/main/kotlin/dev/iustitia/inference/",
    "src/main/kotlin/dev/iustitia/tracking/",
    "src/main/kotlin/dev/iustitia/protocol/",
)


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def issue(errors: list[str], message: str) -> None:
    errors.append(message)


def warning(warnings: list[str], message: str) -> None:
    warnings.append(message)


def check_required_files(errors: list[str]) -> None:
    for name in REQUIRED_FILES:
        if not (ROOT / name).is_file():
            issue(errors, f"missing required file: {name}")


def check_fabric_metadata(errors: list[str], warnings: list[str]) -> None:
    path = RESOURCES / "fabric.mod.json"
    if not path.is_file():
        return
    try:
        data = json.loads(read(path))
    except Exception as exc:
        issue(errors, f"fabric.mod.json is not valid JSON: {exc}")
        return
    if data.get("id") != "iustitia":
        issue(errors, "fabric.mod.json must declare id=iustitia")
    if data.get("environment") != "client":
        issue(errors, "fabric.mod.json must remain client-only")
    entrypoints = data.get("entrypoints", {}).get("client", [])
    if "dev.iustitia.IustitiaClientMod" not in entrypoints:
        issue(errors, "client entrypoint dev.iustitia.IustitiaClientMod is missing")
    if "minecraft" not in data.get("depends", {}):
        issue(errors, "fabric.mod.json is missing the Minecraft dependency")
    if not data.get("recommends", {}).get("viafabricplus"):
        warning(warnings, "ViaFabricPlus is not listed as recommended; verify this is intentional")


def check_mixins(errors: list[str], warnings: list[str]) -> None:
    path = RESOURCES / "iustitia.mixins.json"
    if not path.is_file():
        return
    try:
        data = json.loads(read(path))
    except Exception as exc:
        issue(errors, f"iustitia.mixins.json is not valid JSON: {exc}")
        return
    if data.get("package") != "dev.iustitia.mixin":
        issue(errors, "mixin package must be dev.iustitia.mixin")
    if data.get("injectors", {}).get("defaultRequire") != 1:
        warning(warnings, "mixin defaultRequire is not 1; review target-drift policy before changing it")
    for name in data.get("client", []):
        source = SRC / "mixin" / f"{name}.kt"
        if not source.is_file():
            issue(errors, f"mixin is registered but source file is missing: {name}.kt")


def find_ids(pattern: str, text: str) -> set[str]:
    return set(re.findall(pattern, text, flags=re.MULTILINE))


def find_check_defaults(config_text: str) -> dict[str, dict[str, object]]:
    """Scrape `var <id>: CheckConfig = CheckConfig(enabled, setbackVL, decay, threshold)`.

    Kotlin is the source of truth for detector defaults; `scripts/checks.json` only feeds the
    docs generator. The two drifted apart silently -- six checks were retuned in Kotlin while the
    JSON kept the old numbers -- so the generated pages published defaults no build had ever used.
    An id-only comparison cannot catch that: the ids all still matched. The values have to be
    compared too.

    A parse miss is deliberately not silent. The caller reports an empty result as an error rather
    than skipping the comparison, so a future refactor that changes the declaration shape fails
    loudly instead of quietly retiring the guard.
    """
    out: dict[str, dict[str, object]] = {}
    pattern = re.compile(
        r"var\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*CheckConfig\s*=\s*CheckConfig\(\s*"
        r"(true|false)\s*,\s*(-?[\d.]+(?:[eE][-+]?\d+)?)\s*,\s*(-?[\d.]+(?:[eE][-+]?\d+)?)"
        r"\s*,\s*(-?[\d.]+(?:[eE][-+]?\d+)?)\s*\)"
    )
    for m in pattern.finditer(config_text):
        out[m.group(1)] = {
            "enabled": m.group(2) == "true",
            "setbackVL": float(m.group(3)),
            "decay": float(m.group(4)),
            "threshold": float(m.group(5)),
        }
    return out


def check_check_registry(errors: list[str], warnings: list[str]) -> None:
    entrypoint = SRC / "IustitiaClientMod.kt"
    config = SRC / "config" / "IustitiaConfig.kt"
    checks_json = ROOT / "scripts" / "checks.json"
    if not entrypoint.is_file() or not config.is_file():
        return

    entry_text = read(entrypoint)
    config_text = read(config)

    registered = find_ids(r"Iustitia\.register\((\w+)\(\)\)", entry_text)
    registered_count = len(registered)
    if registered_count == 0:
        issue(errors, "no checks were found in IustitiaClientMod.kt")

    slice_ids = find_ids(r'"([A-Za-z][A-Za-z0-9]*)"\s*->\s*\w+', config_text)
    # The when-expression contains each id twice, while checks() contains each id once.
    check_list_start = config_text.find("fun checks():")
    check_list_end = config_text.find("/** Look up", check_list_start)
    check_list_text = config_text[check_list_start:check_list_end] if check_list_start >= 0 else ""
    list_ids = find_ids(r'"([A-Za-z][A-Za-z0-9]*)"\s+to\s+\w+', check_list_text)

    if list_ids and registered_count != len(list_ids):
        issue(errors, f"registered checks ({registered_count}) do not match config checks() entries ({len(list_ids)})")
    missing_slice = sorted(list_ids - slice_ids)
    if missing_slice:
        issue(errors, f"checks() entries without a slice() branch: {', '.join(missing_slice)}")

    if checks_json.is_file():
        try:
            docs = json.loads(read(checks_json))
            doc_ids = {item.get("id") for item in docs if isinstance(item, dict)}
            if len(docs) != 36:
                warning(warnings, f"scripts/checks.json contains {len(docs)} entries, while the current docs generator expects 36")
            if None in doc_ids:
                issue(errors, "scripts/checks.json contains an entry without an id")
            if list_ids and doc_ids != list_ids:
                issue(errors, "scripts/checks.json IDs do not match IustitiaConfig.checks() IDs")

            # Value drift, the failure ids cannot catch -- see [find_check_defaults].
            kotlin_defaults = find_check_defaults(config_text)
            if len(kotlin_defaults) != len(list_ids or kotlin_defaults):
                issue(errors,
                      f"parsed {len(kotlin_defaults)} CheckConfig(...) defaults from IustitiaConfig.kt, "
                      f"expected {len(list_ids) if list_ids else '?'} -- the defaults guard cannot run")
            for item in docs:
                if not isinstance(item, dict):
                    continue
                cid = item.get("id")
                source = kotlin_defaults.get(cid)
                if source is None:
                    # Unknown ids are already a hard error above; do not double-report here.
                    continue
                declared = item.get("defaults")
                if not isinstance(declared, dict):
                    issue(errors, f"scripts/checks.json entry '{cid}' has no defaults object")
                    continue
                for field, expected in source.items():
                    actual = declared.get(field)
                    if isinstance(actual, bool) or isinstance(expected, bool):
                        if actual != expected:
                            issue(errors, f"scripts/checks.json '{cid}' defaults.{field} is {actual!r}, "
                                          f"but IustitiaConfig declares {expected!r}")
                    elif isinstance(actual, (int, float)) and isinstance(expected, (int, float)):
                        if abs(float(actual) - float(expected)) > 1e-9:
                            issue(errors, f"scripts/checks.json '{cid}' defaults.{field} is {actual}, "
                                          f"but IustitiaConfig declares {expected}")
                    elif actual != expected:
                        issue(errors, f"scripts/checks.json '{cid}' defaults.{field} is {actual!r}, "
                                      f"but IustitiaConfig declares {expected!r}")
        except Exception as exc:
            issue(errors, f"scripts/checks.json is not valid JSON: {exc}")
    else:
        issue(errors, "scripts/checks.json is missing")

    # Surface source/config drift instead of letting an agent overlook it: a reference to a
    # config property that IustitiaConfig does not declare is a compile error waiting to ship.
    identifiers = set(re.findall(r"ConfigManager\.config\.([A-Za-z_][A-Za-z0-9_]*)", "\n".join(
        read(p) for p in SRC.rglob("*.kt")
    )))
    config_fields = set(re.findall(r"\n\s*var\s+([A-Za-z_][A-Za-z0-9_]*)\s*[:=]", config_text))
    unknown = sorted(name for name in identifiers if name not in config_fields and name not in {"checks", "config", "slice"})
    if unknown:
        issue(errors, "source references config properties that are not declared: " + ", ".join(unknown))


def check_selftest_harness(errors: list[str], warnings: list[str]) -> None:
    """Verify the automated live-test harness is present, dev-gated, and runnable.

    The harness is part of the contributor contract (docs/automated-live-testing.md), so
    a silent removal or a broken gate would let agent contributions claim verification
    they cannot actually run. These checks are structural, not judgement calls.
    """
    manifest = HARNESS / "resources" / "fabric.mod.json"
    if manifest.is_file():
        try:
            data = json.loads(read(manifest))
            if data.get("id") != "iustitia_testmod":
                issue(errors, "gametest fabric.mod.json must declare id=iustitia_testmod")
            if data.get("environment") != "client":
                issue(errors, "gametest fabric.mod.json must remain client-only")
            entrypoints = data.get("entrypoints", {}).get("fabric-client-gametest", [])
            if not entrypoints:
                issue(errors, "gametest fabric.mod.json is missing the fabric-client-gametest entrypoint")
        except Exception as exc:
            issue(errors, f"gametest fabric.mod.json is not valid JSON: {exc}")

    entrypoint_source = HARNESS / "kotlin" / "dev" / "iustitia" / "selftest" / "SelfTestEntrypoint.kt"
    if not entrypoint_source.is_file():
        issue(errors, "gametest entrypoint source SelfTestEntrypoint.kt is missing")

    # The flag tap must stay inert in normal play: armed by the JVM property only.
    hooks = SRC / "selftest" / "SelfTestHooks.kt"
    if hooks.is_file():
        if "fabric.selftest" not in read(hooks):
            issue(errors, "SelfTestHooks must gate recording on the fabric.selftest JVM property")
    else:
        issue(errors, "dev.iustitia.selftest.SelfTestHooks is missing (the harness flag tap)")

    build = ROOT / "build.gradle.kts"
    if build.is_file():
        text = read(build)
        if "configureTests" not in text:
            issue(errors, "build.gradle.kts no longer configures the gametest source set (fabricApi.configureTests)")
        if "-Dfabric.selftest=1" not in text:
            issue(errors, "the runClientGameTest task must pass -Dfabric.selftest=1 so the harness can record flags")

    # The three-outcome contract (caught / detector gap / harness gap / false positive) is what
    # keeps a non-alerting scenario honest, so its API must exist and must stay distinct.
    selftest_source = HARNESS / "kotlin" / "dev" / "iustitia" / "selftest" / "SelfTest.kt"
    if selftest_source.is_file():
        text = read(selftest_source)
        for api in ("expectDriveGap", "expectKnownOpen", "expectDocumentedFp", "fun expect("):
            if api not in text:
                issue(errors, f"SelfTest.kt is missing the '{api}' expectation API (docs section 1)")
    report_source = SRC / "selftest" / "SelfTestReport.kt"
    if report_source.is_file():
        text = read(report_source)
        for field in ("knownOpen", "driveGaps", "falsePositives"):
            if field not in text:
                issue(errors, f"ScenarioReport is missing the '{field}' field (the runner reads it)")

    # Provenance rule: a cheat drive with no reference client is an invented pattern, and the
    # per-client catch matrix would label its row "vanilla".
    for lib in ("CheatCombat.kt", "CheatMovement.kt"):
        path = HARNESS / "kotlin" / "dev" / "iustitia" / "selftest" / lib
        if path.is_file() and re.search(r'Pass\.CHEAT\s*,\s*"vanilla"', read(path)):
            issue(errors, f"{lib} has a cheat scenario with source \"vanilla\" - every cheat drive must name a reference client")

    runner = ROOT / "scripts" / "live_selftest.py"
    if runner.is_file():
        text = read(runner)
        for flag in ("--coverage", "--matrix", "--shard", "--strict", "--verbose-log"):
            if flag not in text:
                issue(errors, f"live_selftest.py lost the `{flag}` option (an agent workflow depends on it)")

    # Check-id coverage: a changed check whose id appears in no scenario is unverified.
    harness_text = "\n".join(read(p) for p in HARNESS.rglob("*.kt")) if HARNESS.is_dir() else ""
    for check_file in sorted((SRC / "checks").rglob("*.kt")):
        ids = re.findall(r'override\s+val\s+id\s*:\s*String\s*=\s*"([^"]+)"', read(check_file))
        for check_id in ids:
            if harness_text and f'"{check_id}"' not in harness_text:
                warning(
                    warnings,
                    f"no automated live-test scenario references check '{check_id}' "
                    f"({check_file.name}) - add legit/cheat scenarios (docs/automated-live-testing.md section 4)",
                )
    # Report the gates that are not yet established. This is a warning, not an error: the suite
    # records them deliberately (a harness gap is honest), but a contributor should see that the
    # covered-check count is not the same as "every check has a cheat gate".
    if not (ROOT / "build" / "selftest-manifest.json").is_file():
        warning(
            warnings,
            "no build/selftest-manifest.json yet - run `python scripts/live_selftest.py --check pipeline` "
            "once, then `--coverage` shows which checks have an established cheat gate",
        )


def check_docs(errors: list[str], warnings: list[str]) -> None:
    readme = ROOT / "README.md"
    if not readme.is_file():
        return
    text = read(readme)
    for target in (
        "CONTRIBUTING.md",
        "SUPPORT.md",
        "SECURITY.md",
        "CODE_OF_CONDUCT.md",
        "docs/ai-assisted-development.md",
        ".claude/skills/iustitia-contributor/SKILL.md",
        "docs/live-verification.md",
    ):
        if target not in text:
            issue(errors, f"README does not link contributor resource: {target}")

    # Check relative Markdown links when their target is a repository file.
    for match in re.findall(r"\[[^\]]+\]\(([^)#]+)", text):
        if "://" in match or match.startswith("mailto:"):
            continue
        target = (ROOT / match).resolve()
        try:
            target.relative_to(ROOT.resolve())
        except ValueError:
            warning(warnings, f"README link leaves repository root and was not checked: {match}")
            continue
        if not target.exists():
            issue(errors, f"README link target does not exist: {match}")

    if "iustitia-1.2.0.jar" in text:
        warning(warnings, "README still contains an older 1.2.0 artifact reference")


def check_privacy_signals(errors: list[str], warnings: list[str]) -> None:
    # This is deliberately a review signal, not a simplistic ban on networking APIs. Minecraft
    # client mods must use Minecraft's existing connection, while Iustitia's documented observer
    # boundary forbids new remote reporting/telemetry.
    source_text = "\n".join(read(p) for p in SRC.rglob("*.kt"))
    forbidden = re.findall(r"https?://|URL\s*\(|HttpClient|OkHttp|java\.net\.http", source_text)
    if forbidden:
        issue(errors, "source contains possible new remote/network code; review against the local-only policy")
    if "ClientConnectionMixin.kt" in source_text and "ReplayState.active" not in source_text:
        warning(warnings, "ClientConnectionMixin exists; confirm gameplay packet suppression remains explicitly gated")


def git_changed() -> list[str]:
    try:
        result = subprocess.run(
            ["git", "diff", "--name-only", "HEAD"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        paths = [line.strip().replace("\\", "/") for line in result.stdout.splitlines() if line.strip()]
        untracked = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        paths.extend(line.strip().replace("\\", "/") for line in untracked.stdout.splitlines() if line.strip())
        return sorted(set(paths))
    except OSError:
        return []


def changed_guidance(paths: Iterable[str]) -> list[str]:
    paths = list(paths)
    if not paths:
        return ["No changed files were detected by git; run the verifier from the repository root."]
    guidance: list[str] = []
    if any(p.startswith(DETECTION_PREFIXES) for p in paths):
        guidance.append("Detection-related files changed: run clean/edge-case traces with verbose logging and document false-positive guards.")
        guidance.append("Detection paths changed: run `python scripts/live_selftest.py` (legit pass = false positives, cheat pass = bypasses) and add/extend scenarios per docs/automated-live-testing.md.")
    if any(p.startswith(RUNTIME_PREFIXES) for p in paths):
        guidance.append("Runtime-sensitive files changed: launch ./gradlew runClient and follow docs/live-verification.md.")
    if any("config" in p or "preset" in p for p in paths):
        guidance.append("Configuration-related files changed: test serialization, defaults, migration, and YACL behavior.")
    if any(p.startswith("src/main/kotlin/dev/iustitia/replay/") for p in paths):
        guidance.append("Replay/clip files changed: test start, pause, seek, stop, world change, disconnect, and malformed/old clips.")
        guidance.append("Replay/clip paths changed: run `python scripts/live_selftest.py --check replay` for the automated lifecycle + clip round-trip scenarios.")
    if any(p.startswith("src/main/kotlin/dev/iustitia/mixin/") for p in paths):
        guidance.append("Mixin files changed: confirm no target/injection errors at client launch and exercise both active and inactive paths.")
    if any(p.startswith("src/main/kotlin/dev/iustitia/ui/") or p.startswith("src/main/kotlin/dev/iustitia/hud/") for p in paths):
        guidance.append("UI/HUD files changed: test with screens open/closed and capture visual evidence when useful.")
    if any(p.endswith(".md") or p.endswith(".mdx") for p in paths):
        guidance.append("Documentation changed: check links, version claims, command names, and generated-doc source data.")
    if not guidance:
        guidance.append("No special runtime category detected; static checks, tests, build, and final diff review remain required.")
    return guidance


def run_build() -> int:
    wrapper = ROOT / ("gradlew.bat" if sys.platform.startswith("win") else "gradlew")
    if not wrapper.exists():
        print("BUILD: SKIP (Gradle wrapper missing)")
        return 2
    command = [str(wrapper), "build"]
    print("BUILD: running " + " ".join(command))
    result = subprocess.run(command, cwd=ROOT, check=False)
    print(f"BUILD: {'PASS' if result.returncode == 0 else 'FAIL'} (exit {result.returncode})")
    return result.returncode


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--static", action="store_true", help="run repository invariant checks")
    parser.add_argument("--changed", action="store_true", help="print verification guidance for git changes")
    parser.add_argument("--run-build", action="store_true", help="run ./gradlew build after static checks")
    args = parser.parse_args()

    # No flag means the useful default: static verification plus changed-file guidance.
    do_static = args.static or not (args.changed or args.run_build)
    errors: list[str] = []
    warnings: list[str] = []

    if do_static:
        check_required_files(errors)
        check_fabric_metadata(errors, warnings)
        check_mixins(errors, warnings)
        check_check_registry(errors, warnings)
        check_selftest_harness(errors, warnings)
        check_docs(errors, warnings)
        check_privacy_signals(errors, warnings)

    if args.changed or do_static:
        print("Changed-file guidance:")
        for line in changed_guidance(git_changed()):
            print(f"  - {line}")

    if warnings:
        print("Warnings:")
        for message in warnings:
            print(f"  - {message}")
    if errors:
        print("Errors:")
        for message in errors:
            print(f"  - {message}")
    else:
        print("Static verification: PASS")

    build_code = 0
    if args.run_build:
        build_code = run_build()

    if errors or build_code != 0:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Run Iustitia's automated live tests and turn the result into a verdict.

This is the command an AI agent (or a human) runs to verify a change against the
real client without anyone touching the game. It wraps the Fabric client gametest
suite in `src/gametest` (see docs/automated-live-testing.md):

    python scripts/live_selftest.py                       # full three-pass verification
    python scripts/live_selftest.py --check reach         # only scenarios for one check
    python scripts/live_selftest.py --source LiquidBounce # only one reference client's drives
    python scripts/live_selftest.py --tag world           # only world-interaction drives
    python scripts/live_selftest.py --pass cheat          # only the unfair-advantage pass
    python scripts/live_selftest.py --list                # what exists, no client boot
    python scripts/live_selftest.py --coverage            # which checks are driven, by whom
    python scripts/live_selftest.py --matrix              # per-check x per-client catch matrix

The game window it opens is **silent** (the harness mutes every sound category in memory as its
first action), so it can be run repeatedly while someone is working.

What it does:

1. Runs `./gradlew runClientGameTest`, which boots a real Minecraft client in a
   deterministic flat world, runs each scenario, and exits nonzero on failure.
2. Parses the JSON report the harness prints (`IUSTITIA_SELFTEST_REPORT=...`) and the
   inventory it prints before the run (`IUSTITIA_SELFTEST_MANIFEST=...`).
3. Prints a per-scenario table plus the two verdict lines that matter: legitimate-play
   false positives, and cheating-play bypasses.
4. Writes `build/selftest-report.json` (evidence) and `build/selftest-manifest.json`
   (the scenario/check inventory, so `--list`/`--coverage` work without booting a client).

Only stdlib is used, matching scripts/verify_contribution.py, so a fresh checkout can
run it. Exit code 0 = every scenario passed; 1 = a failure, a `--strict` finding, or the
suite not running at all (which must never be reported as success).
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path
def configure_stdio() -> None:
    """Make stdout/stderr UTF-8-tolerant before anything can print.

    Windows consoles default to cp1252, where a single non-ASCII glyph in the help
    text or a summary line raises UnicodeEncodeError and kills the run -- an agent
    running this unattended would see a traceback instead of a verdict. We also keep
    all *emitted* text ASCII (see the glyph policy below), so this is only a safety
    net for text that arrives from elsewhere (a scenario name, a log line).
    """
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):  # non-reconfigurable stream (piped/test)
            pass


configure_stdio()

ROOT = Path(__file__).resolve().parent.parent
REPORT_PATH = ROOT / "build" / "selftest-report.json"
MANIFEST_PATH = ROOT / "build" / "selftest-manifest.json"
MARKDOWN_PATH = ROOT / "build" / "selftest-report.md"
REPORT_MARKER = "IUSTITIA_SELFTEST_REPORT="
MANIFEST_MARKER = "IUSTITIA_SELFTEST_MANIFEST="
STATUS_RE = re.compile(r"\[iustitia-selftest\] (PASS|FAIL) (\S+)/(\S+)")

# Pass names as emitted by the harness. LEGIT = vanilla-accurate play (false-positive
# gate); CHEAT = unfair advantage (bypass gate); REPLAY = observer tooling.
PASS_LEGIT = "LEGIT"
PASS_CHEAT = "CHEAT"
PASS_REPLAY = "REPLAY"

# The scenario families worth a fast iteration loop: the two verdicts that gate a merge.
# --fast skips the observer-tooling pass (which rebuilds replay/clip state and is slow) and
# nothing else, so it can never silently skip a detection scenario.
FAST_PASSES = (PASS_LEGIT, PASS_CHEAT)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Run Iustitia's automated live tests.")
    p.add_argument(
        "--check", "--scenario",
        dest="check",
        help="only run scenarios whose name contains this substring (e.g. reach, replay, fly)",
    )
    p.add_argument("--pass", dest="pass_name", choices=["legit", "cheat", "replay", "all"], default="all",
                   help="restrict to one pass (default: all)")
    p.add_argument("--source", default=None,
                   help="only run scenarios reproducing this reference client (e.g. Meteor, LiquidBounce)")
    p.add_argument("--tag", default=None,
                   help="only run scenarios carrying this behaviour tag (combat, movement, world, packet, rotation, guard, replay)")
    p.add_argument("--legit-only", action="store_true", help="alias for --pass legit")
    p.add_argument("--cheat-only", action="store_true", help="alias for --pass cheat")
    p.add_argument("--fast", action="store_true",
                   help="skip the replay pass (LEGIT + CHEAT only) for a quicker verdict")
    p.add_argument("--timeout", type=int, default=3600, help="hard timeout in seconds (default 3600)")
    p.add_argument("--tail", type=int, default=60, help="lines of game output to echo on failure")
    p.add_argument("--quiet", action="store_true", help="only print the summary")
    p.add_argument("--verbose", action="store_true", help="print peak VL evidence for every scenario")
    p.add_argument("--verbose-log", action="store_true",
                   help="arm the mod's verbose flag log (prints the sub-flag label + measured value for every flag)")
    p.add_argument("--strict", action="store_true",
                   help="treat documented findings (known-open gaps, verified false positives) as failures")
    p.add_argument("--list", action="store_true",
                   help="print the scenario inventory from the last run's manifest and exit")
    p.add_argument("--coverage", action="store_true",
                   help="print per-check coverage (legit/cheat/multi-client) and exit")
    p.add_argument("--matrix", action="store_true",
                   help="print the check x reference-client catch matrix from the last report and exit")
    p.add_argument("--require-multi-source", type=int, default=2, metavar="N",
                   help="checks in this list must have >=N distinct cheat sources (default 2, 0 disables)")
    p.add_argument("--shard", default=None, metavar="I/N",
                   help="run only shard I of N by scenario name (for splitting a slow suite)")
    p.add_argument("--format", choices=["table", "json", "markdown"], default="table",
                   help="summary format for the run (default: table)")
    p.add_argument("--report-md", action="store_true",
                   help="also write a markdown summary to build/selftest-report.md for a pull request")
    return p.parse_args()


def resolve_pass(args: argparse.Namespace) -> str:
    if args.legit_only:
        return PASS_LEGIT
    if args.cheat_only:
        return PASS_CHEAT
    if args.fast and args.pass_name == "all":
        return ""
    if args.pass_name == "all":
        return ""
    return args.pass_name.upper()


def gradle_wrapper() -> Path:
    """The wrapper the rest of the repo uses: .bat on Windows, the shell script elsewhere."""
    return ROOT / ("gradlew.bat" if sys.platform.startswith("win") else "gradlew")


def build_command(args: argparse.Namespace) -> list[str]:
    cmd = [str(gradle_wrapper()), "runClientGameTest"]
    pass_name = resolve_pass(args)
    if pass_name:
        cmd.append(f"-Pselftest.pass={pass_name}")
    if args.check:
        cmd.append(f"-Pselftest.filter={args.check}")
    if args.source:
        cmd.append(f"-Pselftest.source={args.source}")
    if args.tag:
        cmd.append(f"-Pselftest.tag={args.tag}")
    if args.shard:
        cmd.append(f"-Pselftest.shard={args.shard}")
    if args.verbose_log:
        cmd.append("-Pselftest.verbose=1")
    return cmd


def run_suite(cmd: list[str], timeout: int, quiet: bool) -> tuple[int, str]:
    if not quiet:
        print(f"$ {' '.join(cmd)}", flush=True)
        print("Booting a real Minecraft client for the live suite (no input required)...", flush=True)
    try:
        proc = subprocess.run(
            cmd,
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=timeout,
        )
        return proc.returncode, proc.stdout
    except subprocess.TimeoutExpired as exc:
        captured = exc.stdout.decode() if isinstance(exc.stdout, bytes) else (exc.stdout or "")
        return 124, captured + f"\n[runner] timed out after {timeout}s"
    except FileNotFoundError as exc:
        return 127, f"[runner] could not launch the build: {exc}"


def extract_json_marker(output: str, marker: str):
    for line in reversed(output.splitlines()):
        idx = line.find(marker)
        if idx == -1:
            continue
        blob = line[idx + len(marker):].strip()
        try:
            return json.loads(blob)
        except json.JSONDecodeError:
            return None
    return None


def extract_report(output: str) -> list[dict] | None:
    data = extract_json_marker(output, REPORT_MARKER)
    return data if isinstance(data, list) else None


def extract_manifest(output: str) -> dict | None:
    data = extract_json_marker(output, MANIFEST_MARKER)
    return data if isinstance(data, dict) else None


def extract_statuses(output: str) -> list[tuple[str, str, str]]:
    """Fallback: parse the harness's per-scenario status lines."""
    out = []
    for line in output.splitlines():
        m = STATUS_RE.search(line)
        if m:
            out.append((m.group(1), m.group(2), m.group(3)))
    return out


# ---------------------------------------------------------------------------
# Inventory (offline) -- list / coverage / matrix
# ---------------------------------------------------------------------------

def load_manifest() -> dict | None:
    if MANIFEST_PATH.exists():
        try:
            return json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return None
    return None


def write_manifest(manifest: dict, reports: list[dict], replace: bool = False) -> None:
    """Merge the pre-run inventory with the post-run expectations and cache it.

    The cache is a *union* keyed by scenario name, not a snapshot. A filtered run
    (`--check reach`) or a shard (`--shard 1/3`) only lists the scenarios it ran, so
    overwriting would make the next `--coverage` report false gaps for every check outside
    the filter -- the exact silent-coverage-shrink the coverage report exists to prevent.
    The newest run's entries win for scenarios it actually exercised; the check list
    always comes from the live registry (it is complete even in a one-scenario run).
    """
    prior = {} if replace else (load_manifest() or {})
    order: list[str] = []
    merged: dict[str, dict] = {}
    for s in list(prior.get("scenarios") or []) + list(manifest.get("scenarios") or []):
        name = s.get("scenario")
        if not name:
            continue
        if name not in merged:
            order.append(name)
        row = merged.setdefault(name, {})
        row.update({k: v for k, v in s.items() if v is not None})

    for r in reports:
        name = r.get("scenario")
        if not name:
            continue
        if name not in merged:
            order.append(name)
        row = merged.setdefault(name, {})
        row["scenario"] = name
        row["pass"] = r.get("pass", row.get("pass"))
        row["source"] = r.get("source", row.get("source"))
        row["tags"] = r.get("tags", row.get("tags"))
        row["expectations"] = r.get("expectations", {})
        row["knownOpen"] = r.get("knownOpen", [])
        row["driveGaps"] = r.get("driveGaps", [])
        row["alertCounts"] = r.get("alertCounts", {})
        row["alertCountMisses"] = r.get("alertCountMisses", [])
        row["assertions"] = r.get("assertions", 0)
        row["noAssertions"] = r.get("noAssertions", False)
        row["passed"] = r.get("passed")

    checks = manifest.get("checks") or prior.get("checks") or []
    out = {
        "checks": checks,
        "scenarios": [merged[n] for n in order],
    }
    MANIFEST_PATH.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST_PATH.write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")


def merge_reports(reports: list[dict], replace: bool = False) -> None:
    """Write the run's reports to build/selftest-report.json, newest result per scenario.

    [replace] is for a FULL, unfiltered run: it is authoritative, so scenarios deleted from the
    suite must disappear from the evidence. A filtered or sharded run unions instead, so the
    partial evidence accumulates into a complete picture rather than shrinking the last one.
    """
    prior: list[dict] = []
    if not replace and REPORT_PATH.exists():
        try:
            loaded = json.loads(REPORT_PATH.read_text(encoding="utf-8"))
            if isinstance(loaded, list):
                prior = loaded
        except (OSError, json.JSONDecodeError):
            prior = []
    by_name = {r.get("scenario"): r for r in prior if r.get("scenario")}
    for r in reports:
        if r.get("scenario"):
            by_name[r["scenario"]] = r
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(
        json.dumps(list(by_name.values()), indent=2) + "\n", encoding="utf-8"
    )


def scenario_rows(manifest: dict) -> list[dict]:
    return manifest.get("scenarios", [])


def coverage(manifest: dict, require_multi_source: int) -> tuple[list[dict], list[str]]:
    """Per-check coverage built from the scenario inventory."""
    checks = manifest.get("checks") or []
    rows = []
    for check in checks:
        legit, cheat, sources, known, drive = [], [], set(), [], []
        for s in scenario_rows(manifest):
            exp = (s.get("expectations") or {})
            if check in exp:
                if exp[check]:
                    cheat.append(s.get("scenario"))
                    sources.add(s.get("source", "?"))
                else:
                    legit.append(s.get("scenario"))
            for line in s.get("knownOpen") or []:
                if f"'{check}'" in line:
                    known.append(s.get("scenario"))
            for line in s.get("driveGaps") or []:
                if f"'{check}'" in line:
                    drive.append(s.get("scenario"))
        rows.append({
            "check": check,
            "legit": legit,
            "cheat": cheat,
            "sources": sorted(sources),
            "known_open": known,
            "drive_gap": drive,
        })
    gaps = []
    for r in rows:
        # A harness gap is NOT coverage: the drive never reached the check, so there is no gate
        # either way. It is reported as a gap so the queue stays visible instead of the check
        # looking covered because a scenario happens to mention it.
        if not r["legit"] and not r["known_open"]:
            gaps.append(f"{r['check']}: no LEGIT scenario (no false-positive gate)")
        if not r["cheat"] and not r["known_open"]:
            if r["drive_gap"]:
                gaps.append(
                    f"{r['check']}: HARNESS GAP -- {', '.join(r['drive_gap'])} exists but its drive "
                    f"never reached the check (no bypass gate)"
                )
            else:
                gaps.append(f"{r['check']}: no CHEAT scenario (no bypass gate)")
        if require_multi_source and len(r["sources"]) < require_multi_source and r["sources"]:
            gaps.append(
                f"{r['check']}: only {len(r['sources'])} cheat source(s) "
                f"({', '.join(r['sources'])}) -- important checks need >={require_multi_source}"
            )
    return rows, gaps


def print_list(manifest: dict) -> None:
    scenarios = scenario_rows(manifest)
    print(f"clients/checks in the registry: {len(manifest.get('checks') or [])}")
    print(f"scenarios: {len(scenarios)}")
    print("-" * 78)
    for s in scenarios:
        tags = ",".join(s.get("tags") or [])
        print(f"  {s.get('pass', '?'):<7} {s.get('scenario', '?'):<44} {s.get('source', '?'):<14} {tags}")
    print("-" * 78)
    print("run `--list` again after a full run to refresh this from build/selftest-manifest.json")


def print_coverage(manifest: dict, require_multi_source: int) -> int:
    rows, gaps = coverage(manifest, require_multi_source)
    print("=" * 78)
    print("Check coverage")
    print("=" * 78)
    print(f"  {'check':<20} {'legit':<6} {'cheat':<6} sources")
    for r in rows:
        mark = "  [harness gap]" if r["drive_gap"] and not r["cheat"] else ""
        print(f"  {r['check']:<20} {len(r['legit']):<6} {len(r['cheat']):<6} "
              f"{', '.join(r['sources']) or '--'}{mark}")
    print("-" * 78)
    multi = [r for r in rows if len(r["sources"]) >= max(require_multi_source, 2)]
    print(f"  checks driven by >=2 clients: {len(multi)}/{len(rows)}")
    print(f"  checks with a known-open finding: {sum(1 for r in rows if r['known_open'])}")
    print(f"  checks with a harness gap (drive not yet reaching it): {sum(1 for r in rows if r['drive_gap'])}")
    if gaps:
        print()
        print("  gaps (informational; verify_contribution.py gates the hard ones):")
        for g in gaps:
            print(f"    - {g}")
    print("=" * 78)
    return 0


# How the states rank when two scenarios disagree about the same (check, client) cell. A failure
# outranks a documented limitation, a documented detector failure outranks an unestablished
# assertion, and nothing outranks silence: "drive-gap" and "known-open" must never read as caught.
MATRIX_RANK = {
    "MISS": 5, "FALSE POSITIVE": 5,
    "known-open": 4, "drive-gap": 3,
    "caught": 1, "clean": 1,
}


def print_matrix(reports: list[dict]) -> int:
    """Per-check x per-client catch matrix from a run's report.

    The report's own `knownOpen`/`driveGaps` lines are authoritative, not just the declared
    expectations: a scenario that recorded a harness gap or a detector gap deliberately declares
    NO expectation for that check, so reading only expectations would leave its cell as "--" and
    make an ungated check look merely unattempted.
    """
    checks: dict[str, dict[str, str]] = {}

    def put(check: str, source: str, state: str) -> None:
        cell = checks.setdefault(check, {})
        prev = cell.get(source)
        if prev is None or MATRIX_RANK.get(state, 0) > MATRIX_RANK.get(prev, 0):
            cell[source] = state

    def states_from_gap_lines(r: dict) -> dict[str, str]:
        out: dict[str, str] = {}
        for line in r.get("driveGaps") or []:
            m = re.search(r"'([^']+)'", line)
            if m:
                out[m.group(1)] = "drive-gap"
        for line in r.get("knownOpen") or []:
            if line.startswith("FALSE POSITIVE"):
                continue
            m = re.search(r"'([^']+)'", line)
            if m:
                out[m.group(1)] = "KNOWN-OPEN CLOSED" if line.startswith("KNOWN-OPEN CLOSED") else "known-open"
        return out

    for r in reports:
        source = r.get("source", "?")
        gaps = states_from_gap_lines(r)
        for check, must_alert in (r.get("expectations") or {}).items():
            alerted = check in (r.get("alertedChecks") or [])
            if must_alert:
                # caught = the cheat was flagged; miss = an unexplained bypass
                if alerted:
                    put(check, source, "caught")
                else:
                    put(check, source, gaps.get(check, "MISS"))
            else:
                put(check, source, "clean" if not alerted else "FALSE POSITIVE")
        # A gap recorded on a check this scenario declared no expectation for still belongs in
        # the matrix: that absence IS the finding.
        declared = set(r.get("expectations") or {})
        for check, state in gaps.items():
            if check in declared:
                continue
            # A closed known-open means the detector now catches it, so the honest cell is caught.
            put(check, source, "caught" if state == "KNOWN-OPEN CLOSED" else state)
    if not checks:
        print("no expectations in the report -- run the suite first")
        return 1

    sources = sorted({s for cell in checks.values() for s in cell})
    width = max(20, *(len(s) for s in sources)) if sources else 20
    print("=" * 78)
    print("Catch matrix (rows = check, cols = reference client that drove it)")
    print("=" * 78)
    header = "  " + f"{'check':<22}" + "".join(f"{s[:width]:<{width + 2}}" for s in sources)
    print(header)
    for check in sorted(checks):
        row = "  " + f"{check:<22}"
        for s in sources:
            state = checks[check].get(s, "--")
            row += f"{state:<{width + 2}}"
        print(row)
    print("-" * 78)
    print("  caught = the module was flagged - missing = BYPASS - known-open = documented detector gap")
    print("  drive-gap = the drive never reached the check (assertion not yet established)")
    print("  clean = legit drive stayed silent - FALSE POSITIVE = legit drive alerted")
    print("  a \"--\" cell means that client has no scenario for the check at all")
    print("=" * 78)
    return 0


# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

def summarise(reports: list[dict], require_multi_source: int, verbose: bool) -> tuple[int, list[str], list[str]]:
    legit_scenarios = [r for r in reports if r.get("pass") == PASS_LEGIT]
    cheat_scenarios = [r for r in reports if r.get("pass") == PASS_CHEAT]

    legit_fp: set[str] = set()
    for r in legit_scenarios:
        legit_fp.update(r.get("falsePositives") or [])
    cheat_bypass: set[str] = set()
    for r in cheat_scenarios:
        cheat_bypass.update(r.get("missedChecks") or [])
    findings: list[str] = []
    documented_fp: list[str] = []
    for r in reports:
        for line in r.get("knownOpen") or []:
            findings.append(f"[{r.get('scenario')}] {line}")
            if line.startswith("FALSE POSITIVE"):
                documented_fp.append(line)
    # Harness gaps are NOT findings about Iustitia: the drive never reached the check, so the
    # assertion is unestablished. Kept in their own bucket so they are never mistaken for a
    # detector bypass, and never counted in the bypass total.
    harness_gaps: list[str] = []
    for r in reports:
        for line in r.get("driveGaps") or []:
            harness_gaps.append(f"[{r.get('scenario')}] {line}")
    # Recurrence failures: a check alerted but never re-armed, so the second episode was swallowed.
    # A REAL failure -- it folds into `passed`, so the exit code already reflects it -- but it gets
    # its own section because a count-only failure leaves missedChecks empty, which would otherwise
    # print a FAIL row with a blank detail column and no explanation anywhere.
    recurrence: list[str] = []
    for r in reports:
        for line in r.get("alertCountMisses") or []:
            recurrence.append(f"[{r.get('scenario')}] {line}")
    # Assertion-less scenarios: no expectation, no documented bucket, no inline check. A scenario in
    # that state cannot fail, so a green from it is vacuous and indistinguishable from a scenario
    # whose assertions were deleted in a refactor. Also a real failure (it folds into `passed`), and
    # it gets its own bucket for the same reason `recurrence` does: every other detail column would
    # be empty, leaving a FAIL row with no explanation anywhere.
    assertion_less: list[str] = [
        f"[{r.get('scenario')}] {r.get('pass')} scenario asserted nothing "
        f"(expectations=0, knownOpen=0, driveGaps=0, inline checks=0)"
        for r in reports
        if r.get("noAssertions")
    ]

    print()
    print("=" * 78)
    print("Iustitia automated live tests")
    print("=" * 78)
    for r in reports:
        status = "PASS" if r.get("passed") else "FAIL"
        detail = ""
        if r.get("error"):
            detail = f" error={r['error']}"
        elif r.get("missedChecks"):
            detail = f" BYPASS={r['missedChecks']}"
        elif r.get("falsePositives"):
            detail = f" FALSE-POSITIVE={r['falsePositives']}"
        elif r.get("alertCountMisses"):
            detail = f" ALERT-COUNT={r.get('alertCounts')}"
        elif r.get("noAssertions"):
            detail = " NO-ASSERTIONS (scenario asserted nothing: no expectation, no documented bucket, no inline check)"
        elif verbose and r.get("vl"):
            peaks = ", ".join(f"{k}={v:.1f}" for k, v in sorted(r["vl"].items())[:6])
            detail = f" peakVL({peaks})"
        print(f"  [{status}] {r.get('pass', '?'):<7} {r.get('scenario', '?'):<44}"
              f" {r.get('source', '?'):<14}{detail}")
    print("-" * 78)
    print(f"  scenarios: {len(reports)}   passed: {sum(1 for r in reports if r.get('passed'))}")
    print(f"  legit pass false positives : {sorted(legit_fp) if legit_fp else 'none'}")
    print(f"  cheat pass bypasses        : {sorted(cheat_bypass) if cheat_bypass else 'none'}")
    if findings:
        print(f"  documented findings ({len(findings)}, non-blocking unless --strict):")
        for line in findings:
            print(f"    - {line}")
    if harness_gaps:
        print(f"  harness gaps ({len(harness_gaps)}): the drive did not reach the check, so the")
        print("  alert assertion is not established either way (extend the drive; see the docs):")
        for line in harness_gaps:
            print(f"    - {line}")
    if recurrence:
        print(f"  recurrence failures ({len(recurrence)}): the check alerted but never re-armed, so")
        print("  its episode latch is still held from the first episode:")
        for line in recurrence:
            print(f"    - {line}")
    if assertion_less:
        print(f"  assertion-less scenarios ({len(assertion_less)}): the scenario asserted nothing, so")
        print("  its green would be vacuous. Restore the assertion or document why there is none:")
        for line in assertion_less:
            print(f"    - {line}")
    if documented_fp:
        print()
        print("  ! VERIFIED FALSE POSITIVES above are release-blocking bugs, recorded so unrelated")
        print("    runs stay green. Fix the check (or delete the entry if it no longer fires).")
    print("=" * 78)

    failures = [r for r in reports if not r.get("passed")]
    return (0 if not failures else 1), findings, documented_fp


def write_markdown(reports: list[dict], verdict: int, findings: list[str]) -> None:
    lines = ["# Iustitia automated live tests", ""]
    lines.append(f"- scenarios: {len(reports)} - passed: {sum(1 for r in reports if r.get('passed'))}")
    header = "| pass | scenario | source | result | notes |"
    lines += [header, "|---|---|---|---|---|"]
    for r in reports:
        notes = ""
        if r.get("error"):
            notes = f"error: {r['error']}"
        elif r.get("missedChecks"):
            notes = f"BYPASS: {', '.join(r['missedChecks'])}"
        elif r.get("falsePositives"):
            notes = f"FALSE POSITIVE: {', '.join(r['falsePositives'])}"
        elif r.get("alertCountMisses"):
            notes = "; ".join(r["alertCountMisses"])
        elif r.get("noAssertions"):
            notes = f"NO ASSERTIONS: scenario asserted nothing ({r.get('assertions', 0)} inline checks, no expectation)"
        elif r.get("vl"):
            notes = ", ".join(f"{k}={v:.1f}" for k, v in sorted(r["vl"].items())[:5])
        lines.append(
            f"| {r.get('pass')} | {r.get('scenario')} | {r.get('source')} | "
            f"{'PASS' if r.get('passed') else 'FAIL'} | {notes} |"
        )
    if findings:
        lines += ["", "## Documented findings", ""]
        lines += [f"- {f}" for f in findings]
    lines += ["", f"_runner verdict: {'OK' if verdict == 0 else 'FAILURES'}_", ""]
    MARKDOWN_PATH.parent.mkdir(parents=True, exist_ok=True)
    MARKDOWN_PATH.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    args = parse_args()

    # Offline modes: never boot a client.
    if args.list:
        manifest = load_manifest()
        if not manifest:
            print("No manifest yet -- run the suite once (any filter), then --list works offline.")
            return 1
        print_list(manifest)
        return 0
    if args.coverage:
        manifest = load_manifest()
        if not manifest:
            print("No manifest yet -- run the suite once (any filter), then --coverage works offline.")
            return 1
        return print_coverage(manifest, args.require_multi_source)
    if args.matrix:
        if not REPORT_PATH.exists():
            print(f"No report at {REPORT_PATH.relative_to(ROOT)} -- run the suite first.")
            return 1
        reports = json.loads(REPORT_PATH.read_text(encoding="utf-8"))
        if args.check:
            needle = args.check.lower()
            reports = [r for r in reports if needle in (r.get("scenario") or "").lower()]
        return print_matrix(reports)

    cmd = build_command(args)
    started = time.time()
    code, output = run_suite(cmd, args.timeout, args.quiet)
    elapsed = time.time() - started

    manifest = extract_manifest(output)
    reports = extract_report(output)
    if reports is None:
        statuses = extract_statuses(output)
        if statuses:
            # Report marker missing but scenarios ran: reconstruct a minimal report.
            reports = [
                {
                    "scenario": name,
                    "pass": pass_name,
                    "source": "?",
                    "tags": [],
                    "expectations": {},
                    "passed": status == "PASS",
                    "vl": {},
                    "alertedChecks": [],
                    "missedChecks": [],
                    "falsePositives": [],
                    "alertCounts": {},
                    "alertCountMisses": [],
                    "knownOpen": [],
                    "assertions": 0,
                    "noAssertions": False,
                    "durationMs": 0,
                    "error": None,
                }
                for status, pass_name, name in statuses
            ]
        else:
            tail = "\n".join(output.splitlines()[-args.tail:])
            print("The live suite did not produce a report -- it never reached the scenarios.")
            print(f"gradle exit code: {code}  ({elapsed:.0f}s)")
            print(tail)
            print()
            print("Treat this as NOT RUN, not as success. Common causes: the client failed to")
            print("launch, a mixin failed to apply, or the world could not be created.")
            return 1

    # A run that dies mid-suite (client crash, teardown hang) still emits per-scenario status
    # lines for every scenario it finished, so the reconstruction above can hand us a one-row
    # report and look green. The manifest is printed by the same filtered scenario list the
    # game then runs, so a healthy run's report count must equal it exactly -- anything less
    # means the suite died part-way, which is a FAILURE even when every scenario that ran
    # happened to pass. Checked BEFORE merge_reports so a dead run also stops clobbering the
    # accumulated report/manifest evidence with its partial results.
    expected = len((manifest or {}).get("scenarios") or [])
    if expected and len(reports) < expected:
        ran = {r.get("scenario") for r in reports}
        missing = [s.get("scenario") for s in manifest["scenarios"] if s.get("scenario") not in ran]
        tail = "\n".join(output.splitlines()[-args.tail:])
        print(f"The live suite died mid-run: only {len(reports)}/{expected} scenarios reported.")
        if missing:
            print(f"First scenario never reported: {missing[0]} ({len(missing)} missing total).")
        print("Treat this as a FAILURE, not a partial pass -- the client crashed or a world")
        print("teardown hung before the suite finished. The tail of the game output follows:")
        print()
        print(tail)
        return 1

    # Union with the previous report instead of replacing it. A filtered (`--check`) or sharded
    # (`--shard`) run only contains its own scenarios, so a plain overwrite would make --matrix
    # lose every other shard's evidence -- and, worse, silently shrink it while still looking
    # complete. Newest result wins per scenario (see merge_reports).
    full_run = not (
        args.check or args.source or args.tag or args.shard or args.legit_only
        or args.cheat_only or args.fast or args.pass_name != "all"
    )
    merge_reports(reports, replace=full_run)
    if manifest:
        write_manifest(manifest, reports, replace=full_run)

    verdict, findings, documented_fp = summarise(reports, args.require_multi_source, args.verbose)

    if args.format == "json":
        print(json.dumps({
            "verdict": verdict,
            "elapsedSeconds": round(elapsed, 1),
            "reports": reports,
            "findings": findings,
        }, indent=2))
    if args.report_md:
        write_markdown(reports, verdict, findings)

    print(f"report: {REPORT_PATH.relative_to(ROOT)}   ({elapsed:.0f}s)")
    if args.report_md:
        print(f"markdown: {MARKDOWN_PATH.relative_to(ROOT)}")

    if verdict != 0:
        print()
        print("Paste the table above into the PR, then fix what failed. A FALSE POSITIVE")
        print("means a guard/tuning problem in the detector; a BYPASS means the scenario")
        print("drove a pattern the check does not catch. See docs/automated-live-testing.md.")
    elif findings and not args.strict:
        print()
        print("Documented findings above are recorded detector gaps and verified false positives,")
        print("not failures. Paste them into the PR and do not silence them. Re-run with --strict")
        print("to make them blocking (CI should use --strict before a release).")

    if args.strict and findings:
        print()
        print(f"--strict: {len(findings)} documented finding(s) treated as failures.")
        return 1
    return verdict


if __name__ == "__main__":
    sys.exit(main())

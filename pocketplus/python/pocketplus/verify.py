"""Run Stainless formal verification on the scala files whose paths are given in this script and report the VC results.

Used by the `verify`-marked pytest test (python/tests/test_verify.py); also runnable directly
(`python -m pocketplus.verify`). Standard library only. Verification is slow, so the test that
calls this is excluded from ordinary builds via the `verify` marker.

Settings (timeout, solvers, etc.) live in scala/stainless.conf; pass extra_opts to override any
of them for a single run (e.g. extra_opts=["--timeout=3"]).
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO = os.path.dirname(os.path.dirname(_HERE))  # pocketplus -> python -> repo root

SCALA_DIR = os.path.join(_REPO, "scala")
SCALA_SOURCE_FILES_DIR = os.path.join(SCALA_DIR, "src", "main", "scala", "pocket")
STAINLESS_DIR = os.path.join(_REPO, "tools", "stainless")
JAR = os.path.join(STAINLESS_DIR, "lib", "stainless-dotty-standalone-0.10.5.jar")
CONFIG_FILE = os.path.join(SCALA_DIR, "stainless.conf")
CACHE_DIR = os.path.join(_REPO, "build", ".stainless-cache")
REPORT_JSON = os.path.join(CACHE_DIR, "report.json")

# Configuration for the stainless invocation; files to verify, JVM args
FILES_TO_VERIFY = [
    # os.path.join(SCALA_DIR, "PocketPlus.scala"),
    os.path.join(SCALA_SOURCE_FILES_DIR, "PocketExecSpec.scala"),
    os.path.join(SCALA_SOURCE_FILES_DIR, "PocketImperative.scala"),
    os.path.join(SCALA_SOURCE_FILES_DIR, "PocketExecSpecLemmas.scala"),
    os.path.join(SCALA_SOURCE_FILES_DIR, "ListLemmas.scala"),
    os.path.join(SCALA_SOURCE_FILES_DIR, "datastructure", "arrays", "BitArray.scala"),
    os.path.join(SCALA_SOURCE_FILES_DIR, "datastructure", "arrays", "ByteArray.scala"),
    os.path.join(SCALA_SOURCE_FILES_DIR, "datastructure", "arrays", "IntArray.scala"),
    os.path.join(SCALA_SOURCE_FILES_DIR, "datastructure", "Utils.scala"),
]
# VCs checked in parallel. Each VC runs the whole solver portfolio (z3, cvc5 and the in-process
# Princess), so N workers mean up to 3N solver instances at once. Override with
# POCKETPLUS_VERIFY_PARALLEL; CI sets 1: on a 4-vCPU runner, 4 workers starve Princess, the only
# solver that proves the Int/BigInt conversion lemmas of datastructure/Utils.scala, until they
# time out, while 1 worker verifies everything in under 3 minutes.
N_THREADS = int(os.environ.get("POCKETPLUS_VERIFY_PARALLEL", "4"))


JVM_ARGS = ["-Xss512m", "--sun-misc-unsafe-memory-access=allow", "-Dparallel={}".format(N_THREADS)]

# VC statuses that count as proven; anything else (Invalid, Inconclusive) is unproven.
_PASSING_STATUSES = {"Valid", "ValidFromCache", "Trivial"}

# With sat-precond (set in stainless.conf), Stainless also looks, for each function, for values that
# satisfy its preconditions. A precondition that nothing satisfies is reported Invalid and fails the
# gate like any other VC. The search can also time out, typically on a state with a large invariant:
# that proves nothing either way, so it is listed in the output without failing the gate.
_SAT_PRECOND_KIND = "precondition satisfiability"


def _status(record: dict) -> str:
    return next(iter(record["status"]))


def _sat_precond_inconclusive(record: dict) -> bool:
    return record["kind"] == _SAT_PRECOND_KIND and _status(record) == "Inconclusive"


def _vc_location(record: dict) -> str:
    pos = record["pos"]
    p = pos["begin"] if "begin" in pos else pos
    if "file" not in p:  # e.g. {"kind": {"Unknown": {}}} for VCs Stainless can't place
        return "<unknown position>"
    file = os.path.relpath(p["file"], _REPO)
    return f"{file}:{p['line']}:{p['col']}"


def _vc_summary(record: dict) -> str:
    status = _status(record)
    detail = next(iter(record["status"].values())).get("reason", "")
    detail = f" ({detail})" if detail else ""
    return f"{_vc_location(record)}: {record['kind']} [{status}]{detail}"


def run(files: list[str] | None = None, extra_opts: list[str] | None = None) -> dict:
    """Run Stainless verification; return {total, valid, invalid, unknown, output, unproven,
    sat_precond_inconclusive}.

    `files` defaults to FILES_TO_VERIFY; `extra_opts` are appended to the Stainless command line
    (e.g. to override a setting from stainless.conf for this run). `unproven` lists the VCs that
    did not verify, for diagnostics. `sat_precond_inconclusive` lists the functions whose
    preconditions Stainless could neither satisfy nor refute; they are not counted in `unknown`.
    Raises FileNotFoundError if the vendored Stainless jar is missing.
    """
    if not os.path.isfile(JAR):
        raise FileNotFoundError(f"Vendored Stainless jar not found: {JAR}")

    java_name = "java.exe" if os.name == "nt" else "java"
    java = os.path.join(os.environ["JAVA_HOME"], "bin", java_name) if os.environ.get("JAVA_HOME") else "java"
    env = dict(os.environ)
    env["PATH"] = os.pathsep.join([
        os.path.join(STAINLESS_DIR, "z3"),
        os.path.join(STAINLESS_DIR, "cvc5"),
        env.get("PATH", ""),
    ])
    os.makedirs(CACHE_DIR, exist_ok=True)
    if os.path.isfile(REPORT_JSON):
        os.remove(REPORT_JSON)

    cmd = [java, *JVM_ARGS, "-jar", JAR,
           f"--config-file={CONFIG_FILE}", f"--cache-dir={CACHE_DIR}", f"--json={REPORT_JSON}",
           *(extra_opts or []), *(files or FILES_TO_VERIFY)]
    print(f"[verify] running: {' '.join(cmd)}", flush=True)

    # cwd inside the (gitignored) cache dir so Stainless'/Coursier's stray "null" cache lands there.
    # Stream output live (visible under pytest with -s) while also capturing it for parsing.
    proc = subprocess.Popen(cmd, env=env, cwd=CACHE_DIR, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True)
    captured = []
    for line in proc.stdout:
        print(line, end="", flush=True)
        captured.append(line)
    proc.wait()
    out = re.sub(r"\x1b\[[0-9;]*m", "", "".join(captured))

    if not os.path.isfile(REPORT_JSON):
        return {"total": 0, "valid": 0, "invalid": 0, "unknown": 0, "output": out, "unproven": "",
                "sat_precond_inconclusive": ""}

    with open(REPORT_JSON) as report_file:
        report = json.load(report_file)
        records = next(entry[1][0] for entry in report["stainless"] if entry[0] == "verification")

        valid = sum(1 for r in records if _status(r) in _PASSING_STATUSES)
        sat_inconclusive = [r for r in records if _sat_precond_inconclusive(r)]
        unproven_records = [r for r in records
                            if _status(r) not in _PASSING_STATUSES and not _sat_precond_inconclusive(r)]
        invalid = sum(1 for r in unproven_records if _status(r) == "Invalid")
        unknown = len(unproven_records) - invalid

        sat_inconclusive_text = "\n".join(_vc_summary(r) for r in sat_inconclusive)
        if sat_inconclusive:
            print(f"[verify] preconditions neither satisfied nor refuted ({len(sat_inconclusive)}, "
                  f"not counted as failures):\n{sat_inconclusive_text}", flush=True)

        return {"total": len(records), "valid": valid, "invalid": invalid, "unknown": unknown,
            "output": out, "unproven": "\n".join(_vc_summary(r) for r in unproven_records),
            "sat_precond_inconclusive": sat_inconclusive_text}


def main() -> int:
    files = [a for a in sys.argv[1:] if not a.startswith("-")] or None
    extra_opts = [a for a in sys.argv[1:] if a.startswith("-")]
    r = run(files=files, extra_opts=extra_opts)
    sat_inconclusive = len(r["sat_precond_inconclusive"].splitlines())
    print(f"[verify] total={r['total']} valid={r['valid']} invalid={r['invalid']} unknown={r['unknown']} "
          f"sat-precond-inconclusive={sat_inconclusive}")
    if r["total"] == 0:
        print(r["output"][-4000:])
        print("[verify] FAILED: no Stainless summary (extraction error or crash).")
        return 2
    if r["invalid"] + r["unknown"] > 0:
        print("[verify] unproven VCs:\n" + r["unproven"])
        print("[verify] GATE FAILED.")
        return 1
    print("[verify] GATE PASSED: all VCs valid.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

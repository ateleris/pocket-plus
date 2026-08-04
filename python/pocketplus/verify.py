"""Run Stainless formal verification on the scala sources and report the VC results.

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
STAINLESS_DIR = os.path.join(_REPO, "tools", "stainless")
JAR = os.path.join(STAINLESS_DIR, "lib", "stainless-dotty-standalone-0.10.0.jar")
CONFIG_FILE = os.path.join(SCALA_DIR, "stainless.conf")
CACHE_DIR = os.path.join(_REPO, "build", ".stainless-cache")
REPORT_JSON = os.path.join(CACHE_DIR, "report.json")

# Configuration for the stainless invocation; files to verify, JVM args
FILES_TO_VERIFY = [
    # os.path.join(SCALA_DIR, "PocketPlus.scala"),
    os.path.join(SCALA_DIR, "datastructure", "arrays", "IntArray.scala"),
    os.path.join(SCALA_DIR, "datastructure", "arrays", "ByteArray.scala"),
]
N_THREADS = 4


JVM_ARGS = ["-Xss512m", "--sun-misc-unsafe-memory-access=allow", "-Dparallel={}".format(N_THREADS)]

# VC statuses that count as proven; anything else (Invalid, Inconclusive) is unproven.
_PASSING_STATUSES = {"Valid", "ValidFromCache", "Trivial"}


def _vc_location(record: dict) -> str:
    pos = record["pos"]
    p = pos["begin"] if "begin" in pos else pos
    file = os.path.relpath(p["file"], _REPO)
    return f"{file}:{p['line']}:{p['col']}"


def _vc_summary(record: dict) -> str:
    status = next(iter(record["status"]))
    detail = next(iter(record["status"].values())).get("reason", "")
    detail = f" ({detail})" if detail else ""
    return f"{_vc_location(record)}: {record['kind']} [{status}]{detail}"


def run(files: list[str] | None = None, extra_opts: list[str] | None = None) -> dict:
    """Run Stainless verification; return {total, valid, invalid, unknown, output, unproven}.

    `files` defaults to FILES_TO_VERIFY; `extra_opts` are appended to the Stainless command line
    (e.g. to override a setting from stainless.conf for this run). `unproven` lists the VCs that
    did not verify, for diagnostics. Raises FileNotFoundError if the vendored Stainless jar is
    missing.
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
        return {"total": 0, "valid": 0, "invalid": 0, "unknown": 0, "output": out, "unproven": ""}

    report = json.load(open(REPORT_JSON))
    records = next(entry[1][0] for entry in report["stainless"] if entry[0] == "verification")

    valid = sum(1 for r in records if next(iter(r["status"])) in _PASSING_STATUSES)
    unproven_records = [r for r in records if next(iter(r["status"])) not in _PASSING_STATUSES]
    invalid = sum(1 for r in unproven_records if next(iter(r["status"])) == "Invalid")
    unknown = len(unproven_records) - invalid

    return {"total": len(records), "valid": valid, "invalid": invalid, "unknown": unknown,
            "output": out, "unproven": "\n".join(_vc_summary(r) for r in unproven_records)}


def main() -> int:
    files = [a for a in sys.argv[1:] if not a.startswith("-")] or None
    extra_opts = [a for a in sys.argv[1:] if a.startswith("-")]
    r = run(files=files, extra_opts=extra_opts)
    print(f"[verify] total={r['total']} valid={r['valid']} invalid={r['invalid']} unknown={r['unknown']}")
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

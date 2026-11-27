"""Run Stainless formal verification through the sbt project in scala/ and report the VC results.

Used by the `verify`-marked pytest test (python/tests/test_verify.py); also runnable directly
(`python -m pocketplus.verify`). Standard library only. Verification is slow, so the test that
calls this is excluded from ordinary builds via the `verify` marker.

Stainless is the compiler plugin from Maven Central, set up in scala/build.sbt: the `verify` sbt
project compiles the verified sources (`verifiedSources` in build.sbt) with verification on. The
only requirements are sbt (and a JDK) on the PATH, and the z3 and cvc5 binaries, either installed
by install_solvers.sh in tools/solvers/bin or on the PATH. Settings (timeout, solvers, etc.) live in
scala/stainless.conf, which Stainless finds in the working directory of sbt.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO = os.path.dirname(os.path.dirname(_HERE))  # pocketplus -> python -> repo root

SCALA_DIR = os.path.join(_REPO, "scala")
SOLVERS_DIR = os.path.join(_REPO, "tools", "solvers", "bin")  # written by install_solvers.sh

# VCs checked in parallel. Each VC runs the whole solver portfolio (z3, cvc5 and the in-process
# Princess), so N workers mean up to 3N solver instances at once. Override with
# POCKETPLUS_VERIFY_PARALLEL; CI sets 1: on a 4-vCPU runner, 4 workers starve Princess, the only
# solver that proves the Int/BigInt conversion lemmas of datastructure/Utils.scala, until they
# time out, while 1 worker verifies everything in under 3 minutes.
N_THREADS = int(os.environ.get("POCKETPLUS_VERIFY_PARALLEL", "4"))

# Options of the JVM of sbt, in which the compiler, and so Stainless, runs. -Dparallel is read by
# Stainless; the deep recursion of the extraction needs a big stack.
SBT_JVM_ARGS = ["-Dparallel={}".format(N_THREADS), "-J-Xss512m",
                "-J--sun-misc-unsafe-memory-access=allow", "-Dsbt.color=false", "-Dsbt.supershell=false"]
# `clean` so that Stainless runs even when the sources did not change since the last compilation;
# the VC cache (scala/.stainless-cache) still skips the VCs already proven.
SBT_COMMANDS = ["verify/clean", "verify/compile"]

# VC statuses (in the summary table of Stainless) that count as proven, and the other ones (the
# names of stainless.verification.VCStatus)
_PASSING_STATUSES = {"valid", "valid from cache", "trivial"}
_FAILING_STATUSES = {"invalid", "admitted", "unknown", "timeout", "resource limit reached",
                     "unroll bound reached", "cancelled", "crashed", "unsupported", "external bug"}

_TOTAL = re.compile(r"total:\s*(\d+)\s+valid:\s*(\d+).*?invalid:\s*(\d+)\s+unknown:\s*(\d+)")
# A row of the summary table: position, function, VC kind, status, then the solver, time, etc.
# (depending on the status), separated by 2+ spaces. The kind can contain 2+ spaces too (it may
# quote code), so the status is found by its name.
_ROW = re.compile(r"║\s*(\S+:\d+:\d+):\s+(.*?)\s*║")


def _sbt() -> str:
    # shutil.which also finds sbt.bat on Windows (through PATHEXT)
    sbt = shutil.which("sbt")
    if sbt is None:
        raise FileNotFoundError("sbt not found on PATH; it runs Stainless (see scala/build.sbt)")
    return sbt


def _unproven(out: str) -> list[str]:
    """The rows of the summary table whose status is not a passing one, as
    `position: function kind [status]`."""
    rows = []
    for m in _ROW.finditer(out):
        fields = re.split(r"\s{2,}", m.group(2))
        i = next((i for i in range(2, len(fields)) if fields[i] in _PASSING_STATUSES | _FAILING_STATUSES), None)
        if i is not None and fields[i] in _FAILING_STATUSES:
            rows.append(f"scala/{m.group(1)}: {fields[0]} {' '.join(fields[1:i])} [{fields[i]}]")
    return rows


def run() -> dict:
    """Run Stainless verification; return {total, valid, invalid, unknown, output, unproven}.

    `unproven` lists the VCs that did not verify, for diagnostics. Raises FileNotFoundError if
    sbt is not on the PATH.
    """
    cmd = [_sbt(), "--batch", *SBT_JVM_ARGS, *SBT_COMMANDS]
    print(f"[verify] running in {SCALA_DIR}: {' '.join(cmd)}", flush=True)

    # Stream output live (visible under pytest with -s) while also capturing it for parsing.
    env = dict(os.environ)
    env["PATH"] = os.pathsep.join([SOLVERS_DIR, env.get("PATH", "")])
    proc = subprocess.Popen(cmd, cwd=SCALA_DIR, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    captured = []
    for line in proc.stdout:
        print(line, end="", flush=True)
        captured.append(line)
    proc.wait()
    out = re.sub(r"\x1b\[[0-9;]*m", "", "".join(captured))

    totals = _TOTAL.findall(out)
    if not totals:  # e.g. a compilation or extraction error
        return {"total": 0, "valid": 0, "invalid": 0, "unknown": 0, "output": out, "unproven": ""}
    total, valid, invalid, unknown = (int(n) for n in totals[-1])
    return {"total": total, "valid": valid, "invalid": invalid, "unknown": unknown,
            "output": out, "unproven": "\n".join(_unproven(out))}


def main() -> int:
    r = run()
    print(f"[verify] total={r['total']} valid={r['valid']} invalid={r['invalid']} unknown={r['unknown']}")
    if r["total"] == 0:
        print(r["output"][-4000:])
        print("[verify] FAILED: no Stainless summary (compilation or extraction error).")
        return 2
    if r["invalid"] + r["unknown"] > 0:
        print("[verify] unproven VCs:\n" + r["unproven"])
        print("[verify] GATE FAILED.")
        return 1
    print("[verify] GATE PASSED: all VCs valid.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

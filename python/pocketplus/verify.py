"""Run Stainless formal verification on PocketPlus.scala and report the VC results.

Used by the `verify`-marked pytest test (python/tests/test_verify.py); also runnable directly
(`python -m pocketplus.verify`). Standard library only. Verification is slow, so the test that
calls this is excluded from ordinary builds via the `verify` marker.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO = os.path.dirname(os.path.dirname(_HERE))  # pocketplus -> python -> repo root

STAINLESS_DIR = os.path.join(_REPO, "tools", "stainless")
JAR = os.path.join(STAINLESS_DIR, "lib", "stainless-dotty-standalone-0.10.0.jar")
SCALA = os.path.join(_REPO, "scala", "PocketPlus.scala")
CACHE_DIR = os.path.join(_REPO, "build", ".stainless-cache")

JVM_ARGS = ["-Xss512m", "--sun-misc-unsafe-memory-access=allow"]


def run(timeout: int = 5, solvers: str = "smt-z3,smt-cvc5") -> dict:
    """Run Stainless verification; return {total, valid, invalid, unknown, output, unproven}.

    `unproven` is the list of VC rows that did not verify (for diagnostics). Raises FileNotFoundError
    if the vendored Stainless jar is missing.
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

    cmd = [java, *JVM_ARGS, "-jar", JAR, "--config-file=false",
           f"--timeout={timeout}", f"--solvers={solvers}",
           "--vc-cache=true", f"--cache-dir={CACHE_DIR}", SCALA]

    # cwd inside the (gitignored) cache dir so Stainless'/Coursier's stray "null" cache lands there.
    proc = subprocess.run(cmd, env=env, cwd=CACHE_DIR, capture_output=True, text=True)
    out = re.sub(r"\x1b\[[0-9;]*m", "", proc.stdout + proc.stderr)
    lines = [l for l in out.splitlines() if "WARNING" not in l]

    m = re.search(r"total:\s*(\d+).*?valid:\s*(\d+).*?invalid:\s*(\d+).*?unknown:\s*(\d+)",
                  "\n".join(lines), re.S)
    if not m:
        return {"total": 0, "valid": 0, "invalid": 0, "unknown": 0,
                "output": out, "unproven": ""}
    total, valid, invalid, unknown = (int(g) for g in m.groups())
    unproven = "\n".join(l.strip() for l in lines
                         if re.search(r"\b(invalid|unknown)\b", l) and "total:" not in l)
    return {"total": total, "valid": valid, "invalid": invalid, "unknown": unknown,
            "output": out, "unproven": unproven}


def main() -> int:
    timeout = int(os.environ.get("POCKETPLUS_VERIFY_TIMEOUT", "5"))
    r = run(timeout=timeout)
    print(f"[verify] total={r['total']} valid={r['valid']} invalid={r['invalid']} unknown={r['unknown']}")
    if r["total"] == 0:
        print(r["output"][-4000:])
        print("[verify] FAILED: no Stainless summary (extraction error or crash).")
        return 2
    if r["invalid"] + r["unknown"] > 0:
        print("[verify] unproven VCs:\n" + r["unproven"])
        print(f"[verify] GATE FAILED at {timeout}s/VC.")
        return 1
    print("[verify] GATE PASSED: all VCs valid.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Contract checks every pocketbench adapter must pass: exit 2 on an argv it does not
understand, and on a malformed record container."""

from __future__ import annotations

import subprocess
from pathlib import Path

from pocketbench import adapter, records
from pocketbench.adapter.discovery import DiscoveredAdapter
from pocketbench.records import Container, Record


def _exit_code(d: DiscoveredAdapter, argv: list[str]) -> int:
    proc = subprocess.run(argv, cwd=d.manifest.dir, capture_output=True, text=True, timeout=120)
    return proc.returncode


def assert_rejects_contract_violations(d: DiscoveredAdapter, *, tmp_path: Path) -> None:
    """Each argv here is a harness/adapter mismatch, which must fail loudly with exit 2."""
    inp = tmp_path / "contract.rec"
    inp.write_bytes(records.encode(Container(16, (Record(0, 16, b"\0\0"),))))
    out = tmp_path / "contract.out"
    good = adapter.verb_argv(d, "compress", inp, out)
    cases = {
        "an unknown flag": [*good, "--not-a-real-flag=1"],
        "a missing --out": [a for a in good if not a.startswith("--out=")],
        "a duplicated flag": [*good, f"--in={inp}"],
        "a non-numeric count": [*good, "--iterations=x"],
        "a positional argv": adapter.launch_argv(d, "compress", inp, out),
        "an unknown subcommand": adapter.launch_argv(d, "not-a-subcommand"),
    }
    for why, argv in cases.items():
        code = _exit_code(d, argv)
        assert code == 2, f"{d.manifest.name}: expected exit 2 for {why}, got {code}\nargv: {argv}"
    bad = tmp_path / "malformed.rec"
    bad.write_bytes(b"\x00\x00\x00\x10\x00\x00")
    code = _exit_code(d, adapter.verb_argv(d, "compress", bad, out))
    assert code == 2, f"{d.manifest.name}: expected exit 2 for a malformed container, got {code}"

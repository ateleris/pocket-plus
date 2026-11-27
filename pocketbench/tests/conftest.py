"""Shared fixtures for adapter tests that build a real wrapper."""

import os
import tomllib
from collections import Counter
from pathlib import Path

import pytest

from pocketbench import adapter
from pocketbench.adapter.discovery import DiscoveredAdapter, discover_adapters, source_present

REPO = Path(__file__).resolve().parents[1]


def scripted_adapter(tmp_path: Path, body: str) -> DiscoveredAdapter:
    """An adapter whose every subcommand runs ``body`` (Python), for failure modes the mock never
    shows: crashes, hangs, malformed output."""
    d = tmp_path / "adapters" / "scripted"
    d.mkdir(parents=True)
    (d / "adapter.py").write_text(f"import os, sys, time\nfrom pathlib import Path\n{body}\n")
    (d / "adapter.toml").write_text('interpreter = "python"\nentrypoint = "adapter.py"\n')
    (found,) = discover_adapters(tmp_path / "adapters", repo_root=tmp_path, variables={})
    return found

# Real adapters are built at the codec default (65535 bits): the UAB vectors in test_adapters.py
# have large_f above 720, and one binary cannot be both sizes.


def _settings_variables() -> dict[str, str | Path]:
    """The `[settings]` an adapter `source` may reference as `${var}`, e.g. `${ccsds124_root}`.

    Read straight from config/config.toml rather than via load_config, which also discovers
    datasets and would couple these fixtures to the state of the test data. config.toml is
    machine-local and gitignored, so a fresh clone falls back to the committed template; a missing
    key still yields {}, so the ${var} fails to expand and the test skips as source-absent.
    """
    cfg = REPO / "config" / "config.toml"
    if not cfg.exists():
        cfg = REPO / "config" / "config.example.toml"
    if not cfg.exists():
        return {}
    settings = tomllib.loads(cfg.read_text()).get("settings", {})
    base = cfg.parent
    return {k: (base / v).resolve() for k, v in settings.items() if isinstance(v, str)}


def ccsds124_root() -> Path:
    """Where the external ccsds124 checkout lives, per `[settings] ccsds124_root`.

    The checkout is deliberately kept outside this repo, so there is no in-repo path to fall back
    on. Without the setting this returns a path that cannot exist, so a test guarding on it skips
    rather than errors.
    """
    return Path(_settings_variables().get("ccsds124_root", REPO / "no-ccsds124-root-configured"))


def _built(name: str, max_packet_bits: int | None):
    found = discover_adapters(REPO / "adapters", repo_root=REPO, variables=_settings_variables())
    matches = [d for d in found if d.manifest.name == name]
    if not matches:
        pytest.skip(f"{name} adapter folder not present")
    d = matches[0]
    if not source_present(d):
        pytest.skip(f"{name}: codec source not present at {d.error or d.resolved_source}")
    result = adapter.build(d, max_packet_bits=max_packet_bits)
    assert result.ok, f"{name} build failed:\n{result.stderr}"
    return d


_BUILT: dict[str, DiscoveredAdapter] = {}


def built_adapter(name: str) -> DiscoveredAdapter:
    """A built adapter for `name`, or skip if its codec is not present.

    Memoized because reference-c's Makefile recompiles on every request (its FORCE target) and the
    parametrized suite asks from every test body.
    """
    if name not in _BUILT:
        _BUILT[name] = _built(name, None)
    return _BUILT[name]



def _skip_reason(report) -> str:
    reason = report.longrepr[2] if isinstance(report.longrepr, tuple) else str(report.longrepr)
    return reason.removeprefix("Skipped: ")


def _failure_message(report) -> str:
    crash = getattr(report.longrepr, "reprcrash", None)
    text = crash.message if crash else str(report.longrepr)
    return text.splitlines()[0].replace("|", "\\|")[:200] if text else ""


def pytest_terminal_summary(terminalreporter, exitstatus, config):
    """Counts, failures and grouped skip reasons on the GitHub Actions job page."""
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    stats = terminalreporter.stats
    failed = [r for key in ("failed", "error") for r in stats.get(key, [])]
    skipped = Counter(_skip_reason(r) for r in stats.get("skipped", []))
    counts = [f"{len(stats.get('passed', []))} passed", f"{len(failed)} failed",
              f"{sum(skipped.values())} skipped", f"{len(stats.get('deselected', []))} deselected"]
    lines = [f"### pytest: {', '.join(counts)}", ""]
    if config.option.keyword:
        lines += [f"Selected with `-k \"{config.option.keyword}\"`.", ""]
    if failed:
        lines += ["| Failed | Error |", "|---|---|"]
        lines += [f"| `{r.nodeid}` | {_failure_message(r)} |" for r in failed]
        lines.append("")
    if skipped:
        lines += ["| Skipped | Reason |", "|---|---|"]
        lines += [f"| {n} | {reason.replace('|', '\\|')} |" for reason, n in skipped.most_common()]
        lines.append("")
    with open(path, "a") as fh:
        fh.write("\n".join(lines) + "\n")

"""Shared fixtures for adapter tests that build a real wrapper."""

import tomllib
from pathlib import Path

import pytest

from pocketbench import adapter
from pocketbench.adapter.discovery import DiscoveredAdapter, discover_adapters, source_present

REPO = Path(__file__).resolve().parents[1]

# Every Spec in test_adapters.py leaves max_packet_bits at None, i.e. the codec default of 65535
# bits: the conformance tests feed UAB vectors whose large_f exceeds 720, and one binary cannot be
# both sizes.


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


def built_adapter(name: str, max_packet_bits: int | None = None) -> DiscoveredAdapter:
    """A built adapter for `name`, or skip if its codec is not present.

    Memoized because reference-c's Makefile recompiles on every request (its FORCE target) and the
    parametrized suite asks from every test body. A plain function rather than a fixture so a test
    can pass its Spec's own build settings.
    """
    if name not in _BUILT:
        _BUILT[name] = _built(name, max_packet_bits)
    return _BUILT[name]


"""Discover adapters by scanning `adapters/*/adapter.toml` and gate them on presence.

Discovery is a pure folder scan. Presence is checked in two gates at the phases where it matters:
Gate 1 (source present) belongs to the build step, so a committed wrapper never tries to build
against an absent codec; Gate 2 (entrypoint present) belongs to run time, so `--no-build` runs
work off the built wrapper alone (which has linked the codec in).
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

from pocketbench.adapter.manifest import Manifest, load_manifest, resolve_source


@dataclass(frozen=True)
class DiscoveredAdapter:
    """A manifest plus its resolved codec location (or the reason it could not resolve)."""

    manifest: Manifest
    resolved_source: Path | None  # None if self-contained OR if `error` is set
    error: str | None             # e.g. "unknown settings variable ${x}"; None if fine


def _non_empty(path: Path) -> bool:
    if path.is_dir():
        return any(path.iterdir())
    return path.is_file() and path.stat().st_size > 0


def discover_adapters(
    adapters_root: Path, *, repo_root: Path, variables: Mapping[str, str | Path]
) -> list[DiscoveredAdapter]:
    """One DiscoveredAdapter per `adapters_root/*/adapter.toml`, sorted by manifest name."""
    found: list[DiscoveredAdapter] = []
    if not adapters_root.is_dir():
        return found
    for child in sorted(p for p in adapters_root.iterdir() if p.is_dir()):
        if not (child / "adapter.toml").is_file():
            continue
        manifest = load_manifest(child)
        try:
            resolved = resolve_source(manifest, repo_root=repo_root, variables=variables)
            error = None
        except KeyError as exc:
            resolved, error = None, str(exc).strip('"')
        found.append(DiscoveredAdapter(manifest=manifest, resolved_source=resolved, error=error))
    return sorted(found, key=lambda d: d.manifest.name)


def source_present(d: DiscoveredAdapter) -> bool:
    """Gate 1: is the codec source available so the wrapper can be built?"""
    if d.manifest.source is None:
        return True  # self-contained (e.g. the mock)
    if d.error is not None:
        return False
    return d.resolved_source is not None and _non_empty(d.resolved_source)


def entrypoint_path(d: DiscoveredAdapter) -> Path:
    return d.manifest.dir / d.manifest.entrypoint


def entrypoint_present(d: DiscoveredAdapter) -> bool:
    """Gate 2: is the built contract executable there so the wrapper can run?"""
    return entrypoint_path(d).exists()

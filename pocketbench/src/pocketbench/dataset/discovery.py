"""Discover datasets by scanning `datasets/*/dataset.toml`.

Discovery is a pure folder scan, mirroring `adapter/discovery.py`. Each folder's data location is
resolved against `[settings]` variables and the repo root; a `${var}` that cannot be expanded is
captured as an error (not raised) so one broken manifest never aborts the whole scan.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

from pocketbench.dataset.manifest import DatasetManifest, load_dataset_manifest, resolve


@dataclass(frozen=True)
class DiscoveredDataset:
    """A dataset manifest plus its resolved data location (or the reason it could not resolve)."""

    manifest: DatasetManifest
    resolved_source: Path | None  # None only when `error` is set
    error: str | None             # e.g. "unknown settings variable ${x}"; None if fine


def discover_datasets(
    datasets_root: Path, *, repo_root: Path, variables: Mapping[str, str | Path]
) -> list[DiscoveredDataset]:
    """One DiscoveredDataset per `datasets_root/*/dataset.toml`, sorted by manifest name."""
    found: list[DiscoveredDataset] = []
    if not datasets_root.is_dir():
        return found
    for child in sorted(p for p in datasets_root.iterdir() if p.is_dir()):
        if not (child / "dataset.toml").is_file():
            continue
        manifest = load_dataset_manifest(child)
        try:
            resolved = resolve(
                manifest.source, repo_root=repo_root, variables=variables, default=manifest.dir
            )
            error = None
        except KeyError as exc:
            resolved, error = None, str(exc).strip('"')
        found.append(DiscoveredDataset(manifest=manifest, resolved_source=resolved, error=error))
    return sorted(found, key=lambda d: d.manifest.name)

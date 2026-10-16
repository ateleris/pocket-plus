"""Parse a per-dataset `dataset.toml` and resolve where its data lives.

A dataset manifest is deliberately minimal (like `adapter.toml`): it carries only what pocketbench
needs before it can locate and interpret the data. The compression parameters of a `reference`
dataset are NOT here; they are read from the vectors' own `*-metadata.json`, so they cannot drift
from the reference output they generated.
"""

from __future__ import annotations

import re
import tomllib
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

_VAR = re.compile(r"\$\{([^}]+)\}")


@dataclass(frozen=True)
class DatasetManifest:
    """Everything pocketbench needs before it can locate and load a dataset's data.

    `kind` selects the loader. `source` is where the data lives: a repo-relative path, a `${var}`
    escape hatch, or None when the data is present in the folder itself. `vector` names a single
    reference vector; when omitted a `reference` manifest is a collection (every vector under
    `source`). `manifest` is the conformance-only sidecar path (file_list.csv).
    """

    name: str
    kind: str
    source: str | None
    vector: str | None
    manifest: str | None
    dir: Path


def load_dataset_manifest(dataset_dir: Path) -> DatasetManifest:
    """Load `dataset_dir/dataset.toml`. `name` defaults to the folder name; `kind` is required."""
    dataset_dir = dataset_dir.resolve()
    with (dataset_dir / "dataset.toml").open("rb") as fh:
        data = tomllib.load(fh)
    kind = data.get("kind")
    if not kind:
        raise ValueError(f"{dataset_dir}/dataset.toml: 'kind' is required")
    return DatasetManifest(
        name=data.get("name", dataset_dir.name),
        kind=kind,
        source=data.get("source"),
        vector=data.get("vector"),
        manifest=data.get("manifest"),
        dir=dataset_dir,
    )


def _expand(raw: str, variables: Mapping[str, str | Path]) -> str:
    def sub(match: re.Match[str]) -> str:
        key = match.group(1)
        if key not in variables:
            raise KeyError(f"unknown settings variable ${{{key}}} in dataset manifest")
        return str(variables[key])

    return _VAR.sub(sub, raw)


def resolve(
    raw: str | None,
    *,
    repo_root: Path,
    variables: Mapping[str, str | Path],
    default: Path,
) -> Path:
    """Resolve a raw manifest path to an absolute location.

    A `${var}` is expanded from `variables`; a resulting relative path is taken relative to
    `repo_root` (the convention case). `None` resolves to `default` (the dataset dir, for data that
    is present in the folder rather than linked).
    """
    if raw is None:
        return default
    expanded = Path(_expand(raw, variables))
    return expanded if expanded.is_absolute() else (repo_root / expanded)

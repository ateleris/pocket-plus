"""Per-`kind` loaders that map a discovered dataset into a uniform descriptor.

Each loader turns an on-disk structure into the object the commands consume, so nothing else in
pocketbench inspects the folder layout:

- ``reference`` -> ``list[config.Dataset]``. Params come from each vector's ``*-metadata.json`` (the
  recipe that produced the reference output), so they cannot drift. Paths are derived by on-disk
  convention from the vector stem, NOT from ``metadata.input.file`` (a generator label that can
  disagree with the real filename, e.g. venus-express).
- ``conformance`` -> ``config.ConformanceSuite``. Binds the linked suite dir with its grading sidecars.
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from pathlib import Path

from pocketbench.config import ConformanceSuite, Dataset
from pocketbench.dataset.discovery import DiscoveredDataset
from pocketbench.dataset.manifest import resolve


def _stems(root: Path) -> list[str]:
    """Vector stems under ``root``: the ``<stem>`` of every ``expected-output/<stem>-metadata.json``."""
    suffix = "-metadata.json"
    return sorted(
        p.name[: -len(suffix)] for p in (root / "expected-output").glob(f"*{suffix}")
    )


def _dataset_for(root: Path, stem: str, name: str) -> Dataset:
    """Build a Dataset for one vector, reading params from its metadata and paths by convention."""
    meta_path = root / "expected-output" / f"{stem}-metadata.json"
    if not meta_path.is_file():
        raise FileNotFoundError(f"no metadata for vector {stem!r}: {meta_path}")
    meta = json.loads(meta_path.read_text())
    comp = meta["compression"]
    params = comp["parameters"]

    inputs = sorted((root / "input").glob(f"{stem}.*"))
    if not inputs:
        raise FileNotFoundError(f"no input file for vector {stem!r} under {root / 'input'}")
    input_path = inputs[0]
    expected = root / "expected-output" / f"{input_path.name}.pkt"

    # The metadata's `packet_bits` (F, in bits) is authoritative; `packet_length` (bytes) is the
    # byte-aligned fallback, F = packet_length * 8.
    packet_bits = comp.get("packet_bits")
    if packet_bits is None:
        packet_bits = comp["packet_length"] * 8

    return Dataset(
        name=name,
        input=input_path,
        expected=expected if expected.is_file() else None,
        packet_bits=packet_bits,
        pt=params["pt"],
        ft=params["ft"],
        rt=params["rt"],
        robustness=params["robustness"],
    )


def load_reference(discovered: DiscoveredDataset) -> list[Dataset]:
    """Load a `reference` dataset: one Dataset for the named vector, or one per vector (collection).

    A single-vector manifest takes the folder name as the dataset name; a collection expands to
    ``<folder>-<stem>`` per vector, preserving the historical ``reference-simple`` style names.
    """
    root = discovered.resolved_source
    if root is None:
        raise RuntimeError(f"{discovered.manifest.name}: unresolved source ({discovered.error})")
    vector = discovered.manifest.vector
    if vector is not None:
        return [_dataset_for(root, vector, discovered.manifest.name)]
    return [
        _dataset_for(root, stem, f"{discovered.manifest.name}-{stem}") for stem in _stems(root)
    ]


def load_conformance(
    discovered: DiscoveredDataset,
    *,
    repo_root: Path,
    variables: Mapping[str, str | Path],
) -> ConformanceSuite:
    """Load a `conformance` dataset: the linked suite dir plus its resolved expected-output manifest."""
    data_dir = discovered.resolved_source
    if data_dir is None:
        raise RuntimeError(f"{discovered.manifest.name}: unresolved source ({discovered.error})")
    manifest = discovered.manifest
    if manifest.manifest is None:
        raise ValueError(f"{manifest.name}: conformance dataset needs a 'manifest' (file_list.csv)")
    manifest_path = resolve(
        manifest.manifest, repo_root=repo_root, variables=variables, default=data_dir
    )
    return ConformanceSuite(name=manifest.name, data_dir=data_dir, manifest=manifest_path)

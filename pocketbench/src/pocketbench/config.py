"""Configuration models and loader for pocketbench.

Parses ``config/config.toml`` into validated :mod:`pydantic` models and resolves every path, so the
rest of the package only ever handles absolute paths.
"""

from __future__ import annotations

import tomllib
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field


class Dataset(BaseModel):
    """A test set: an input file plus the parameters to compress it with."""

    model_config = ConfigDict(frozen=True)

    name: str
    input: Path
    expected: Path | None = None
    packet_bits: int  # F (large_f): the packet field width in bits; byte stride is ceil(F/8)
    pt: int
    ft: int
    rt: int
    robustness: int


class ConformanceSuite(BaseModel):
    """The UAB/CNES conformance suite: a linked data dir plus its expected-output manifest.

    Does not fit the per-packet ``Dataset`` model (it is self-describing, multi-vector, compared
    against ``file_list.csv``), so it is its own descriptor consumed only by the ``conformance``
    command.
    """

    model_config = ConfigDict(frozen=True)

    name: str
    data_dir: Path          # extracted suite: encoder_input/, decoder_input/
    manifest: Path          # file_list.csv (expected size + SHA-256 per vector)


class Settings(BaseModel):
    ccsds124_root: Path
    results_dir: Path
    # Path to the extracted UAB/CNES conformance suite (encoder_input/,
    # decoder_input/, file_list.csv). Optional; only needed by `conformance`.
    conformance_data_dir: Path | None = None


class Config(BaseModel):
    model_config = ConfigDict(arbitrary_types_allowed=True)

    settings: Settings
    datasets: dict[str, Dataset]
    adapters: list = Field(default_factory=list)  # list[DiscoveredAdapter], discovered not parsed
    conformance_suite: ConformanceSuite | None = None    # the UAB/CNES suite, discovered from datasets/

    def select_impls(self, names: list[str] | None) -> list[DiscoveredAdapter]:
        """Return the requested discovered adapters, or all when ``names`` is falsy.

        Raises KeyError naming the unknown impl (an impl not present as an adapters/ folder). This
        is distinct from an impl that is *unavailable* (folder present, codec/entrypoint missing),
        which prepare_impls handles with the strict-vs-skip rule.
        """
        by_name = {d.manifest.name: d for d in self.adapters}
        if not names:
            return list(self.adapters)
        return [self._one(by_name, n, "implementation") for n in names]

    def select_datasets(self, names: list[str] | None) -> list[Dataset]:
        """Return the requested datasets, or all when ``names`` is falsy."""
        if not names:
            return list(self.datasets.values())
        return [self._one(self.datasets, n, "dataset") for n in names]

    @staticmethod
    def _one(mapping: dict, name: str, kind: str):
        try:
            return mapping[name]
        except KeyError:
            raise KeyError(
                f"unknown {kind} {name!r}; available: {', '.join(sorted(mapping))}"
            ) from None


def _resolve(value: str, *, base: Path, ccsds124_root: Path | None) -> Path:
    """Expand ``${ccsds124_root}`` and resolve a path relative to ``base``."""
    if ccsds124_root is not None:
        value = value.replace("${ccsds124_root}", str(ccsds124_root))
    path = Path(value)
    return path if path.is_absolute() else (base / path).resolve()


def load_config(path: str | Path) -> Config:
    """Load and validate the TOML config at ``path``, resolving all paths."""
    config_path = Path(path).resolve()
    base = config_path.parent
    with config_path.open("rb") as fh:
        raw = tomllib.load(fh)

    ccsds124_root = _resolve(
        raw["settings"]["ccsds124_root"], base=base, ccsds124_root=None
    )
    results_dir = _resolve(
        raw["settings"]["results_dir"], base=base, ccsds124_root=ccsds124_root
    )
    conformance_data = raw["settings"].get("conformance_data_dir")
    conformance_data_dir = (
        _resolve(conformance_data, base=base, ccsds124_root=ccsds124_root)
        if conformance_data
        else None
    )

    # Imported lazily: importing the adapter/dataset subpackages at module top would cycle
    # (adapter -> driver -> benchmark -> config). By call time config is fully loaded.
    from pocketbench.adapter.discovery import discover_adapters
    from pocketbench.dataset.discovery import discover_datasets
    from pocketbench.dataset.loaders import load_conformance, load_reference

    repo_root = base.parent
    variables: dict[str, str | Path] = {"ccsds124_root": ccsds124_root}
    if conformance_data_dir is not None:
        variables["conformance_data_dir"] = conformance_data_dir

    # An example adapter (the mock) stays visible to discover_adapters for the unit tests, but
    # must never reach bench/validate/profile/conformance or the "all impls" default.
    adapters = [
        d
        for d in discover_adapters(repo_root / "adapters", repo_root=repo_root, variables=variables)
        if not d.manifest.example
    ]

    # A manifest whose data cannot resolve is skipped, not fatal.
    datasets: dict[str, Dataset] = {}
    conformance_suite: ConformanceSuite | None = None
    for disc in discover_datasets(repo_root / "datasets", repo_root=repo_root, variables=variables):
        if disc.error is not None:
            continue
        if disc.manifest.kind == "reference":
            for ds in load_reference(disc):
                datasets[ds.name] = ds
        elif disc.manifest.kind == "conformance":
            conformance_suite = load_conformance(disc, repo_root=repo_root, variables=variables)

    return Config(
        settings=Settings(
            ccsds124_root=ccsds124_root,
            results_dir=results_dir,
            conformance_data_dir=conformance_data_dir,
        ),
        datasets=datasets,
        adapters=adapters,
        conformance_suite=conformance_suite,
    )

"""Tests for config loading, adapter discovery, and impl/dataset selection."""

from pathlib import Path

import pytest

from pocketbench.config import load_config

_CONFIG_DIR = Path(__file__).resolve().parents[1] / "config"
# config.toml is machine-local and gitignored, so a fresh clone (and the CI unit job) has only the
# committed template. Both have the same shape, and every test needing machine-local data skips.
CONFIG = _CONFIG_DIR / "config.toml"
if not CONFIG.exists():
    CONFIG = _CONFIG_DIR / "config.example.toml"


@pytest.fixture
def cfg():
    return load_config(CONFIG)


def reference_vectors(cfg):
    """The reference datasets, or skip: they come from the external ccsds124 checkout, which is
    absent on a machine (or a CI job) that has not placed it."""
    if "reference-simple" not in cfg.datasets:
        pytest.skip("ccsds124 test-vectors not present")
    return cfg.datasets


def conformance_settings(cfg):
    """The settings, or skip: the licensed UAB/CNES suite is local-only and left unconfigured by
    both the committed template and ci.toml."""
    if cfg.settings.conformance_data_dir is None:
        pytest.skip("conformance_data_dir not configured")
    return cfg.settings


def test_discovers_adapters(cfg):
    names = {d.manifest.name for d in cfg.adapters}
    # The committed real adapters are always discoverable regardless of codec presence.
    assert {"reference-c", "reference-rust", "pocketrust"} <= names
    # The mock is an example adapter, excluded from real runs (bench/validate/...).
    assert "mock" not in names


def test_select_impls_returns_adapters(cfg):
    picked = cfg.select_impls(["reference-rust"])
    assert [d.manifest.name for d in picked] == ["reference-rust"]


def test_select_all_impls_by_default(cfg):
    assert len(cfg.select_impls(None)) == len(cfg.adapters)


def test_unknown_impl_raises(cfg):
    with pytest.raises(KeyError):
        cfg.select_impls(["cobol"])


def test_loads_datasets(cfg):
    datasets = reference_vectors(cfg)
    assert datasets["reference-simple"].robustness == 1
    assert datasets["reference-housekeeping"].rt == 100


def test_discovers_all_reference_vectors(cfg):
    # The reference collection expands to one dataset per shipped vector, params from metadata.
    names = set(reference_vectors(cfg))
    assert {
        "reference-simple", "reference-hiro", "reference-edge-cases",
        "reference-housekeeping", "reference-venus-express",
    } <= names
    assert cfg.datasets["reference-hiro"].robustness == 7  # matches hiro-metadata.json


def test_conformance_suite_discovered(cfg):
    settings = conformance_settings(cfg)
    suite = cfg.conformance_suite
    assert suite is not None
    assert suite.data_dir == settings.conformance_data_dir
    assert suite.manifest.name == "file_list.csv"


def test_select_datasets_subset(cfg):
    reference_vectors(cfg)
    picked = cfg.select_datasets(["reference-simple", "reference-housekeeping"])
    assert [d.name for d in picked] == ["reference-simple", "reference-housekeeping"]


def test_unknown_dataset_raises(cfg):
    with pytest.raises(KeyError):
        cfg.select_datasets(["nope"])


def test_paths_are_resolved_absolute(cfg):
    assert cfg.settings.ccsds124_root.is_absolute()
    simple = reference_vectors(cfg)["reference-simple"]
    assert simple.input.is_absolute()
    assert "${" not in str(simple.input)


def test_conformance_data_dir(cfg):
    assert conformance_settings(cfg).conformance_data_dir.is_absolute()

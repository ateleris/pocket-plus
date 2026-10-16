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
    assert {"reference-c", "reference-cpp", "pocketrust-u32", "pocketrust-u64", "pocketplus"} <= names
    # The mock is an example adapter, excluded from real runs (bench/validate/...).
    assert "mock" not in names


def test_select_impls_returns_adapters(cfg):
    picked = cfg.select_impls(["pocketrust-u64"])
    assert [d.manifest.name for d in picked] == ["pocketrust-u64"]


def test_select_all_impls_by_default(cfg):
    assert len(cfg.select_impls(None)) == len(cfg.adapters)


def test_select_impls_by_glob(cfg):
    picked = cfg.select_impls(["pocketrust-*", "pocketrust-u64"])
    assert [d.manifest.name for d in picked] == ["pocketrust-u32", "pocketrust-u64"]


def test_an_impl_glob_matching_nothing_raises(cfg):
    with pytest.raises(KeyError, match="no implementation matches 'cobol-\\*'"):
        cfg.select_impls(["cobol-*"])


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


def _private_config(tmp_path, local_dir):
    (tmp_path / "config").mkdir(exist_ok=True)
    cfg = tmp_path / "config" / "config.toml"
    cfg.write_text(
        '[settings]\nccsds124_root = "../ccsds"\nresults_dir = "../results"\n'
        f'local_adapters_dir = "{local_dir}"\n'
    )
    return cfg


def _adapter_folder(parent, name):
    d = parent / name
    d.mkdir(parents=True)
    (d / "adapter.toml").write_text(f'name = "{name}"\ninterpreter = "python"\nentrypoint = "adapter.py"\n')


def test_local_adapters_dir_is_discovered_too(tmp_path):
    _adapter_folder(tmp_path / "private", "private-impl")
    cfg = load_config(_private_config(tmp_path, tmp_path / "private"))
    assert [d.manifest.name for d in cfg.adapters] == ["private-impl"]
    assert cfg.settings.local_adapters_dir == tmp_path / "private"


def test_a_missing_local_adapters_dir_adds_nothing(tmp_path):
    _adapter_folder(tmp_path / "adapters", "public-impl")
    cfg = load_config(_private_config(tmp_path, tmp_path / "does-not-exist"))
    assert [d.manifest.name for d in cfg.adapters] == ["public-impl"]


def test_an_adapter_name_defined_twice_is_an_error(tmp_path):
    _adapter_folder(tmp_path / "adapters", "twin")
    _adapter_folder(tmp_path / "private", "twin")
    with pytest.raises(ValueError, match="twin"):
        load_config(_private_config(tmp_path, tmp_path / "private"))


def _hktm_config(tmp_path, data_dir):
    d = tmp_path / "datasets" / "hktm-1week"
    d.mkdir(parents=True)
    (d / "dataset.toml").write_text('kind = "reference"\nsource = "${hktm_1week_dir}"\n')
    (tmp_path / "config").mkdir()
    cfg = tmp_path / "config" / "config.toml"
    line = f'hktm_1week_dir = "{data_dir}"\n' if data_dir else ""
    cfg.write_text(f'[settings]\nccsds124_root = "../ccsds"\nresults_dir = "../results"\n{line}')
    return cfg


def test_hktm_1week_dir_feeds_a_dataset_source(tmp_path):
    data = tmp_path / "hktm"
    (data / "input").mkdir(parents=True)
    (data / "expected-output").mkdir()
    (data / "input" / "1028.ccsds").write_bytes(bytes(180))
    (data / "expected-output" / "1028-metadata.json").write_text(
        '{"compression": {"packet_length": 90, "packet_bits": 720,'
        ' "parameters": {"pt": 10, "ft": 20, "rt": 50, "robustness": 1}}}'
    )
    cfg = load_config(_hktm_config(tmp_path, data))
    assert cfg.settings.hktm_1week_dir == data
    assert cfg.datasets["hktm-1week-1028"].input == data / "input" / "1028.ccsds"


def test_unset_hktm_1week_dir_skips_the_dataset(tmp_path):
    assert load_config(_hktm_config(tmp_path, None)).datasets == {}


def _named(*names):
    from pocketbench.config import Config, Dataset, Settings

    datasets = {
        n: Dataset(name=n, input=Path(n), expected=None, packet_bits=8, pt=0, ft=0, rt=0, robustness=0)
        for n in names
    }
    return Config(settings=Settings(ccsds124_root=Path("."), results_dir=Path(".")), datasets=datasets)


def test_select_datasets_by_glob():
    cfg = _named("reference-simple", "reference-hiro", "hktm-1week-1028", "hktm-1week-2049")
    assert [d.name for d in cfg.select_datasets(["hktm-1week-*"])] == ["hktm-1week-1028", "hktm-1week-2049"]


def test_select_datasets_mixes_globs_and_names_without_duplicates():
    cfg = _named("reference-simple", "reference-hiro", "synthetic-f1")
    picked = cfg.select_datasets(["synthetic-f1", "reference-*", "reference-hiro"])
    assert [d.name for d in picked] == ["synthetic-f1", "reference-simple", "reference-hiro"]


def test_a_glob_matching_nothing_raises():
    with pytest.raises(KeyError, match="no dataset matches 'refrence-\\*'"):
        _named("reference-simple").select_datasets(["refrence-*"])


def _skipping_config(tmp_path):
    _adapter_folder(tmp_path / "private", "decoder-only")
    toml = tmp_path / "private" / "decoder-only" / "adapter.toml"
    toml.write_text(toml.read_text() + 'skip_commands = ["bench"]\n')
    _adapter_folder(tmp_path / "adapters", "full")
    return load_config(_private_config(tmp_path, tmp_path / "private"))


def test_skip_commands_drops_an_adapter_from_that_commands_default_and_globs(tmp_path):
    cfg = _skipping_config(tmp_path)
    names = lambda picked: [d.manifest.name for d in picked]  # noqa: E731
    assert names(cfg.select_impls(None, command="bench")) == ["full"]
    assert names(cfg.select_impls(["*"], command="bench")) == ["full"]
    assert names(cfg.select_impls(None, command="conformance")) == ["full", "decoder-only"]
    assert names(cfg.select_impls(None)) == ["full", "decoder-only"]


def test_naming_a_skipped_adapter_exactly_still_selects_it(tmp_path):
    cfg = _skipping_config(tmp_path)
    picked = cfg.select_impls(["decoder-only"], command="bench")
    assert [d.manifest.name for d in picked] == ["decoder-only"]


def test_a_hand_built_dataset_is_its_own_group():
    from pocketbench.config import Dataset

    ds = Dataset(name="x", input=Path("x"), packet_bits=8, pt=1, ft=1, rt=1, robustness=0)
    assert ds.group == "x"


def test_loaded_datasets_are_grouped_by_their_folder(tmp_path):
    data = tmp_path / "hktm"
    (data / "input").mkdir(parents=True)
    (data / "expected-output").mkdir()
    for stem in ("1028", "2049"):
        (data / "input" / f"{stem}.ccsds").write_bytes(bytes(180))
        (data / "expected-output" / f"{stem}-metadata.json").write_text(
            '{"compression": {"packet_length": 90, "packet_bits": 720,'
            ' "parameters": {"pt": 10, "ft": 20, "rt": 50, "robustness": 1}}}'
        )
    cfg = load_config(_hktm_config(tmp_path, data))
    assert {ds.group for ds in cfg.datasets.values()} == {"hktm-1week"}

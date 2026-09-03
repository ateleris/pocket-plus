"""Dataset manifest parsing and source resolution."""

from pathlib import Path

import pytest

from pocketbench.dataset.manifest import (
    DatasetManifest,
    load_dataset_manifest,
    resolve,
)


def _write(dataset_dir: Path, body: str) -> Path:
    dataset_dir.mkdir(parents=True, exist_ok=True)
    (dataset_dir / "dataset.toml").write_text(body)
    return dataset_dir


def test_load_reference_collection(tmp_path):
    d = _write(
        tmp_path / "reference",
        'kind = "reference"\n'
        'source = "${ccsds124_root}/test-vectors"\n',
    )
    m = load_dataset_manifest(d)
    assert m.name == "reference"
    assert m.kind == "reference"
    assert m.source == "${ccsds124_root}/test-vectors"
    assert m.vector is None  # no vector => collection
    assert m.dir == d.resolve()


def test_load_reference_single_vector(tmp_path):
    d = _write(
        tmp_path / "just-simple",
        'kind = "reference"\n'
        'source = "${ccsds124_root}/test-vectors"\n'
        'vector = "simple"\n',
    )
    m = load_dataset_manifest(d)
    assert m.vector == "simple"


def test_load_conformance(tmp_path):
    d = _write(
        tmp_path / "uab-crossval",
        'kind = "conformance"\n'
        'source = "${conformance_data_dir}"\n'
        'manifest = "${ccsds124_root}/crossvalidation/file_list.csv"\n',
    )
    m = load_dataset_manifest(d)
    assert m.kind == "conformance"
    assert m.source == "${conformance_data_dir}"
    assert m.manifest == "${ccsds124_root}/crossvalidation/file_list.csv"


def test_load_missing_kind_raises(tmp_path):
    d = _write(tmp_path / "bad", 'source = "x"\n')
    with pytest.raises(ValueError):
        load_dataset_manifest(d)


def test_name_defaults_to_folder(tmp_path):
    d = _write(tmp_path / "my-set", 'kind = "reference"\n')
    m = load_dataset_manifest(d)
    assert m.name == "my-set"
    assert m.source is None  # data present in the folder


def test_resolve_none_source_is_dataset_dir(tmp_path):
    m = DatasetManifest("my-set", "reference", None, None, None, tmp_path)
    assert resolve(m.source, repo_root=tmp_path, variables={}, default=m.dir) == tmp_path


def test_resolve_relative_is_repo_relative(tmp_path):
    resolved = resolve("data/foo", repo_root=tmp_path, variables={}, default=tmp_path)
    assert resolved == (tmp_path / "data" / "foo")


def test_resolve_expands_var(tmp_path):
    resolved = resolve(
        "${ccsds124_root}/test-vectors",
        repo_root=tmp_path,
        variables={"ccsds124_root": "/opt/ccsds124"},
        default=tmp_path,
    )
    assert resolved == Path("/opt/ccsds124/test-vectors")


def test_resolve_unknown_var_raises(tmp_path):
    with pytest.raises(KeyError):
        resolve("${missing}/x", repo_root=tmp_path, variables={}, default=tmp_path)

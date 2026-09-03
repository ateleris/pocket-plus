"""Dataset discovery: scan datasets/*/dataset.toml and resolve their data location."""

from pathlib import Path

from pocketbench.dataset.discovery import discover_datasets


def _dataset(root: Path, name: str, body: str) -> Path:
    d = root / name
    d.mkdir(parents=True, exist_ok=True)
    (d / "dataset.toml").write_text(body)
    return d


def test_discovers_each_manifest_sorted(tmp_path):
    datasets = tmp_path / "datasets"
    _dataset(datasets, "b-set", 'kind = "reference"\nsource = "vectors/b"\n')
    _dataset(datasets, "a-set", 'kind = "reference"\nsource = "vectors/a"\n')
    (datasets / "not-a-dataset").mkdir()  # no dataset.toml -> skipped

    found = discover_datasets(datasets, repo_root=tmp_path, variables={})
    assert [d.manifest.name for d in found] == ["a-set", "b-set"]


def test_resolves_source_relative_to_repo(tmp_path):
    datasets = tmp_path / "datasets"
    _dataset(datasets, "x", 'kind = "reference"\nsource = "vectors/x"\n')
    (found,) = discover_datasets(datasets, repo_root=tmp_path, variables={})
    assert found.resolved_source == (tmp_path / "vectors" / "x")
    assert found.error is None


def test_resolves_var(tmp_path):
    datasets = tmp_path / "datasets"
    _dataset(datasets, "x", 'kind = "reference"\nsource = "${ccsds124_root}/test-vectors"\n')
    (found,) = discover_datasets(
        datasets, repo_root=tmp_path, variables={"ccsds124_root": "/opt/c"}
    )
    assert found.resolved_source == Path("/opt/c/test-vectors")


def test_no_source_resolves_to_folder(tmp_path):
    datasets = tmp_path / "datasets"
    d = _dataset(datasets, "present", 'kind = "reference"\n')
    (found,) = discover_datasets(datasets, repo_root=tmp_path, variables={})
    assert found.resolved_source == d.resolve()


def test_unknown_var_captured_as_error_not_raised(tmp_path):
    datasets = tmp_path / "datasets"
    _dataset(datasets, "x", 'kind = "conformance"\nsource = "${missing}"\n')
    (found,) = discover_datasets(datasets, repo_root=tmp_path, variables={})
    assert found.error is not None
    assert found.resolved_source is None


def test_missing_root_returns_empty(tmp_path):
    assert discover_datasets(tmp_path / "nope", repo_root=tmp_path, variables={}) == []

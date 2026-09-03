"""Manifest parsing and source resolution."""

from pathlib import Path

import pytest

from pocketbench.adapter.manifest import Manifest, load_manifest, resolve_source


def _write(adapter_dir: Path, body: str) -> Path:
    adapter_dir.mkdir(parents=True, exist_ok=True)
    (adapter_dir / "adapter.toml").write_text(body)
    return adapter_dir


def test_load_manifest_full(tmp_path):
    d = _write(
        tmp_path / "reference-c",
        'name = "reference-c"\n'
        'language = "C"\n'
        'source = "implementations/ccsds124/implementations/c"\n'
        'build = "make SOURCE={source} MAX_PACKET_BITS={max_packet_bits}"\n'
        'entrypoint = "build/adapter"\n'
        'version_cmd = "gcc --version"\n',
    )
    m = load_manifest(d)
    assert m.name == "reference-c"
    assert m.language == "C"
    assert m.source == "implementations/ccsds124/implementations/c"
    assert m.build == "make SOURCE={source} MAX_PACKET_BITS={max_packet_bits}"
    assert m.entrypoint == "build/adapter"
    assert m.interpreter is None
    assert m.version_cmd == "gcc --version"
    assert m.dir == d.resolve()


def test_load_manifest_defaults_name_to_folder_and_optional_none(tmp_path):
    d = _write(tmp_path / "mock", 'interpreter = "python"\nentrypoint = "adapter.py"\n')
    m = load_manifest(d)
    assert m.name == "mock"
    assert m.language is None
    assert m.source is None
    assert m.build is None
    assert m.interpreter == "python"


def test_load_manifest_missing_entrypoint_raises(tmp_path):
    d = _write(tmp_path / "bad", 'name = "bad"\n')
    with pytest.raises(ValueError):
        load_manifest(d)


def test_resolve_source_none_for_self_contained(tmp_path):
    m = Manifest("mock", None, None, None, "adapter.py", "python", None, tmp_path)
    assert resolve_source(m, repo_root=tmp_path, variables={}) is None


def test_resolve_source_relative_is_repo_relative(tmp_path):
    m = Manifest("c", "C", "implementations/x/c", None, "build/adapter", None, None, tmp_path)
    resolved = resolve_source(m, repo_root=tmp_path, variables={})
    assert resolved == (tmp_path / "implementations" / "x" / "c")


def test_resolve_source_expands_var(tmp_path):
    m = Manifest("c", "C", "${ccsds124_root}/implementations/c", None, "build/adapter", None, None, tmp_path)
    resolved = resolve_source(
        m, repo_root=tmp_path, variables={"ccsds124_root": "/opt/ccsds124"}
    )
    assert resolved == Path("/opt/ccsds124/implementations/c")


def test_resolve_source_unknown_var_raises(tmp_path):
    m = Manifest("c", "C", "${missing}/c", None, "build/adapter", None, None, tmp_path)
    with pytest.raises(KeyError):
        resolve_source(m, repo_root=tmp_path, variables={})

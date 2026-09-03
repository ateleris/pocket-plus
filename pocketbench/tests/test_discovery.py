"""Adapter discovery and the two presence gates."""

from pathlib import Path

from pocketbench.adapter.discovery import (
    discover_adapters,
    entrypoint_present,
    source_present,
)


def _adapter(root: Path, name: str, toml_body: str) -> Path:
    d = root / name
    d.mkdir(parents=True, exist_ok=True)
    (d / "adapter.toml").write_text(toml_body)
    return d


def test_discovers_each_manifest_sorted(tmp_path):
    adapters = tmp_path / "adapters"
    _adapter(adapters, "b-impl", 'source = "implementations/b"\nentrypoint = "build/adapter"\n')
    _adapter(adapters, "a-impl", 'interpreter = "python"\nentrypoint = "adapter.py"\n')
    (adapters / "not-an-adapter").mkdir()  # no adapter.toml -> skipped

    found = discover_adapters(adapters, repo_root=tmp_path, variables={})
    assert [d.manifest.name for d in found] == ["a-impl", "b-impl"]


def test_gate1_self_contained_always_present(tmp_path):
    adapters = tmp_path / "adapters"
    _adapter(adapters, "mock", 'interpreter = "python"\nentrypoint = "adapter.py"\n')
    (found,) = discover_adapters(adapters, repo_root=tmp_path, variables={})
    assert source_present(found) is True


def test_gate1_missing_source_absent(tmp_path):
    adapters = tmp_path / "adapters"
    _adapter(adapters, "c", 'source = "implementations/nope"\nentrypoint = "build/adapter"\n')
    (found,) = discover_adapters(adapters, repo_root=tmp_path, variables={})
    assert source_present(found) is False


def test_gate1_present_source_nonempty(tmp_path):
    adapters = tmp_path / "adapters"
    _adapter(adapters, "c", 'source = "implementations/x"\nentrypoint = "build/adapter"\n')
    src = tmp_path / "implementations" / "x"
    src.mkdir(parents=True)
    (src / "codec.c").write_text("int main(){}")
    (found,) = discover_adapters(adapters, repo_root=tmp_path, variables={})
    assert source_present(found) is True


def test_gate1_empty_source_dir_absent(tmp_path):
    adapters = tmp_path / "adapters"
    _adapter(adapters, "c", 'source = "implementations/x"\nentrypoint = "build/adapter"\n')
    (tmp_path / "implementations" / "x").mkdir(parents=True)  # exists but empty
    (found,) = discover_adapters(adapters, repo_root=tmp_path, variables={})
    assert source_present(found) is False


def test_unknown_var_captured_as_error_not_raised(tmp_path):
    adapters = tmp_path / "adapters"
    _adapter(adapters, "c", 'source = "${missing}/c"\nentrypoint = "build/adapter"\n')
    (found,) = discover_adapters(adapters, repo_root=tmp_path, variables={})
    assert found.error is not None
    assert source_present(found) is False


def test_gate2_entrypoint_presence(tmp_path):
    adapters = tmp_path / "adapters"
    d = _adapter(adapters, "c", 'source = "implementations/x"\nentrypoint = "build/adapter"\n')
    (found,) = discover_adapters(adapters, repo_root=tmp_path, variables={})
    assert entrypoint_present(found) is False
    (d / "build").mkdir()
    (d / "build" / "adapter").write_text("#!/bin/sh\n")
    assert entrypoint_present(found) is True

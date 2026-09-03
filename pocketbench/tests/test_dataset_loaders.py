"""Loaders that map a discovered dataset into a uniform descriptor.

reference -> list[config.Dataset] (params read from each vector's *-metadata.json).
conformance  -> config.ConformanceSuite (binds suite dir + file_list.csv).
"""

import json
from pathlib import Path

import pytest

from pocketbench.dataset.discovery import DiscoveredDataset
from pocketbench.dataset.loaders import load_conformance, load_reference
from pocketbench.dataset.manifest import DatasetManifest


def _vector(root: Path, stem: str, input_name: str, params: dict, meta_input_file: str) -> None:
    """Write a fake ccsds124-style vector: input, expected .pkt, and *-metadata.json."""
    (root / "input").mkdir(parents=True, exist_ok=True)
    (root / "expected-output").mkdir(parents=True, exist_ok=True)
    (root / "input" / input_name).write_bytes(b"\x00" * 90)
    (root / "expected-output" / f"{input_name}.pkt").write_bytes(b"\x01\x02")
    (root / "expected-output" / f"{stem}-metadata.json").write_text(
        json.dumps(
            {
                "name": stem,
                "input": {"file": meta_input_file},
                "compression": {"packet_length": params["packet_length"],
                                 "parameters": {k: v for k, v in params.items()
                                                if k != "packet_length"}},
            }
        )
    )


def _discovered(name: str, source: Path, *, vector=None, manifest=None):
    m = DatasetManifest(
        name=name, kind="reference" if manifest is None else "conformance",
        source=str(source), vector=vector, manifest=manifest,
        dir=source,
    )
    return DiscoveredDataset(manifest=m, resolved_source=source, error=None)


def test_reference_collection_yields_dataset_per_vector(tmp_path):
    root = tmp_path / "test-vectors"
    _vector(root, "simple", "simple.bin",
            {"packet_length": 90, "pt": 10, "ft": 20, "rt": 50, "robustness": 1}, "simple.bin")
    _vector(root, "venus-express", "venus-express.ccsds",
            {"packet_length": 90, "pt": 20, "ft": 50, "rt": 100, "robustness": 2},
            meta_input_file="1028packets.ccsds")  # deliberately wrong on-disk name

    got = {d.name: d for d in load_reference(_discovered("reference", root))}
    assert set(got) == {"reference-simple", "reference-venus-express"}

    simple = got["reference-simple"]
    assert (simple.packet_bits, simple.pt, simple.ft, simple.rt, simple.robustness) == (720, 10, 20, 50, 1)
    assert simple.input == (root / "input" / "simple.bin")
    assert simple.expected == (root / "expected-output" / "simple.bin.pkt")

    # Extension varies (.ccsds) and metadata.input.file is a generator label, not the on-disk name.
    venus = got["reference-venus-express"]
    assert venus.input == (root / "input" / "venus-express.ccsds")
    assert venus.robustness == 2


def test_reference_single_vector_uses_folder_name(tmp_path):
    root = tmp_path / "test-vectors"
    _vector(root, "simple", "simple.bin",
            {"packet_length": 90, "pt": 10, "ft": 20, "rt": 50, "robustness": 1}, "simple.bin")
    _vector(root, "hiro", "hiro.bin",
            {"packet_length": 90, "pt": 10, "ft": 20, "rt": 50, "robustness": 7}, "hiro.bin")

    got = load_reference(_discovered("only-hiro", root, vector="hiro"))
    assert [d.name for d in got] == ["only-hiro"]
    assert got[0].robustness == 7


def test_reference_packet_bits_metadata_is_authoritative(tmp_path):
    """When metadata states `packet_bits` (F in bits), the loader uses it verbatim rather than
    deriving from `packet_length * 8`; this is how a sub-byte vector (e.g. F=1) is expressed."""
    root = tmp_path / "test-vectors"
    (root / "input").mkdir(parents=True)
    (root / "expected-output").mkdir(parents=True)
    (root / "input" / "f1.bin").write_bytes(b"\x00" * 8)
    (root / "expected-output" / "f1.bin.pkt").write_bytes(b"\x01")
    (root / "expected-output" / "f1-metadata.json").write_text(
        json.dumps(
            {
                "name": "f1",
                "input": {"file": "f1.bin"},
                "compression": {
                    "packet_length": 1,   # byte stride ceil(F/8) = 1
                    "packet_bits": 1,     # F: authoritative, NOT packet_length * 8 = 8
                    "parameters": {"pt": 8, "ft": 16, "rt": 32, "robustness": 1},
                },
            }
        )
    )
    got = load_reference(_discovered("synthetic-f1", root, vector="f1"))
    assert got[0].packet_bits == 1  # F=1, not 8


def test_reference_unknown_vector_raises(tmp_path):
    root = tmp_path / "test-vectors"
    _vector(root, "simple", "simple.bin",
            {"packet_length": 90, "pt": 10, "ft": 20, "rt": 50, "robustness": 1}, "simple.bin")
    with pytest.raises(FileNotFoundError):
        load_reference(_discovered("x", root, vector="nope"))


def test_conformance_binds_suite_dir_and_manifest(tmp_path):
    suite = tmp_path / "suite"
    suite.mkdir()
    d = _discovered("uab-crossval", suite, manifest="${ccsds124_root}/cv/file_list.csv")
    got = load_conformance(d, repo_root=tmp_path, variables={"ccsds124_root": "/opt/c"})
    assert got.name == "uab-crossval"
    assert got.data_dir == suite
    assert got.manifest == Path("/opt/c/cv/file_list.csv")

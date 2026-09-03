"""The pocketbench adapter-CLI seam: manifests, discovery, and the contract driver.

Depends on no other pocketbench module except benchmark.derive.
"""

from __future__ import annotations

from pocketbench.adapter.driver import (
    AdapterRunResult,
    BenchPayload,
    Capabilities,
    Params,
    bench,
    build,
    capabilities,
    compress,
    conformance_compress,
    conformance_decompress,
    decompress,
    conformance_argv,
    launch_argv,
    oneshot_argv,
    to_metrics,
    version,
)

__all__ = [
    "AdapterRunResult",
    "BenchPayload",
    "Capabilities",
    "Params",
    "bench",
    "build",
    "capabilities",
    "compress",
    "conformance_compress",
    "conformance_decompress",
    "decompress",
    "conformance_argv",
    "launch_argv",
    "oneshot_argv",
    "to_metrics",
    "version",
]

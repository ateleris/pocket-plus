"""The pocketbench adapter-CLI seam: manifests, discovery, and the contract driver.

Depends on no other pocketbench module except records and benchmark.derive.
"""

from __future__ import annotations

from pocketbench.adapter.driver import (
    AdapterRunResult,
    BenchPayload,
    Capabilities,
    VerbRun,
    bench,
    build,
    capabilities,
    launch_argv,
    run_verb,
    to_metrics,
    verb_argv,
    version,
)

__all__ = [
    "AdapterRunResult",
    "BenchPayload",
    "Capabilities",
    "VerbRun",
    "bench",
    "build",
    "capabilities",
    "launch_argv",
    "run_verb",
    "to_metrics",
    "verb_argv",
    "version",
]

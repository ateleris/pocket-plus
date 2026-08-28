"""Stainless formal-verification gate, as a pytest test.

Marked `verify` and excluded from ordinary builds (which run `-m "not verify"`) because it is slow
(runs Stainless over all VCs). Run it explicitly from Test Explorer, or:  pytest -m verify
Override the per-VC timeout with the POCKETPLUS_VERIFY_TIMEOUT env var (default 5s).
"""
import os

import pytest

from pocketplus import verify


@pytest.mark.verify
def test_stainless_all_vcs_valid():
    timeout = int(os.environ.get("POCKETPLUS_VERIFY_TIMEOUT", "5"))
    r = verify.run(timeout=timeout)
    assert r["total"] > 0, "Stainless produced no VCs (extraction error?):\n" + r["output"][-2000:]
    assert r["invalid"] == 0 and r["unknown"] == 0, (
        f"Stainless gate failed at {timeout}s/VC: {r['invalid']} invalid + {r['unknown']} unknown "
        f"of {r['total']} VCs. Unproven:\n{r['unproven']}"
    )

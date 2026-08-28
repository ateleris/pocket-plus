"""Stainless formal-verification gate, as a pytest test.

Marked `verify` and excluded from ordinary builds (which run `-m "not verify"`) because it is slow
(runs Stainless over all VCs). Run it explicitly from Test Explorer, or:  pytest -m verify
Timeout and solvers come from scala/stainless.conf.
"""
import pytest

from pocketplus import verify


@pytest.mark.verify
def test_stainless_all_vcs_valid():
    r = verify.run()
    assert r["total"] > 0, "Stainless produced no VCs (extraction error?):\n" + r["output"][-2000:]
    assert r["invalid"] == 0 and r["unknown"] == 0, (
        f"Stainless gate failed: {r['invalid']} invalid + {r['unknown']} unknown "
        f"of {r['total']} VCs. Unproven:\n{r['unproven']}"
    )

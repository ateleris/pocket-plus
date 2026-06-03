# pocket-plus

POCKET+ / CCSDS 124.0-B-1 lossless housekeeping-telemetry codec, formally specified in Scala for
[Stainless](https://epfl-lara.github.io/stainless/), transpiled to C (Stainless **GenC**), wrapped
in a small C ABI, and exercised from Python.

```
scala/PocketPlus.scala   the single source of truth (Stainless-verifiable, GenC-compatible)
native/                  C project: GenC output + hand-written DLL shim (pp_* API)  → pocketplus.dll
python/                  Python project: ctypes interop + pytest round-trip suite
tools/stainless/         vendored Stainless toolchain (jar + z3 + cvc5)
build/                   MSBuild glue (GenC + Verify) — no shell scripts
data/                    private test vectors (git-ignored; see data/README.md)
```

## Prerequisites

- **JDK** with `java` on `PATH` and `JAVA_HOME` set (only used to run the vendored Stainless).
- **Python 3** on `PATH` (the test dependencies live in a VS-managed virtual environment).
- **Visual Studio** with the **"C++ Clang tools for Windows"** component (provides the `ClangCL`
  platform toolset — required because the GenC output uses C99 variable-length arrays that MSVC's
  `cl` rejects) and the **Python development** workload.

Nothing else: the Stainless compiler and the z3/cvc5 solvers are vendored under `tools/stainless/`.

## Build / test (Visual Studio)

Open `pocket-plus.slnx`. The first time, accept VS's prompt to create the virtual environment from
`python/requirements.txt` (or it is created automatically on first build).

**Build Solution** in **Debug** or **Release**:
  1. regenerates `native/generated/pocketplus.{c,h}` from `scala/PocketPlus.scala` (only when the
     Scala changed — incremental),
  2. compiles `pocketplus.dll` with ClangCL,
  3. runs the Python `pytest` interop suite, excluding the slow `verify` test (a failing test fails
     the build).

**Run the tests** from **Test Explorer** (Test → Test Explorer) — build once so `pocketplus.dll`
exists, then Run/Debug individual tests.

**Run formal verification** as the `verify`-marked test: in Test Explorer run
`test_stainless_all_vcs_valid` (under `python/tests/test_verify.py`), or from the CLI
`pytest -m verify`. It runs Stainless over all VCs and **fails if any VC is invalid or unknown**
(a strict gate). It is excluded from ordinary builds because it is slow; results are cached under
`build/.stainless-cache`, so re-runs only re-check changed VCs. Override the per-VC timeout with the
`POCKETPLUS_VERIFY_TIMEOUT` env var (default 5s).

## Build / test (command line)

From a *Developer Command Prompt for VS* (so `msbuild`/ClangCL are on `PATH`):

```
msbuild pocket-plus.slnx /t:Build /p:Configuration=Debug   # GenC → DLL → pytest (no verify)
cd python && env\Scripts\python -m pytest tests -m verify  # Stainless verification gate
```

## Notes

- The codec API the DLL exports (`pp_compressor_create`, `pp_compress`, `pp_decompressor_create`,
  `pp_decompress`, …) is hand-written in `native/src/pp_shim.c`; it owns the buffers so Python only
  passes flat `(bool*, length)` arrays. The GenC output in `native/generated/` is never hand-edited.
- The verification gate is **red by design today**: the Scala carries no verification invariants yet,
  so the array-bounds / overflow / termination VCs are unproven. It turns green as invariants are added.
- To regenerate or verify outside VS, the same `java -jar tools/stainless/lib/...jar` invocations
  used by `build/Stainless.targets` and `python/pocketplus/verify.py` can be run directly
  (`python -m pocketplus.verify`).

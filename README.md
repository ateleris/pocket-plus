# pocket-plus

POCKET+ / CCSDS 124.0-B-1 lossless housekeeping-telemetry codec, formally specified in Scala for
[Stainless](https://epfl-lara.github.io/stainless/), transpiled to C (Stainless **GenC**), wrapped
in a small C ABI, and exercised from Python.

```
scala/PocketPlus.scala   the single source of truth (Stainless-verifiable, GenC-compatible)
native/                  C project: GenC output + hand-written shared-library shim (pp_* API)
python/                  Python project: ctypes interop + pytest round-trip suite
tools/stainless/         Stainless toolchain (jar + z3 + cvc5) — downloaded by install.sh, git-ignored
build/                   MSBuild glue (GenC + Verify) — no shell scripts
data/                    private test vectors (git-ignored; see data/README.md)
```

## Prerequisites

- **Dev container** (alternative to everything below): open the repo in VS Code and *Reopen in
  Container* — `.devcontainer/` provides JDK 25, the Scala toolchain (Coursier: `scala`, `scalac`,
  `sbt`), clang, Python, and Claude Code, and downloads `tools/stainless` + creates the Python venv
  on first start.
- **Stainless toolchain**: run `./install.sh (linux|mac-arm64|mac-x86|win)` once — it downloads the
  Stainless compiler and the z3/cvc5 solvers from GitHub releases into `tools/stainless/`.
- **JDK** with `java` on `PATH` (only used to run Stainless).
- **Python 3** on `PATH` (the test dependencies live in a virtual environment, see below).
- **Windows only:** **Visual Studio** with the **"C++ Clang tools for Windows"** component (provides
  the `ClangCL` platform toolset — required because the GenC output uses C99 variable-length arrays
  that MSVC's `cl` rejects) and the **Python development** workload.
- **macOS/Linux only:** a C compiler (`cc`, e.g. Xcode command-line tools or gcc/clang).

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

## Build / test (command line, Windows)

From a *Developer Command Prompt for VS* (so `msbuild`/ClangCL are on `PATH`):

```
msbuild pocket-plus.slnx /t:Build /p:Configuration=Debug   # GenC → DLL → pytest (no verify)
cd python && env\Scripts\python -m pytest tests -m verify  # Stainless verification gate
```

Outside a Developer prompt (e.g. on CI), use the PowerShell scripts instead — `build.ps1` locates
MSBuild via `vswhere` and builds only the native project (no VS Python workload needed):

```
powershell -File install.ps1               # once, downloads tools/stainless (win build)
powershell -File native\build.ps1          # GenC → native\x64\Release\pocketplus.dll
python -m venv python\env && python\env\Scripts\pip install -r python\requirements.txt
cd python && env\Scripts\python -m pytest -m "not verify"
```

## Build / test (macOS / Linux)

```
./install.sh mac-x86            # or mac-arm64 / linux — once, downloads tools/stainless
./native/build.sh               # GenC (incremental) → native/build/libpocketplus.{dylib,so}
python3 -m venv python/env && python/env/bin/pip install -r python/requirements.txt   # once
cd python
env/bin/python -m pytest -m "not verify"   # interop suite
env/bin/python -m pytest -m verify         # Stainless verification gate (slow)
```

## Notes

- The codec API the DLL exports (`pp_compressor_create`, `pp_compress`, `pp_decompressor_create`,
  `pp_decompress`, …) is hand-written in `native/src/pp_shim.c`; it owns the buffers so Python only
  passes flat `(bool*, length)` arrays. The GenC output in `native/generated/` is never hand-edited.
- The C is built as **C99** (`-std=c99 -pedantic` / `/clang:-std=c99`) with warnings as errors:
  `-Wall -Wextra -Wshadow -Wstrict-prototypes -Wmissing-prototypes -Wpointer-arith -Wconversion
  -Werror`. The flag set is defined twice — `std`/`warn` in `native/build.sh` and `PPClangOptions`
  in `native/pocketplus.vcxproj` — and the two must be kept in sync. C99 rather than C11 because
  the GenC output relies on variable-length arrays, which C11 demotes to optional. Two warnings are
  waived for `native/generated/pocketplus.c` only (it is machine-written): `-Wno-unused-variable`
  and `-Wno-parentheses`. Every platform builds with the gate on, and CI adds a second Linux pass
  with `CC=clang` — gcc and clang warn about disjoint things.
- **32-bit builds.** The flight target is 32-bit; `build.sh` honours `CC`, so
  `CC="cc -m32" ./native/build.sh` produces an i386 library (the dev container ships
  `gcc-multilib`/`libc6-dev-i386` on x86 hosts for this; CI does the same). It is a *compile* gate
  only: a 32-bit `.so` cannot be loaded by the 64-bit CPython the `pytest` suite runs under, and
  Ubuntu no longer packages an i386 CPython, so running the suite against a 32-bit build needs an
  i386 container. The codec itself is word-size agnostic — every value in the generated code and
  the shim is `int32_t`/`bool`, and the ctypes binding passes only opaque handles and flat
  `(bool*, int32_t)` pairs, never a struct layout.
- The verification gate is **red by design today**: the Scala carries no verification invariants yet,
  so the array-bounds / overflow / termination VCs are unproven. It turns green as invariants are added.
- To regenerate or verify outside VS, the same `java -jar tools/stainless/lib/...jar` invocations
  used by `build/Stainless.targets` and `python/pocketplus/verify.py` can be run directly
  (`python -m pocketplus.verify`).

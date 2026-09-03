# Implementation interface (the adapter contract)

This document defines what you provide to drive an implementation with `pocketbench`.
`pocketbench` is a harness, not a codec: each implementation is fronted by a small **adapter**, a
wrapper executable that speaks one fixed contract over argv + stdout. pocketbench core owns dataset
staging, warmup/iteration counts, all metric derivation, aggregation, reporting, and conformance
grading; the adapter owns mapping the uniform parameter set to the codec's real API and running the
codec.

Everything between those two (subcommand dispatch, flag parsing, file IO, output buffer sizing,
`packets_per_iter`, the warmup+timed loop, the JSON payloads) is **shared plumbing**, held once per
language under `adapters/common/`. An adapter written against it supplies only codec calls plus its
declarative capability facts, so a change to this contract is edited in the shared plumbing rather
than in every adapter. See "The shared plumbing" below.

You add an implementation by dropping a folder under `adapters/<name>/` with an `adapter.toml`
manifest and a built (or interpreted) entrypoint. No framework code changes are required. The
central `[implementations.*]` config model no longer exists.

## One folder

```
pocketbench/
  adapters/            # committed; one folder per impl. The only place a codec is named.
    common/            # NOT an adapter (no adapter.toml, so discovery skips it): shared plumbing
      c/               #   adapter_common.{h,c}   - used by reference-c and reference-cpp
      rust/            #   pocketbench-adapter/   - used by reference-rust and pocketrust
    mock/              # self-contained identity codec: the worked example for an interpreted impl
      adapter.toml
      adapter.py
    reference-c/
      adapter.toml
      Makefile
      src/adapter.c
    reference-rust/    # Cargo wrapper depending on the codec crate
    pocketrust/
```

Only the adapters are committed, and the codecs live entirely outside this repo so that no second
git repository is ever nested inside it: the ccsds124 checkout, reached via `${ccsds124_root}`
(`reference-c`, `reference-cpp`, `reference-rust`, and the `reference-*` datasets), and
`pocketrust`, the sibling folder `../pocketrust` of the enclosing pocket-plus repo. An adapter's
`source` is the single place that says where its codec is.

## The manifest: `adapters/<name>/adapter.toml`

The manifest carries only what pocketbench needs *before* it can build and run the wrapper.
Everything descriptive is self-reported at run time via `capabilities`, so it cannot drift.

```toml
name        = "reference-c"                              # optional; defaults to the folder name
language    = "C"                                        # display label for reports
source      = "${ccsds124_root}/implementations/c"        # codec location; omit for self-contained
build       = "make SOURCE={source} MAX_PACKET_BITS={max_packet_bits}"   # omit if nothing to build
entrypoint  = "build/adapter"                            # contract executable, relative to the adapter dir
interpreter = "python"                                   # optional: run `python <entrypoint>` (e.g. the mock)
version_cmd = "gcc --version"                            # optional: toolchain string for reports
```

- **`source`** resolves two ways: a `${var}` expanded from `[settings]` in `config.toml` (e.g.
  `source = "${ccsds124_root}/implementations/c"`), which is how every codec kept outside this repo
  is reached and the only form that lets CI and a workstation disagree about the location while the
  adapter stays identical; or a plain repo-relative path (e.g. `../pocketrust` for a sibling of the
  enclosing repo) when the location is fixed on every clone. Omit `source`
  entirely for a self-contained adapter (the mock).
- **`build`** is a shell command run in the adapter dir, with two substitutions: `{source}` (the
  resolved codec path) and `{max_packet_bits}` (see the C build wrinkle below). Omit it if there is
  nothing to build.
- **`entrypoint`** is the contract executable relative to the adapter dir. With `interpreter` set,
  pocketbench runs `<interpreter> <entrypoint> <subcommand> ...` (useful when the exec bit does not
  stick, e.g. on the `/mnt/c` NTFS filesystem).

## The contract

The entrypoint responds to six subcommands. Everything after the subcommand is a **keyed
`--key=value` flag**, in any order. `packet-bits` is **F (large_f), the packet field width in bits**
(CCSDS 124.0-B-1 allows `1..=65535`); each packet occupies a byte-padded stride of `ceil(F/8)` bytes
in the input/output files (meaningful bits high, padding bits low), so the adapter derives the byte
stride from F. For a byte-aligned dataset `F = packet_length_bytes * 8` and the stride is `F/8`.
pocketbench always passes explicit absolute input and output paths. Exit non-zero on failure. An
impl whose codec API is byte-aligned only (e.g. reference-rust's public whole-buffer API,
reference-cpp's compile-time template) must reject a sub-byte F cleanly rather than mishandle it,
and should say so in `capabilities.limitations`.

### Flags are keyed, and parsing is strict

An adapter **must exit 2** on an argv it cannot honor exactly:

| Case | Why it must fail |
|---|---|
| an unknown flag | the harness passes a parameter this adapter does not implement |
| a missing required flag | the harness has dropped or renamed a parameter |
| a value that does not parse completely | e.g. `--pt=abc`, or a negative count |
| a positional argument | the pre-keyed argv shape, from a harness older than the adapter |

Strictness is what keeps a harness/adapter mismatch from becoming quiet bad data: a mismatch is a
named error before any codec runs, rather than a run that used a parameter the adapter misread.
There is deliberately no contract version number, so strictness is the only drift detector and an
adapter must never ignore a flag it does not recognize.

`tests/adapter_contract.py` asserts all four cases for every adapter, so an adapter that has not
caught up with a contract change fails by name.

### `capabilities`

Print a single JSON object to stdout and exit 0:

```json
{
  "ops": ["compress", "decompress"],
  "timing_tier": "in_process",
  "reference_conformant": true,
  "conformance_compress": true,
  "conformance_decompress": true,
  "param_schedule": "pt_ft_rt"
}
```

`build_profile` and `limitations` are optional (default `""`); the mock omits both. An adapter built
on the shared plumbing does not write this JSON itself: it declares only the descriptive fields, and
`ops` / `conformance_compress` / `conformance_decompress` are **derived** from which codec hooks it
actually provided, so the report cannot claim support that is not implemented.

| Field | Meaning |
|---|---|
| `ops` | which of `compress` / `decompress` the impl supports (subset of those two) |
| `timing_tier` | `"in_process"` (wrapper runs the loop; clean numbers) or `"subprocess"` (wrapper can only spawn a whole-file CLI per iteration; tagged so it is never compared against in-process rows) |
| `reference_conformant` | `true` if compressed output is byte-identical to the ESA reference; `false` gates `validate` down to round-trip only |
| `conformance_compress` / `conformance_decompress` | whether the impl runs the encoder / decoder conformance phase |
| `param_schedule` | free label for how the impl uses the params (e.g. `"pt_ft_rt"`, `"init_incremental"`, `"identity"`) |

### `compress` / `decompress`

```
compress   --in=<path> --out=<path> --packet-bits=F --pt=N --ft=N --rt=N --robustness=N
decompress --in=<path> --out=<path> --packet-bits=F --pt=N --ft=N --rt=N --robustness=N
```

Read `--in`, run the op, write the result to **exactly** `--out` (no discover-by-extension: core
names the path). Map the uniform param set to the codec's real API; ignore any params the codec
does not use (the mock identity codec, for instance, ignores `pt/ft/rt` and `robustness`
entirely). Round-trip `decompress(compress(x)) == x` must hold for the impl's own format.

### `bench`

```
bench --op=compress|decompress --in=<path> --warmup=N --iterations=N \
      --packet-bits=F --pt=N --ft=N --rt=N --robustness=N
```

Load `--in` once, run `--op` `--warmup` times untimed and `--iterations` times timed, and print the
raw per-iteration nanoseconds as JSON:

```json
{"op": "compress", "iterations": 100, "packets_per_iter": 100, "nanos": [1234, 1250, ...]}
```

Time exactly what a real user pays per call (include any per-call setup the codec does, matching the
codec's own bench). Do **not** pre-summarize: core computes the median and derives every metric, so
all impls are comparable and percentiles stay available. `packets_per_iter` is the packet count of
the input (`len(input) / ceil(packet_bits / 8)`). For a decompress bench, compress the input once up front
(outside the timer) and time decompressing that. The shared plumbing does all of this, including
allocating the output buffer once up front so a timed iteration allocates nothing.

### `conformance-compress` / `conformance-decompress`

```
conformance-compress   --in=<in.raw+config> --out=<out.124>
conformance-decompress --in=<in.124+config> --out=<out.raw+large_f>
```

Optional (reported via `capabilities`). Read one self-describing UAB/CNES vector, write the byte-
exact result to `<out>`, exit 0. pocketbench compares `<out>`'s size + SHA-256 to the manifest. If
`capabilities` reports the phase unsupported, pocketbench never calls that verb and notes it as
skipped.

## The shared plumbing

`adapters/common/` is not an adapter (it has no `adapter.toml`, so discovery skips it). It holds one
implementation of the contract per language, and the compiled adapters are written against it:

| | Used by | Adapter supplies |
|---|---|---|
| `common/c/adapter_common.{h,c}` | reference-c, reference-cpp | a `pb_adapter` struct: declarative `pb_caps` + up to four hooks |
| `common/rust/pocketbench-adapter/` | reference-rust, pocketrust | an `impl Adapter`: `caps()` + two codec methods + optional conformance hooks |

Both own: subcommand dispatch, keyed-flag parsing and validation (including F's `1..=65535` range),
file IO, output buffer sizing, `packets_per_iter`, the warmup+timed loop, and the `capabilities` and
raw-nanos JSON. Both derive `ops` and the two `conformance_*` flags from which hooks exist.

The C header is `extern "C"`-guarded and `adapter_common.c` is always compiled as C, so the C++
adapter links the same object; its templated `compress<N>` dispatch happens inside its own hooks.

What that leaves in an adapter is codec-specific only: reference-rust is 55 lines and
reference-cpp 90. reference-c (436) and pocketrust (384) are longer because each carries its two UAB
conformance drivers, ported verbatim from that codec's own crossvalidation harness.

**Timing.** The hook is reached through a function pointer (C) or a trait object (Rust), so the
codec call is not inlined into the loop. Measured cost of the whole shared loop with a no-op hook:
**38 ns per iteration**, nearly all of it the two `clock_gettime` calls. A real iteration on the
smallest reference dataset is 20,000-100,000 ns, so the loop contributes under 0.05%. The output
buffer is allocated once up front, not per iteration.

An adapter does not have to use the shared plumbing; `adapters/mock/` speaks the contract directly
in Python. The contract is the process interface, and nothing in core knows which route an adapter
took.

## Discovery and the two presence gates

Discovery is a pure folder scan of `adapters/*/adapter.toml`. Presence is checked at the two phases
where it matters:

- **Gate 1 (source present), at build.** If `source` resolves to a missing/empty path, the build is
  skipped and the impl is unavailable. Self-contained adapters (no `source`) pass trivially.
- **Gate 2 (entrypoint present), at run.** With `--no-build`, if the built entrypoint is absent the
  impl is unavailable. Once built, the wrapper has linked the codec in, so `source` need not be
  present at run time.

**Selection-driven strict-vs-skip:** an unavailable impl is a hard **error** when named explicitly
with `--impl` (so CI, or a deliberate run, cannot silently skip it), and a friendly **skip**
otherwise. One rule, strict in CI and friendly locally.

## The C build wrinkle (`{max_packet_bits}`)

The ccsds124 C codec's max packet length is a compile-time macro that sizes a per-packet buffer it
memsets on every packet. `bench` wants it small (the dataset size; the default 65535 bits inflates
the buffer to ~96 KB and slows compression ~5x); `validate` / `profile` / `conformance` need the
full 65535 for the UAB `large_f` vectors. So the C adapter's `build` keeps a `{max_packet_bits}`
substitution and pocketbench builds the right variant per command
(`max(dataset packet_bits)`, floored at 720, for bench; the codec default otherwise). Because `make` ignores a
flag-only change, the reference-c Makefile forces a recompile (a `FORCE` prerequisite) so the size
always takes effect. Non-C adapters ignore the placeholder.

## Worked examples

**A compiled adapter: start from the shared plumbing.** `adapters/reference-rust/src/main.rs` (55
lines) and `adapters/reference-cpp/src/adapter.cpp` (90 lines) are the shortest complete adapters
and the shape to copy: declare the capability facts, implement the codec calls, hand the rest to
`pocketbench_adapter::run` / `pb_main`.

**An interpreted adapter: `adapters/mock/`.** The self-contained example the unit tests drive (so
the suite needs no C/Rust build), and the reference for what the contract looks like without the
shared plumbing: it parses the keyed flags itself, with the same strictness. Its manifest omits
`source`/`build`:

```toml
name        = "mock"
language    = "Python"
interpreter = "python"
entrypoint  = "adapter.py"
```

`adapters/mock/adapter.py` implements the whole contract: `compress`/`decompress` copy bytes
(identity, so round-trip is exact), `conformance-*` echo, `capabilities` prints canned JSON (omitting
the optional `build_profile`/`limitations`, so it also exercises core's defaults), and `bench` runs a
real in-process warmup+timed loop over a trivial op and prints the raw-nanos payload. Its `_flags`
helper is the minimum strict parser the contract requires.

## Adding an implementation (step by step)

1. **Put the codec somewhere outside this repo** (a checkout, clone, or copy), so pocket-plus
   never nests a second git repository. If its location varies per machine, add a `[settings]` key
   for it in `config/config.toml` and reference it as `${var}` from the adapter; if it is fixed
   relative to the repo, a plain relative `source` is enough. Skip this step for a self-contained
   adapter.
2. **Create the adapter folder** `adapters/<name>/`. Copy `adapters/mock/` as the starting shape.
3. **Write `adapter.toml`** (see "The manifest" above): set `name`, `language`, `source` (the
   `${var}` or relative codec path, or omit if self-contained), `build` (with `{source}` /
   `{max_packet_bits}` as needed, or omit), `entrypoint`, and optional `interpreter` / `version_cmd`.
4. **Implement the entrypoint.** In C, C++ or Rust, build it on the shared plumbing
   (`adapters/common/`): supply the capability facts plus the codec hooks and let it own the
   contract, so the six subcommands, the strict flag parsing and the timed loop come for free and a
   later contract change reaches you through it. In any other language, implement the six
   subcommands directly, following `adapters/mock/adapter.py` (strict keyed flags included). Either
   way, reuse the codec's own library entry points; only fall back to spawning a whole-file CLI (and
   reporting `timing_tier = "subprocess"`) if the codec truly cannot be linked, and report exactly
   what the impl supports so the commands gate themselves.
5. **Build and smoke-test:**
   ```bash
   uv run pocket-bench build --impl <name>
   uv run pocket-bench validate --impl <name> --dataset reference-simple
   uv run pocket-bench bench --impl <name> --dataset reference-simple --iterations 20
   ```
   Naming the impl with `--impl` makes an unavailable impl a hard error (not a silent skip), so a
   missing codec or a broken build surfaces immediately.
6. **Add a row to `ADAPTERS` in `tests/test_adapters.py`** naming what is impl-specific about it
   (conformance support, sub-byte F, the `build_profile`/`limitations` substrings). The whole
   parametrized suite, including `assert_adapter_conforms` and the strict-argv checks, then covers
   it, and it skips rather than fails when the codec is absent. No framework code changes are needed
   to wire in the impl itself.

## Adding a dataset

Datasets, like implementations, are **discovered folders**, not config blocks. Drop a folder under
`datasets/<name>/` with a `dataset.toml` manifest. The data is either **present** in the folder or
**linked** to an external location via `source` (the same present-or-linked model an adapter uses
for its codec); linked or present-in-folder data is gitignored. Discovery is a scan of
`datasets/*/dataset.toml`; select with repeatable `--dataset <name>` (omit to run all). No framework
code changes are required.

`kind` selects how the dataset is loaded.

### `kind = "reference"`

A ccsds124-style vector set: raw fixed-length packets, an `expected-output/*.pkt`, and a
`*-metadata.json` that carries the compression parameters. **Params are read from the metadata, not
restated**, so they cannot drift from the reference output they generated.

```toml
# A collection: one dataset per vector under `source` that has a *-metadata.json.
kind   = "reference"
source = "${ccsds124_root}/test-vectors"    # link; omit if the data is present in the folder
# Single vector instead of a collection:
#   vector = "simple"
```

- **Collection** (no `vector`): expands to one dataset per vector, named `<folder>-<stem>` (e.g.
  `reference-simple`, `reference-venus-express`).
- **Single** (`vector = "<stem>"`): one dataset, named after the folder.
- For each vector the loader reads `expected-output/<stem>-metadata.json` for the params
  (`packet_length` + `pt/ft/rt/robustness`) and derives paths by on-disk convention from the stem:
  input is `input/<stem>.*` (the extension varies, e.g. `.bin` vs `.ccsds`), expected is
  `expected-output/<input-name>.pkt`. It does **not** trust `metadata.input.file` (a generator label
  that can disagree with the real filename).

### `kind = "conformance"`

The UAB/CNES suite, used only by `conformance`. Always link-only (too large / licensed to commit).
It binds the suite dir with its expected-output manifest:

```toml
kind     = "conformance"
source   = "${conformance_data_dir}"                          # extracted suite
manifest = "${ccsds124_root}/crossvalidation/file_list.csv"
```

`${conformance_data_dir}` and `${ccsds124_root}` expand from `[settings]` in `config.toml`, so the
machine-specific paths stay out of git.

## Per-implementation isolation

A build or run failure for one implementation is recorded and the suite continues with the others
(unless that impl was explicitly selected, per the strict-vs-skip rule); one implementation's
breakage never aborts the whole run.

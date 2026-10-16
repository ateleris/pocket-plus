# Implementation interface (the adapter contract)

This document defines what you provide to drive an implementation with `pocketbench`.
`pocketbench` is a harness, not a codec: each implementation is fronted by a small **adapter**, a
wrapper executable that speaks one fixed contract over argv + stdout. pocketbench core owns
everything that is not the codec: dataset kinds, the pt/ft/rt flag schedule, re-framing the
yellow-book formats, warmup/iteration counts, comparison, all metric derivation and reporting. The
adapter owns mapping each packet onto the codec's real API.

Everything between those two (subcommand dispatch, flag parsing, the record container, output
buffer sizing, the warmup+timed loop, the summary JSON) is **shared plumbing**, held once per
language under `adapters/common/`. An adapter written against it supplies only per-packet codec
hooks plus two report strings, so a change to this contract is edited in the shared plumbing rather
than in every adapter. See "The shared plumbing" below.

You add an implementation by dropping a folder under `adapters/<name>/` with an `adapter.toml`
manifest and a built (or interpreted) entrypoint. No framework code changes are required. The
central `[implementations.*]` config model no longer exists.

## One folder

```
pocketbench/
  adapters/            # committed; one folder per impl. The only place a codec is named.
    common/            # NOT an adapter (no adapter.toml, so discovery skips it): shared plumbing
      c/               #   adapter_common.{h,c}   - used by reference-c, reference-cpp, pocketplus
      rust/            #   pocketbench-adapter/   - used by pocketrust-u32, pocketrust-u64
    mock/              # self-contained identity codec: the worked example for an interpreted impl
      adapter.toml
      adapter.py
    reference-c/
      adapter.toml
      Makefile
      src/adapter.c
    pocketrust/        # NOT an adapter (no adapter.toml): the crate both pocketrust-* adapters build
    pocketrust-u32/    # adapter.toml only: builds ../pocketrust with --features block32
    pocketrust-u64/    # adapter.toml only: builds ../pocketrust with --features block64
    pocketplus/        # C wrapper over the Stainless GenC output and its pp_* shim
```

Only the adapters are committed, and the codecs live entirely outside this repo so that no second
git repository is ever nested inside it: the ccsds124 checkout, reached via `${ccsds124_root}`
(`reference-c`, `reference-cpp`, and the `reference-*` datasets), and
`pocketrust` and `pocketplus`, the sibling folders `../pocketrust` and `../pocketplus` of the
enclosing pocket-plus repo. An adapter's
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
skip_commands = ["bench"]                                # optional: commands that leave it out
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
- **`skip_commands`** (any of `build`, `validate`, `bench`, `profile`, `conformance`, `explain`,
  `sweep`)
  leaves the adapter out of those commands' default selection and of `--impl` globs, e.g. a
  decoder-only implementation that `bench` cannot time. Naming it with `--impl <name>` still runs
  it. It never filters codec input; it only decides which impls a command runs by default.

## The contract

The entrypoint responds to three subcommands. Everything after the subcommand is a **keyed
`--key=value` flag**, in any order, and pocketbench always passes absolute paths.

| Subcommand | Purpose |
|---|---|
| `capabilities` | print `{"build_profile": "...", "limitations": "..."}`: report text, gating nothing |
| `compress --in= --out= [--warmup= --iterations=]` | compress a record container to exactly `--out` |
| `decompress --in= --out= [--warmup= --iterations=]` | decompress a record container to exactly `--out` |

Every command uses these two verbs: bench adds `--warmup` / `--iterations`, validate, profile,
conformance and explain do not. **Parsing is strict**: an unknown, duplicated or unparseable flag, a
missing `--in` / `--out`, any positional argument, or a malformed container exits 2, so a harness
that has moved on from what an adapter implements fails loudly.

### The record container

```
header:  F        u32 BE   (decompress input: 0 = unknown, the codec discovers it)
record:  kind     u8
         bits     u32 BE   declared length in bits
         payload  ceil(bits/8) bytes, or fewer when the stream ends first
```

| Stream | Records | `kind` | `bits` | payload |
|---|---|---|---|---|
| compress in | first: initial mask M_0 | 0 | F | the mask bytes as given |
| | then one per packet | the yellow-book flag byte (bit 7 reserved, f, p, r, R) | F | the field bytes as given |
| compress out | one per packet | 0 | packet length | the packet, zero-padded to a byte |
| decompress in | one per element | 0 received, 1 lost | declared length | the bytes present |
| decompress out | one per element | 0x00 decoded, 0x01 failed, 0x02 lost | F if decoded, else 0 | the decoded field |

The decompress output header carries the decoder's trailer F. A compress output is a valid
decompress input as it stands.

### The summary

stdout is one JSON object: `records_in`, `records_out`, `stopped_at` (null, or the index of the
record whose hook failed; 0 for a failed create), `error` (null, `input not representable by this
codec's API`, `codec error <code>` or `output buffer exhausted`), and `nanos` (the per-iteration
nanoseconds) if and only if `--iterations` was given. The output file holds everything produced
before a stop. A decompress hook may stop the stream too (a negative return in C, an `Err` in
Rust, an exception in the mock); the output then holds the records before it and the trailer F. Exit 0 when the container was read (a codec rejection is a result), 1 on an IO error
or an exhausted output buffer, 2 on a contract violation.

### No filtering

Neither core, the shared plumbing nor an adapter filters, validates or pre-checks input: every
value that parses is handed to the codec. The only adapter-side refusal is "input not representable
by this codec's API", returned without calling the codec when its API cannot take the input (a value
its types cannot hold, a field of the wrong length). A codec without a concept (packet loss, unknown
F) implements the hook honestly and fails the cases that need it.

## The shared plumbing

`adapters/common/` is not an adapter (it has no `adapter.toml`, so discovery skips it). It holds the
contract once per language, and the compiled adapters supply only per-packet hooks:

| | Used by | Adapter supplies |
|---|---|---|
| `common/c/adapter_common.{h,c}` | reference-c, reference-cpp, pocketplus | a `pb_adapter`: two report strings + `enc_create` / `enc_packet` / `enc_destroy` / `dec_create` / `dec_packet` / `dec_lost` / `dec_trailer_f` / `dec_destroy` |
| `common/rust/pocketbench-adapter/` | pocketrust-u32, pocketrust-u64 | an `impl Codec` with the same hooks |

The plumbing owns flag parsing, the container, the warmup + timed loop (per iteration: create,
every packet, destroy), output sizing and the summary. `adapters/mock/adapter.py` is the third,
Python, copy; `tests/test_plumbing.py` runs the same behaviours against all three through identity
fixtures (`tests/fixtures/adapters/{c,rust}-identity`), so the copies cannot drift.

## Discovery and the two presence gates

Discovery is a pure folder scan of `adapters/*/adapter.toml`. `[settings] local_adapters_dir`
(optional, machine-local) names a second folder scanned the same way, for adapters that must stay
outside the repository; an adapter name defined in both is a configuration error. Presence is
checked at the two phases where it matters:

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
substitution, and an adapter whose `build` takes it is **size-specialized** (derived from the
manifest, so it cannot drift). `bench` groups the selected datasets by the size they need (each
dataset's F, floored at 720) and rebuilds every size-specialized adapter once per group, timing each
group on a build sized for it; every other adapter is built once. The report says so explicitly:
the build-settings section lists one build per size and explains that this is a compile-time
advantage the run-time-sized impls do not take, and each result row names the build it ran on
(`reference-c (built for F ≤ 720)`). `validate` / `profile` / `conformance` build the codec default;
a standalone `build` sizes to the largest F of all datasets. Because `make` ignores a flag-only
change, the reference-c Makefile forces a recompile (a `FORCE` prerequisite) so the size always
takes effect. With `--no-build` nothing is rebuilt, so the report states the size as unknown
("as last built") rather than claiming one.

## Worked examples

**A compiled adapter: start from the shared plumbing.** `adapters/reference-cpp/src/adapter.cpp`
and `adapters/pocketplus/src/adapter.c` are the shapes to copy: two report strings and the
per-packet hooks, everything else in `pb_main` / `pocketbench_adapter::run`.

**An interpreted adapter: `adapters/mock/`.** The self-contained example the unit tests drive (so
the suite needs no C/Rust build). Its manifest omits `source`/`build`:

```toml
name        = "mock"
language    = "Python"
interpreter = "python"
entrypoint  = "adapter.py"
```

`adapters/mock/adapter.py` is an identity codec plus the Python copy of the plumbing: the same
strict flags, container, timed loop and summary as the C and Rust copies.

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
   (`adapters/common/`) and supply only the per-packet hooks. In any other language, copy the
   plumbing half of `adapters/mock/adapter.py`, add the language to `tests/test_plumbing.py` with an
   identity fixture, and supply the hooks. Map the hooks onto the codec's own library entry points
   and never add input checks of your own.
5. **Build and smoke-test:**
   ```bash
   uv run pocket-bench build --impl <name>
   uv run pocket-bench validate --impl <name> --dataset reference-simple
   uv run pocket-bench bench --impl <name> --dataset reference-simple --iterations 20
   ```
   Naming the impl with `--impl` makes an unavailable impl a hard error (not a silent skip), so a
   missing codec or a broken build surfaces immediately.
6. **Add a row to `ADAPTERS` in `tests/test_adapters.py`** naming what is impl-specific about it
   (sub-byte F, an unsupported size, whether its output matches the two UAB vectors, the
   `build_profile`/`limitations` substrings). The whole parametrized suite, including
   `assert_rejects_contract_violations`, then covers
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

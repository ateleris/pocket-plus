# pocketbench

A Python (uv) test and benchmark framework for **CCSDS 124.0-B-1 (POCKET+)** implementations.
It builds each implementation, validates correctness (round-trip, reference vectors, and
cross-impl), profiles performance (throughput, compression ratio, peak memory), and
(eventually) visualizes the results.

pocketbench is a harness, not a codec. Each implementation is fronted by a small committed
**adapter** under `adapters/<name>/` that speaks a fixed contract; pocketbench talks only to that
contract. The codecs themselves are never committed here and never nested as a git repo inside
this one: the `ccsds124` reference repo is an external checkout reached through the
`ccsds124_root` setting, and the RHEA-internal `pocketrust` is the sibling folder `../pocketrust`
of the enclosing pocket-plus repo.

## Quick start

```bash
uv sync                                            # create the environment
cp config/config.example.toml config/config.toml   # then edit the paths in it
uv run python -m pytest                            # the framework's own tests (no C/Rust build needed)
uv run pocket-bench --help
```

`config/config.toml` is machine-local and not committed, because it names where the external
`ccsds124` checkout lives on this machine. The template's default is `../implementations/ccsds124`,
so cloning the checkout there needs no edit at all:

```bash
git clone https://github.com/tanagraspace/ccsds124 implementations/ccsds124
```

Without that checkout nothing breaks: the `reference-*` adapters and the reference datasets are
skipped, and `pocketrust` plus the synthetic datasets still build, validate and bench.

Select which implementations and datasets to run (both default to "all discovered"):

```bash
uv run pocket-bench bench --impl reference-c --impl reference-rust --dataset reference-simple --dataset reference-housekeeping
uv run pocket-bench validate --impl reference-rust
```

Impls are discovered from `adapters/*/adapter.toml`. An impl selected with `--impl` that is
unavailable (its codec is not placed, or its wrapper is not built) is a hard error; an unavailable
impl in a default "all" run is skipped with a note. This makes the same command strict in CI and
friendly locally.

## The adapter contract

Every impl provides one wrapper executable that responds to these subcommands. Arguments are keyed
`--key=value` flags in any order, and `--packet-bits` is F, the packet field width in **bits**;
pocketbench passes explicit input and output paths:

| Subcommand | Purpose |
|---|---|
| `capabilities` | print JSON self-describing the impl (ops, timing tier, `reference_conformant`, conformance support, param schedule) |
| `compress --in= --out= --packet-bits= --pt= --ft= --rt= --robustness=` | compress to exactly `--out` |
| `decompress --in= --out= --packet-bits= --pt= --ft= --rt= --robustness=` | decompress to exactly `--out` |
| `bench --op= --in= --warmup= --iterations= --packet-bits= --pt= --ft= --rt= --robustness=` | run the in-process warmup+timed loop, print the raw per-iteration nanos as JSON |
| `conformance-compress --in= --out=` | run one UAB/CNES encoder vector (optional) |
| `conformance-decompress --in= --out=` | run one UAB/CNES decoder vector (optional) |

Parsing is strict: an unknown, missing or unparseable flag, or any positional argument, must exit 2,
so a harness that has moved on from what an adapter implements fails loudly instead of running with
a parameter it misread.

pocketbench core owns dataset staging, warmup/iteration counts, all metric derivation, aggregation,
reporting, and conformance grading. The adapter owns mapping the uniform parameter set to the codec's
real API and calling the codec; the contract plumbing between them (flag parsing, IO, the timed loop,
the JSON payloads) is shared, one implementation per language under `adapters/common/`. See
[`docs/IMPLEMENTATION-INTERFACE.md`](docs/IMPLEMENTATION-INTERFACE.md) for the full contract and how
to add an implementation.

## Benchmarking

`pocket-bench bench` runs a uniform in-process benchmark for each selected (impl, dataset):

```bash
uv run pocket-bench bench --warmup 10 --iterations 100
uv run pocket-bench bench --impl reference-rust --dataset reference-simple --iterations 100
uv run pocket-bench bench --iterations 100 --no-build   # reuse already-built adapters
```

For each op (compress, decompress) the adapter loads the input once, runs `--warmup` untimed
iterations then `--iterations` timed iterations, and returns the raw per-iteration nanoseconds.
pocketbench derives time, packets/sec, µs/pkt, and throughput (MB/s, MiB) in **one code path** for
every impl, so numbers are strictly comparable. Because timing happens inside one long-lived
adapter process, there is no per-iteration spawn overhead. The bench is file-driven, so any dataset
can be benched.

Notes:
- **Iterations / warmup**: defaults are `100` / `10`; lower iteration counts run faster but noisier.
- **Timing tier**: an adapter that can only wrap a whole-file CLI reports `timing_tier =
  subprocess` via `capabilities`, so its rows are never silently compared against `in_process` rows.
- Results are written to `results/benchmark.md` (a `BENCHMARK.md`-style report with a reproducible
  **Build Settings** section) and `results/benchmark.json`, and printed as tables. Absolute numbers
  depend on the host, so use them for relative comparison.

## Validating correctness

`pocket-bench validate` checks that each implementation is actually correct, not just fast:

```bash
uv run pocket-bench validate --impl reference-c --impl reference-rust --dataset reference-simple --dataset reference-hiro
uv run pocket-bench validate --no-build          # skip rebuilding the adapters
```

For every selected (implementation, dataset) it runs, using the adapter's `compress`/`decompress`
with explicit output paths:

- **reference** - the compressed output is byte-identical to the reference packet
  (`test-vectors/expected-output/*.pkt`).
- **round-trip** - `decompress(compress(x)) == x`.
- **cross-impl** - all selected implementations produce the same packet (one SHA-256),
  equal to the reference packet when one exists.

`capabilities.reference_conformant` gates the checks: an impl that is not byte-identical to the ESA
reference (e.g. the mock identity codec) runs round-trip only and is excluded from the
reference / cross-impl comparison. The command exits non-zero if
any check fails, so it doubles as a CI gate.

Run `validate` before trusting `bench` numbers: the benchmark measures speed only and would happily
report a fast but incorrect codec.

## Profiling memory

`pocket-bench profile` measures peak memory and compression ratio, complementing `bench`:

```bash
uv run pocket-bench profile --impl reference-c --impl reference-rust --dataset reference-simple --dataset reference-venus-express --runs 3
uv run pocket-bench profile --no-build
```

It runs the adapter's `compress`/`decompress` under `/usr/bin/time -v` and reports, per
(implementation, dataset, operation): input/output sizes, compression ratio, and **peak resident
set size** (the max over `--runs`, since RSS is near-deterministic). Results are written to
`results/profile.json`.

Notes:
- Peak RSS is a whole-process figure, so it includes the language runtime's baseline (Rust's is a
  bit higher than C's).
- No speed is reported here: a subprocess wall clock includes process startup. Use `bench` for
  speed comparisons.
- Requires `/usr/bin/time` (GNU time).

## UAB/CNES conformance

`pocket-bench conformance` runs an implementation against the UAB/CNES conformance suite
(7,935 encoder + 16,965 decoder vectors):

```bash
uv run pocket-bench conformance --impl reference-c --mode encoder
uv run pocket-bench conformance --impl reference-c --mode both --no-build
uv run pocket-bench conformance --impl reference-c --limit 200   # quick smoke test
```

These vectors are **not** pocketbench datasets: they are self-describing `.raw+config` files
(embedded `large_f`, mask, and per-packet flags). pocketbench runs each vector through the adapter's
`conformance-compress` / `conformance-decompress` verb and compares the output (size + SHA-256) to
`crossvalidation/file_list.csv`. It **reports rather than grades**: you get total / passed / failed per
impl and the failing vector names in `results/conformance-<impl>.txt`, with no pass/fail verdict. A
failing vector never affects the exit code; only a run that could not execute does. Conformance is
local-only: the licensed yellow-book vectors are never uploaded, so it never runs in CI.

Requirements:
- `[settings] conformance_data_dir` in the config must point at the extracted suite.
- The impl's adapter must report conformance support via `capabilities`. reference-c and pocketrust do;
  reference-rust does not (the Rust codec exposes no per-packet flag API), so it is skipped.

## Explaining a failure

`conformance` tells you *which* vectors failed; `explain` tells you *what* diverged.

```bash
uv run pocket-bench explain --impl reference-c decoder_sequence_08724
uv run pocket-bench explain --impl reference-c --frame -1 --mode bin decoder_sequence_08748
```

```
DECODER  reference-c  decoder_sequence_08724.raw+large_f
  expected 353 B, got 181 B  (-172)
  large_f trailer  1376 -> 1376  ok
  frame 4  status  0x00 (decoded) -> 0x01 (not decodable with guarantee)
           you rejected a packet the reference decoded
  4 of 5 frames match
```

It re-runs the named vector through the adapter and diffs the result against the suite's expected
output bytes. Decoder output is compared **structurally**, as a sequence of frames (a status byte
plus, when decoded, `large_f` bits padded to the byte) followed by a 32-bit `large_f` trailer, so you
get the diverging frame, its status change, and differing payload bit ranges. Encoder output is an
unframed concatenation of compressed packets, so it gets a byte-level diff only.

`--frame N` dumps that frame against ground truth (`-1` picks the first divergence) in `--mode hex`
or `bin`, marking the differing bytes or bits. From a notebook, `pb.explain(...)` returns a run with
`.summary()`, `.to_dataframe()` (one row per diverging frame), and `show_frame(i, mode=...)`.

## Running the notebook example

`notebooks/example.ipynb` drives the same `PocketBench` facade the CLI uses (build, bench,
conformance, explain), so notebook results can never diverge from CLI results.

```bash
uv run --with jupyterlab --extra notebook jupyter lab --no-browser
```

`--no-browser` means **it does not open a browser for you**. Copy the tokenized URL it prints and
open that yourself, then pick `notebooks/example.ipynb` from the file browser:

```
http://localhost:8888/lab?token=<token>
```

The port is 8888 only if free; it silently moves up (8889, ...) when something else already holds it,
so take the port from the printed line rather than assuming. Stop the server with Ctrl-C twice. The
token changes every launch, so an old URL will not authenticate.

Jupyter is deliberately **not** a project dependency: `--with jupyterlab` layers it into an ephemeral
environment on top of the project venv, so nothing is added to `pyproject.toml` or `uv.lock`.
`--extra notebook` pulls in pandas (for `.to_dataframe()`) and jinja2 (for the `DataFrame.style`
highlighting). Both flags are needed; neither is installed by a plain `uv sync`.

Run it from the repo root, as above. The notebook resolves its config as
`Path("..") / "config" / "config.toml"`, which relies on the kernel's working directory being
`notebooks/`; Jupyter sets that from the notebook's own location when you open it, so this works
regardless of where the server was launched.

Note that the conformance cell runs the **full** suite (24,900 vectors) as written. Uncomment its
`limit=200` for a smoke test.

## Configuration

`config/config.toml` holds only `[settings]`: the `ccsds124_root` checkout location, `results_dir`,
and the local-only `conformance_data_dir`. It is gitignored, since those paths are machine-specific;
`config/config.example.toml` is the committed template. `config/ci.toml` is the committed variant the
GitHub workflow uses, and differs only in that it omits `conformance_data_dir` (the licensed
UAB/CNES yellow book must never reach a hosted runner).

Neither implementations nor datasets are configured here. Each implementation is a wrapper folder
`adapters/<name>/` with its own `adapter.toml`, and each test set is a folder `datasets/<name>/`
with its own `dataset.toml`; both are discovered by scanning. Add either by adding a folder (see
[`docs/IMPLEMENTATION-INTERFACE.md`](docs/IMPLEMENTATION-INTERFACE.md)). No framework code changes
are required either way.

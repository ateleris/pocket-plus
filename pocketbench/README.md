# pocketbench

A Python (uv) test and benchmark framework for **CCSDS 124.0-B-1 (POCKET+)** implementations.
It builds each implementation, validates correctness (round-trip, reference vectors, and
cross-impl), profiles performance (throughput, compression ratio, peak memory), and
(eventually) visualizes the results.

pocketbench is a harness, not a codec. Each implementation is fronted by a small committed
**adapter** under `adapters/<name>/` that speaks a fixed contract; pocketbench talks only to that
contract. The codecs themselves are never committed here and never nested as a git repo inside
this one: the `ccsds124` reference repo is an external checkout reached through the
`ccsds124_root` setting, and the RHEA-internal `pocketrust` and `pocketplus` are the sibling folders
`../pocketrust` and `../pocketplus` of the enclosing pocket-plus repo. pocketrust runs as two
adapters, `pocketrust-u32` and `pocketrust-u64`, one per block width (its `block32` / `block64`
features); both build the one adapter crate in `adapters/pocketrust/`.

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
skipped, and `pocketrust-u32`, `pocketrust-u64`, `pocketplus` and the synthetic datasets still build, validate and bench.

Select which implementations and datasets to run (both default to "all discovered"):

```bash
uv run pocket-bench bench --impl reference-c --impl pocketrust-u64 --dataset reference-simple --dataset reference-housekeeping
uv run pocket-bench validate --impl pocketrust-u32 --impl pocketrust-u64
uv run pocket-bench bench --impl 'pocketrust-*' --dataset 'reference-*' --dataset 'synthetic-*'
```

`--impl` and `--dataset` also take a glob (quote it so the shell leaves it alone); a glob that
matches nothing is an error, and anything selected twice runs once. Impls matched by a glob count
as named, so an unavailable one is a hard error, as with `--impl <name>`.

Impls are discovered from `adapters/*/adapter.toml`. An impl selected with `--impl` that is
unavailable (its codec is not placed, or its wrapper is not built) is a hard error; an unavailable
impl in a default "all" run is skipped with a note. This makes the same command strict in CI and
friendly locally.

## The adapter contract

Every impl provides one wrapper executable that responds to three subcommands. Arguments are keyed
`--key=value` flags in any order, and pocketbench always passes absolute paths:

| Subcommand | Purpose |
|---|---|
| `capabilities` | print `{"build_profile": "...", "limitations": "..."}`: report text, gating nothing |
| `compress --in= --out= [--warmup= --iterations=]` | compress a record container to exactly `--out` |
| `decompress --in= --out= [--warmup= --iterations=]` | decompress a record container to exactly `--out` |

Every command uses these two verbs: bench adds `--warmup` / `--iterations`, validate, profile,
conformance and explain do not. **Parsing is strict**: an unknown, duplicated or unparseable flag, a
missing `--in` / `--out`, any positional argument, or a malformed container exits 2, so a harness
that has moved on from what an adapter implements fails loudly.

pocketbench core owns everything that is not the codec (dataset kinds, the pt/ft/rt schedule,
re-framing, comparison, metrics, reports); the adapter supplies per-packet codec hooks over the
shared plumbing. See [`docs/IMPLEMENTATION-INTERFACE.md`](docs/IMPLEMENTATION-INTERFACE.md).

## Benchmarking

`pocket-bench bench` runs a uniform in-process benchmark for each selected (impl, dataset):

```bash
uv run pocket-bench bench --warmup 10 --iterations 100
uv run pocket-bench bench --impl pocketrust-u32 --impl pocketrust-u64 --dataset reference-simple --iterations 100
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
- **Time budget** (`--time-budget SECONDS`, opt-in): instead of `--iterations` everywhere, each
  measurement is probed with one iteration and then runs as many timed iterations as fit in the
  budget, at least 5 and at most `--iterations`, with warmup scaled to keep the warmup:iterations
  ratio. Small datasets still get the full count; large ones (e.g. 151,200 packets) get a few, which
  is enough because one iteration already averages over every packet. Each result row records the
  count it ran. Measurements always run one at a time: concurrent runs skew the timings (on a hybrid
  CPU, by up to 60% at 8 concurrent runs).
- **What is timed**: per iteration, the adapter creates its codec state, runs every packet through
  the same per-packet hooks conformance verifies, and drops the state; nothing else is in the timed
  region.
- The console shows a **Throughput Overview** (MB/s, one row per dataset, one column per impl
  under Compress and Decompress) and the build settings; `-v` / `--verbose` lists every selected
  impl and dataset (otherwise only counted) and adds a full table per dataset and operation (time,
  packets/sec, µs/pkt, MB/s). On `validate` and `profile`, `-v` only lists the selection. `results/benchmark.md` (a
  `BENCHMARK.md`-style report with a reproducible **Build Settings** section) and
  `results/benchmark.json` always hold everything. Absolute numbers depend on the host, so use them
  for relative comparison.

## Sweeping parameters (compression ratio)

`pocket-bench sweep` compresses each selected dataset once per parameter configuration and reports
the compression ratio in exact bits (packets x F in, the compressed packets' bits out, no byte
padding). Each of `--pt`, `--ft`, `--rt`, `--robustness` takes a value spec (`1-50`, `5,10,20`,
`off`, or mixed); every combination runs, and a parameter not given keeps each dataset's own value.
`off` (not for robustness) sets that flag on the first packet only.

```bash
uv run pocket-bench sweep -i pocketrust-u64 -d 'hktm-1week-*' --pt 1-50 --ft off --rt off --robustness 0
uv run pocket-bench sweep -i pocketrust-u64 -d 'hktm-1week-*' --robustness 0-7 -v
```

- `--impl` is required: conformant encoders emit identical bits, so one is enough; a second one
  only cross-checks.
- The console shows one row per configuration with each corpus's mean ratio (a corpus is the
  dataset folder, e.g. `hktm-1week`); `-v` adds a row per dataset. Measurements that did not
  complete are listed with their reason and left out of the means. Writes `results/sweep.json`.
- Nothing is timed, so measurements run in parallel (`--jobs`, default one per CPU), each in its
  own adapter process.
- In Python, `pb.sweep(...)` also takes `configs=[dict(pt=10, ft=2 * T, rt=T, robustness=0), ...]`
  for linked parameters. The notebook reproduces Figures 3, 5, 6 and 7 of Hernandez-Cabronero et
  al., *Resiliency and Efficiency of the CCSDS 124.0-B-1 Telemetry Compression Standard* (2024).

## Validating correctness

`pocket-bench validate` checks that each implementation is actually correct, not just fast:

```bash
uv run pocket-bench validate --impl reference-c --impl pocketrust-u64 --dataset reference-simple --dataset reference-hiro
uv run pocket-bench validate --no-build          # skip rebuilding the adapters
```

For every selected (implementation, dataset) it runs, using the adapter's `compress`/`decompress`
with explicit output paths:

- **reference** - the compressed output is byte-identical to the reference packet
  (`test-vectors/expected-output/*.pkt`).
- **round-trip** - `decompress(compress(x)) == x`.
- **cross-impl** - all selected implementations produce the same packet (one SHA-256),
  equal to the reference packet when one exists.

Every implementation goes through every check. The command exits non-zero if any check fails,
so it doubles as a CI gate.

Run `validate` before trusting `bench` numbers: the benchmark measures speed only and would happily
report a fast but incorrect codec.

## Profiling memory

`pocket-bench profile` measures peak memory and compression ratio, complementing `bench`:

```bash
uv run pocket-bench profile --impl reference-c --impl pocketrust-u64 --dataset reference-simple --dataset reference-venus-express --runs 3
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
(embedded `large_f`, mask, and per-packet flags). pocketbench re-frames each vector into the record container, runs it through the same
`compress` / `decompress` verb every command uses, and compares the output (size + SHA-256) to
`crossvalidation/file_list.csv`. It **reports rather than grades**: you get total / passed / failed per
impl and each failing vector with its reason in `results/conformance-<impl>.txt`, with no pass/fail
verdict. A
failing vector never affects the exit code; only a run that could not execute does. Conformance is
local-only: the licensed yellow-book vectors are never uploaded, so it never runs in CI.

Requirements:
- `[settings] conformance_data_dir` in the config must point at the extracted suite.
- Nothing else: every selected impl runs every vector, and each failing vector is logged with its
  reason.

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

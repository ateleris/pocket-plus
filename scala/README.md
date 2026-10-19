# PocketPlus (Scala / Stainless)

## Setup

Run `../install.sh <platform>` from the repo root once to install Stainless and the
`sbt-stainless` plugin into this project (see `project/plugins.sbt`).

## Running the tests

There is no `sbt test` suite here — tests are plain `main` methods that print
`PASS`/`FAIL` per case and exit non-zero on failure. Run them with `sbt runMain`
from the `scala/` directory:

```bash
sbt "runMain pocket.SpecTest"
```

Checks `EncodingStep` (in `PocketExecSpec.scala`) against hand-written cases and the
differential test vectors in `src/test/res/test-vectors/differential/cases.json`.

```bash
sbt "runMain pocket.CrossCheckTest"
```

Cross-checks the Stainless-verified `List[Boolean]` encoder (`PocketExecSpec`) against
the array-based `Decompressor` in `PocketPlus.scala`, over randomized packet sequences
at several values of `F`.

```bash
sbt "runMain pocket.Main"
```

Runnable demo that compresses a couple of hand-picked input vectors and prints every
intermediate bit vector, for eyeballing the packet layout against the blue book
(section 5.3.3).

## Stainless verification

```bash
./verify.sh
```

Runs `stainless-dotty` on `PocketExecSpec.scala` to verify the contracts/preconditions
in the spec (uses `stainless.conf`). Pass extra Stainless flags as `$1`, e.g.
`./verify.sh --watch`.

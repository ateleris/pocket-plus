# PocketPlus (Scala / Stainless)

## Setup

Run `../install.sh <platform>` from the repo root once to install Stainless and the
`sbt-stainless` plugin into this project (see `project/plugins.sbt`).

## Running the tests

There is no `sbt test` suite here — tests are plain `main` methods that print
`PASS`/`FAIL` per case and exit non-zero on failure. Run them with `sbt runMain`
from the `scala/` directory:

```bash
sbt "runMain pocket.test.SpecTest"
```

Checks `Encoder` (in `PocketExecSpec.scala`) against hand-written cases and the
differential test vectors in `src/test/res/test-vectors/differential/cases.json`.

```bash
sbt "runMain pocket.test.CrossCheckTest"
```

Cross-checks the Stainless-verified `List[Boolean]` encoder (`PocketExecSpec`) against
the array-based `Decompressor` in `PocketPlus.scala`, over randomized packet sequences
at several values of `F`.

```bash
sbt "runMain pocket.test.RoundTripTest"
```

Round-trips randomized packet sequences (fixed seed, 50 runs of 120 steps) through
`Encoder.step` and `Decoder.step` (in `PocketExecSpec.scala`), with random `R_t`, `p_t`,
`f_t`, `r_t`. It checks that every input vector is recovered and that the decoder state
stays in sync with the encoder (`decoderView`). It also checks that malformed packets are rejected.

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

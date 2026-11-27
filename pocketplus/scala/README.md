# PocketPlus (Scala / Stainless)

## Setup

Only sbt and a JDK are needed: `build.sbt` gets Stainless from Maven Central, as its library and
its plugin for the Scala 3 compiler. Compiling the project (`sbt compile`, `sbt runMain ...`) does
not verify it: the plugin only removes the ghost code there.

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


Run `sbt verify/clean verify/compile`: the `verify` project of `build.sbt` compiles the verified
sources (`verifiedSources`, the spec, the imperative codec and its data structures) with
verification on. Stainless reads its options from `stainless.conf`.

For local development, `./verify.sh` runs a `stainless` installed on the `PATH` on the same files
instead (`./verify.sh --parallel N` checks N VCs at a time, and a Stainless option can follow).

`sbt genc` translates the imperative codec to C, into `../native/generated/` (used by
`../native/build.sh` and the Visual Studio project).

### Main interest points

- main encoder implementation: PocketExecSpec.scala:170
  - which uses the `outputVector` function which follows the blue book closely: PocketExecSpec.scala:216

- main decoder implementation: PocketExecSpec.scala:306
  - which uses the `decodeOutputVector` function which mirrors the `outputVector` function: PocketExecSpec.scala:329

- main invertibility theorems:
  - `mainRoundTripTheorem`: PocketExecSpec.scala:944
    - proves that the decoding an encoded vector returns the original vector, for all possible inputs and states
  - `mainStateSynchronizationTheorem`: PocketExecSpec.scala:970
    - proves that the decoder state stays in sync with the encoder state, for all possible inputs and states, after encoding and decoding a vector

- invertibility of sub-functions:
  - `lemmarunLengthEncodeRoundTrip`: PocketExecSpec.scala:1420

An overview figure of one encode/decode step and of the main theorems is in
[`docs/verification_overview.md`](../../docs/verification_overview.md).

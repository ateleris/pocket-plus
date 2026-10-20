# Verification Plan (WP 2.2)

Matt Bovel, Samuel Chassot, Viktor Kunčak  
19.10.2026

## Introduction

The document implements the verifiation plan, as described in WP 2.2 at page 27 of the proposal (doc-number: ATS-2026-RHEA-Vol1, issue: I1R0, release date: 31.03.2026):

> Institution: EPFL  
> WP Manager: V. Kunčak  
> Start Event: MS-1 (T0+3), Planned Date: 01.09.2026  
> End Event: MS-2 (T0+5), Planned Date: 02.11.2026  
> Issue 1  
> Issue Date: 12.03.2026
>
> Objectives: 
> - Define the scope and structure of formal verification for the activity.
> - Specify  which  components  will  be  formally  verified,  which  properties  will  be  proven,  and which Stainless proof patterns apply. 
> 
> Inputs: 
> - D1 (Requirements Document)
> - D2 (Review of CCSDS 124 Standard) 
> 
> Tasks: 
> - Specification of which components will be formally verified (core encoder/decoder state machine, validity checks, robustness-level accounting) 
> - Selection  of  Stainless  proof  patterns  applicable  to  the  compression/decompression  algorithms 
> - Definition of the boundary between formally verified and tested-only components 
>
> Outputs: 
> - Formal verification plan annex to D3

## Open Questions

- “The verified decoder receives one packet with its exact bit length”; is that true?
- To confirm: decode get the vector length.

## Structure

### Scope

The formally verified part of SW2 is the core of the codec: the mask update (blue book section 4), the basic encoding functions (5.2), the encoding step (5.3), the decoding step with its validity checks (green book 3.3). Everything around this core (packet framing, loss detection, parameter scheduling, multiplexing, monitoring, the C shim) is only tested. The boundary is given in _Tested-only features_ below.

### Two implementations and a refinement

The core is written twice, in two subsets of Scala that Stainless verifies:

- The **functional specification** is a purely functional, **executable** model of the encoder and the decoder. It is the reference: the properties below are stated and proven on it.
- The **imperative implementation** is the fast, low-level version from which Stainless GenC generates the C99 code delivered in SW2. It is written in the GenC-compatible imperative subset of Scala and shaped by the flight constraints of D1: no allocation after initialisation, bounded stack, integer and bitwise operations only, bit-exact output.

| | Functional specification | Imperative implementation |
|---|---|---|
| State | immutable record passed to and returned by each step | caller-allocated struct mutated in place (the context object of R-PERF-07) |
| Integers | unbounded `BigInt` | bounded `BigInt`, compiled to 32-bit machine integers in C, absence of overflows checked by Stainless |
| Bit vectors | singly linked lists of booleans, MSB first; bits are `Boolean`s; induction is the main proof tool | packed machine words with explicit bit lengths; bits are machine bits |
| Step | pure function `(state, input, flags) → (output, state')` | procedure writing into a caller-provided output buffer and updating the state in place |
| History | what the definitions need: the last 15 change vectors, the new-mask flags of the robustness window, exact `t` | 8 change vectors and a zero-run counter, 15 flag bits, saturating `t` (green book table 5-1) |
| Control flow | recursion over lists | loops with invariants, no recursion |
| Role | object of the functional proofs; oracle for random cross-validation | source of the generated C; object of the safety proofs; subject of the acceptance tests |

The proofs follow this split. Each property is proven once, on the functional specification. A refinement theorem then shows that the imperative implementation is behaviorally equivalent to it: a relation links every imperative state to the functional state it represents, and every entry point of the API produces the same bits as its functional counterpart and lands in a related state. Through this theorem every functional property transfers to the imperative implementation (_Lifted properties_ below). Only memory safety, frame conditions, and termination, are proven again directly on the imperative code.

What remains trusted is Stainless and the SMT solvers, the GenC translator, and the C compiler. For this reason the generated C is cross-validated against the Scala reference on the shared test vectors (proposal 2.3), and the D4 campaign additionally runs it under a memory-error detector (D1, R-DES-05).

### Verified properties

The following properties will be proven with Stainless. Each has a section under _Properties_ below. An empty status means to be done. Items marked _stretch_ are attempted after the committed ones and fall back to testing only if the proofs turn out too costly (proposal 2.9.1).

| Property | Statement | Status |
|---|---|---|
| **Basic encoding functions** | COUNT, RLE and BE (blue book 5.2) are prefix-decodable: decoding the encoding followed by any suffix returns the original value and the untouched suffix. | Partial: COUNT and RLE round trips proven in `PocketExecSpec.scala`; BE pending. |
| **State invariant** | the encoder and decoder states stay well-formed over any sequence of steps, for every F, every robustness level R_t in 0..7 and every initial mask. | Partial? TODO: Check when t < R_t (to fix). |
| **Validity checks** | the decoder is a total function: every packet is either decoded or rejected as non-compliant, rejection leaves the decoder state unchanged, and the rejection cases are exactly the validity conditions (green book 3.3.6, corrected where needed). This list is the one R-SYS-05 and R-SYS-06 refer to. |  |
| **Round-trip (single step)** | decoding the output vector of a step from the synchronised decoder state yields the input vector and the next synchronised decoder state. As a corollary, every output vector produced by the encoder passes every validity check (completeness). |  |
| **Round-trip (multiple steps)** | by induction, any sequence of input vectors and user flags, including raw steps where the input vector is sent instead of the output vector (red book clause 6), is reconstructed exactly in the absence of losses of vectors. |  |
| **Refinement** | the imperative encoder and decoder implement the functional specification: a relation links each imperative state to the functional state it represents (the imperative state is lossy: 8 change vectors and a counter instead of 15, a saturating t), and every entry point of the C API (encoder init, initial mask, compress step, mask-update-only step for red book clause 6; decoder init, decode step, loss signal) produces the same bits as its functional counterpart and lands in a related state. Bit-exactness across platforms (R-PORT-01) follows. |  |
| **Lifted properties** | corollaries of Refinement, stated and proven for the imperative implementation so that D9 can map each requirement to a theorem about the delivered code: round-trip (single and multiple steps), validity-check totality and completeness, and the output size bound and robustness if proven. The multi-step case is one induction over call sequences, using that every call preserves the imperative state invariant. |  |
| **Imperative safety** | the imperative implementation has no out-of-bounds access, no integer overflow or wraparound, terminates, and uses no recursion, no division and no heap allocation (R-DES-05, R-PERF-03, R-PERF-04, R-PERF-05). Frame conditions: input buffers are never written, the output never exceeds the caller-provided capacity (checked at run time against the capacity, with an error code on overflow, so this does not depend on the size bound), and a rejected packet leaves the decoder state unchanged (R-PERF-07). |  |
| **Output size bound (stretch)** | every output vector has at most 9.5·F + 44 bits (green book table 5-1); this fixes the static buffer sizes of the C API. |  |
| **Robustness (stretch)** | the normative guarantee of the blue book (2.1): if the decoder was synchronised before a gap of at most V_t consecutive lost output vectors and F is known, decoding o_t yields M_t and I_t; and an output vector with f_t = r_t = 1 is decoded correctly from any decoder state.  |
| **Recovery (stretch)** | soundness of the remaining resynchronisation rules of the green book (3.3.4, full and partial), of backward decoding (3.3.5) and of the per-bit validity mask (R-SYS-07, R-SYS-08, R-SYS-11). |  |

### Tested-only features

The following are outside the verified core and are validated by the test campaign (D4, D5, D9) only. An empty status means to be done.

| Feature | Description | Status |
|---|---|---|
| **Packet framing** | packet length and delimitation are carried by the transport layer (D2 F-04), in octets; the verified decoder receives one byte-aligned packet at a time, consumes exactly one output vector, returns the number of bits consumed and checks that the remaining bits are padding (green book 3.3.6 a to c). |  |
| **Loss and corruption detection** | sequence counting and error detection are provided by the layers below (D2 F-01); the verified decoder receives the number of lost packets as an input. |  |
| **Parameter scheduling** | autonomous injection of p_t, f_t, r_t and R_t, and safe defaults after reset (R-SYS-10, R-OPS-01 to R-OPS-03); the verified encoder takes the flags as inputs at each step. |  |
| **Multiplexing** | the presentation layer of red book clause 6 adapted to space packets and APIDs (R-SYS-15), the stream manager and the per-stream state allocation (R-SYS-02). |  |
| **Monitoring and control** | interfaces, events and metrics (R-IF-01 to R-IF-03). |  |
| **Integration layer** | the C shim around the generated code, the Python interop and the coupling with the test harness SW1. |  |
| **Performance and footprint** | timing, CPU load, code size and state size on the LEON3 target (R-PERF-01, R-PERF-02, R-RES-01), by measurement and static analysis of the generated C. |  |
| **Code generation toolchain** | the Stainless C code generator and the C compiler are trusted; the generated C is cross-validated against the Scala reference on the shared test vectors (proposal 2.3). |  |
| **Compression performance** | compression ratio and parameter optimisation (D4, D9). |  |

## Functional Specification

### Parameters

The encoder state at step t is one immutable record, `CompressionState`. It groups two kinds of fields: the fixed configuration of the stream (F, M_0, R_t) and the derived state carried from one step to the next (t, I_(t-1), B_(t-1), M_(t-1), the change-vector history, C_t and the history of previous new-mask flags needed for c_t). The user-specified flags of a step (p_t, f_t, r_t) are inputs of `Encoder.step`, not part of the record. The well-formedness of a state is `paramsInvariant`; it is the invariant of the `CompressionState` class (expressed with the `require`), so a state can only be built when it holds, and the step has to re-establish it (property _State invariant_).

```scala
case class CompressionState(
    t: BigInt,
    f: BigInt,
    m0: List[Boolean],
    robustnessT: BigInt,
    pts: List[Boolean],
    ct: BigInt,
    itMinusOne: List[Boolean],
    btMinusOne: List[Boolean],
    mtMinusOne: List[Boolean],
    dts: List[List[Boolean]]
) {
  require(paramsInvariant(t, f, m0, robustnessT, pts, ct, itMinusOne, btMinusOne, mtMinusOne, dts))
  ...
}
```

```scala
def paramsInvariant(
    t: BigInt,
    f: BigInt,
    m0: List[Boolean],
    robustnessT: BigInt,
    pts: List[Boolean],
    ct: BigInt,
    itMinusOne: List[Boolean],
    btMinusOne: List[Boolean],
    mtMinusOne: List[Boolean],
    dts: List[List[Boolean]]
): Boolean = {
  (t >= 0)
  && (1 <= f && f <= BasicEncodingFunctions.MAX_F)
  && (m0.size == f)
  && (0 <= robustnessT && robustnessT <= 7)
  && (if t <= robustnessT + ct then pts.size == t else pts.size == robustnessT + ct) // pts is the history of the previous new-mask flags p_{t-1}, p_{t-2}, ... over the "R_t + C_t" window (the current p_t is an input of the step), and therefore must be updated at every time step.
  && (0 <= ct && ct <= min(t, 15) - robustnessT)
  // ct is the number of consecutive all-zero D_t's, starting right after the robustnessT
  // window and counting backwards in time (dts is newest-first).
  && (ct == dts.drop(robustnessT).takeWhile(isAllZero).size)
  && (itMinusOne.size == f)
  && (btMinusOne.size == f && (if t == 0 then btMinusOne == build0(f) else true))
  && (mtMinusOne.size == f && (if t == 0 then mtMinusOne == m0 else true))
  && (dts.forall(dv => dv.size == f) && dts.size == min(t, 15)) // last min(t,15) D_t's, newest first: enough history to compute both X_t (first robustnessT of them) and C_t (5.3.2.2, eq. 14)
}
```

### Encoder and Decoder

```scala
object Encoder {
  def step(
      params: CompressionState,
      it: List[Boolean],
      pt: Boolean,
      ft: Boolean,
      rt: Boolean
  ): (List[Boolean], CompressionState)
}
```

```scala
object Decoder {
  def step(
      st: DecompressionState,
      ot: List[Boolean]
  ): (Option[List[Boolean]], DecompressionState)
}
```

## Imperative Implementation

## Properties

### Basic Encoding Functions

### State Invariant

### Validity Checks

### Round-Trip

### Round-Trip (multiple steps)

### Refinement

### Lifted Properties

### Imperative Safety

### Output Size Bound (stretch)

### Robustness (stretch)

### Recovery (stretch)

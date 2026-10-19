# Stainless Verification Workflow

General instructions for working on any Scala project verified with
[Stainless](https://github.com/epfl-lara/stainless) — a formal verifier for a
functional subset of Scala that checks preconditions, postconditions, and
termination via SMT solvers.

## Running verification

**First, check whether a `verify.sh` (or similarly named wrapper script, e.g.
in the project root or a `scripts/` dir) already exists.** If it does, use it
rather than inventing your own invocation — it typically encodes the right
file set, config file, and flags for this project. If one exists but you're
unsure what it does, read it before running it; it's usually a one-liner
wrapping the `stainless` CLI. Likewise check for a `stainless.conf` (or
`.stainless.conf`) — Stainless picks this up automatically, and it's where a
project pins solvers, timeouts, and other defaults.

If there's no wrapper script, invoke the CLI directly:

```bash
stainless path/to/File.scala [path/to/Other.scala ...] [OPTIONS]
```

Always start with `stainless --help` to see the options available in the
installed version — flags and defaults do change between versions, so don't
assume this doc is exhaustive or current for the exact build in use.

### Options worth knowing

- `--functions=f1,f2,...` — only check the named functions (plus whatever
  they depend on). **Use this constantly while iterating.** Full-project
  verification re-checks every VC in every file and can be slow; almost every
  edit only affects a handful of functions. Narrow to what you touched (and
  its direct callers) while debugging, then do a full run before considering
  the change done.
- `--watch` — re-run verification automatically when a source file changes.
  Good for a tight edit/verify loop.
- `--config-file=FILE` — explicit path to a config file, if not using the
  default `stainless.conf`/`.stainless.conf` discovery.
- `--timeout=N` — per-VC solver timeout in seconds. A VC that fails with
  `unknown` (timeout) is not the same as one Stainless proves `invalid` — see
  debugging below before reaching for this.
- `--solvers=s1,s2,...` — which SMT backends to try per VC (e.g. `nativez3`,
  `smt-z3`, `smt-cvc5`, `princess`); a VC only needs one solver to succeed.
  Trying multiple solvers can turn a timeout into a pass without changing the
  proof at all.
- `--vc-cache` / cache directory — Stainless caches VC results keyed by
  content, usually under `.stainless-cache/`. If results look stale or
  inconsistent with a change you just made, try clearing the cache directory
  before assuming the proof itself is broken.
- `--batched` — process the whole given program together instead of doing
  per-file dependency analysis. Needed when functions/lemmas across files
  call each other and you want them checked as one unit.
- `--check-measures` / `--infer-measures` — control termination/measure
  checking for recursive functions (see termination below).
- `--debug=verification,smt` (and other component names listed by
  `stainless --help`) — turn on detailed internal logging, including the
  actual SMT-LIB queries sent to the solver. Reach for this when you're stuck
  on *why* a VC isn't discharging, not as a first step.
- `--json=report.json` — dump the verification/termination report to a file,
  useful for diffing results across runs or feeding into other tooling.

Run `stainless --help` again whenever you're unsure an option exists or what
its current default is — don't guess at flag names.

## Core idioms

- **Contracts**: `require(...)` expresses preconditions, `.ensuring(res =>
  ...)` expresses postconditions. A complex invariant that recurs across many
  functions or a case class's methods is usually worth factoring into its own
  boolean predicate function, asserted in every `require` that needs it,
  rather than duplicating the condition inline everywhere.
- **Ghost code**: proof-only helpers that have no runtime effect are
  annotated `@ghost`. A typical "lemma" is `@ghost def lemmaXxx(...): Unit =
  { ... }.ensuring(_ => <property>)` — it returns `Unit`, and its only
  purpose is to make `<property>` available as a known fact to the caller
  after it's invoked. Naming convention across most Stainless codebases is
  `lemma<WhatItProves>`.
  - `@opaque` prevents the solver from unfolding a function's/lemma's body at
    call sites — callers only see its contract, which keeps VCs small and
    proofs stable. Use it on any non-trivial lemma or function whose body
    isn't itself needed by callers.
  - `@inlineOnce` controls whether/how a call gets inlined during
    extraction, as distinct from whether the solver unfolds it logically.
  - Small, trivial helpers can skip both annotations if inlining their body
    doesn't blow up VC size — but default to adding them on anything with a
    recursive or branching body.
  - Call a lemma purely for its `ensuring` side effect: the statement
    `lemmaFoo(x)` on its own line (sometimes wrapped as `ghostExpr(lemmaFoo(x))`)
    brings its postcondition into scope for the solver at that point. Stainless
    does **not** search for and apply lemmas automatically — if a fact isn't
    obviously derivable from what's already in scope, you must invoke the
    lemma that proves it, at the point you need it.
- **Termination**: every recursive function needs to provably terminate.
  Stainless will often infer a measure automatically; when it can't (mutual
  recursion, recursion on a value derived from an argument rather than the
  argument itself, etc.), add an explicit `decreases(expr)` as the first
  statement in the body, where `expr` is something that strictly decreases on
  every recursive call.
- **Refinement types inline**: a local `val` can carry a refinement directly,
  e.g. `val x: List[T] with x.size == n = ...`, to pin a fact about an
  intermediate result without wrapping it in a separate function with its own
  `.ensuring`. Reach for this when the fact is only needed locally.
- **`stainless.lang.StaticChecks.*`**: importing this makes `require`/
  `.ensuring` behave as ordinary runtime-checked assertions when compiled
  with plain `scalac`/`sbt` (outside Stainless), so contracts stay meaningful
  even when code runs un-verified.
- **Tie proofs back to a spec**: when the code implements an external
  specification (a standard, an RFC, an algorithm from a paper), reference
  the exact section/equation in a doc comment next to the function or lemma
  it corresponds to. This is what makes a proof reviewable against the
  original spec rather than just against itself.

## Debugging a failing or slow verification condition (VC)

1. **Isolate it**: re-run with `--functions=<theFunction>` (or whatever
   subset is relevant) so you're not waiting on or reading through unrelated
   VCs.
2. **Distinguish a real counterexample from a timeout.** Stainless reports a
   VC as `invalid` with a counterexample when a solver actually disproved it
   — trust that; the property is either wrong or missing a precondition that
   rules the counterexample out. It reports `unknown` when no solver decided
   it within the timeout — that's inconclusive, not a disproof.
3. **For a timeout, help the solver before reaching for `--timeout` or
   `--solvers`:**
   - Add intermediate `assert(...)` statements for sub-facts you believe
     hold. This breaks one hard VC into several easier ones and pinpoints
     exactly which step the solver can't get through.
   - If a needed fact is proven by some lemma that's in scope but not
     obviously applied, call that lemma explicitly right before the point you
     need its postcondition — remember Stainless never does this
     automatically.
   - Check `@opaque`/inlining on the functions involved: an unopaque function
     with a large or recursive body can flood the VC with irrelevant detail
     instead of a clean contract. Making it opaque (or, conversely, briefly
     un-opaque to see what the solver is missing) can clarify things.
   - Only after the above, consider trying a different/additional solver
     (`--solvers=...`) or a longer `--timeout` — these can mask a proof gap
     as readily as they fix a genuine solver weakness, so treat a pass after
     only bumping these numbers with some suspicion.
4. **Turn on `--debug=verification,smt`** to see the actual SMT-LIB query and
   solver response when you're stuck on *why*, not just *that*, a VC fails.
5. **Rule out a stale cache**: if results seem inconsistent with the current
   source (a fixed VC still reported failing, or vice versa), clear the
   Stainless cache directory and re-run before digging further into the
   proof itself.
6. **Tooling crash vs. proof failure**: a raw Java/Scala stack trace (as
   opposed to a reported `invalid`/`unknown` VC) usually means a solver
   binary is missing, misconfigured, or incompatible with the installed
   Stainless/Inox version — check that every solver named in `solvers=`
   (in config or on the command line) is actually installed and on `PATH`
   before assuming the spec itself is at fault.
7. Once the isolated function verifies, re-run including its callers (or do a
   full run) before calling the change done — fixing one lemma can change
   what's available to or expected by callers, and can break a previously
   passing proof elsewhere.

## General rules for editing verified code

- Never weaken a `require` or strengthen an `.ensuring` just to make a VC
  pass without first understanding why the original contract was stated that
  way — check git history and any referenced spec/section first. A contract
  that's easy to satisfy is often a contract that no longer states anything
  useful.
- Don't quietly add `@extern`, disable a solver, or raise a timeout as a way
  to "make verification green" without flagging it — these are ways of *not*
  proving something, not ways of proving it, and should be called out
  explicitly if used.
- If the project has non-verified code that's meant to behave like the
  verified spec (e.g. a faster hand-written implementation, a generated
  binding), look for and run whatever cross-check/differential test exists
  between them after changing the verified side — Stainless proves the
  contracts hold, but it can't catch a spec whose contracts verify yet no
  longer express the intended behavior (e.g. an accidentally vacuous
  postcondition).

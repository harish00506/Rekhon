<!--
  Why:  CLAUDE.md §5 — any decision or deviation from the SRS needs an ADR.
  What: issue 12.1 — the shared engine test harness: its guards, its seeded property driver, and why
        the snapshot update never writes the fixture.
  Result: a reader can see why the harness is text not JSON, why an absent key is null not zero, and
          why regeneration is deliberately a two-step manual action.
  Changelog: 2026-10-02 — Created.
-->

# ADR-0064 — The golden harness refuses to be vacuous, and never rewrites its own fixture

**Status:** Accepted · **Date:** 2026-10-02 · **Issue:** 12.1 · **SRS:** §21.5, P-08

## Context

§21.5 asks every engine for golden-file tests, property tests and seeded-determinism tests. By this
issue twenty-four engines had them — and **each had written its own fixture reader**, because
`:domain:*` is pure Kotlin with no serialisation dependency by design (ARC-002) and so cannot simply
parse JSON. Twenty-eight fixture files, ~24 readers.

Twenty-four readers are twenty-four chances to get wrong the one thing a test cannot check about
itself: *that the fixture was read at all.* A typo'd path, an emptied file, or a `records()` returning
nothing turns a frozen gate into a loop over zero cases that passes in silence. This repository has
found five vacuous gates already (1.5, 7.2, 11.5, 11.6, 11.7) — none of them in a golden file yet, and
that is luck rather than design.

## Decision

### 1 · The harness lives in `:core:common`'s test fixtures

No new module and no new dependency. `java-test-fixtures` is already applied there and already
publishes `FakeClock`, `FakeIdGenerator` and `TestDispatchers`; the harness joins them. An engine adds
`testImplementation(testFixtures(project(":core:common")))`, which is **test-only**, so ARC-002 is
untouched — the engine itself still depends on nothing but `:core:model` and `:core:common`.

### 2 · Text, not JSON — and the marker is a line

A golden file is read by people far more often than by code, so the format leaves room for the
paragraph explaining what the numbers mean: everything before the first `===` line is ignored. JSON
would have required a serialisation dependency in every engine, which ARC-002 forbids.

**The record marker is a *line* beginning `===`, not the substring anywhere.** The first implementation
split on the substring, and this harness's own sample fixture broke it — the prose explaining the
format mentions `===` inline, and two paragraphs became records. Found by writing the test first.

### 3 · Every ambiguous input is an error, not an empty result

The harness is biased one way throughout, because the cost is asymmetric: a false alarm costs a reader
five minutes, and a missed one means a test that asserts nothing for years.

| Situation | Decision |
|---|---|
| Resource missing | Error. An empty list would make the gate pass vacuously |
| No records in the fixture | Error |
| A line inside a record is not `# key=value` | Error — a line meant to be an assertion that cannot be read is a dropped expectation |
| `required` on an absent key | Error, naming the key **and** the record |
| `longOrNull` on an absent key | `null`, **never `0`** — zero is a legitimate amount, and conflating them lets a dropped field pass as a deliberate zero, which is exactly the diff a golden file exists to show |
| `boolean` reads anything but `true`/`false` | Error. `toBoolean()` maps every typo to `false`, so `# flag=ture` would silently assert the opposite |

### 4 · Seeded property cases, with the seed in the failure

`SeededCases(seed, count)` drives a property test. The seed is a fixed literal in the test, never a
clock (P-08): an unseeded property test fails on Tuesday, passes on Wednesday, and leaves nobody able
to reproduce either. The failure message is the actual deliverable — it names the seed and the case
index and says how to re-run just that far.

**Each case gets its own `Random`,** derived from the sequence rather than shared. That is what makes
"re-run with `count = index + 1`" reproduce the failure: case 7 draws the same values whether or not
cases 1–6 consumed any. A shared source breaks it silently, and a mutation proved no test caught that
until one was added.

`count = 0` is rejected: a property test over no cases asserts nothing.

### 5 · `assertDeterministic` runs the subject twice

P-08 promises fixed input → fixed output, and that promise breaks quietly: a `hashCode` in an
iteration order, a `System.currentTimeMillis()` in a helper, a global `Random`. A determinism test that
runs the engine **once** proves nothing at all. This runs it twice at the same seed with a fresh source
each time and compares by value.

### 6 · The snapshot update never writes the fixture

A golden file has to be regenerable, or the first large-but-correct change makes updating thirty
records by hand unpleasant enough that somebody loosens the assertion instead — and a loosened golden
test is worse than none, because it still looks like a gate.

The obvious implementation is a flag that rewrites the fixture in place. **That would be the most
dangerous thing in this repository.** Set once in CI — an env var, a stray `gradle.properties`, a
copied command line — every golden test in the project would pass for ever, rewriting its expectations
to match whatever the code had started doing. Five vacuous gates have been found here; none came with
a switch, and none should.

So `GoldenSnapshot.propose` writes a candidate under `build/`, **fails the test**, and prints the `cp`
command. A human performs the overwrite and the diff goes through review. `GoldenSnapshotTest` asserts
the fixture is untouched, and a mutation that overwrites it fails three tests.

### 7 · One engine adopts it; twenty-three are not migrated in bulk

`:domain:engines:card` is the reference: 41 lines of hand-written reader deleted, the fixture unchanged,
all fifteen records still asserted, and the inherited guards demonstrated to bite (hiding the fixture,
then emptying it, each fails the card test rather than passing it).

The others stay as they are **for now, deliberately.** Their fixture formats genuinely differ —
block-delimited `# key=value`, space-separated pairs, custom separators — and a sweep large enough to
unify twenty-three gates would risk every one of them for no behavioural gain. The documented guidance
is to migrate an engine when already editing its golden test. Stated so the remaining duplication is a
recorded decision rather than an oversight.

## Alternatives rejected

| Alternative | Why not |
|---|---|
| JSON fixtures | Needs a serialisation dependency in every `:domain:*` module — ARC-002 forbids it. |
| A new `:core:testing` module | `:core:common` already publishes test fixtures; a module for three files is overhead. |
| kotest or jqwik for property tests | A new dependency needing a `DECISIONS.md` row, for a seeded loop the standard library already provides. |
| Split the fixture on the `===` substring | The first attempt; the harness's own sample fixture broke it, because prose mentions the marker. |
| `toBoolean()` for booleans | Maps every typo to `false`, so a fixture could silently assert the opposite of its intent. |
| `longOrNull` defaulting to `0` | A dropped field would pass as a deliberate zero. |
| A `-Dgolden.update=true` flag that rewrites fixtures | One stray setting in CI and every golden test in the project self-approves for ever. |
| Migrate all twenty-four engines now | Their formats differ; the sweep would risk twenty-three working gates for no behavioural gain. |

## Consequences

- A new engine inherits the anti-vacuity guards instead of re-deriving them, and its golden test reads
  as assertions rather than string handling.
- A property failure is reproducible from its message alone.
- Regenerating a fixture is one command and always a reviewed diff.
- **Twenty-three engines still carry their own reader.** Migration is opportunistic and documented; the
  duplication is a recorded decision.
- The harness is test-only, so no engine's runtime dependencies changed.

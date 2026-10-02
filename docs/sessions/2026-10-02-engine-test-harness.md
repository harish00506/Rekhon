<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 12.1 — the shared engine test harness, and the two documented promises a mutation
        found had no gate.
  Result: a reader can see why the harness errors instead of returning empty, why regeneration is
          manual, and why 23 engines were left alone.
  Changelog: 2026-10-02 — Created.
-->

# 2026-10-02 — A test harness that refuses to pass for nothing (issue 12.1, ADR-0064)

**Branch:** `feature/12-1-engine-golden-file-property-test-harness` off `dev` (`eba3d3f`)
**Versions:**
- **VERSION** 0.10.14 → **0.12.1** — Epic 12 starts its own series; `0.11.x` is deliberately unused
- **versionCode** 58 → 59
- **Schema** 29 → **29 (unchanged — this issue ships no runtime code)**

---

## 1 · Decisions this session

The full argument for each is in [ADR-0064](../adr/0064-the-golden-harness-refuses-to-be-vacuous-and-never-rewrites-its-own-fixture.md).

- **The harness lives in `:core:common`'s test fixtures.** No new module, no new dependency, and
  test-only — so ARC-002 holds and no engine's runtime dependencies changed.
- **Text, not JSON.** `:domain:*` has no serialisation dependency by design, and a golden file is read
  by people far more often than by code, so the format leaves room for the prose that explains the
  numbers. Everything before the first `===` line is ignored.
- **Every ambiguous input is an error, never an empty result.** A missing resource, a fixture with no
  records, an unparseable line, an absent `required` key. The asymmetry justifies it: a false alarm
  costs a reader five minutes; a missed one means a test asserting nothing for years.
- **An absent key is `null`, never `0`.** Zero is a legitimate amount; conflating the two lets a dropped
  field pass as a deliberate zero — exactly the diff a golden file exists to show.
- **`boolean` refuses anything but `true`/`false`**, because `toBoolean()` maps every typo to `false`,
  so `# flag=ture` would silently assert the opposite of its author's intent.
- **Each property case gets its own `Random`.** That is what makes re-running with `count = index + 1`
  reproduce a failure; a shared source would shift every later case. The failure message carries the
  seed, because a property failure you cannot reproduce is a flake, not a finding.
- **`assertDeterministic` runs the subject twice.** A determinism test that runs the engine once proves
  nothing, and that is how a clock read or a global `Random` survives review.
- **The snapshot update never writes the fixture.** It writes a candidate under `build/` and fails with
  a `cp`. A flag that rewrote fixtures in place would be the most dangerous thing in this repository:
  set once in CI, every golden test in the project would pass for ever, rewriting its expectations to
  match whatever the code had started doing. Five vacuous gates have been found here; none came with a
  switch.
- **Twenty-three engines are deliberately not migrated.** Their fixture formats genuinely differ, and a
  sweep large enough to unify them would risk twenty-three working gates for no behavioural gain.
  Recorded as a decision so the remaining duplication is not read as an oversight.

**What this found.**

1. **Two documented promises with no gate**, both from mutations that survived:
   - `boolean` falling back to `toBoolean()` — the doc comment argued that `ture` would assert the
     opposite, and nothing tested it.
   - every case sharing one `Random` — all the existing tests exercised `map`, never `forEach`'s
     per-case isolation, which is the thing that makes a failure reproducible.
2. **The first parser broke on the harness's own sample fixture.** Splitting on the `===` substring
   turned two paragraphs of explanatory prose into records, because the prose mentions the marker.
   Markers are now anchored to the start of a line — found only because the test came first.
3. **The survey corrected two assumptions.** "There are no golden files yet" (there are 28, as `.txt`,
   because ARC-002 forbids a serialisation dependency) and "one parser can be retrofitted to all 24"
   (the formats differ). Both changed the scope before any code was written.
4. **A version-numbering drift, flagged rather than papered over.** The changelog's convention is
   `0.<epic>.<issue>`, and Epic 11 shipped as `0.10.8`–`0.10.14` under the Epic 10 heading. Epic 12
   starts `0.12.x` with its own heading and `0.11.x` is left unused, with the reason written down —
   `VERSION` and versionCode 51–58 are already in commits, so renumbering would make the changelog
   disagree with the history it documents.

## 2 · Flow changed this session

A test path — `FLOW.md` §2.27 — where the interesting decisions are all refusals:

```
any engine's :test → testFixtures(:core:common)
├─ GoldenFixture.load    missing · empty · malformed line · absent required  ⇒ ERROR
│  └─ GoldenRecord       longOrNull absent ⇒ null NEVER 0 · boolean strict
├─ SeededCases           each case its OWN Random · the seed in the message · count 0 ⇒ ERROR
├─ assertDeterministic   runs the subject TWICE (P-08)
└─ GoldenSnapshot        candidate under build/ + FAIL; never touches src/test/resources
```

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `core/common/testFixtures/GoldenFixture.kt` (new) | the shared golden reader and `GoldenRecord`, erroring on every ambiguous input |
| `core/common/testFixtures/SeededCases.kt` (new) | reproducible property cases and `assertDeterministic` |
| `core/common/testFixtures/GoldenSnapshot.kt` (new) | regeneration that proposes and fails, never overwrites |
| `core/common/src/test/{GoldenFixture,SeededCases,GoldenSnapshot}Test.kt` (new) | 25 tests, 12 mutations |
| `core/common/src/test/resources/golden/harness-sample.txt` (new) | the sample fixture that disproved the first parser |
| `core/common/build.gradle.kts` | the module's own tests see its test fixtures |
| `domain/engines/card/.../CardGoldenTest.kt` | adopts the harness; 41 lines of reader gone, sentinels kept local |
| `domain/engines/card/build.gradle.kts` | the test-only harness dependency |
| `docs/testing/engine-test-harness.md` (new) | the guide, including the snapshot-update workflow |
| `docs/adr/0064-…`, `DECISIONS.md`, `FLOW.md` §2.27, `CHANGELOG.md`, `docs/memory.md`, `VERSION` | the records |

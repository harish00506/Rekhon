<!--
  Why:  issue 12.1 — AC2 asks for the snapshot-update workflow to be documented, and AC1 for a
        harness any engine can use. This is the page an engine author reads.
  What: how to write a golden-file test, a property test and a determinism test with the shared
        harness, and how to regenerate a fixture.
  Result: a new engine inherits the guards instead of re-deriving them.
  Changelog: 2026-10-02 — Created for issue 12.1.
-->

# The engine test harness

`:core:common`'s **test fixtures** carry the shared harness §21.5 asks for. Add it to an engine with:

```kotlin
testImplementation(testFixtures(project(":core:common")))
```

Test-only, so **ARC-002 is untouched** — the engine itself still depends on nothing but `:core:model`
and `:core:common`.

Reference adoption: [`:domain:engines:card`](../../domain/engines/card/src/test/kotlin/com/aicfo/domain/engines/card/CardGoldenTest.kt).

---

## 1 · Why this exists

By issue 12.1, twenty-four engines each had a golden-file test and **each had written its own fixture
reader** — because `:domain:*` has no serialisation dependency by design (ARC-002), so none of them
could simply parse JSON. Twenty-four readers are twenty-four chances to get wrong the one thing a test
cannot check about itself: **that the fixture was read at all.** A typo'd resource path, an emptied
file, or a `records()` that returns nothing turns a frozen gate into a loop over zero cases that
passes in silence.

The harness exists so that guard is written once. Everything in it is biased the same way: **a missing
or malformed input is an error, never an empty list.**

## 2 · Golden-file tests

The format is text, not JSON. A golden file is read by people far more often than by code, so it
leaves room for the prose explaining what the numbers mean — everything before the first `===` line is
ignored.

```
Amounts are PAISE (MNY-001). Ratios are integer BASIS POINTS (MNY-002): 3500 is 35%.

=== the statement day itself
# label=cycle walk 1
# today=2026-03-01
# outstanding=5000000
# expect_utilisation_bps=2500
# expect_alerts=CARD_UTILISATION_HIGH
```

```kotlin
private fun records(): List<GoldenRecord> = GoldenFixture.load(this, "/golden/card.txt")

@Test
fun `every golden record still holds`() {
    records().forEach { record ->
        val result = engine.evaluate(inputFrom(record))
        assertWithMessage(record.label())           // names the case in the failure
            .that(result.utilisationBps)
            .isEqualTo(record.int("expect_utilisation_bps"))
    }
}
```

Accessors: `required`, `optional`, `long`, `longOrNull`, `int`, `intOrNull`, `boolean`, `list`,
`label()`, `heading`, `keys`.

**What the harness refuses to do**, each with a test:

| Situation | Result |
|---|---|
| The resource is missing | **Error.** Returning an empty list would make the gate pass vacuously |
| The fixture has no `===` records | **Error** |
| A line inside a record is not `# key=value` | **Error.** A line meant to be an assertion that cannot be read is a dropped expectation |
| A key is absent and read with `required` | **Error**, naming the key *and* the record |
| A key is absent and read with `longOrNull` | **`null`**, never `0` — zero is a legitimate amount, and conflating them lets a dropped field pass as a deliberate zero |
| `boolean` reads anything but `true`/`false` | **Error.** `toBoolean()` maps every typo to `false`, so `# flag=ture` would silently assert the opposite |

**A marker is a line beginning `===`, not the substring.** The harness's own sample fixture proved why:
its prose mentions `===` inline, and a substring split turned two paragraphs into records.

**Module-specific sentinels stay in the module.** `:domain:engines:card` writes `none` for "absent",
which is not zero (P-03); its `bpsOrNull`/`alerts` accessors remain in `CardGoldenTest` because the
harness has no opinion on one fixture's conventions.

## 3 · Property tests

```kotlin
@Test
fun `a split always sums to the original`() {
    SeededCases(seed = 20_261_002, count = 500).forEach { random ->
        val total = Money(random.nextLong(0, 10_000_000))
        val parts = total.split(random.nextInt(2, 9))
        assertThat(parts.sumOf { it.minor }).isEqualTo(total.minor)
    }
}
```

- **The seed is a fixed literal, never a clock.** P-08 — randomness comes only from an injected,
  seedable source. A property test on an unseeded source fails on Tuesday, passes on Wednesday, and
  leaves nobody able to reproduce either.
- **A failure names the seed and the case index**, and tells you how to re-run just that far. That
  message is the deliverable: a property failure you cannot reproduce is a flake.
- **Each case gets its own `Random`,** derived from the sequence. So case 7 draws the same values
  whether or not cases 1–6 consumed any — which is what makes re-running with `count = index + 1`
  reproduce the failure. A shared source would break that silently, and there is a test for it.
- `count = 0` is rejected: a property test over no cases asserts nothing.

## 4 · Determinism tests (P-08)

```kotlin
@Test
fun `the engine is deterministic`() {
    assertDeterministic(seed = 7) { random -> engine.evaluate(randomInput(random)) }
}
```

Runs the subject **twice** with a fresh source at the same seed and compares. This is the only thing
that separates a genuinely deterministic engine from one reading a clock, a global `Random`, or an
iteration order that depends on a hash code — a "determinism test" that runs the engine once proves
nothing. Compare by value, so return a data class, value class or collection.

## 5 · Regenerating a fixture — the snapshot-update workflow

When a change is large but correct, updating thirty records by hand is unpleasant enough that someone
will loosen the assertion instead. So the harness can regenerate — but **it never writes the fixture.**

```kotlin
if (rendered != File(FIXTURE_PATH).readText()) {
    GoldenSnapshot.propose(
        name = "card.txt",
        content = rendered,
        candidateDirectory = File("build/golden-update"),
        fixturePath = "domain/engines/card/src/test/resources/golden/card.txt",
    )
}
```

The candidate lands under `build/`, the test **fails**, and the message gives you the command:

```
cp .../build/golden-update/card.txt domain/engines/card/src/test/resources/golden/card.txt
```

**Why it is not automatic, and must never become automatic.** A flag that rewrote fixtures in place
would be the most dangerous thing in this repository: set once in CI — an env var, a stray
`gradle.properties`, a copied command line — **every golden test in the project would pass for ever**,
rewriting its expectations to match whatever the code had started doing. This repository has already
found five gates that could not fail; none of them came with a switch. A human runs the `cp`, and the
diff goes through review like any other change. `GoldenSnapshotTest` asserts the fixture is untouched.

## 6 · Adopting it in an existing engine

Twenty-three engines still carry their own reader. They are **not** being migrated in bulk: their
fixture formats differ (block-delimited `# key=value`, space-separated pairs, custom separators), and
a sweep large enough to unify them would risk the gates it touched for no behavioural gain. Migrate an
engine when you are already editing its golden test, and keep the module's own sentinels local. The
card migration is the worked example: 41 lines of reader deleted, the fixture unchanged, and all
fifteen records still asserted.

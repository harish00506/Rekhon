<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 12.5 — the release train and its gate, and the two drifts reconstructing the history
        exposed.
  Result: a reader can see why the gate reads the issue id rather than the heading, why both drifts
          were left in place, and why there is still no tag.
  Changelog: 2026-10-03 — Created.
-->

# 2026-10-03 — The release train, and what it found in the past (issue 12.5, ADR-0068)

**Branch:** `feature/12-5-ci-gates-release-train` off `dev` (`d82a0a7`)
**Versions:**
- **VERSION** 0.12.4 → **0.12.5** — bumped by the new train itself
- **versionCode** 62 → 63
- **Schema** 29 → **29 (unchanged)**

**Epic 12 completes with this issue.**

---

## 1 · Decisions this session

The full argument for each is in [ADR-0068](../adr/0068-the-release-train-checks-four-files-and-found-two-drifts-already-in-the-history.md).

- **AC1 was already satisfied**, and saying so was the first useful thing. Every gate — coverage, AI
  eval, screenshots, dependency scan, release hardening, the emulator E2E — was wired by issues 1.1
  through 12.4. This issue adds the tenth and documents all of them in one page.
- **A gate over four files.** `VERSION`, `versionCode`, `CHANGELOG.md` and a reconstructed ledger have
  to agree, and nothing had ever checked that. It runs **first** in CI: seconds, and a bad bump should
  fail before the emulator job rather than after it.
- **The rule compares the issue id, not the heading** — and the first implementation did not. More
  below.
- **A ledger reconstructed from git**, not written from memory: every commit that changed `VERSION`,
  read with `app/build.gradle.kts` at the same commit. The gate reads it because a shallow CI clone
  cannot answer "what was the last `versionCode`?".
- **The train writes everything except the release notes.** A generated line saying "issue 12.5
  shipped" is worse than none, because it looks like documentation.
- **It does not tag.** Tags belong to a promotion, where `stage`/`main` are protected and a human is
  already in the loop. A tag on `dev` would assert a release that did not happen.

**What this found.**

1. **The gate's own rule was wrong first, and only the real data showed it.** Comparing an entry's
   minor with its epic heading's minor passes `### [0.10.11]` under `## [0.10.0]` happily — both say
   10. What was actually wrong is that it delivered **Issue 11.4**. Running the implementation against
   this repository's own `CHANGELOG.md` is what exposed it; the unit tests were all green. The rule now
   parses the issue id out of the title, and the old check is kept with a test pinning its blind spot.
2. **Epic 11's seven issues shipped in the `0.10.x` series.** The workflow says an epic starts with a
   minor bump, so this is a documented-rule violation rather than a style preference. Seven releases,
   nobody noticed — I continued it myself for five of them before flagging it in 12.1.
3. **Ten versions shipped with a `versionCode` that did not increase** — `0.3.10`, `0.3.11` and `0.4.0`
   all carried 18. Play rejects such an upload *after* a release is cut.
4. **Both left exactly as they are**, marked and explained. Renumbering released versions would make
   the changelog disagree with the commits it documents, and none of the stalled codes was ever
   uploaded — this app has not shipped. Leaving a known-wrong history in place is a decision, which is
   why it is written down rather than quietly fixed.
5. **One mutation was unkillable until the test got better.** Removing the positive-`versionCode` guard
   changed no behaviour — the monotonic check rejects 0 anyway. The guard earns its place through a
   clearer message, so the test now pins the message rather than the count. A second mutation
   (loosening the SemVer regex) is genuinely equivalent and is recorded as such rather than counted.

## 2 · Flow changed this session

`FLOW.md` §2.31:

```
scripts/release.py <patch|minor|major>
├─ VERSION · versionCode (one above the ledger's HIGHEST, not its last) · the ledger row
└─ prints what is left: the changelog entry and, at promotion, the tag

./gradlew verifyReleaseMetadata            runs FIRST in CI
├─ SemVer · exactly one entry · versionCode positive, monotonic, stable once released
└─ the entry's **Issue N.x** must have N == the version's minor
   ⇣ not the heading's minor — that rule called the drifted releases consistent
```

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `scripts/verify_release_metadata.py` (new) | the four-file gate, reporting every problem rather than the first |
| `scripts/release.py` (new) | the bump; writes no release notes and no tag, deliberately |
| `scripts/test_release_metadata.py`, `scripts/test_release_bump.py` (new) | 32 tests, 8 mutations |
| `docs/releases.md` (new) | 70 version/versionCode rows reconstructed from git, 10 marked ⚠ |
| `docs/releases-process.md` (new) | every blocking gate, the bump, the tag, and both historical drifts |
| `build.gradle.kts` | `verifyReleaseMetadata`, with its four inputs declared |
| `.github/workflows/ci.yml` | the gate runs first |
| `docs/adr/0068-…`, `DECISIONS.md`, `FLOW.md` §2.31, `CHANGELOG.md`, `docs/memory.md`, `VERSION` | the records |

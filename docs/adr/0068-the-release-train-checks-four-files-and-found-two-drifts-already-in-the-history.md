<!--
  Why:  CLAUDE.md §5 — any decision or deviation from the SRS needs an ADR.
  What: issue 12.5 — the release train, the metadata gate, and the two historical drifts
        reconstructing the history exposed.
  Result: a reader can see why the gate reads the issue id rather than the heading, why the two
          drifts were left in place, and why tagging is not automated.
  Changelog: 2026-10-03 — Created.
-->

# ADR-0068 — The release train checks four files, and found two drifts already in the history

**Status:** Accepted · **Date:** 2026-10-03 · **Issue:** 12.5 · **SRS:** §21.6, §26, SemVer

## Context

§26 and `00-issue-workflow.md` set the versioning rules: `VERSION` is the single source of truth,
`versionName` follows it, `versionCode` increases monotonically, every release gets a `CHANGELOG.md`
entry, and **a new epic is a minor bump**.

All of it was done by hand on every issue, and **nothing checked that the pieces agreed.**

AC1's gates turned out to be largely in place already — build, coverage, lint, screenshots, AI eval,
dependency scan and the emulator E2E were each wired by issues 1.1 through 12.4. The genuine gaps were
the release train itself and the absence of any consistency check.

## Decision

### 1 · A gate over four files

`verifyReleaseMetadata` checks that `VERSION` parses as SemVer, has exactly one changelog entry, that
the entry's issue belongs to the epic its minor names, and that `versionCode` is positive, monotonic,
and unchanged for a version already released. It runs **first** in CI: it costs seconds, and a bad
bump should fail before the emulator job rather than after it.

### 2 · The rule compares the **issue id**, not the heading — and the first version did not

The obvious check is that an entry sits under an epic heading whose minor it shares. That was
implemented first, and running it against this repository's own history showed it calling the
known-drifted releases **consistent** — because `### [0.10.11]` under `## [0.10.0]` does agree by
minor. The thing that was actually wrong is that `0.10.11` delivered **Issue 11.4**.

So the gate parses the issue id out of the entry title and requires its epic to equal the version's
minor. That is what would have caught the drift on the first release instead of the seventh.

Both checks are kept: the heading rule catches a differently-shaped mistake, and the test that pins
the heading rule's blind spot says so explicitly rather than being deleted.

An entry naming no issue is skipped rather than flagged. A hotfix or a hand-written note need not cite
one, and inventing a rule for them would mean editing seventy historical entries to satisfy a checker.

### 3 · A ledger, because git cannot answer the question in CI

`docs/releases.md` records every version and its `versionCode`, reconstructed from git history rather
than written from memory — each commit that changed `VERSION`, read together with
`app/build.gradle.kts` at that same commit. The gate reads the ledger because a shallow CI clone
cannot answer "what was the last `versionCode`?".

### 4 · The train writes everything except the release notes

`scripts/release.py` computes the next version, writes `VERSION`, sets `versionCode` to one above the
ledger's highest, and appends the row. It deliberately does **not** write the changelog entry: that is
the release notes, and a generated line saying "issue 12.5 shipped" is worse than none because it
looks like documentation.

It was dogfooded immediately — this issue's own bump, `0.12.4 → 0.12.5` / code 63, was performed by it.

### 5 · Tagging stays manual, and this repository has no tags

`release.py` prints the `git tag` command and does not run it. Tags belong to a promotion, where
`stage` and `main` are protected and a human is already in the loop (`00-issue-workflow.md` §7), and a
tag on `dev` would assert that something was released when it was not.

**As of this issue there are no tags at all**, because nothing has been promoted past `dev`. That is a
true statement about the project, not a gap in the process, and AC3 is satisfied by the release build
being produced and the tagging step being documented rather than by inventing a release.

## What reconstructing the history found

Both were found the first time anything read the files and compared them. Neither was caught by review.

1. **Epic 11's seven issues shipped as `0.10.8`–`0.10.14`**, under the Epic 10 heading, when the rule
   says an epic starts with a minor bump. Seven releases.
2. **Ten versions shipped with a `versionCode` that did not increase** — `0.3.10`, `0.3.11` and
   `0.4.0` all carried 18, among others. Play rejects such an upload *after* a release has been cut.

**Both are left exactly as they are**, marked in the ledger and explained in `docs/releases-process.md`.
Renumbering released versions would make the changelog disagree with the commits it documents, and
none of the stalled codes was ever uploaded — this app has not shipped. The gate is forward-looking.

Leaving a known-wrong history in place is a decision rather than an omission, which is why it is here.

## Alternatives rejected

| Alternative | Why not |
|---|---|
| Keep bumping by hand and be careful | Two drifts, ten and seven releases long, neither noticed. Care is not a mechanism. |
| Check only that the entry's minor matches its heading | Implemented first; measured against the real history, it calls the drifted releases consistent. |
| Flag every entry that names no issue | Would mean editing seventy historical entries to satisfy a checker. |
| Read the previous `versionCode` from git | A shallow CI clone cannot answer it. |
| Renumber Epic 11 into `0.11.x` | `VERSION` and codes 51–58 are in commits; the changelog would disagree with its own history. |
| Rewrite the ten stalled `versionCode`s | Inventing a past that did not happen, for releases nobody ever uploaded. |
| Generate the changelog entry | It is the release notes. A generated line is worse than none because it looks like documentation. |
| Tag automatically on bump | Tags belong to promotions; a tag on `dev` asserts a release that did not happen. |

## Consequences

- A version bump that disagrees with the changelog or the ledger fails CI in seconds.
- An issue shipped into the wrong epic's series fails on the **first** such release.
- A `versionCode` that does not increase fails before the Play console sees it.
- The release process, every blocking gate, and both historical drifts are documented in one place.
- **Still open, and stated plainly:** nothing has been promoted past `dev` — 29 issues and ~0.6.4 →
  0.12.5 of work — so no tag exists and no release has been cut. That is the project's state, not this
  issue's gap, and it needs a human: `stage` and `main` are protected.

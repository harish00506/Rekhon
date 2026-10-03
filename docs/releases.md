<!--
  Why:  issue 12.5 — the release ledger. `verify_release_metadata.py` reads it to check that a
        new release's versionCode actually increases, which git cannot answer in a shallow CI clone.
  What: every version this repository has produced, with the versionCode it carried.
  Result: a versionCode that does not move fails the build instead of being rejected by Play after
          the release is cut.
  Changelog: 2026-10-03 — Created for issue 12.5, reconstructed from git history.
-->

# Release ledger

Every version this repository has produced and the `versionCode` it carried, **reconstructed from
git history** (each commit that changed `VERSION`, read together with `app/build.gradle.kts` at the
same commit) rather than written from memory.

`scripts/verify_release_metadata.py` reads this file, so a release whose `versionCode` does not
increase fails the build. It is checked here rather than against git because a shallow CI clone
cannot answer the question.

## What this reconstruction found

**10 versions shipped with a `versionCode` that did not move** — marked ⚠ below. Google
Play rejects an upload whose code did not increase, so each of those would have failed at upload
time, after the release was cut and tagged. Nothing in the build checked it; the workflow said
"bump `versionCode` monotonically" and relied on somebody remembering.

They are left as they are. These versions were never uploaded anywhere — this app has not shipped —
so rewriting the history would be inventing a past that did not happen. The gate is forward-looking:
from this release on, a code that does not increase fails `verifyReleaseMetadata`.

| Version | versionCode | |
|---|---|---|
| 0.2.1 | 2 |  |
| 0.2.2 | 3 |  |
| 0.2.3 | 4 |  |
| 0.2.4 | 5 |  |
| 0.2.5 | 6 |  |
| 0.2.6 | 7 |  |
| 0.2.7 | 8 |  |
| 0.3.1 | 9 |  |
| 0.3.2 | 10 |  |
| 0.3.3 | 11 |  |
| 0.3.4 | 12 |  |
| 0.3.5 | 13 |  |
| 0.3.6 | 14 |  |
| 0.3.7 | 15 |  |
| 0.3.8 | 16 |  |
| 0.3.9 | 17 |  |
| 0.3.10 | 18 |  |
| 0.3.11 | 18 | ⚠ did not increase |
| 0.4.0 | 18 | ⚠ did not increase |
| 0.4.1 | 18 | ⚠ did not increase |
| 0.4.2 | 18 | ⚠ did not increase |
| 0.4.3 | 18 | ⚠ did not increase |
| 0.4.4 | 18 | ⚠ did not increase |
| 0.4.5 | 18 | ⚠ did not increase |
| 0.5.0 | 18 | ⚠ did not increase |
| 0.5.1 | 18 | ⚠ did not increase |
| 0.5.2 | 19 |  |
| 0.5.3 | 20 |  |
| 0.5.4 | 21 |  |
| 0.5.5 | 22 |  |
| 0.6.1 | 23 |  |
| 0.6.2 | 24 |  |
| 0.6.4 | 25 |  |
| 0.6.5 | 26 |  |
| 0.7.1 | 28 |  |
| 0.7.2 | 29 |  |
| 0.7.3 | 29 | ⚠ did not increase |
| 0.7.4 | 30 |  |
| 0.7.5 | 31 |  |
| 0.7.6 | 32 |  |
| 0.7.7 | 33 |  |
| 0.8.1 | 34 |  |
| 0.8.2 | 35 |  |
| 0.8.3 | 36 |  |
| 0.9.1 | 37 |  |
| 0.9.2 | 38 |  |
| 0.9.3 | 39 |  |
| 0.9.4 | 40 |  |
| 0.9.5 | 41 |  |
| 0.9.6 | 42 |  |
| 0.9.7 | 43 |  |
| 0.10.0 | 44 |  |
| 0.10.1 | 45 |  |
| 0.10.2 | 46 |  |
| 0.10.3 | 47 |  |
| 0.10.4 | 48 |  |
| 0.10.5 | 49 |  |
| 0.10.6 | 50 |  |
| 0.10.7 | 51 |  |
| 0.10.8 | 52 |  |
| 0.10.9 | 53 |  |
| 0.10.10 | 54 |  |
| 0.10.11 | 55 |  |
| 0.10.12 | 56 |  |
| 0.10.13 | 57 |  |
| 0.10.14 | 58 |  |
| 0.12.1 | 59 |  |
| 0.12.2 | 60 |  |
| 0.12.3 | 61 |  |
| 0.12.4 | 62 |  |
| 0.12.5 | 63 |  |

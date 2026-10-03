<!--
  Why:  issue 12.5 — AC2 asks for a release train wired to VERSION/CHANGELOG, and AC3 for a tagged
        release build that is documented. This is the page somebody shipping a release reads.
  What: the gates, the bump, the tag, and what is deliberately left to a person.
  Result: a release that is internally consistent by construction and checked by the build.
  Changelog: 2026-10-03 — Created for issue 12.5.
-->

# The release train

Four files have to move together: `VERSION`, `app/build.gradle.kts`'s `versionCode`, `CHANGELOG.md`
and `docs/releases.md`. Doing it by hand has already failed twice in this repository — see §4.

---

## 1 · The gates

Everything below runs in CI on every PR into `dev`, `stage` or `main`. All of them block a merge.

| Gate | Command | What it stops |
|---|---|---|
| Release metadata | `verifyReleaseMetadata` | A version bump that disagrees with the changelog or the versionCode ledger |
| Architecture | `-p build-logic :convention:test` | A pure-Kotlin module gaining an Android dependency (ARC-002) |
| Style + lint | `ktlintCheck detekt lintDebug` | Including six custom rules: money as a float, wall-clock in domain, `GlobalScope`, PII in logs, hardcoded UI strings, hand-rolled crypto |
| Unit + coverage | `unitTests koverVerify` | Engine coverage below 85%, money math below 100% |
| AI accuracy | `aiEval` | Categorisation < 92%, receipts < 95%, SMS < 95%, the forecast backtest's bounds |
| Guardrail | `guardrailEval` | A fabricated or adversarial figure reaching a user |
| Screenshots | `verifyPaparazziDebug` | A visual change to the design system or the critical screens |
| Dependencies | `scanDependencies` | A new HIGH/CRITICAL advisory, or a stale allowlist entry |
| Release hardening | `verifyReleaseLogStripping` | A release APK that still references a stripped log method |
| Offline + restore | `offlineSmoke`, `restoreDrill` | **Release path only.** The app failing with the radio off, or a backup that will not restore |

Locally, `/pre-merge` runs the Definition of Done; `./gradlew unitTests koverVerify ktlintCheck
detekt lintDebug` is the usual pre-push set.

## 2 · Bumping a version

```bash
python3 scripts/release.py patch      # finishing an issue
python3 scripts/release.py minor      # starting a new epic — resets the patch to 0
python3 scripts/release.py major      # a breaking release
```

It writes `VERSION`, increments `versionCode` to one above the highest the ledger records, appends the
ledger row, and prints what is left.

**It does not write the changelog entry.** That is the release notes, and a generated line saying
"issue 12.5 shipped" would be worse than nothing. Write it under the current epic's heading:

```
### [0.12.5] — Issue 12.5: <title>  (YYYY-MM-DD)
- **Implemented:** <what a user can now do> (<REQ-IDs> · ADR-NNNN)
- **Tests:** N passed, M skipped — and why anything was skipped
```

Then `./gradlew verifyReleaseMetadata`, which confirms all four agree.

**A new epic is a minor bump**, and its heading goes in before its first issue. Getting this wrong is
how Epic 11 ended up in the `0.10.x` series.

## 3 · Tagging and the release build

The release APK is produced by `./gradlew :app:assembleRelease` — R8-minified, resource-shrunk, with
the log strip verified by `verifyReleaseLogStripping` (ADR-0062). The mapping file lands at
`app/build/outputs/mapping/release/mapping.txt` and is **needed to read any release stack trace**;
nothing archives it yet, which is a recorded gap (ADR-0062).

Tag at the **promotion**, not at the bump:

```bash
git tag -a v0.12.5 -m "Release 0.12.5"
git push origin v0.12.5
```

`release.py` prints the command and deliberately does not run it. `stage` and `main` are protected and
a human is already in the loop for a promotion (`00-issue-workflow.md` §7), and a tag on `dev` would
assert that something was released when it was not.

**As of issue 12.5 this repository has no tags**, because nothing has been promoted past `dev`. That
is a true statement about the project rather than a gap in this process.

## 4 · What went wrong before this existed

Both found by reconstructing the history during issue 12.5, and both left in place rather than tidied:

- **Epic 11's seven issues shipped as `0.10.8`–`0.10.14`**, under the Epic 10 heading, when the rule
  says an epic starts with a minor bump. Nobody noticed for seven releases. `verifyReleaseMetadata`
  now compares the issue id in an entry's title with the version's minor, which is what would have
  caught it on the first one. `0.11.x` is left unused — renumbering released versions would make the
  changelog disagree with the commits it documents.
- **Ten versions shipped with a `versionCode` that did not increase** (⚠ in `docs/releases.md`). Play
  rejects such an upload *after* a release has been cut. None of them was ever uploaded — this app has
  not shipped — so the history stands and the gate is forward-looking.

Neither was caught by review. Both were caught the first time something read the files and compared
them, which is the argument for the gate rather than for more care.

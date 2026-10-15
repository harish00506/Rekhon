<!--
  Why:  CLAUDE.md §5 — a new tool in the build and a set of rules deliberately switched off are
        both decisions a reader could reasonably expect to have gone the other way.
  What: SonarQube Community Build as a reporting-only analysis, the configuration defect that made
        its first run cover a third of the repository, and the rules turned off per file with the
        argument for each.
  Result: a reader can see why the dashboard is trustworthy now, and why nine rules are off.
  Changelog: 2026-10-15 — Created.
-->

# ADR-0081 — Static analysis gets a server, and the first run was vacuous

**Status:** Accepted · **Date:** 2026-10-15 · **Relates to:** [ADR-0001](0001-custom-lint-module-and-money-heuristic.md), [ADR-0003](0003-app-lock-gate-and-deferred-user-auth-key.md), [ADR-0075](0075-android-free-is-not-kmp-portable-and-money-is-the-blocker.md), [ADR-0079](0079-the-on-device-model-is-real-and-its-weights-are-apache-2-0.md) · **SRS:** §21.6, §21.5 · **Rules:** P-01, P-03, P-04, DB-003

## Context

The repository already fails a build on four analysers: ktlint, detekt, Android Lint, and the five
custom detectors in `:lint`. All four are **file-local** — each sees one file, has no cross-module
view, and keeps no history. So two questions had no answer: *is there a problem that only shows up
across modules?* and *did this change make the code worse than last week?*

## Decision

### 1 · SonarQube reports; it does not gate

`org.sonarqube` is applied at the root and points at a local SonarQube Community Build. It is
**reporting-only**, and `sonar.skipProject` is true whenever `SONAR_TOKEN` is unset.

That is not timidity, it is P-04 applied to the tooling: detekt, ktlint and `:lint` run with no
server, no container and no network, and they must stay the gate that blocks a merge. An analyser
that needs a server to say yes is an analyser that says nothing on an aeroplane. Credentials come
from the environment and are never committed — this token can write analyses, so a copy in the repo
is a credential leak (P-01).

### 2 · The first run covered a third of the repository and nothing said so

The first analysis reported 54 issues over 293 files and 17,677 lines, and looked healthy. It was
**vacuous for every Android module.** 295 of the 450 main Kotlin files were never read.

The scanner takes an AGP module's sources from `android.sourceSets["main"].java.srcDirs`, which is
`src/main/java` — a directory this repository does not have, because the Kotlin Android plugin puts
them in `src/main/kotlin`. `sonar.sources` therefore resolved to nothing, and each module was
indexed as zero lines of code. Nothing failed. `:app`, `:data:repository`, all thirteen `:feature:*`,
`:core:database`, `:core:crypto`, `:core:datastore`, `:core:network`, `:core:designsystem`,
`:ml:llm`, `:ml:ocr`, `:widget` and `:sync:backup` were all silently absent. The pure-Kotlin modules
were fine, which is exactly why it was believable.

The only reason it was caught: Android Lint's own report named files the index did not contain, and
the scanner logged `No input file found for …TransactionsScreen.kt` as an aside among successes.

This is the fourth time this repository has shipped a gate that measured nothing — audit G-01's
vacuous coverage gate, the `:lint` tests reachable from no task (issue 2.6), the Paparazzi baseline
nothing compared against (issue 6.5), and now this. **The lesson is the same every time: a green
result is not evidence until you have checked what it looked at.** The fix declares the real source
and test directories for every module the Android plugins touch; the honest coverage is 578 files
and 51,241 lines, and the issue count went from 54 to 147.

`plugins.withId` rather than `plugins.hasPlugin`, because when the root script configures
`subprojects` the module scripts have not run yet and `hasPlugin` answers false for every one of
them — which would have left this fix looking applied while changing nothing.

### 3 · Nine rules are off, per file, with the argument next to the path

Exclusions live in `build.gradle.kts` as `sonar.issue.ignore.multicriteria`, not as "won't fix"
clicks in the server's database. A reviewer reads the repository; nobody reads a resolution log, and
a volume wipe erases one. Every entry is a decision this project has **already** made somewhere the
rule cannot see:

| Rule | Where | Why it is wrong here |
|---|---|---|
| `kotlin:S107` (>7 parameters) | Hilt `@Provides` modules, `RepositoryFactory`, Room `Daos.kt`, `RankedInsightEngine.insight` | The parameter list *is* the data — a dependency graph, a row's columns, a card's fields. `insight` already carries `@Suppress("LongParameterList")` with this argument for detekt's copy of the rule |
| `kotlin:S1192` (duplicated literal) | `Migrations.kt` | A migration is a frozen record of what version N did. A shared constant means one edit rewrites 34 past migrations — worse than DB-003's destructive migration: a retroactive one. **The duplication is the safety property** |
| `kotlin:S6311` (pointless dispatcher) | `ReceiptRepository` | `ReceiptImageStore.read` does its file I/O and AES-GCM decrypt without dispatching internally, so the `withContext(io)` is the only thing keeping a receipt decrypt off the main thread |
| `kotlin:S6619` (null check always fails) | `TemplateLlmEngine` | False positive. The `when` arms yield `String?`/`Int?`/`String?`. Removing the guard renders the text `null` in a sentence about the user's money — the P-03 failure it exists for |
| `kotlin:S5324` (external storage) | `AndroidSecureEraser` | The access is a **delete**. The hotspot is about putting data on shared storage; skipping this sweep is what leaves data behind |
| `kotlin:S6288` (key without user auth) | `KeystoreMasterKey` | [ADR-0003](0003-app-lock-gate-and-deferred-user-auth-key.md). The lock gates a `SessionLock` flag and the Hilt provider refuses to build while locked. An auth-bound key would also break the background snapshot worker |
| `kotlin:S6293` (biometric without `CryptoObject`) | `AppLockGate` | ADR-0003 from the other end: a class-3 prompt flips a session flag, it does not unwrap the database key |
| `xml:S5322` (intents received safely) | `widget/AndroidManifest.xml` | `APPWIDGET_UPDATE` is a protected broadcast, so no third-party app can reach the receiver — and an AppWidgetProvider the launcher cannot bind to is a widget that never appears |
| `kotlin:S6517` (make interface functional) | `MarketDataApi`, `AccountAggregatorApi` | `MarketDataApi` has three named implementations (Retrofit client, offline stand-in, test fake); `AccountAggregatorApi` is a named contract for an unbuilt backend whose one implementation carries a documented refusal. No caller passes a lambda to either. Declaring them functional produced the *opposite* finding, `kotlin:S6516`, asking for those documented objects to become anonymous lambdas that §5 cannot document. The other 47 conversions stand |

`spike/` is excluded wholesale: ADR-0075 calls it a throwaway portability probe.

### 3b · The 49 `fun interface` conversions, and the two that came back

47 of the 49 single-abstract-method interfaces are now `fun interface`: engine contracts, repository
contracts, the notifiers, `IdGenerator`. The declaration says what was already true and makes a
lambda usable in a test, and Hilt binds them unchanged.

The two in `:core:network` were a mistake, and the analyser is what revealed it: converting them
raised `kotlin:S6516` against their offline stand-ins, asking for a documented named object to
become an anonymous lambda. **A rule pair that argues with itself is a signal that the shape was
wrong, not that the second rule needs silencing** — so those two declarations were reverted to plain
interfaces, which is what a contract resolved through a factory should be.

### 4 · One issue is accepted on the server, deliberately visible

`kotlin:S6474` — no `gradle/verification-metadata.xml` — is marked **Accepted** with a comment,
not excluded. Supply-chain integrity here is covered by `scanDependencies` (SEC-007), which queries
every resolved release-runtime coordinate against OSV. Gradle dependency verification is a different
and stronger control, and worth adopting — but it cannot be switched on halfway: the moment that
file exists Gradle enforces it for every configuration it resolves, so metadata generated from one
task's graph breaks every task whose graph differs. That needs its own ADR and a full-graph
generation pass. It is left as an open item rather than silenced so the gap stays visible.

## Consequences

- The dashboard covers the whole repository, so a clean result means something.
- `./gradlew build` on a machine with no server is unchanged.
- A tenth exclusion should be hard to add: the table above is the bar.
- **Open:** Gradle dependency verification (§4).
- **Open:** the launcher icon added here is a placeholder. It is a legible mark, not branding.

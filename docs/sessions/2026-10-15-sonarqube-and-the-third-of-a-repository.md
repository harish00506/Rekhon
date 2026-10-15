<!--
  Why:  CLAUDE.md §10 — one session file per working session, holding the full reasoning the root
        records only point at.
  What: a SonarQube server stood up against this repo, the configuration defect that made its first
        run cover a third of it, and the 147 findings that run produced — what was fixed, what was
        switched off, and why.
  Result: a reader can see which of the 147 were real, and can check the two numbers that prove the
          analysis is not lying.
  Changelog: 2026-10-15 — Created.
-->

# 2026-10-15 — SonarQube, and the third of a repository it was reading (ADR-0081)

On `dev`. No version bump: nothing user-visible changed except an app icon that did not exist and
five sentences that counted wrongly. Schema unchanged.

---

## 0 · The task

*"Run SonarQube, solve all the issues, and run again until all the issues are solved."*

There was no SonarQube. `git grep -il sonar` found one file, and it was a test about the chat
guardrail. So the work was: stand one up, point it at this repository, and then actually fix what it
found.

---

## 1 · Decisions this session

### 1.1 · SonarQube reports; it does not gate (ADR-0081 §1)

`org.sonarqube` 7.5.0.8588 applied at the root against SonarQube Community Build 26.9 in Docker.
`sonar.skipProject` is true whenever `SONAR_TOKEN` is unset, so `./gradlew build` on a machine with
no server is unchanged.

Reporting-only is P-04 applied to the tooling rather than only to the app. ktlint, detekt,
`lintDebug` and `:lint`'s own tests run with no server, no container and no network, and they stay
the things that fail a build. An analyser that needs a server to say yes says nothing on an
aeroplane. SonarCloud was rejected outright: it would mean uploading this codebase to a third party,
which is not what P-01 is about but is the same instinct.

### 1.2 · The first run was vacuous, and this is the fourth time (ADR-0081 §2)

**The thing worth writing down.** The first analysis reported **54 issues over 293 files and 17,677
lines** and looked like a healthy result for a young codebase.

It had not read 295 of the 450 main Kotlin files.

The scanner takes an AGP module's sources from `android.sourceSets["main"].java.srcDirs`, which is
`src/main/java`. This repository does not have that directory — the Kotlin Android plugin puts
sources in `src/main/kotlin`. So `sonar.sources` resolved to the empty string for every Android
module, each was indexed as zero lines of code, and **nothing failed**. `:app`,
`:data:repository`, all thirteen `:feature:*`, `:core:database`, `:core:crypto`, `:core:datastore`,
`:core:network`, `:core:designsystem`, `:ml:llm`, `:ml:ocr`, `:widget` and `:sync:backup` were
absent from a dashboard that said it had analysed the project. The pure-Kotlin modules were read
correctly, which is exactly what made it believable.

What caught it was not a failure. It was an aside in the log — `No input file found for
…TransactionsScreen.kt. No android lint issues will be imported on this file.` — Android Lint's own
report naming files the index did not contain.

That is the **fourth** gate in this repository to measure nothing: audit G-01's vacuous coverage
gate, `:lint`'s tests reachable from no task (issue 2.6), the Paparazzi baseline nothing compared
against (issue 6.5), and now this. The pattern is identical every time: a green number that nobody
checked the denominator of.

Honest coverage after declaring the real directories: **578 files, 51,394 lines, 147 issues.**
Those two numbers are now written into `FLOW.md` §2.32 as the thing to compare against before
believing a clean dashboard.

`plugins.withId` rather than `plugins.hasPlugin`, because the module scripts have not run when the
root configures `subprojects` — `hasPlugin` answers false for all of them, and the fix would have
looked applied while changing nothing. Which would have been the same bug again.

### 1.3 · Of 147 findings, 134 were fixed in code and 13 rules were switched off

The exclusions live in `build.gradle.kts` with the argument beside each path, not as "won't fix"
clicks in the server's database: a reviewer reads the repository, nobody reads a resolution log, and
a volume wipe erases one. Every one is a decision this project had **already** made somewhere the
rule cannot see — an ADR, a `@Suppress` with a reason, or a platform guarantee. The table is in
ADR-0081 §3.

Two of them are worth repeating here because they are not style questions:

- **`kotlin:S1192` on `Migrations.kt`** asked for 24 repeated column fragments to become shared
  constants. That is the one change that must never be made: a migration is a frozen record of what
  schema version N did, and a shared constant means one edit retroactively rewrites 34 past
  migrations. DB-003 forbids destructive migrations; this would be worse. **The duplication is the
  safety property.**
- **`kotlin:S6619` on `TemplateLlmEngine`** called a null check dead. It is not: the `when` arms
  yield `String?`, `Int?` and `String?`, and removing the guard would render the text `null` in a
  sentence about someone's money — the P-03 failure the guard exists for.

### 1.4 · The 49 `fun interface` conversions, and the two that came back (ADR-0081 §3b)

47 single-abstract-method interfaces became `fun interface`. The two in `:core:network` were a
mistake, and **the analyser is what revealed it**: converting them raised `kotlin:S6516` against
their offline stand-ins, asking for a documented named object to become an anonymous lambda.

A rule pair that argues with itself is a signal that the shape was wrong, not that the second rule
needs silencing. Both were reverted to plain interfaces, which is what a contract resolved through a
factory should be.

### 1.5 · Coverage, and the gate that is still red

Sonar reads Kover's XML directly (it is JaCoCo-format), so no second coverage tool was added.
Overall line coverage reads **85.2%**.

The quality gate is still **ERROR**, on `new_coverage` 54.3% < 80%. That is not an unfixed issue —
`new_violations` is 0. It is a new-code condition whose baseline, on a first analysis, treats a
large body of already-uncommitted work as new: 191 of its 251 uncovered lines are in
`RepositoryModule.kt` (Hilt wiring) and `MediaPipeLlmRuntime.kt`, which ADR-0079's file says is "not
unit tested, on purpose". Left red and explained rather than chased, because chasing it means
writing tests for a DI module.

### 1.6 · One finding is accepted on the server, deliberately visible

`kotlin:S6474` — no `gradle/verification-metadata.xml`. Supply-chain integrity here is
`scanDependencies` against OSV (SEC-007). Gradle dependency verification is stronger and worth
having, but cannot be switched on halfway: once that file exists Gradle enforces it for every
configuration it resolves, so metadata generated from one task's graph breaks every task whose graph
differs. It needs its own ADR. Accepted with that comment rather than excluded, so the gap stays on
the dashboard.

### 1.7 · Two things the analysis found that were nothing to do with static analysis

- **The app had no launcher icon.** `MissingApplicationIcon`, and true: there was no `mipmap`, no
  `drawable`, no `android:icon`. Every launcher drew the default grey Android. An adaptive icon was
  added — **a placeholder, not branding**, and ADR-0081 says so.
- **`allowBackup="false"` was doing a job Android 12 had deprecated it out of.**
  `cfo_data_extraction_rules.xml` now excludes every domain from both cloud backup and device
  transfer, which is what P-01 meant all along. `usesCleartextTraffic="false"` was also stated
  explicitly: the platform default is only false from API 28 and `minSdk` is 26, so on two API
  levels an unencrypted request was permitted by omission.

### 1.8 · A test the conversions needed, that nothing existing would have caught

Five `<string>` entries that counted things became `<plurals>` across all four locales.
`TranslationCoverageTest` checks that a plural stays a plural everywhere and declares the categories
its language needs — but **not that the `one` form reads as a singular.** A plurals block with the
plural sentence pasted into both categories passes every check in that file and still prints
"1 months".

`CountedSentenceTest` pins the five. Mutation-checked: changing the dashboard singular to
"Imported %1$d rows." fails it by name.

### 1.9 · And then it became a rule — because the first use of it already leaked

Asked for after the commits above: *"before commit run sonarcube, if you found anything fix them.
add this rule also."*

The request landed on a real hole. Between the last analysis and the commit, `CountedSentenceTest`
was **rewritten twice** to satisfy detekt's `NestedBlockDepth` and `ReturnCount`. Both rewrites went
in after the dashboard said zero, so the file that this session added to catch a resource bug was
itself never analysed. Re-running against the committed tree came back clean — 0 issues, 582 files,
51,394 ncloc, and `CountedSentenceTest.kt` confirmed present in the index as `UTS` rather than
merely absent from the issue list — but that was luck, not process.

CLAUDE.md **§8.1** now says: run `./gradlew sonarAnalysis` before every commit, fix what it finds,
re-run until clean. Two cautions carried into the rule, both earned here: use `sonarAnalysis` and
never bare `sonar`, and **check the denominator before believing a zero** (§1.2). `/pre-merge`
gained step 6a. ADR-0081 gained §5.

It is written as **agent-followed, not build-enforced**, in the same words §10 uses for session
records. Nothing in Gradle or CI fails if somebody skips it, and the rule says so rather than
implying a gate that does not exist — which is the mistake this whole session was about.

---

## 2 · Flow changed this session

One new build path, documented as `FLOW.md` §2.32:

```
SONAR_TOKEN=… ./gradlew sonarAnalysis
├─ <module>:lintDebug          ×26   → lint-results-debug.xml, the external_android-lint source
├─ <module>:koverXmlReport     ×74   → report.xml, the only coverage source
└─ :sonar                     (mustRunAfter both)
   ├─ sonar.sources / sonar.tests declared explicitly for every AGP module  ← §1.2
   └─ sonar.issue.ignore.multicriteria   13 entries                          ← §1.3
```

`sonarAnalysis` exists because `sonar` alone publishes from whatever is on disk: a finding fixed an
hour ago still reports from a stale lint file, and coverage shows 0%. A task that cannot be invoked
with the wrong inputs beats a README asking people to remember.

One runtime path changed shape, trivially: five `stringResource`/`getString` calls became
`pluralStringResource`/`getQuantityString`, so the quantity now selects the sentence.

---

## 3 · Code changed this session

| Path | What it does now |
|---|---|
| `build.gradle.kts` | Applies the scanner, declares every AGP module's real source and test dirs, wires Kover coverage, holds the 13 documented rule exclusions, and registers `sonarAnalysis` |
| `gradle/libs.versions.toml` | `sonarqube = "7.5.0.8588"` + the plugin alias |
| `core/model/…/Money.kt` | `allocate`'s working buffer is a `LongArray`; arithmetic unchanged |
| `core/crypto/…/PinCredentialStore.kt` | The rename-retry reports whether the old file was cleared |
| `core/database/…/WrappedPassphraseStore.kt` | Same, twice; `clearPending` now errors when a staged file survives instead of reporting success |
| `core/network/…/MarketDataApi.kt`, `…/AccountAggregatorApi.kt` | Plain interfaces, with the reason in the KDoc |
| `data/repository/…/ForecastRepository.kt` | One nullable `lookbackFrom` instead of a date plus a Boolean; the `!!` is gone |
| `data/repository/…/TheilSenVehicleEngine.kt` (`domain`) | Same shape; the `requireNotNull` is gone |
| `data/repository/…/{Insight,SafeToSpend,Transaction}Repository.kt` | A redundant cast, a mutable set, and an unused local removed |
| `domain/engines/budget/…/BudgetEngine.kt` | Two `require` messages are named constants |
| `domain/engines/sms/…/DefaultSmsEngine.kt` | `PAYEE_STOP_WORDS` is a documented list; the character classes no longer duplicate under `IGNORE_CASE` |
| 47 engine/repository/notifier interfaces | `fun interface` |
| `ml/llm/…/MediaPipeLlmRuntime.kt` | `@Suppress("DEPRECATION")` with ADR-0079's reasoning |
| `feature/transactions/…/TransactionsScreen.kt` | `modifier` is the first optional parameter |
| `app/src/main/AndroidManifest.xml` | Icon, `dataExtractionRules`, `usesCleartextTraffic="false"` |
| `app/src/main/res/{mipmap-anydpi-v26,drawable,values}/ic_launcher*` | A placeholder adaptive icon |
| `app/src/main/res/xml/cfo_data_extraction_rules.xml` | Nothing leaves the device via the platform |
| `{app,feature/transactions,widget}` manifests + 20 `strings.xml` | Backup/cleartext stated; five counted sentences are plurals in four locales |
| `app/src/test/…/i18n/CountedSentenceTest.kt` | Pins those five singulars |

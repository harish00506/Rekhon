<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 11.6 — R8, the log strip verified in the shipped DEX, OSV scanning, and the total
        lockout the release build turned out to contain.
  Result: a reader can see why both gates are checked against reality rather than configuration,
          and why the release APK now goes on a device every time.
  Changelog: 2026-10-02 — Created.
-->

# 2026-10-02 — A smaller, quieter release, and a scan that can say no (issue 11.6, ADR-0062)

**Branch:** `feature/11-6-dependency-scan-osv-minified-release` off `dev` (`019f294`)
**Versions:**
- **VERSION** 0.10.12 → **0.10.13**
- **versionCode** 56 → 57
- **Schema** 29 → **29 (unchanged — this issue ships no runtime behaviour)**

---

## 1 · Decisions this session

The full argument for each is in [ADR-0062](../adr/0062-the-release-is-minified-and-the-log-strip-is-read-out-of-the-shipped-dex.md).

- **R8 and resource shrinking on for release**, with every `-keep` justified in a comment. A keep
  rule nobody can explain silently stops R8 shrinking, and pasting rules "to be safe" is how a
  minified build ends up the size of an unminified one.
- **The strip covers `v/d/i/w` and `isLoggable`; `e` and `wtf` stay.** An error path that cannot
  speak is a release nobody can diagnose, and `CfoPiiInLogs` already blocks PII in those arguments.
- **The `assumenosideeffects` rules carry `return` clauses.** Measured: without them 42 `isLoggable`
  calls and one `w` survive, because R8 cannot delete a call whose result is used.
- **The strip is verified by parsing the shipped DEX.** A rule file can be edited and
  `isMinifyEnabled` flipped back with every test green — this repository has shipped a gate that
  never ran twice already. A hand-written parser rather than `dexdump`, which lives under a versioned
  `build-tools` path CI would have to pin.
- **The OSV step is not `|| true`**, which is what the placeholder it replaced suggested.
- **"Could not reach OSV" exits 2, not 0.** A scanner that passes when it cannot scan is green on
  every offline machine forever. That is also why `scanDependencies` is not in `unitTests`: P-04 is a
  promise about the *app*, not a licence for a security gate to pass while blind.
- **The floor is HIGH, not MODERATE.** Blocking on every moderate advisory makes a gate people switch
  off; moderates are printed instead. An unparseable severity is `UNKNOWN` — reported, not blocking,
  because calling it LOW would be a claim the data does not support.
- **The allowlist expires.** Every entry requires `review_by`, an entry past it fails the scan even
  with no findings, and a missing field raises rather than defaulting to "forever". An acceptance
  without an expiry is how a known vulnerability becomes policy, inherited by people who never saw
  the argument.
- **The scan reads what is *resolved*, not what is requested**, and across every module — a library
  pulled in only by `:core:crypto` ships in the APK just the same. An empty coordinate list throws.
- **No new third-party dependency.** No Gradle vulnerability plugin and no pytest; ADR-0062 records
  why each was rejected. That is AC3 satisfied by subtraction.

**What this found.**

1. **A release-only total lockout, with all 5,102 tests green.** A clean install of the minified
   release opened on **"Locked — enter your PIN"** with no PIN ever set, so there was no way into the
   app. The same commit as a debug build opened on onboarding step 1. No crash, no exception, nothing
   in logcat.

   protobuf-javalite resolves its generated message classes reflectively; R8 renamed them, the Proto
   DataStore settings read failed, and `AppLockUiState`'s fail-secure default treated an unknown lock
   state as locked — which is the right choice over data it could not trust. A correct component
   behaving correctly on top of a silently broken one. Nothing in the JVM suite could see it: every
   test runs against unminified code. Fixed with a keep rule carrying the evidence that produced it.
2. **The measurement came before the assertion, twice.** `dexdump` was run before any gate was
   written, which is how the surviving `isLoggable` and `w` calls were found rather than asserted
   away; and the scanner was pointed at known-vulnerable coordinates before "0 findings" on the real
   set was believed. 56 blocking findings there — so the zero is a real zero, not a broken query.
3. **Both gates were proven able to fail**, which is the standing requirement here: the log-strip
   checker finds all six methods in the unminified debug APK, and eight mutations against the scan
   policy are all killed.
4. **This app logs nothing.** Three `grep` matches across every `src/main`, all doc comments inside
   the rule that bans logging. §21.6 was already satisfied for our code by vacancy, so the
   requirement had to be restated — "no chatty log surface in the APK, ours *or a dependency's*" —
   before it meant anything.
5. **A release-process gap, recorded rather than discovered later:** a release stack trace now needs
   `mapping.txt`, and nothing in this repository archives it.

## 2 · Flow changed this session

A build path rather than a runtime one — `FLOW.md` §2.25 — because it decides what is in the shipped
binary, which no runtime diagram would show:

```
:app:assembleRelease → minifyReleaseWithR8
  ├─ Log.v/d/i/w/isLoggable + println   call AND argument construction removed
  ├─ keep datastore.proto.**            ← without this, a clean install opens LOCKED
  └─ shrinkResources                    84.5 MB → 66.6 MB

verifyReleaseLogStripping → parses every classes*.dex; Log.e/wtf allowed by design
scanDependencies → resolved releaseRuntimeClasspath (278) → OSV
                   HIGH+ unallowlisted ⇒ 1 · expired entry ⇒ 1 · unreachable ⇒ 2
```

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `app/proguard-rules.pro` (new) | the log strip, the proto keep the device run demanded, and the serializer keeps that protect the archive's field names |
| `app/build.gradle.kts` | `isMinifyEnabled` / `isShrinkResources` on, with the optimising default rules file |
| `scripts/verify_release_log_stripping.py` (new) | parses the shipped DEX and fails on a surviving stripped method |
| `scripts/osv_scan.py` (new) | the scan, the severity floor, the expiring allowlist; exits 2 rather than passing when blind |
| `scripts/test_osv_scan.py` (new) | 22 tests on the policy; 8 mutations, all killed |
| `config/osv/allowlist.json` (new) | empty on purpose, and documents why every entry needs a review date |
| `build.gradle.kts` | `verifyReleaseLogStripping`, `writeDependencyCoordinates`, `scanDependencies`, `scriptTests` — the last wired into `unitTests` so it cannot be forgotten |
| `.github/workflows/ci.yml` | both gates, neither with `|| true`; replaces the placeholder that suggested it |
| `docs/adr/0062-…`, `DECISIONS.md`, `FLOW.md` §2.25, `CHANGELOG.md`, `docs/memory.md`, `VERSION` | the records |

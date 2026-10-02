<!--
  Why:  CLAUDE.md §5 — any decision or deviation from the SRS needs an ADR.
  What: issue 11.6 — R8 minification, the log strip verified against the shipped binary, and OSV
        dependency scanning that can actually fail.
  Result: a reader can see why the strip is checked in the DEX rather than asserted, why the scanner
          exits non-zero when it cannot scan, and what the release build broke that no test saw.
  Changelog: 2026-10-02 — Created.
-->

# ADR-0062 — The release is minified, and the log strip is read out of the shipped DEX

**Status:** Accepted · **Date:** 2026-10-02 · **Issue:** 11.6 · **SRS:** §21.3, §21.6, SEC-007

## Context

§21.6 bans PII and amounts from logs; SEC-007 asks for dependency scanning. Until this issue the
release build had `isMinifyEnabled = false` with a comment pointing at this issue, and CI carried a
commented-out scan placeholder that suggested `|| true`.

A useful thing surfaced while surveying: **this app's own production code contains no `Log` calls at
all.** The only matches in the tree are doc comments inside the lint rule that bans them. So §21.6
was already satisfied for our code by there being nothing to satisfy it about, and the honest
framing of the release requirement is not "strip our logs" but "leave no chatty log surface in the
APK, ours or a dependency's".

## Decision

### 1 · R8 on, with every keep rule justified in a comment

`isMinifyEnabled` and `isShrinkResources` are on for release, with `proguard-android-optimize.txt`
plus `app/proguard-rules.pro`. The optimising default file matters: it is what lets
`assumenosideeffects` remove the **argument construction** as well as the call, so a
`"balance=" + money` concatenation never runs in a release build either.

Every `-keep` in that file carries its reason. A keep rule nobody can explain is dead weight that
silently stops R8 shrinking, and the reflex of pasting rules "to be safe" is how a minified build
ends up the size of an unminified one. Room, Hilt, Retrofit, OkHttp, Tink, SQLCipher, Glance and
Compose ship their own consumer rules, which AGP merges — copying them here would duplicate
something that drifts.

### 2 · The strip covers `v/d/i/w` and `isLoggable`, and deliberately keeps `e` and `wtf`

An error path that cannot say anything is a release nobody can diagnose, and `CfoPiiInLogs` already
blocks PII in those arguments at compile time. The four chatty levels and `println` go.

The `return` clauses in the `assumenosideeffects` rules are not decoration. Without them R8 can only
delete a call whose result is unused, and a surprising number are used — `if (Log.isLoggable(...))`
guards a block, and `Log.w` returns an int. **Measured on this app:** without the returns, 42
`isLoggable` calls and one `w` survived; with them, all four levels reach zero.

### 3 · The strip is verified by reading the shipped DEX

That is the part that matters. A rule file can be edited and `isMinifyEnabled` can be flipped back
with every test in the repository staying green — this project has shipped a gate that never ran
twice already. So `verifyReleaseLogStripping` assembles the release APK, parses every `classes*.dex`
and fails on any reference to a stripped method.

**A DEX parser rather than `dexdump`**, because `dexdump` lives under a versioned `build-tools` path
that differs per machine and would have to be pinned in CI; the parser needs only Python's standard
library. It was cross-checked against `dexdump -d` on this app's own release APK — both agree that
`v`, `d`, `i`, `w` and `isLoggable` are absent and that `e` (128 call sites) and `wtf` (9) remain —
and it is proven non-vacuous by running it against the **unminified debug** APK, where it finds all
six and exits 1.

### 4 · The OSV scan can fail, including when it cannot scan

`scripts/osv_scan.py` resolves every release-runtime coordinate (278 of them), queries OSV's public
batch API, and fails on anything at or above HIGH that is not allowlisted. The CI step is **not**
`|| true`, which the placeholder it replaced suggested: a scan that cannot fail is the vacuous gate
this repository keeps finding.

**Exit 2 — "could not reach OSV" — is not success.** A scanner that passes when it cannot scan would
be green on every offline machine forever. That is why it is not part of `unitTests`: it runs in CI,
and locally it is expected to fail with a network error. P-04 is a promise about the *app*, not about
a developer tool.

The severity floor is HIGH, not MODERATE. Blocking every build on a moderate advisory makes the gate
something people switch off, which is worse than a gate that is imperfect — but moderates are still
printed, so nothing is silent. An unparseable severity is `UNKNOWN`, which is reported and does not
block: calling it LOW would be a claim the data does not support.

### 5 · The allowlist expires

Some findings genuinely cannot be fixed today — a transitive dependency with no patched release, or
an advisory that does not apply to how this app uses the library. `config/osv/allowlist.json` accepts
those, and every entry **requires** a `review_by` date. An entry past it fails the scan, even when
there are no findings at all: a stale acceptance means nobody has looked since that date.

An allowlist entry without an expiry is how a known vulnerability quietly becomes policy, inherited
by people who never saw the argument for it. A missing `review_by` raises `KeyError` rather than
defaulting to "forever", and there is a test for that.

### 6 · The scan reads what is **resolved**, not what is requested

`writeDependencyCoordinates` resolves each module's `releaseRuntimeClasspath` rather than parsing
`./gradlew :app:dependencies`. Requested versions are not what ships: a transitive upgrade would be
invisible to a scan that read the request. It also covers every module, not just `:app` — a library
pulled in only by `:core:crypto` ships in the APK exactly the same way. An empty result throws,
because a scan with nothing to scan would pass by having checked nothing.

## What the release build broke, with every test green

**A clean install of the minified release opened on the lock screen with no PIN set — no way into the
app at all.** The same commit as a debug build opened on onboarding step 1. No crash, no exception,
nothing in logcat.

protobuf-javalite resolves its generated message classes reflectively; R8 renamed them, the Proto
DataStore settings read failed, and the app lock's fail-secure default did exactly what it promises —
`AppLockUiState` treats an unknown lock state as locked, never as open, and that is the right choice
over broken data. The symptom was a correct component behaving correctly on top of a silently broken
one.

Nothing in 5,100 tests could have caught it: they all run on the JVM against unminified code. It was
found by installing the release APK on a device and comparing it against the debug build on an
identical clean state. The fix is a keep rule for the generated proto classes, and it is in the rules
file **with the evidence that produced it** rather than as a precaution.

The rest of the release was then exercised under R8 on the device: demo mode (Room + Hilt + every
engine), typed navigation routes (`@Serializable` objects), and the archive export — whose JSON field
names are a contract with files already on users' phones and survive minification intact.

## Alternatives rejected

| Alternative | Why not |
|---|---|
| Leave `isMinifyEnabled = false` | §21.6's log ban would hold only by our code happening to have no logs. A dependency's `Log.d` can carry a URL or a query. |
| Trust the rule file; do not inspect the APK | The exact shape of gate this repository has shipped twice and found later. A rule file is not evidence about a binary. |
| Use `dexdump` for the check | A versioned `build-tools` path that differs per machine and would need pinning in CI. The parser needs only the standard library. |
| Strip `Log.e` and `Log.wtf` too | A release nobody can diagnose. The lint rule already covers PII in their arguments. |
| `assumenosideeffects` without `return` clauses | Measured: 42 `isLoggable` calls and one `w` survive, because R8 cannot delete a call whose result is used. |
| `... || true` on the scan step | A gate that cannot fail. This is what the placeholder suggested and the reason it is called out in the workflow comment. |
| Treat "could not reach OSV" as a pass | Green forever on any machine without network — the same defect in a different coat. |
| Block on MODERATE and above | Makes the gate something people turn off. HIGH blocks; everything else is printed. |
| An allowlist without expiry dates | A known vulnerability becomes policy by inheritance. |
| Parse `./gradlew :app:dependencies` | Reports requested versions as well as resolved ones; a transitive upgrade would be missed. |
| Add a Gradle vulnerability plugin (e.g. dependency-check) | A new build dependency, a feed to download, and a plugin to keep current, for logic that is forty lines of standard-library Python against OSV's public API. **No new third-party dependency was added by this issue** — which is the point of AC3. |
| Add pytest for the script tests | A dependency for twenty-two assertions `unittest` already covers. |

## Consequences

- The release APK is 66.6 MB against the debug build's 84.5 MB, and contains no `Log.v/d/i/w`,
  `isLoggable` or `println` reference anywhere — verified on the binary, in CI.
- A new HIGH or CRITICAL advisory in anything that ships fails CI, and so does an allowlist entry
  that nobody has reviewed since its due date.
- **R8 is now a thing that can break the app in ways no test sees.** The device run on the release
  APK is not optional, and the protobuf lockout is the standing argument for that.
- `scanDependencies` fails locally without network, on purpose. It is not in `unitTests`.
- The mapping file (`app/build/outputs/mapping/release/mapping.txt`) is now required to read a release
  stack trace, and nothing in this repository archives it yet — a release-process gap, recorded here
  rather than discovered after a crash report.

# Session — 2026-08-17 — Issue 5.5, Home-screen widget (Glance)

**Branch:** `feature/5-5-home-screen-widget-glance` → `dev` · **VERSION:** 0.5.4 → 0.5.5  
**Issue:** [5.5](../issues/5.5-home-screen-widget-glance.md) · **SRS:** §5.2, §35; P-01, P-03, P-04, SEC-002

`:widget` had been an empty `ModulePlaceholder` since issue 1.1, Glance had been pinned in the
version catalog and consumed by nothing, and `FLOW.md` said in as many words that a fourth shape
would get a section "when one is actually built". Issue 5.3 had shipped a privacy blur that reached
every amount inside the app and left one acceptance criterion unticked, because the surface most
exposed to a stranger — the launcher — had no module to reach. This session built that module.

The whole design turns on one fact that is easy to miss until it crashes: **`CoreModule.provideDatabase`
throws while the app is locked (SEC-002), and a home screen is read locked far more often than
unlocked.** Everything below follows from not being allowed to touch the database on the render path.

---

## 1 · Decisions this session

### 1.1 Glance's own preference state *is* the cache — there is no second store

The obvious build is a widget that reads `SafeToSpendRepository` and `NetWorthRepository` the way
the dashboard does. It cannot: the render happens in the launcher's process, on a device that is
usually locked, and the gated database provider throws rather than returning empty.

That forced a cache outside SQLCipher. Three candidates: a new `widget_snapshot` Room table (inside
the encrypted database — solves nothing, costs a migration), new fields on `CfoSettingsProto`
(cached financial figures are not settings, and Glance would *still* need its own state to trigger a
redraw, so this adds a store rather than replacing one), or Glance's preference state itself.

The third wins on a property the others cannot have: **the value the widget reads is the value the
widget was redrawn for.** There is nothing to fall out of step. It also means `provideGlance` reads
`currentState()` and nothing else — no Hilt entry point, no `Provider`, no suspend call that can
throw — so `:widget` has no dependency injection at all and its render is unit-testable on the JVM.

The cost is real and is written down rather than glossed: two amounts now sit in an **unencrypted**
app-private file. That is accepted knowingly. The figures are on the home screen in plain sight by
design — that is the feature — and a widget that could only render with the database open would not
render. [ADR-0024](../adr/0024-the-widget-renders-from-glance-state-not-the-database.md).

A second consequence worth naming: **Safe-to-Spend had no cache at all.** Net worth has
`net_worth_snapshot`; `RoomSafeToSpendRepository` recomputes live from five Room reads on every
emission. The acceptance criterion's "cached values" had nothing to point at, so the cache had to be
created either way. The worker taking `.first()` of that flow is what turns a live computation into
a cached figure.

### 1.2 Two writers, and the split is the point

`WidgetRefreshWorker` writes the figures. `WidgetBlurWatcher` writes only the blur flag. The
tempting simplification is one writer — the worker already reads the flag anyway.

It would break the feature. A user taps the blur toggle because somebody is looking *now*, often
having just locked the phone. If hiding the widget required recomputing its figures, hiding would
depend on the database, and on a locked device the amounts would stay on the home screen at exactly
the moment they were asked to go. `writeBlurred` touches nothing but a preference file, so it always
succeeds.

The worker writes the flag too, in the same pass — deliberate redundancy. A refresh landing between
the toggle and the watcher's write would otherwise repaint with the old flag and the new figures.

**A watcher rather than a call in the toggle's handler**, for the reason `SmsConsentWatcher` already
records: today one screen writes this flag, tomorrow a settings screen is the second, and the one
that forgot would be the one leaving a balance on a home screen. Started in `CfoApplication.onCreate`
beside the existing two.

Unlike `SmsConsentWatcher`, it acts on **every** distinct emission rather than only on a transition.
That watcher fires only on granted → revoked because purging is destructive; writing a boolean is
not, and a cold start is precisely when the widget's stored flag may disagree with settings, because
the process was dead when the user last changed it. Skipping the first emission would be the bug.

### 1.3 `maskOf` moved down to `:core:model` rather than being widened

Issue 5.5's own acceptance criteria offered two options — widen `:core:designsystem`'s `internal
maskOf`, or mirror the fixed-width mask in the widget. Both are wrong for the same reason from
opposite directions: widening makes `:widget` depend on a whole Material3 Compose module for three
lines that contain no Compose, and mirroring gives the project **two definitions of how wide the
mask is**, which is two definitions of how much the blur leaks. The fixed width is the entire point
of ADR-0022 — a mask sized to the digits hides the number and leaks its order of magnitude.

So the definition moved to `MoneyFormatter.mask` in `:core:model`, beside `MoneyFormatter.format`,
which is its counterpart in the same expression and already lives there for the same argument (pure
Kotlin, no Android import, ARC-002 holds). `PrivacyBlur.maskOf` delegates. Its own tests are
unchanged and still green, which is the proof the move was behaviour-neutral.

### 1.4 No Paparazzi baselines, and saying so rather than shipping green ones

Every other UI surface in this repo has Paparazzi baselines and this one cannot: Glance emits
`RemoteViews` for an `AppWidgetHost` to inflate in another process, which LayoutLib has nothing to
render.

The tempting substitute is a Paparazzi test over a plain-Compose mirror of the widget's layout. It
was rejected, and this is the decision most worth recording, because it would have produced green
baselines that prove nothing — the thing rendered would not be the thing shipped. **A screenshot test
of a different tree is worse than none, because it reads as coverage.** This repo has already shipped
one governance gate that nothing ever ran; it does not need a second.

What replaced it, in three layers:

- `WidgetTextTest` — plain JUnit over `amountText`, the one function in the module that can put a
  digit on a home screen. Extracted out of the composable *so that* the sweep could be exhaustive
  over the real branch table rather than over whichever strings a harness exposes.
- `CfoWidgetContentTest` — `runGlanceAppWidgetUnitTest`, proving the decision reaches the emitted
  tree (a correct `amountText` still shows nothing if the layout drops a figure).
- `WidgetDeviceTest` — binds a real widget and inflates its **actual RemoteViews** on a device. This
  is the step neither JVM layer can reach, and it is where the bug in §1.6 was caught.

Light and dark were verified by looking at them on the emulator and logged in the tracker as an
observation, not as a test.

### 1.5 `CfoHardcodedUiString` widened to cover `:widget`

The detector's scope was `/feature/` and `/designsystem/`. `:widget` is neither — it has no
ViewModel and no nav graph — so the app's most-read surface was silently outside the strings rule,
and would have stayed outside it the moment it grew real text. Two entries fixed it; Glance's `Text`
already matched the existing name-based call set.

Proved rather than assumed: a seeded literal turned `:widget:lintDebug` red before the fix was kept.

### 1.6 Two bugs, and how each was caught

**`compose()` without a `GlanceId` reads empty state.** The first `WidgetDeviceTest` called
`CfoWidget().compose(context)`, which composes against *empty* preferences regardless of what was
written — so it rendered two "Not yet worked out" labels no matter what. The `rendersTheCachedFigures`
test caught it immediately.

The interesting half is that **`hidesEveryDigitWhenBlurred` passed on the same broken render**, and
would have shipped as a green privacy assertion. A widget showing nothing has no digits to leak. The
test now asserts both masks are *present* before sweeping for digits, so it cannot pass against an
empty widget. Caught only because the pair ran together and one of them failed.

**The Glance harness's default timeout is a cold-start trap.** Two `CfoWidgetContentTest` cases
passed on `testDebugUnitTest` and timed out on `testReleaseUnitTest`, which looks exactly like a
variant/resource problem. It was not: a probe confirmed release resolves resources fine, and the
same tests failed on *debug* when run cold with `--rerun-tasks`. The harness's one-second budget goes
on Robolectric and Compose class loading before a single node is emitted. Given an explicit 30 s
timeout — these assertions are about content, and making them a speed test as well would mean a
flake that looks like a privacy regression.

Worth noting what did **not** need debugging: three `WidgetRefreshWorkerTest` failures in a row were
the engine types refusing bad fixtures — `SafeToSpend` requires a breakdown, requires a cited rule,
and requires the lines to sum to the figure. The invariants worked as designed on the first caller
that got them wrong.

### 1.7 Six hours, not one day

The other five workers all run daily. Safe-to-Spend moves with every transaction, so a daily widget
would be wrong for most of the day it was right at the start of. It is not shorter than six hours
because `refreshNow` on every app launch already covers the case that matters — the user has just
been in the app — and a tighter alarm spends battery redrawing figures nobody looked at.

Nothing written is derived from the clock, which is what makes the refresh **idempotent by
construction** rather than by a claim: two runs with unchanged data write identical bytes. That is
also why the widget carries no "last updated" line — rendering one needs the profile zone (TIM-001)
on the render path, the thing this whole design avoids, and it would make every run write a new
value even with both figures unchanged.

---

## 2 · Flow changed this session

New **`FLOW.md` §4.5 · Shape D — the home-screen widget**, the fourth shape the file had explicitly
reserved a slot for. The spine's worker count went 5 → 6 and gained `widgetBlurWatcher.start()`; the
"`:widget` does not exist yet" ceiling note was retired; the lint table row now names the widened
scope.

```
WRITE — :app only
CfoApplication.onCreate()
├─ WidgetRefreshWorker.schedule(this)        periodic 6h, KEEP, NO Constraints (P-04)
├─ WidgetRefreshWorker.refreshNow(this)      one-shot REPLACE — this launch's figures
└─ widgetBlurWatcher.start()

WidgetRefreshWorker.doWork()                          the FIGURES path — needs the database
├─ !sessionLock.isUnlocked.value → Result.retry()     BEFORE Provider<T>.get() (SEC-002)
├─ settingsStore.observe().first()                    blur
├─ safeToSpend.get().observeSafeToSpend().first()     null is an answer, never ₹0 (P-03)
├─ netWorth.get().observeCurrent().first()
└─ CfoWidget.writeFigures(...)                        a null figure REMOVES its key

WidgetBlurWatcher.start()                             the BLUR path — no database at all
└─ settingsStore.observe().map{blur}.distinctUntilChanged()
    └─ CfoWidget.writeBlurred(context, blurred)
                          ⇣
        files/datastore/appWidget-<id>.preferences_pb          ← THE CACHE
                          ⇣
READ — in the launcher's process, and it may not fail
CfoWidgetReceiver → CfoWidget.provideGlance()
└─ currentState<Preferences>().toWidgetSnapshot()     no DI, no DAO, nothing that throws
    └─ GlanceTheme { CfoWidgetContent(snapshot) }
        ├─ amountText(amount, blurred, pending)       the only place a digit reaches a launcher
        └─ clickable(actionStartActivity(launchIntent))   resolved from the package manager
```

---

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `widget/build.gradle.kts` | Glance + the Compose compiler; `:core:model` only. No Hilt — nothing here is injected |
| `widget/src/main/AndroidManifest.xml` | Declares the `<receiver>`; merges into the APK now that `:app` depends on the module |
| `widget/src/main/res/xml/cfo_widget_info.xml` | `updatePeriodMillis="0"` — the system's 30-minute alarm is refused; WorkManager drives every update |
| `widget/src/main/res/values/strings.xml` | The five widget strings, now covered by `CfoHardcodedUiString` |
| `widget/.../CfoWidget.kt` | The `GlanceAppWidget`; `provideGlance` reads state only; `writeFigures` / `writeBlurred` are the two writers |
| `widget/.../CfoWidgetContent.kt` | The whole render, a pure function of a snapshot; `GlanceTheme` for light/dark |
| `widget/.../WidgetSnapshot.kt` | The cache's shape, its preference keys, and `amountText` — the branch that decides what a home screen may show |
| `widget/.../CfoWidgetReceiver.kt` | Plain `GlanceAppWidgetReceiver`, deliberately not `@AndroidEntryPoint` |
| `widget/.../ModulePlaceholder.kt` | Deleted |
| `widget/src/test/.../WidgetTextTest.kt` | Exhausts `amountText`: blur beats figure, figure beats zero, no digit survives the blur |
| `widget/src/test/.../WidgetSnapshotTest.kt` | Absence stays absence from an unwritten key to a pending label; a negative figure survives |
| `widget/src/test/.../CfoWidgetContentTest.kt` | The composed tree carries both figures, both masks, and two pending labels |
| `app/.../work/WidgetRefreshWorker.kt` | The sixth worker: lock check → both repositories → the cache. 6-hourly `KEEP` + `refreshNow` |
| `app/.../widget/WidgetBlurWatcher.kt` | Pushes the blur flag into the widget with no database touched; `publish` is `open` purely so a test can see it |
| `app/.../CfoApplication.kt` | Starts the watcher and schedules both refresh jobs |
| `app/src/androidTest/.../WidgetDeviceTest.kt` | Binds a real widget and inflates its real RemoteViews; skips by name if `appwidget grantbind` was not given |
| `app/src/test/.../work/WidgetRefreshWorkerTest.kt` | The locked path reads nothing; a missing figure is a success; two runs are idempotent |
| `app/src/test/.../widget/WidgetBlurWatcherTest.kt` | Cold start publishes; both toggle directions publish; a repeat does not; an unreadable store leaves the widget unmasked |
| `core/model/.../MoneyFormatter.kt` | Gains `mask` — one definition of the fixed-width mask for the app and the widget |
| `core/designsystem/.../component/PrivacyBlur.kt` | `maskOf` delegates; behaviour unchanged, and its untouched test proves it |
| `lint/.../HardcodedUiStringDetector.kt` | Scope covers `/widget/` and `com.aicfo.widget` |
| `lint/src/test/.../CfoLintDetectorsTest.kt` | A literal in the widget module is an error |
| `gradle/libs.versions.toml` | `glance-appwidget-testing`, test-only, on the pinned `glance` version |

---

## 4 · Verification

Full log: [tracker](../issues/5.5-home-screen-widget-glance-tracker.md#verification-log).

Headlines:

- `ktlintCheck detekt lintDebug` green; `unitTests` green on debug **and** release; `koverVerify` and
  `verifyPaparazziDebug` green.
- `:app:connectedDebugAndroidTest` **9/9**, with airplane mode on for the entire run (P-04).
- **Observed on a real launcher**, all of it in airplane mode: pending labels — not ₹0.00 — on a
  fresh profile; `₹18,675.00` / `₹3,59,925.00` matching the dashboard beside it; masking to
  `₹•••••••` on the blur toggle and back; correct in light and in dark; tap opens the app.
- **The SEC-002 guard was observed, not merely unit-tested**: `WM-WorkerWrapper` logged `RETRY` while
  the session was locked at cold start and `SUCCESS` on the retry thirty seconds later.
- Three red-checks were run deliberately, and one of them **failed to go red** — a literal threaded
  through a helper composable escapes `CfoHardcodedUiString`. Pre-existing, not introduced here, and
  recorded in the tracker's carry-forward rather than quietly left out.

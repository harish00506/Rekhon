# ADR-0024 — The home-screen widget renders from Glance state, not the database — and is not screenshot-tested

- **Status:** accepted
- **Date:** 2026-08-17
- **Deciders:** Harish G
- **SRS refs:** §5.2, §35; P-01, P-03, P-04, SEC-002, ARC-001; issues 5.5 and 5.3

## Context

§35 asks for a home-screen widget showing Safe-to-Spend and net worth, honouring the privacy blur
and updating from cached engine output. Four questions had to be answered before that could be
code, and three of them have an obvious answer that is wrong here.

**Where does the widget read its figures?** The obvious answer is "the same repositories the
dashboard reads". `CoreModule.provideDatabase` **throws** when the session is locked (SEC-002), and
a home screen is looked at far more often locked than unlocked. A widget that touched Room on its
render path would blank out or crash precisely when it is most visible.

**What cache does it read, then?** Net worth already has one — `net_worth_snapshot`, written daily
by `NetWorthSnapshotWorker`. Safe-to-Spend has none: `RoomSafeToSpendRepository` recomputes it live
from five Room reads on every emission. A cache had to be created either way, and it had to live
outside SQLCipher for the reason above.

**How does the blur reach it?** Issue 5.3 built the blur as a Compose `CompositionLocal` read at two
points in `:core:designsystem`. Glance renders through `RemoteViews` in the launcher's process and
cannot see a Compose local. 5.3 left this acceptance criterion unticked rather than claiming it.

**How is it screenshot-tested?** Every other UI surface in this repo has Paparazzi baselines, and
the acceptance criteria ask for light/dark.

## Decision

**Glance's own preference state *is* the cache.** `CfoWidget` uses `PreferencesGlanceStateDefinition`
and `provideGlance` reads `currentState()` and nothing else — no Hilt entry point, no repository, no
suspend call that can throw. The module has no dependency injection at all.

**`:app` is the only writer, through two deliberately separate calls.**
`WidgetRefreshWorker` (six-hourly, plus one on each app launch) checks `SessionLock` first, resolves
both repositories as `Provider`s, and writes the figures. `WidgetBlurWatcher`, started in
`CfoApplication` beside `SmsConsentWatcher`, writes **only** the blur flag.

**A `null` figure removes its key rather than writing zero.** Absence travels from an unwritten
preference to a "Not yet worked out" label.

**`maskOf` moved from `:core:designsystem` down to `MoneyFormatter.mask` in `:core:model`.** The
design-system function now delegates to it.

**The widget is not screenshot-tested, and the tracker says so rather than shipping a baseline that
checks nothing.** It is covered by `runGlanceAppWidgetUnitTest` (content, blur, pending) plus a
plain-JUnit test of `amountText`, the one function that can put a digit on a home screen.

## Consequences

- **Positive (state as cache):** the value the widget reads is the value it was redrawn for. A
  separate proto field or Room table would be a second store to fall out of step with the redraw,
  and the redraw is what the user actually sees.
- **Positive (state as cache):** the render path cannot throw. The widget survives a locked device,
  a wiped profile and an airplane-mode boot, because none of those change what is in a preference
  file.
- **Negative / cost (state as cache):** two amounts now sit in an **unencrypted** app-private file,
  outside SQLCipher. This is a real reduction and it is accepted knowingly: the figures are on the
  home screen in plain sight by design — that is the feature — and the file is inside the app
  sandbox and under file-based encryption at rest. The alternative buys nothing, because a widget
  that could only render with the database open would not render.
- **Negative / cost (state as cache):** Safe-to-Spend is now cached in two shapes — computed live
  for the dashboard, and stored for the widget. They can disagree for up to six hours. Mitigated by
  `refreshNow` on every app launch, and bounded by the fact that a stale widget is a display fault,
  not a wrong figure: nothing reads the cache back.
- **Positive (two writers):** turning the blur on hides the widget **while the app is locked**. If
  the flag rode along with the figures, hiding would depend on the database, and the amounts would
  stay on the home screen exactly when the user asked for them to go.
- **Negative / cost (two writers):** the flag is written from two places (the watcher, and the
  worker's own pass). Deliberate: a refresh landing between the toggle and the watcher would
  otherwise repaint with the old flag and the new figures.
- **Positive (`mask` moved):** one definition of how wide the mask is. Two would be two definitions
  of how much the blur leaks, and the fixed width is the entire point of ADR-0022.
- **Negative / cost (`mask` moved):** `:core:model` gains a display concern. Small — it already owns
  `MoneyFormatter.format` for the same reason, and neither has an Android import (ARC-002 holds).
- **Negative / cost (no screenshots):** the widget's pixels are checked by a person, not by CI. A
  colour-token regression in light or dark would not turn the build red. Bounded: the layout is two
  labels and two figures, and it uses `GlanceTheme`'s colours rather than a hand-copied palette, so
  there is very little that can drift.
- **Follow-up:** if Roborazzi is ever added for another reason, `GlanceAppWidget.compose()` returns
  `RemoteViews` that Robolectric can inflate, and this becomes a real screenshot test for the cost
  of one test file. It is not worth a second screenshot framework on its own.

## Alternatives considered

- **Read repositories in `provideGlance` via a Hilt entry point** — rejected: crashes or blanks on a
  locked device (SEC-002), and puts a five-way Room join on the launcher's draw path.
- **A new `widget_snapshot` Room table** — rejected: it lives inside the encrypted database, so the
  widget still could not read it while locked. It would have solved nothing and cost a migration.
- **New fields on `CfoSettingsProto`** — rejected: cached financial figures are not settings, and
  Glance would still need its own state to trigger the redraw, so this adds a store rather than
  replacing one.
- **Read the blur live in `provideGlance`** — rejected: it needs Hilt in the widget *and* still
  needs something to trigger the redraw when the flag flips, so it is strictly more machinery for
  the same result. It would handle a system-initiated redraw (reboot, resize) more freshly; that
  redraw uses the last written flag, which is no staler than the amounts beside it.
- **A "last updated" line on the widget** — rejected: rendering a date correctly needs the profile
  time zone (TIM-001), which means reading settings on the render path — the thing this design
  exists to avoid — and it would make the refresh non-idempotent, since every run would write a new
  value even with both figures unchanged.
- **A one-hour or 15-minute refresh** — rejected: `refreshNow` already covers the case that matters
  (the user has just been in the app), and a tighter alarm spends battery redrawing figures nobody
  looked at.
- **Widen `maskOf` in place and depend on `:core:designsystem` from `:widget`** — rejected: it drags
  a whole Material3 Compose module in for three lines that contain no Compose.
- **Paparazzi over a plain-Compose mirror of the widget layout** — rejected, and this is the
  tempting one: it would produce green baselines that prove nothing, because the thing rendered
  would not be the thing shipped. A screenshot test of a different tree is worse than none, since it
  reads as coverage.

## Compliance with golden rules

- **P-01:** no data leaves the device; the blur reaches the launcher, and the mask is fixed-width so
  no magnitude leaks (ADR-0022). The cache's move outside SQLCipher is stated as a cost above, not
  glossed.
- **P-03:** the widget computes nothing. Every figure comes from `SafeToSpendEngine` or
  `NetWorthEngine` through their repositories, and an absent figure renders as a label, never ₹0.00.
- **P-04:** no network on any path, no `Constraints` on the worker; identical behaviour in airplane
  mode.
- **P-07:** the widget states a position and opens the app; it moves no money.
- **P-08:** nothing written is derived from the wall clock, which is what makes the refresh
  idempotent by construction rather than by a claim.
- **ARC-001:** `:widget → :core:model` only. The tap action resolves the launcher intent from the
  package manager rather than naming `MainActivity`, so the widget never depends on `:app`.
- **MNY-001:** paise as `Long` end to end — `longPreferencesKey`, `Money`, `MoneyFormatter`.
- **SEC-002:** the lock is checked before any repository is resolved, and the blur write bypasses the
  database entirely so it works while locked.

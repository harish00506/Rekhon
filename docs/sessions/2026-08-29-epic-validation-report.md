# 2026-08-29 — Validation sweep of Epics 1–6.4, and one root cause

**Scope:** every shipped feature through issue 6.4. Automated suites, instrumented suites on AVD
`CfoTest`, and manual exercise of the user-facing flows on a freshly wiped install.

**Headline:** the engines, the data layer and the screens all work. Every figure I checked was
correct. One root cause produces a family of defects, and it is a golden-rule violation rather than
a cosmetic one.

---

## 1 · What passed

| Gate | Result |
|------|--------|
| JVM unit tests, every module | **3 289 passed**, 0 failed, 0 skipped |
| `ktlintCheck` · `detekt` · `lintDebug` | green |
| `koverVerify` (engine ≥ 85%, money 100%) | green |
| Instrumented, `:core:database` | **22 passed** — encrypted store, and every migration 1→18 round-tripped |
| Instrumented, `:app` | **9 passed** — real graph boots, ML Kit reads a receipt, four real SMS-provider tests, two widget tests |
| Instrumented, `:feature:onboarding` | 1 passed |
| **Instrumented total** | **32 / 32, zero skipped** |
| Room schema fixtures 1…18 | all present |
| `RuleCitation`s in code vs `rules-kb.json` + `classification-kb.json` | 24 checked, 0 dangling |
| `TODO` / `FIXME` / `NotImplementedError` in main source | none |
| Crashes in the device log across the whole sweep | none |

**The five build-failing lint detectors were proven, not assumed.** A probe file tripped all five in
one `lintDebug` run — `CfoMoneyAsFloatingPoint` (MNY-001), `CfoWallClockInDomain` (TIM-001),
`CfoGlobalScope` (ARC-006), `CfoHardcodedUiString` and `CfoPiiInLogs` — and the build aborted. The
probe was then deleted and lint returned green. This repo has shipped a documented gate that nothing
ever ran (issue 2.6), so "the detector has passing unit tests" was not accepted as evidence.

**Two gates that had never actually executed now have.** `WidgetDeviceTest`'s two tests had been
skipping since issue 5.5 because they need `adb shell appwidget grantbind`, and gradle's per-run
reinstall revokes that grant. Installing both APKs by hand, granting, and driving the instrumentation
directly made them run — and pass. Issue **3.9**'s outstanding emulator gate is likewise closed by
the four `SmsInboxDeviceTest` cases passing against the real content provider.

**Manually exercised end to end, all correct:** six-step onboarding on a wiped install; account
creation (net worth `+₹2,50,000.00`); add-transaction in **3 taps** (amount → category → Save);
nature classification (`Needs ₹1,250.00 · Wants ₹0.00 · Kept ₹0.00`); budget CRUD with pace
(`₹935.40 by today`) and projection (`₹1,336.12 — over budget`); the over-budget alert surfacing as
`1 category needs attention · Rule RULE-BUD-ALERT v1.0`, which closes issue **4.5**'s outstanding
gate; export to a valid 8.3 kB schema-versioned archive and a destructive import round-trip that
restored every row; Safe-to-Spend computing `+₹89,000.00` with its `RULE-STS v1.0` citation; and
6.4's allocation screen with all three concentration rules firing.

---

## 2 · The defect, and its root cause

### What I observed

A brand-new user who taps past onboarding's optional **"A head start (optional)"** step lands on a
dashboard that says, in two places:

> *Not worked out yet — add your monthly income **in Settings** and we'll work it out.*
> *No budget yet. Add your monthly income **in Settings** and we'll suggest one.*

There is no Settings screen. `CfoRoute` has twelve destinations and none is Settings; there is no
`:feature:settings` module; and none of the 85 backlog issues creates one. I then set a ₹1,000
budget through the Budgets screen and the dashboard **still** said "No budget yet" beside a card
correctly reading `₹1,250.00 of ₹1,000.00 spent`.

### Root cause

Two distinct budget stores exist, and the dashboard reads the wrong one.

- `QuickSetupRepository.observeLatestEnvelopes()` returns the **quick-setup envelope plan**, written
  exclusively by onboarding.
- Issue 4.4 later added real per-category **budgets** in their own table, with their own screen.

`DashboardViewModel.observeBudget()` and `SafeToSpendRepository.incomeBasis()` both still read the
first. `QuickSetupRepository`'s own doc comment records the assumption that has since expired:

> *"'The newest period that exists' is the honest answer while the only budgets in the app came from
> onboarding."*

That was true when it was written and stopped being true when 4.4 shipped. Nothing rewired the
dashboard, and the empty-state copy was written pointing at a Settings screen that FR-SET-001
specifies but which was never built.

### The blast radius is wider than the dashboard

Grepping for which module can write each user-controllable setting returns exactly one answer for
all three:

| Setting | Written only by | Reachable after first run? |
|---|---|---|
| Monthly income | `:feature:onboarding` | **No** |
| `SMS_PARSING` consent | `:feature:onboarding` | **No** |
| App lock (biometric/PIN) | `:feature:onboarding` | **No** |

`CfoNavHost` registers `CfoRoute.Onboarding` and pops it `inclusive = true` on finish; it is the
start destination only on first launch. So all three are frozen at whatever the user chose in their
first minute.

### Why this is a golden-rule violation, not a polish item

**P-01 requires consent to be "explicit, revocable, per-feature."** SMS parsing can be granted at
onboarding and then **never revoked from the UI**. The data layer does support revocation —
`SmsConsentWatcher` purges drafts when the consent flips — but nothing can flip it. The capability
exists and is unreachable.

Secondarily, **the app lock (issue 2.2, a security feature) cannot be enabled by anyone who skipped
it**, and five user-visible strings across `:feature:dashboard`, `:feature:onboarding` and
`:feature:transactions` promise a Settings screen that does not exist.

### Severity

**High, but not data-threatening.** Nothing is computed wrongly and nothing is lost. Safe-to-Spend
degrades rather than dies — its ledger-income fallback works, which is how it reached ₹89,000 for me
after I recorded income. The damage is that a privacy control the golden rules call revocable is
not, a security control is one-shot, and the app tells users to visit a place that does not exist.

---

## 3 · What I did not change, and why

The correct fix is a Settings screen: income, the consent ledger, and the app-lock toggle. That is
a feature — a new module, route, screen, ViewModel, strings and tests, comparable in size to issue
6.4 — and it is absent from the backlog, so it needs an issue and probably an ADR for the
dashboard's store switch. Building it unrequested in the middle of a validation pass would be scope
I was not asked for, so I stopped at the diagnosis.

**Recommended, in priority order:**

1. **New issue — Settings screen (FR-SET-001).** Income, consents, app lock. Closes the P-01
   revocability gap and unfreezes the security control. Issue 11.3 ("consents dashboard, one-tap
   revoke") already covers a third of it and is still Todo — this may be a re-scope of 11.3 rather
   than a new issue.
2. **Rewire the dashboard's spend split to the 4.4 budgets table**, or state deliberately that the
   split means the onboarding plan and fix the copy accordingly. Needs an ADR either way, because it
   changes what a shipped figure means.
3. **Correct the five "in Settings" strings** so they name a reachable action. Safe to do
   immediately and independently of 1 and 2.

---

## 4 · Process findings

- **Issue status lines are unreliable.** Every Epic 1 and 2 issue still reads `Todo` while its code
  demonstrably works; 4.1 reads "not committed" but is committed as `baf0a03`. The backlog cannot
  currently be read as a progress signal.
- **There is no E2E smoke matching the workflow's own description.** Step 9 of
  `00-issue-workflow.md` describes "onboard → add data → verify dashboard/forecast → export/import
  round-trip → airplane-mode pass". `CfoSmokeTest` has two tests and does none of that chain. It is
  issue **12.4**, still Todo — so the end-to-end claim rests on manual runs like this one.
- **`connectedDebugAndroidTest` uninstalls the app afterwards**, which silently revokes any shell
  grant a device test depends on. That is why the widget gate had never run. Worth a line in the
  workflow.

---

## 5 · Corrections to my own earlier statements in this session

Recorded because two of them were wrong in the direction of alarm:

- I first reported the seven `CLS-NAT-*` / `CLS-USER-HISTORY` citations as dangling. They are not —
  they cite `ai/knowledge/classification-kb.json`; my check only read `rules-kb.json`.
- I first reported that Safe-to-Spend was permanently disabled for a user who skips quick setup.
  It is not — `incomeBasis` falls back to posted ledger income, which I then proved on the device.
  The correct claim is that it is *degraded*, and that the copy explaining it is wrong.

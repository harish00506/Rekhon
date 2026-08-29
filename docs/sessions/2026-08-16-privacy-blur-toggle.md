# Session — 2026-08-16 — Issue 5.3, Privacy blur toggle

**Branch:** `feature/5-3-privacy-blur-toggle` → `dev` · **VERSION:** 0.5.2 → 0.5.3
**Issue:** [5.3](../issues/5.3-privacy-blur-toggle.md) · **SRS:** §23, FR-PRIV-*, P-01

A one-tap blur that hides every amount, for shoulder-surfing and screen-sharing. The persistence
half already existed and had never been read; this session built the UI half and the capture guard.

---

## 1 · Decisions this session

### 1.1 The blur masks text; it does not blur pixels

Recorded as **[ADR-0022](../adr/0022-privacy-blur-masks-text-and-sets-flag-secure.md)**. The
requirement says "blur", Compose has `Modifier.blur`, and it is the wrong tool three times over: it
is a **silent no-op below API 31** (a privacy feature whose failure mode is "looks like it worked"),
it does not stop a screenshot or a screen-share — half of what the requirement names — and blurring
a region hides the labels and navigation along with the figures.

Masking the text is testable as an assertion rather than an eyeball, which is what made §1.5 below
possible at all.

### 1.2 The mask is fixed-width, and that is the whole security property

`₹•••••••` for every amount, whatever its magnitude. The tempting version — dots matching the
number's own length — hides the digits and **leaks the order of magnitude**, which for a salary or a
balance is most of what makes the figure sensitive. It would also look completely convincing in a
screenshot, and no reviewer would catch it.

So `PrivacyBlurTest` pins it as a property over seven orders of magnitude rather than as an example:
₹0.01 and ₹10,00,00,000 mask to the identical string, asserted as a one-element set.

The sign survives. Direction is not the secret, and `CfoAmountText` colours by sign — dropping the
minus would leave colour as the only signal of direction, which that component's own doc comment
records as the thing it exists to prevent (P-02, greyscale, colour-blind users).

### 1.3 Both rendering paths, not just the component

The load-bearing discovery of the session. Amounts reach the screen **two** ways:

| Path | Sites | Blurs from the component alone? |
|---|---|---|
| `CfoAmountText(amount)` | 14 | yes |
| `MoneyFormatter.format(x)` into a `stringResource` placeholder | ~24 | **no** |

The second exists because a sentence cannot contain a composable — "Received %1$s · Spent %2$s",
"%1$s of %2$s spent". Patching only `CfoAmountText` would have left the cash-flow line, the budget
totals, the whole budgets screen and 5.2's own Safe-to-Spend breakdown perfectly readable, while
looking finished.

Hence `maskedAmount(amount)` — a `@Composable` with the same shape as `MoneyFormatter.format`, so
converting a call site is a one-word edit. Composable *because* that is what lets it read the local;
a plain function would have to take the flag as an argument, which is the forty-signature problem
again.

### 1.4 A `CompositionLocal`, and a plain `Boolean`

`LocalPrivacyBlur`, `static` (it changes only on a tap, and amounts are on the hottest screens).
Provided once in `AppContent` around the whole nav graph — the argument `CfoDemoBanner` already
makes one level down: no destination, existing or added later or deep-linked, can render outside it.

**A `Boolean`, not a settings object**, so `:core:designsystem` keeps depending on `:core:model` and
nothing else (ARC-001). Reading `SettingsStore` there would drag `:core:datastore` into every module
that draws a button.

### 1.5 The test asserts the absence of amounts, not the presence of masks

`DashboardPrivacyBlurTest` (Robolectric, so it runs on every `unitTests` rather than only with a
device) sweeps **every rendered string in the tree** for `₹`-plus-digit. Deliberately not "the
masked strings are there": this feature's failure mode is a *missed call site*, and a test that
checked for masks would pass while a real figure sat beside them.

It has a control — "amounts are on screen when the blur is off" — because without it the sweep
passes perfectly against a screen that rendered nothing at all.

**Watched go red:** one dashboard call site reverted to `MoneyFormatter.format` fails the build.

### 1.6 `FLAG_SECURE` while the blur is on — 5.3's share of issue 11.2

The criterion says "combined with FLAG_SECURE (11.2)", and 11.2 is unbuilt. A mask stops someone
*reading* the screen; only `FLAG_SECURE` stops a screenshot, a recording or a shared call — and if
the user turned the blur on during a call, the call is the thing carrying the figures away.

So `PrivacyCaptureGuard` sets it from the same flag that drives the mask, with `onDispose` clearing
it (otherwise a blurred session leaves every later screen uncapturable, which looks like a broken
phone). 11.2 keeps the policy: always-on or not, per-screen exemptions, the recents thumbnail.

Lint caught the first version — `LocalContext as? Activity` trips `ContextCastToActivity`, correctly;
it is `LocalActivity` now.

### 1.7 The notification is the most exposed surface, so it blurs too

A budget alert renders on the **lock screen**, without the app lock, to anyone who glances at a phone
face-up on a table — precisely the shoulder-surfing case §23 is about. Masking the on-screen figures
while a notification announced "You have spent ₹8,000 of ₹10,000" would leave the biggest hole open.

Blurred, the message carries the category and the band and **no digits at all** — not even the
percentage, which still says how deep into the budget the user is.

The flag reaches it as a **parameter**, read once per batch by `BudgetAlertWorker` (already a
coroutine), rather than by a `SettingsStore` read inside the notifier: that keeps the interface
synchronous, keeps DataStore out of the class whose job is Android's notification stack, and makes
both message variants equally easy to assert.

### 1.8 Editable amount fields are exempt

Add-transaction, the account editor, the budget editor, and the transaction filter's bounds keep
`MoneyFormatter.format`. Masking what someone is typing replaces their input with dots and leaves
them unable to correct it; a filter bound is also a number the user supplied, not a fact about their
money. The first sweep converted the two filter fields and the compiler caught it — `remember { }`
is not a composable scope — which was the right answer arriving for the wrong reason.

### 1.9 Fail **open**, deliberately

An unreadable settings store leaves amounts visible. Failing closed would turn a DataStore hiccup
into an app where every figure has vanished for a reason the user cannot diagnose. The blur is a
display preference; the security boundary is the app lock (SEC-002), which does fail closed. Pinned
by a test so it cannot be "fixed" later.

---

## 2 · Flow changed this session

New **[FLOW.md §2.0](../../FLOW.md)** — the only value in the app that travels down the whole tree:

```
SettingsStore.observe() → MainViewModel.isPrivacyBlurred
  ├─ PrivacyCaptureGuard  → FLAG_SECURE add/clear      (capture blocked)
  ├─ CfoPrivacyBlurToggle → setPrivacyBlur → DataStore (persists, re-emits)
  └─ CompositionLocalProvider(LocalPrivacyBlur) { CfoNavHost }
         ├─ CfoAmountText(amount)      14 sites
         └─ maskedAmount(amount)      ~24 sites
              └─ maskOf → "-₹•••••••"  fixed width, sign kept

BudgetAlertWorker.doWork()
  └─ settingsStore.observe().first() → notifier.notify(alert, blurAmounts)
```

---

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `core/designsystem/component/PrivacyBlur.kt` | **New** — `LocalPrivacyBlur`, `maskedAmount`, `maskOf` |
| `core/designsystem/component/CfoAmountText.kt` | Reads the local; discards the caller's content description while blurred |
| `core/designsystem/res/values/strings.xml` | **New** — `cfo_amount_hidden`, the module's first string, with the exception argued |
| `core/designsystem/test/…/PrivacyBlurTest.kt` | **New** — the fixed-width property and what survives the mask |
| `app/PrivacyBlurToggle.kt` | **New** — the chrome toggle, `stateDescription` for on/off |
| `app/PrivacyCaptureGuard.kt` | **New** — `FLAG_SECURE` follows the flag, cleared on dispose |
| `app/MainViewModel.kt` | `isPrivacyBlurred` + `setPrivacyBlur`; fails open |
| `app/MainActivity.kt` | Provides the local around the graph, hosts the toggle and the guard |
| `app/notification/BudgetAlertNotifier.kt` | `notify(alert, blurAmounts)`; figure-free variants |
| `app/work/BudgetAlertWorker.kt` | Reads the flag once per batch and passes it through |
| `feature/{dashboard,budgets,accounts}/**` | ~24 `MoneyFormatter.format` → `maskedAmount` at read-only sites |
| `feature/transactions/TransactionsFilterUi.kt` | Unchanged behaviour; the exemption documented |
| `feature/dashboard/test/DashboardPrivacyBlurTest.kt` | **New** — the whole-screen sweep |
| `feature/dashboard/test/DashboardFixtures.kt` | **New** — `populatedDashboardState()`, extracted so the sweep and the screenshots render the same screen |
| `feature/dashboard/{build.gradle.kts,DashboardScreenshotTest.kt}` | Robolectric/Compose-test deps, release-variant exclusion, `blurred_light` baseline |
| `app/test/FakeAppSettingsStore.kt` | **New** — writes visible to reads, so a ViewModel that never wrote cannot pass |
| `docs/adr/0022-*.md` · `DECISIONS.md` · `FLOW.md` | The decision, its index row, the new path |
| `VERSION` · `app/build.gradle.kts` · `CHANGELOG.md` | 0.5.3, `versionCode` 20 |

---

## 4 · Verification

Full log in the [tracker](../issues/5.3-privacy-blur-toggle-tracker.md#verification-log). Headlines:

- `ktlintCheck detekt lintDebug unitTests koverVerify verifyPaparazziDebug` — all OK
- `:app:connectedDebugAndroidTest` — **7/7** from a clean install
- **Emulator, airplane mode:** every amount masked on the dashboard *and* the accounts screen;
  `adb exec-out screencap` returned **0 bytes** with the blur on and **178,382** with it off; the
  blur survived a force-stop; TalkBack labels read "Amount hidden", never a figure.

**Not met, and filed rather than ticked:** "honoured by the widget". `:widget` is still a
`ModulePlaceholder` — the Glance widget is issue 5.5. Carried there.

**Pre-existing, seen again:** `CfoSmokeTest.theRecurringSectionProposesASeriesFromTheRealLedger`
failed while the emulator held demo data from manual driving, and passed 7/7 after a clean
reinstall. Same conclusion as the 5.2 session, now with the negative and positive both observed:
the smoke suite is not hermetic about app data. Worth its own issue if it keeps costing a re-run.

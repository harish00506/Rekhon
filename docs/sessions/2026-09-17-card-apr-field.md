<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: the card APR field, the follow-up the 7.5 device run found (FR-ACC-002, MNY-002, §36).
  Result: a reader can see why the field was missing, what adding it changed beyond the field, and
          how the loop from the order-of-operations screen was proven on a device.
  Changelog: 2026-09-17 — Created.
-->

# 2026-09-17 — The card's interest rate (follow-up to 7.5)

**Branch:** `feature/6-1-card-apr-field` off `dev` (`350464b`) · **VERSION** 0.7.5 → **0.7.6** ·
**versionCode** 31 → 32 · **Schema** 22, unchanged · no engine version moved

---

## 1 · Decisions this session

### 1a · Reuse the loan's rate conversion

The loan section (6.2) already reads percent as basis points through `parseRateBps` and shows them
back through `formatRatePercent`. Those use `MoneyFormatter`'s two-decimal text arithmetic —
1% is 100 bps exactly as ₹1 is 100 paise — so `42.5` becomes `4250` with no `Double`, `8.555` is
refused rather than rounded, and a stored `3600` reopens as `36.00`. The card field uses the same
two functions; nothing new was written for the conversion.

### 1b · Optional, and blank means "not recorded"

A new card has no statement to read a rate from, and a 0% card would rank as low-rate debt. So
the field is optional, blank maps to `aprBps = null`, and AI-FOO keeps treating that as fire debt,
saying so on screen.

### 1c · A partial card section is now an error

`CreditCard` needs its limit and both days. 6.1 made that all-or-nothing and **silently dropped** a
partial section on save. That was tolerable while the optional fields were a statement amount and a
minimum. It is not tolerable for a rate: the order-of-operations screen now sends people to this form
*to enter a rate*, and "saved" with the rate thrown away is the worst answer the form could give.

`hasPartialCardTerms` — any card field typed while the three required ones are incomplete — now
returns the validation error and keeps the typing on screen. A wholly blank section still saves as
"no terms yet". This changes 6.1's behaviour for a partial section with only a statement amount, in
the same direction and for the same reason; no existing test depended on the silent drop.

### 1d · Bring the link back

7.5 removed "Add the rate in Accounts" because it led to a screen that could not keep the promise.
It can now, so the link and its action (`OrderOfOperationsActions.onOpenAccounts`) are restored and
the reason text asks for the rate again. A Compose test asserts the link appears **only** for an
unrated card and navigates.

---

## 2 · What only running it could find

Nothing new was wrong on the device. What it proved, in order:

| Step | Observed |
|---|---|
| Full order, ICICI card with no terms | Step 3, "rate not entered", reason asks for the rate, **"Add the card's rate in Accounts"** shown |
| Follow the link → ICICI → Edit | The card section now ends with the rate field (then labelled *Interest rate (% a year)* — see the lint note) and the new help text |
| Limit 1,50,000 · days 12 / 2 · rate **12** → Save → Back | The full order had already recomputed: ICICI in **Step 7**, `12.00% a year`, beside the 12.00% equity comparison; Step 3 holds only the 14% car loan, with no rate prompt |
| Edit → rate **42** → Save | ICICI back in **Step 3** at `42.00%`, sorted above the 14% loan |
| Reopen the editor | the rate loads as **`42.00`** |
| After the lint rename, reinstall and reopen | the field reads **Annual interest rate (%)**, still `42.00` |

The crash buffer held one entry — a native abort in `com.google.android.bluetooth`, the emulator's
own Bluetooth stack — and none for the app.

**Two automation slips, neither the app's.** The emulator had exited between sessions, so the first
`installDebug` failed and the UI helper then waited on a device that was not there. And the helper
first searched the account list in the wrong direction; it now scrolls down, and anchors on the
row's "ICICI Bank" subtitle because the name can scroll off the top.

**Lint caught what the device could not.** The first label was *Interest rate (% a year)*. Compose
renders it fine, which is why the device run looked right, but Android lint fails the build on it
(`StringFormatInvalid`): `% a` parses as a format specifier and would throw the day anything formats
that string. Renamed to *Annual interest rate (%)*, the loan field's own pattern.

**Gates proved red:** APR not carried into `CreditCard` → *saved in basis points* and *survives a
re-save* FAILED; the partial check removed → *reported, not silently dropped* FAILED.

---

## 3 · Flow changed this session

Added to `FLOW.md` §2.8:

```
OrderOfOperationsScreen ─ "Add the card's rate in Accounts"   (only under a card with no rate)
└─ CfoRoute.Accounts → AccountEditorScreen → "Annual interest rate (%)"
    └─ AccountEditorViewModel.save
        ├─ hasPartialCardTerms → Err(validation)
        └─ toCreditCard: parseRateBps → CreditCardRepository.save → credit_card.apr_bps
            └─ creditCardDao.observeForProfile re-emits → the ranking moves the card
```

---

## 4 · Code changed this session

| Path | What it does now |
|---|---|
| `feature/accounts/…/AccountsUiState.kt` | `aprText`; `hasPartialCardTerms`; `CardField.APR` |
| `feature/accounts/…/AccountEditorViewModel.kt` | fills, edits, parses and saves the APR; a partial card section is a validation error |
| `feature/accounts/…/AccountEditorScreen.kt` | the rate field in the card section |
| `feature/accounts/src/main/res/values/strings.xml` | the field label; the help text says what is optional and what a partial section does |
| `feature/accounts/src/test/…` | 7 ViewModel tests, 1 Compose test, the APR assertion in the card-section test |
| `feature/dashboard/…/OrderOfOperationsScreen.kt`, `strings.xml` | the Accounts link and its reason text restored |
| `feature/dashboard/src/test/…/OrderOfOperationsFlowTest.kt` | the link appears only for an unrated card, and navigates |
| `app/…/navigation/CfoNavHost.kt` | `onOpenAccounts` wired again |
| `domain/engines/orderofoperations/…/OrderOfOperationsEngine.kt`, `ENGINE.md` | wording only |
| `docs/adr/0037-…`, `DECISIONS.md`, `FLOW.md`, `CHANGELOG.md`, `VERSION`, `app/build.gradle.kts`, `docs/memory.md` | the records |

---

## 5 · Not delivered

- **A rate history.** The field holds today's APR; a card whose rate changes is simply edited.
- **Rate on the card row.** The accounts list still shows utilisation, not the rate; the rate is
  visible in the editor and on the full-order screen.

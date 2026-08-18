# Session — 2026-08-18 — Issue 6.1, Credit card details + alerts

**Branch:** `feature/6-1-credit-card-details-alerts` → `dev` · **VERSION:** 0.5.5 → 0.6.1  
**Issue:** [6.1](../issues/6.1-credit-card-details-alerts.md) · **SRS:** §5.7, §11, §17.1; FR-ACC-002;
P-01, P-02, P-03, P-04, P-08

Epic 6 opens with the account type where *dates* cost money. Every other balance in this app is a
number the user reads at leisure; a credit card has a statement day, a due day, a minimum due that
avoids a late fee, and a utilisation ratio a bureau records for years. Miss the date and the cost is
a late fee plus interest on the whole statement — the most expensive ordinary mistake this app is in
a position to see coming.

Until this issue a card was an `AccountType` and nothing else: it had a balance, and the app knew
none of its terms. This session gave it terms, an engine, two notifications, and the table that
stops those notifications repeating.

---

## 1 · Decisions this session

### 1.1 `RULE-CC-UTIL` was read, not written; the date question got its own row

`RULE-CC-UTIL` shipped long before this issue. It already carries `max_utilisation_pct: 30` and
already names `card_alerts` in its `consumed_by` — the rulebook was written expecting this engine.
So the utilisation band is *read* exactly as it stands and the row is untouched.

It has no reminder window, though, and there is nowhere in it a date belongs. Adding
`remind_days_before` to its `params_json` is the obvious move and the one ADR-0019 already refused
once for `RULE-BUD-PACE`: a params change bumps the row's version (§29), which is ADR-0017's
**trigger 3** — and honouring trigger 3 means building `:core:rules`, an `ai/`-to-assets pipeline, a
`rules_knowledge_base` table and a migration, and retrofitting every shipped mirror, before this
issue could compile.

So `RULE-CC-DUE` v1.0 was minted instead. The split is right on the merits too: `RULE-CC-UTIL` scores
*how much of a limit is in use* and feeds the health score, while `RULE-CC-DUE` decides *when to
interrupt someone about a date*. §17.1 classes a card payment as a **Critical money event** — exempt
from NTF-001's daily budget, allowed past quiet hours — while a utilisation nudge is ordinary
discipline on an ordinary channel. One row with both jobs would have one severity for two severities'
worth of decisions. [ADR-0025](../adr/0025-card-alerts-mint-rule-cc-due-and-claim-per-statement-cycle.md).

`_meta.version` moved 1.12.0 → 1.13.0. That number describes the *file*, so `BudgetRules` and
`SafeToSpendRules` restate their pins with no row of their own changing — the drift gate caught both
on the first `unitTests` run, which is the gate doing its job for the second issue running.

### 1.2 The alert claim is keyed by the statement date, not the month

`budget_alert` claims by `(profile, budget, month_start, band)` and a card looks like the same
problem. It is not. A card billing on the 25th has a cycle straddling two calendar months: the
statement cut on 25 August and due on 14 September would be claimed under August, re-derived on
1 September, find no September claim, and remind a second time about one bill.

So `card_alert`'s key is `UNIQUE(profile_id, account_id, cycle_start_iso_date, kind)`, where
`cycle_start_iso_date` is the statement date the engine computed. `kind` is in the key because
`DUE_SOON` and `UTILISATION` can both be true on the same day and go to different channels — a key
without it would silently drop one. As with budget alerts, the claim is a database constraint and the
worker claims *before* it notifies: a crash between the two costs one notification, never a duplicate.

The bug this avoids is invisible for cards billing early in the month, which is exactly what makes it
worth a decision record rather than a comment.

### 1.3 Two utilisations, both returned, both labelled

"Utilisation" is one word for two true numbers: the live outstanding ÷ limit, and the last statement
÷ limit. The app shows both, each carrying its `UtilisationBasis`, because a single figure labelled
"utilisation" would be one of them with no way for the user to tell which (P-02).

The **alert** acts on the statement figure only. A live figure moves with every swipe, so an alert
built on it would either nag or be claimed once and then be wrong for the rest of the cycle. The
statement is also what a bureau records, which is what `RULE-CC-UTIL`'s rationale is actually about.

### 1.4 A card with no statement is absent, not zero

A card whose statement has never been recorded gets `null` utilisation and no alerts — not 0%.
Rendering absence as zero would claim the user owes nothing, which is a fabricated number on a
screen about debt (P-03). It is also why the due reminder reads `lastStatement` rather than the live
balance: the statement is the bill, and spend since the cut is not owed yet.

### 1.5 The engine reads no clock, so a whole billing cycle is a golden file

`today` is an argument (TIM-001; `CfoWallClockInDomain` would fail the build anyway). That is what
makes the acceptance criterion's "golden-file test on a billing cycle" literal: the fixture walks one
₹2,00,000 card day by day — statement day, mid-cycle, the window opening, the due day, the day after
— and asserts the full answer at each step. A rule that fires a day early or stops a day late is a
diff here, where the same bug is invisible to a test that only ever asks about one day.

The calendar edge that needed the care is clamping: a card billing on the 31st, in February, and the
`!isAfter` (not `isBefore`) comparison that keeps the statement day itself inside the new cycle
rather than sending the user back a month every time it comes round.

### 1.6 Card terms live on the account editor as text, with no nested state object

The editor's first type-specific fields, held as `String` like the opening balance and parsed once on
Save — a half-typed "2,00,0" is a real intermediate state and parsing per keystroke fights the user.
Nullable-by-blank rather than a nested `CardFields?`, because the ten other account types simply
leave them empty and a nested object would be created the moment the user picked `CREDIT_CARD` and
discarded the moment they changed their mind.

### 1.7 The card's own name is declared as *text* to the guardrail

`NumericGuardrail.verify` runs over the composed notification with the engine's own figures as the
allow-list (AI-ARC-004). "HDFC Regalia 4521" is an ordinary thing to call a card, and without
declaring the account name as allowed text its digits read as an unverifiable count and a correct
reminder is silently dropped — the failure issue 4.6 hit with category names, avoided here by
precedent rather than by rediscovery. `usedPercent` lives on `CardAlert` rather than in the notifier
so the message and its allow-list cannot round differently.

The privacy blur reaches the notification too: blurred, it names the card and the fact and no amount.
The flag is read once per batch in the worker, not per alert, and a failed read means "not blurred"
exactly as it does in `MainViewModel` (ADR-0022).

### 1.8 The device run found the one figure the guardrail could not catch

The emulator posted: *"This statement used ₹97,637.00 of **₹2,00,034.82**."* The card's limit is
₹2,00,000.00 exactly, and the accounts screen said so on the same device at the same moment.

`CardAlertNotifier` had been recovering the limit as `amount x 10 000 / ratioBps` — because
`CardAlert` carried the ratio and the amount but not the limit, on the argument that an alert is a
decision rather than a screen. `ratioBps` is truncated to a whole basis point (48.8185 % → 4 881),
so inverting it cannot return the number it came from.

The part worth keeping is **why the guardrail did not stop it**: `NumericGuardrail` was handed the
same reconstruction as its allow-list, so the wrong figure verified against itself. A guardrail
proves the words match the numbers they were given; it cannot know the numbers were invented. That
is the whole content of P-03 — the engine emits every figure, and the words never re-derive one —
and it is why a green suite plus a green guardrail still did not close this issue. The app had to be
run.

`CardAlert` now carries `creditLimit`. The regression test asserts both halves: the alert holds the
real limit, *and* the inversion that produced the bug does not equal it.

### 1.9 Two build-level fixes the suite forced, both precedent-following

- `AccountEditorCardFieldsTest` is a Robolectric Compose test and launches a `ComponentActivity`,
  which exists only in the debug variant's merged manifest (`ui-test-manifest` is a
  `debugImplementation` by design). It joins `AccountsFlowTest` in the module's Release-variant
  exclusion — the same line `:feature:onboarding` established.
- `BudgetRules.RULEBOOK_VERSION` and `SafeToSpendRules.RULEBOOK_VERSION` restated to 1.13.0 (§1.1).

---

## 2 · Flow changed this session

New worker, seventh in the spine (`FLOW.md` §1, §3.1):

```
CfoApplication.onCreate() → CardAlertWorker.schedule(context)
    → WorkManager.enqueueUniquePeriodicWork("card-payment-alerts", KEEP, every 1 day)
    → CardAlertWorker.doWork()
        → sessionLock.isUnlocked.value == false → Result.retry()        SEC-002, before injecting
        → CreditCardRepository.pendingAlerts()
            → creditCardDao().forProfile() + accountDao().findWithBalance()
            → balance negated once here → CardEngine.alert(CardAlertInput(card, clock.today(), …))
            → minus rows already in card_alert for this cycle
        → settingsStore.observe().first() → privacyBlurEnabled          once per batch
        → CreditCardRepository.markNotified(alert)                      CLAIM FIRST (unique index)
        → CardAlertNotifier.notify(alert, blurAmounts)                  only if this run claimed it
            → NumericGuardrail.verify(...)                              AI-ARC-004
```

New read path on the accounts surfaces:

```
AccountsViewModel → CreditCardRepository.observeCardStatuses()
    → accountDao().observeWithBalances() + creditCardDao().observeForProfile()
    → CardEngine.status(...) → Map<accountId, CardStatus> → AccountsUiState.cards → AccountsScreen

AccountEditorViewModel → CreditCardRepository.find(accountId)   (card fields shown when type = CREDIT_CARD)
                       → CreditCardRepository.save(CreditCard)  on Save, after the account row
```

Archive path gains two tables: `ArchiveRepository.export/import` → `creditCards`, `cardAlerts`.

---

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `settings.gradle.kts` | Includes `:domain:engines:card` |
| `domain/engines/card/build.gradle.kts` | Pure-Kotlin engine module; declares `ai/rules/rules-kb.json` as a test input so the drift gate cannot report green against a file it never read |
| `domain/engines/card/.../CardEngine.kt` | The interface (`cycle`, `status`, `alert`), its inputs, and the result types `BillingCycle` / `CardStatus` / `CardAlert` / `Utilisation` |
| `domain/engines/card/.../BillingCycles.kt` | Places a date in the cycle; clamps a day-31 card to a short month; signed `daysUntilDue` |
| `domain/engines/card/.../CardUtilisations.kt` | `ratioBps`, `unbilled`, `available` — integer bps, no `Double` |
| `domain/engines/card/.../CardAlerts.kt` | The two alert decisions, each citing exactly the row that fired it |
| `domain/engines/card/.../CardRules.kt` | Typed mirror of `RULE-CC-UTIL` + `RULE-CC-DUE`, injected so a test can move a threshold |
| `domain/engines/card/.../DefaultCardEngine.kt` | `internal` implementation; `CardEngineFactory` is the DI seam (ARC-003) |
| `domain/engines/card/ENGINE.md` | Contract, formula, assumptions, rules consumed, version log (§21.6) |
| `domain/engines/card/src/test/**` | Golden cycle walk, boundary tests, property tests, rulebook drift test |
| `core/model/.../CreditCard.kt` | The card's terms; refuses a day outside 1..31, a non-positive limit, a negative minimum due or APR |
| `core/database/.../Entities.kt`, `Daos.kt`, `CfoDatabase.kt` | `credit_card` and `card_alert` entities, DAOs (`insertIfNew` for the claim), schema **16** |
| `core/database/.../Migrations.kt` | `MIGRATION_15_16` — two `CREATE TABLE`s and their indices, nothing destroyed (DB-003) |
| `core/database/schemas/…/16.json` | Exported schema for the migration tests |
| `core/database/src/{test,androidTest}/**` | Migration safety + round-trip coverage for 15 → 16 |
| `data/repository/.../CreditCardRepository.kt` | The only class that touches the card DAOs (ARC-005): negates the stored liability once, calls the engine, filters already-claimed alerts, claims by unique index |
| `data/repository/.../Archive*.kt`, `DemoModeRepository.kt` | Both new tables export, import and clear with the profile |
| `app/.../work/CardAlertWorker.kt` | Daily worker: lock check → pending → claim → notify |
| `app/.../notification/CardAlertNotifier.kt` | Composes both messages, guardrails them, honours the blur, one notification id per (account, kind) |
| `app/.../notification/CfoNotifications.kt` | The card channels (§17.1 severities) |
| `app/.../di/{Engine,Notification,Repository}Module.kt`, `CfoApplication.kt` | Engine, notifier and repository bound; the worker scheduled at cold start |
| `feature/accounts/**` | Card terms on the editor, card status on the list, strings externalised |
| `domain/engines/{budget,safetospend}/…Rules.kt` | `RULEBOOK_VERSION` restated to 1.13.0 (no row changed) |
| `feature/accounts/build.gradle.kts` | Release-variant exclusion for the new Robolectric Compose test |
| `ai/rules/rules-kb.json`, `ai/rules/rulebook.md` | `RULE-CC-DUE` v1.0; `_meta.version` → 1.13.0 |
| `CHANGELOG.md`, `VERSION`, `app/build.gradle.kts` | 0.6.1, `versionCode` 23 |
| `FLOW.md`, `DECISIONS.md`, `docs/adr/0025-*.md` | The worker's path, the ADR index row, and the decision record |

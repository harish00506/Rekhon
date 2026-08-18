# CardEngine — FR-ACC-002

**SRS:** §5.7, §11, §17.1 · **Pipeline layer:** L3 (rules) · **Module:** `:domain:engines:card`
**Version:** 1.0 · **Status:** active

## Why this engine exists

A credit card is the one account in the app where *dates* cost money. Every other balance is a
number the user reads at leisure; a card has a statement day, a due day after it, a minimum due that
avoids a late fee, and a utilisation ratio a credit bureau records for years. Miss the date and the
cost is a late fee plus interest on the whole statement, not on the unpaid part — the most expensive
avoidable mistake this app is in a position to see coming.

Three questions look like one and are not:

- **Where am I in the billing cycle?** Pure calendar arithmetic with one nasty edge — a card that
  bills on the 31st, in February.
- **How much of the limit am I using?** A ratio with two defensible numerators, and the app shows
  both (the live balance the user feels, and the statement figure a bureau sees).
- **Should the user be interrupted today?** The only one that can wake a person up, and the only one
  governed by rulebook rows rather than by this code.

Splitting them into three operations means the accounts screen can ask for a figure without the
alert machinery running, and `CardAlertWorker` can ask for alerts without formatting anything.

Downstream: `:feature:accounts` renders `CardStatus` on the account editor and the accounts list;
`CreditCardRepository` calls `alert` from `CardAlertWorker` once a day and claims each alert in
`card_alert` before `CardAlertNotifier` posts it.

## Contract

```kotlin
interface CardEngine {
    fun cycle(input: CardCycleInput): Result<BillingCycle, AppError>
    fun status(input: CardStatusInput): Result<CardStatus, AppError>
    fun alert(input: CardAlertInput): Result<List<CardAlert>, AppError>
}
```

Every operation is **total** — it returns `Ok` in every case. `CreditCard`'s own `init` has already
refused a day outside `1..31`, a non-positive limit, a negative minimum due and a negative APR, so
there is no input this engine can be handed that it must reject.

- **Input** — `CardStatusInput` / `CardAlertInput`:

  | Field | Meaning | Format |
  |---|---|---|
  | `card` | The card's terms and its last statement | `CreditCard` |
  | `today` | The date to place — supplied by the caller's injected `Clock` (TIM-001) | `LocalDate` |
  | `outstanding` | What is owed right now, as a **positive magnitude**: the ledger stores a card balance negative because it is a liability, and the repository flips it once so no ratio here reasons about a sign | `Money`, paise, `>= 0` |
  | `nowUtcMillis` | The caller's instant — **passed in, never read** (TIM-001) | UTC epoch millis |
  | `rules` | `RULE-CC-UTIL` + `RULE-CC-DUE` thresholds, injected so a test can move one | `CardRules` |

  `CardCycleInput` carries `today`, `statementDay`, `dueDay` and the same `nowUtcMillis` / `rules`.

- **Output** — `BillingCycle` (`statementDate`, `dueDate`, `nextStatementDate`, signed
  `daysUntilDue`); `CardStatus` (`creditLimit`, `live` and `statement` `Utilisation`, `unbilled`,
  `available`, `cycle`, `minimumDue`, `provenance`); `List<CardAlert>` (`kind`, `accountId`,
  `cycleStartIsoDate`, `amount`, `creditLimit`, `minimumDue`, `daysUntilDue`, `ratioBps`,
  `provenance`).
  Provenance is `engineId = "card-planner"`, `engineVersion = "1.0"`, `computedAtUtcMillis`, and the
  citation(s) of the rows that fired. **No `confidenceBps`** — this is calendar arithmetic and
  integer division, not an inference.

## Formula / algorithm

```text
# cycle — BillingCycles.of(today, statementDay, dueDay)
clamp(d, day)      = d.withDayOfMonth(min(day, d.lengthOfMonth()))      # the 31st, in February
thisMonth          = clamp(today, statementDay)
statementDate      = thisMonth <= today ? thisMonth                     # the statement day itself
                                        : clamp(today - 1 month, statementDay)
dueDate            = clamp(statementDate, dueDay) > statementDate
                       ? clamp(statementDate, dueDay)
                       : clamp(statementDate + 1 month, dueDay)         # due day before statement day
nextStatementDate  = clamp(statementDate + 1 month, statementDay)
daysUntilDue       = DAYS.between(today, dueDate)                       # signed: negative = overdue

# utilisation — CardUtilisations (MNY-002, integer bps throughout)
ratioBps(used, limit) = used == null || limit <= 0 ? null
                      : used <= 0                 ? 0                   # a refund is not negative use
                      : used.minor * 10_000 / limit.minor
unbilled              = max(outstanding - (lastStatement ?: 0), 0)
available             = max(limit - outstanding, 0)

# alerts — CardAlerts.evaluate, at most one of each kind
DUE_SOON      fires when  lastStatement != null
                    and   0 <= daysUntilDue <= RULE-CC-DUE.remind_days_before
                    and   not (daysUntilDue == 0 and !RULE-CC-DUE.remind_on_due_day)
                    and   not (RULE-CC-DUE.skip_when_nothing_due and outstanding <= 0)
UTILISATION   fires when  lastStatement != null
                    and   ratioBps(lastStatement, limit) >= RULE-CC-UTIL.max_utilisation_pct x 100
```

Two decisions in there are load-bearing and easy to get backwards:

- **`>=`, not `>`, at the utilisation line.** The rule says 30%, not "past 30%" — the boundary is
  inside the band.
- **The due alert reads the *statement*, not the live balance.** The statement is the bill; spend
  since the cut is not owed yet. The utilisation alert reads the statement for the same reason a
  bureau does — a figure that moves with every swipe would either nag or be claimed once and then be
  wrong for the rest of the cycle.

An alert's claim key is `cycleStartIsoDate` — the **statement date**, never a month start. A card
billing on the 25th has a cycle straddling two calendar months, so a month-keyed claim would let one
statement's reminder fire twice. `card_alert`'s UNIQUE index enforces "once per cycle per kind", and
`CardAlertWorker` claims before it notifies, so a retried run is silent.

## Assumptions & guardrails

- Money is `Long` paise end to end (MNY-001); every ratio and the APR are integer basis points
  (MNY-002). No `Double` appears in the module.
- **No clock, ever** (TIM-001): `today` is an argument. Calendar arithmetic *on* that argument is
  fine — `DefaultRecurringEngine` has the same shape — but reading the wall clock in `:domain:*`
  fails the build (`CfoWallClockInDomain`). It is also what makes a whole cycle testable: the golden
  file walks `today` forward a day at a time.
- Absence is not zero (P-03): a card with no statement recorded gets a `Utilisation` with `null`
  amounts, not `0`, and is never alerted about. Rendering that as 0% would claim the user owes
  nothing.
- `outstanding` is a magnitude; the repository negates the stored liability before calling, and the
  input's `require` catches a missed flip.
- The engine produces numbers only (P-03). `CardAlertNotifier` verbalises them, and its text passes
  the numeric guardrail (AI-ARC-004) against the same integers the alert carries — `usedPercent`
  lives on `CardAlert` so the message and its allow-list cannot round differently, and `creditLimit`
  is carried rather than recovered from the ratio. That recovery is what the device run caught: a
  truncated bps ratio inverts to ₹2,00,034.82 for a ₹2,00,000 limit, and the guardrail passed it
  because it had been handed the same wrong number. A guardrail proves the words match the figures
  it was given; it cannot know a figure was invented.
- It does not move money and does not pay a bill (P-07): a due alert deep-links to the account and
  the user decides.

## Rules / knowledge consumed

| ID / file | What it provides |
|-----------|------------------|
| `RULE-CC-UTIL` v1.0 (`ai/rules/rules-kb.json`) | `max_utilisation_pct: 30` — the credit-discipline line. Shipped before this issue and already naming `card_alerts` in `consumed_by`; **read, not authored** — the row is untouched. |
| `RULE-CC-DUE` v1.0 (`ai/rules/rules-kb.json`) | `remind_days_before: 3`, `remind_on_due_day: true`, `skip_when_nothing_due: true`. Minted by issue 6.1 (ADR-0025). |

`CardRules` holds these as hardcoded defaults — the recorded deferral ADR-0005 made for
`QuickSetupRules` and ADR-0017 restated for `BudgetRules`: nothing in the app loads `ai/` at runtime,
and honouring §6 literally would mean an asset pipeline and a JSON parser in a module that has no
serialisation dependency by design (ARC-002). `RulebookDriftTest` closes the gap that matters — edit
either row in the rulebook and the build goes red until this file agrees.

## Evidence shown to the user (P-02)

`CardStatus` cites **both** rows: the screen shows the utilisation line `RULE-CC-UTIL` draws *and*
the cycle dates `RULE-CC-DUE`'s window is measured against, so a drill-down citing only one would
leave half the card unexplained. Each `CardAlert` cites **exactly one** row — the one that decided it
— because pointing "why am I seeing this?" at a rule that had no part in the decision is worse than
citing nothing. Both utilisations carry their `UtilisationBasis`, so no ratio reaches the user
unlabelled.

## Tests

- **Golden file** (`src/test/resources/golden/card.txt`, parsed by the test itself — `:domain:*` has
  no serialisation dependency): one ₹2,00,000 card walked day by day across a whole billing cycle
  (statement day → mid-cycle → the window opening → the due day → the day after), then the edges that
  walk cannot reach. `the fixture still covers the paths it says it does` guards the fixture itself.
- **Boundary tests** (`CardEngineTest`, `BillingCycleTest`): the window's first day and the day
  before it, the due day and the flag that silences it, the utilisation line and one paise below it,
  a day-31 card in February and in a leap year, a due day before the statement day, the year end, an
  overdue card, a settled card, a card with no statement.
- **Property tests** (`CardEnginePropertyTest`): the identities across a thousand generated cards,
  utilisation monotonic in the balance, and the same inputs giving the same answers twice (P-08).
- **Drift test** (`RulebookDriftTest`): every threshold and every citation matches
  `ai/rules/rules-kb.json`, and the rulebook revision the engine names is the one on disk.
- Coverage: engine ≥ 85%, money math 100%.

## Version log

| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-08-17 | Initial implementation for issue 6.1 from SRS §5.7 / §11 / §17.1; `RULE-CC-UTIL` v1.0 read, `RULE-CC-DUE` v1.0 minted (ADR-0025). |
| 1.0 | 2026-08-18 | `CardAlert` carries `creditLimit` (no engine-version bump — no figure this engine computes changed; the alert gained a value it already held). Found on the emulator. |

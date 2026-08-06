# Changelog

All notable changes to AI Personal CFO are recorded here.
Format: [Keep a Changelog](https://keepachangelog.com). Versioning: [SemVer](https://semver.org).
Single source of truth for the version number is the repo-root [`VERSION`](VERSION) file; keep
`app/build.gradle.kts` `versionName` equal to it. Epics map to the SRS roadmap (§26); every
entry cites its requirement IDs (§28). See [`docs/issues/00-issue-workflow.md`](docs/issues/00-issue-workflow.md).

## [0.3.0] — Epic 3: Transactions & Capture

> The capture path. The `transactions` table has existed since issue 1.6 with nothing the user could
> reach writing to it; this epic makes a transaction theirs to create — by hand first, then by
> transfer, split, receipt and SMS.

### [0.3.9] — Issue 3.8: OCR receipt scanning (ML Kit)  (2026-08-06)

- **Implemented:** the app can read. Point it at a receipt — from the camera or the gallery — and it
  extracts the total, the date, the merchant and the GST on-device, pre-fills a review screen, and
  keeps the original image encrypted beside the transaction (FR-OCR-001..006).
  - **Nothing leaves the device, and there is no code path that could carry it** (FR-OCR-002, P-01).
    ML Kit's **bundled** text-recognition model ships inside the APK rather than the thin variant
    that downloads it from Play Services, so recognition also works on first launch in airplane mode
    (P-04). The whole flow was verified with airplane mode on.
  - **No `CAMERA` permission, and no storage permission** — deliberately, and it is why capture uses
    `ActivityResultContracts.TakePicture` and the system photo picker rather than CameraX. The
    camera app owns the camera; this app is handed one scratch file through a `FileProvider` scoped
    to a single cache directory.
  - **A new engine, `:domain:engines:receipt`** (pure Kotlin, ARC-002), implementing §18.1's pipeline
    literally. The load-bearing rule is that **an amount is only a candidate if it is written like
    money** — a currency marker, or a decimal point: without it `GST 18%` above `Bill No 20260406`
    reads as a ₹20,260,406 purchase. Every figure comes out of `MoneyFormatter.parse`, which refuses
    anything not exactly representable in paise; every confidence is integer basis points. **There is
    not a `Double` in the module** (MNY-001, MNY-002). Dates are read **day-first**, because
    `03/04/2026` is 3 April in every shop in India.
  - **The ≥ 95% gate is real** (§18.1, §21.5): 46 frozen anonymised receipts in
    `eval/receipts.txt`, six of them deliberately awkward, asserting total-amount accuracy ≥ 95% and
    field-complete ≥ 80% — plus a counterweight that a correct read is usually *not* flagged, so the
    parser cannot pass by flagging everything. Verified to bite by mis-labelling three fixtures.
    `ENGINE.md` states plainly what the set does **not** prove: it was written alongside the parser,
    so it is regression protection rather than an independent accuracy estimate.
  - **The image is encrypted at rest** (FR-OCR-005, SEC-003): Tink AEAD over an Android Keystore
    keyset **of its own**, so rotating or destroying the attachment key is not the database key.
    EXIF is stripped by decoding and re-encoding — one operation, so it cannot be forgotten — which
    matters because a phone writes the GPS coordinates of where a photo was taken into it. The
    attachment id is the AEAD's associated data, so a blob cannot be moved between rows.
  - **A new table** (schema **v10 → v11**): `attachments`, additive, with **no BLOB column** — the
    bytes live in a separate encrypted file so "delete the image, keep the transaction" is a file
    deletion rather than a row rewrite that leaves the old bytes in SQLite's freelist.
  - **The duplicate guard is FR-OCR-006 in SQL**: a `manual` or `sms` row within **±1% and ±1 day**
    is offered as a merge instead of a second transaction. The band is computed in integer paise
    before the query, never as `amount * 0.99`. "Save a new one" exists because the guard is a
    heuristic and two identical coffees on one afternoon are real (P-07).
  - **Low-confidence fields are flagged in words, not only in colour** (FR-OCR-004), as supporting
    text *and* a content description — the marker is invisible to a screen reader otherwise. Save is
    disabled until there is an amount and a date, and the ViewModel refuses too, because a disabled
    button is a rendering.
- **Fixed — found by running it, not by reading it.** Every unit test passed before the app was put
  on a device, and the device found two things nothing else could have:
  - **Recognised blocks are a receipt's *cells*, not its rows.** `GRAND TOTAL     365.80` came back
    as two blocks side by side, so every line-based heuristic saw a keyword with no amount and fell
    through to an item price — ₹248.00 offered as the total. Single-line blocks within
    `same_row_bps` of each other are now joined into one logical line, which is what §18.1's "near
    keywords" means on a two-column layout and the reason `RecognizedBlock` carries a position.
  - **ML Kit returns `365.8` for a printed `365.80`.** One decimal digit is now accepted: it is
    *tens* of paise, and `MoneyFormatter` pads it exactly. The guards on either side of the pattern
    carry the cost — without them `04.08.2026` would read as ₹4.08, which has its own test.
  - **A receipt could not be saved at all**, because `stampsFor` refuses a past date and a receipt is
    by definition already spent. Back-dating is now decided by **provenance**: a row read off a
    record may be back-dated, a row a person types may not. **ADR-0011** records the trade-off — the
    frozen daily net-worth series does not follow a back-dated row — and names who owes the fix.
  - **A blob was silently not deleted on Windows.** Attachment ids are `att:<uuid>`, and a colon in a
    path is an NTFS alternate-data-stream separator, so the write went to a stream and the delete
    reported success while leaving the data. The file name is now derived from the sanitised id; the
    AEAD binding still uses the full one. A regression test covers it.
- **Requirements:** FR-OCR-001, FR-OCR-002, FR-OCR-003, FR-OCR-004, FR-OCR-005, FR-OCR-006,
  FR-TXN-009; SRS §18.1, §20.1; MNY-001, MNY-002, TIM-001, TIM-002, DB-003, SEC-003, ARC-002/003/005,
  AI-ARC-003/006; P-01, P-02, P-03, P-04, P-07, P-08.
- **Deliberately not in scope:** line items (FR-OCR-003 calls them best-effort — qty–name–price table
  detection is its own project), multi-page stitching (FR-OCR-001 says MAY), a merchant knowledge
  base (issue 4.1 owns `merchant_id`; guessing a name the user never saw is worse than blank), an
  in-app camera preview, and any way to share a receipt out of the app.

### [0.3.8] — Issue 3.7: recurring detection  (2026-08-05)

- **Implemented:** the ledger now notices a pattern in itself. A deterministic detector proposes a
  recurring series when two or more transactions share a merchant, an amount within tolerance and a
  regular cadence; the user confirms or rejects it on the transactions list (FR-TXN-006).
  - **A new engine, `:domain:engines:recurring`** (pure Kotlin, ARC-002). It groups by normalised
    merchant, classifies the **median** gap as weekly/monthly/yearly, and then checks **every** gap
    against the rulebook's tolerance — the second step is the one that matters: classifying on the
    median alone would propose 1 Mar / 31 Mar / 30 Apr / 30 Jun as a monthly bill the user never had.
  - **No floating point anywhere on the money path** (MNY-001). The 5% amount tolerance is checked by
    cross-multiplication rather than by dividing into a ratio, and both medians are *lower* medians,
    so no rounding decision arises and the amount shown for confirmation is one actually paid. The
    next-due date uses `java.time`, not `+30 days`: 31 Jan + one month is 28 Feb, not 2 March.
  - **The thresholds are a rulebook row**, `RULE-RECUR-DETECT` in `ai/rules/rules-kb.json`
    (`min_occurrences: 2`, `amount_tolerance_pct: 5`, `cadence_tolerance_days: 2/4/10`), mirrored as
    an injected `RecurringRules` per **ADR-0005** and guarded by a `RulebookDriftTest` that was
    verified to bite by tampering with the rulebook before it was trusted (CLAUDE.md §6).
  - **A rejection is stored** (schema **v9 → v10**): `recurring_rule.dismissed_at_utc_millis`, one
    nullable `ADD COLUMN`, no backfill. It is deliberately *not* a tombstone — "the user said no" has
    to keep the merchant out of the detector while `deleted_at_utc_millis` means the rule is gone.
    That is what makes the acceptance criterion "decisions feed back as data, not code" literal: the
    exclusion is a row read back on the next emission, not a flag in the UI.
  - **The card shows its working** (P-02): merchant, amount, cadence, and the *dates* of the payments
    that matched — a claim the user can check against their own memory rather than a verdict they
    have to trust. Rule ids are derived (`<profile>:recurring:<merchant>`), so confirming twice
    updates one row rather than minting two.
  - **It proposes only** (P-07). Nothing here posts a transaction or moves money; a confirmed rule
    predicts, and wiring it into the forecast is issue 9.2's.
  - **Income and spending share one tolerance.** The representative amount is the lower median **by
    magnitude**, not by signed value. The band is relative to that figure, so ordering by sign made
    it depend on the direction of the money — two outflows were measured against the larger expense
    and two inflows of the same spread against the smaller, and a mirror-image ledger got a
    different answer.
- **Fixed:** `:app:connectedDebugAndroidTest` — named in the Definition of Done's phase 8 — had **no
  `androidTest` source set at all**, so it ran zero tests and reported success. Every issue that
  ticked that phase ticked a no-op. `CfoSmokeTest` now boots the **real** Hilt graph against the
  **real** SQLCipher database and drives the recurring flow end to end; proven to bite by renaming
  the section heading and watching it go red. Same failure mode as the coverage gate in the
  2026-07-25 governance audit.
- **Verified:** 1 714 unit tests green (debug + release variants); engine coverage 100% line (gate
  85%); ktlint, detekt, `lintDebug` and Paparazzi clean; 16 instrumented tests on device — 14
  migration cases including the 9 → 10 round trip, plus the 2 new app smoke cases. Driven by hand on
  the emulator **in airplane mode** (P-04): five series detected from the demo ledger, one confirmed
  and one rejected, and the screen re-opened to prove both decisions stayed.
- **Not in scope:** auto-posting (P-07; issue 9.2 owns the forecast), a recurring-rules manager
  (FR-SET-001 puts it in Settings), and merchant aliasing — matching is on the merchant string until
  issue 4.1 lands `merchant_id`.

### [0.3.7] — Issue 3.6: search, filters and bulk edit  (2026-08-04)

- **Implemented:** the transaction list stopped being a 30-day window and became the whole ledger —
  searchable, filterable, paged, with multi-select recategorise, retag and delete (FR-TXN-007,
  FR-TXN-008).
  - **Search covers payee, note, tag and amount** behind one field: a user looking for a transaction
    knows *something* about it and should not have to say which kind of something first. The amount
    match is exact via `MoneyFormatter.parse`, not a substring on the stored paise — typing `250`
    finds ₹250, not ₹2.50, ₹1,250 and ₹25,000 as well. Typed `%` and `_` are escaped, so the search
    means what the user typed.
  - **Filters:** account, category, type, source, tag, amount range and date range, as one
    `TransactionFilter` expanded into one nullable-parameter `@Query`. The amount bounds are on the
    **magnitude** (MNY-001) — under a signed comparison "between ₹100 and ₹500" would exclude every
    expense, which is most of a ledger.
  - **Tags are new** (schema **v8 → v9**): `tags` and `transaction_tags`, the two tables SRS §20.1
    names. Additive DDL, no backfill, with a round-trip case that asserts the unique index as well
    as the rows — an index a migration forgot is invisible until a user has two of something.
  - **Paging 3** (`androidx.paging` + `room-paging`, first-party, no network). Two consequences that
    are not obvious from the requirement: a transfer's two legs are now collapsed **in SQL** rather
    than paired within a loaded day, because paging can put them in different pages; and each day's
    total comes from its own `GROUP BY` query rather than a fold over loaded rows, because a page
    boundary can fall inside a day and a folded total would understate it until the user scrolled.
  - **Bulk edit is reversible.** Delete returns the ids it *actually* removed — for a transfer that
    is both legs (FR-TXN-003) — and undo restores exactly those, so the snackbar cannot leave money
    in one account with no counterpart. Recategorise skips transfer legs and split parents in SQL
    rather than trusting the caller to remember (FR-TXN-003, FR-TXN-004).
  - **`observeRecent` was removed, not kept alongside.** Its window was scaffolding whose own doc
    comment named this issue as its removal. The *upper* bound survives, so a scheduled payment
    still stays out of the actuals unless a filter names a future date (FR-TXN-010).
- **Tests:** 1457 passed, 0 skipped. 37 new repository tests (each facet, paging across a page
  boundary, day totals across a page boundary, bulk + undo), 42 Compose tests, 40 ViewModel tests,
  and the 8 → 9 migration round trip. The `LIKE`-escaping guard was proven to fail on purpose before
  being trusted — its first version passed with the escaping removed, because the decoy row had no
  digits in it.

### [0.3.6] — Fix: the add screen was missing two FR-TXN-001 fields  (2026-08-03)

- **Fixed:** the add-transaction screen never captured a **merchant** or a **time of day**, both of
  which FR-TXN-001 lists ("amount, currency, date-time, account, category, subcategory,
  payee/merchant, notes, …"). No schema change — both were already stored.
  - **Merchant was plumbed end to end and unreachable.** `transactions.merchant` has been a column
    since schema v1, `TransactionDraft.merchant` since issue 3.1, the list row falls back to it for
    its title and issue 3.5's detail sheet renders it — but **only `DemoDataset` ever wrote one**.
    The visible symptom: every row on a real profile read "Uncategorised" unless the user happened
    to type a note. **Hidden for a transfer**, which has no payee — which is why `TransferDraft` has
    no field to carry one, the same reason it has no category (FR-TXN-003).
  - **Time of day is now the user's to state.** `occurredAtUtcMillis` was always the app's choice —
    now for today, the start of the day for a future date — so recording this morning's coffee in
    the evening ordered it after everything bought since. `BookingStamps.stampsFor` takes an
    optional `LocalTime` and resolves it through the profile `ZoneId`.
  - **`null` still means "the app's choice"** for both fields, so every existing call site and the
    ≤ 3-tap path are byte-for-byte unchanged (FR-TXN-002).
  - **The time changes ordering, never money.** Balances and budgets bound on `booked_on_iso_date`,
    so the hour decides where a row sits within its day and nothing else — and posting stays a
    property of the day (ADR-0010): a row booked for 09:00 tomorrow is no more posted than one
    booked for tomorrow with no time at all.
- **Tests:** 944 passed, 0 skipped (+24). One failed first and taught something worth keeping:
  **`ZonedDateTime` resolves a DST-gap time forward by the length of the gap, not to the first valid
  instant.** Asking for 00:30 on Chile's 2026-09-06 gives 01:30 local (04:30Z), *not* the 04:00Z
  `startOfDay` produces for the same day — because that asks for 00:00. The obvious expectation is
  wrong, and the test was written asserting it.
- **Verified on a device:** a ₹899 expense saved with merchant "Big Bazaar" at 7:03 AM renders as
  **"Big Bazaar"** rather than "Uncategorised", and sorts below rows stamped at 19:03 — the hour
  visibly driving intra-day order. Airplane mode throughout (P-04).

### [0.3.5] — Issue 3.5: Transaction source tracking  (2026-08-03)

- **Implemented:** every transaction has recorded where it came from since issue 3.1 — this makes the
  app *show* it (**FR-TXN-009**, P-02; ARC-004, ARC-005). **No schema change:** the data has been
  right all along, and only nothing surfaced it.
  - **The row this exists for is the reconciliation adjustment.** Its `note` is deliberately null
    (FR-ACC-006), so it rendered as an anonymous "Uncategorised −₹500.00" with nothing saying the
    *app* had posted it to close a gap against a statement. It now reads **"Balance adjustment"**,
    verified against a live reconciliation on the emulator.
  - **A source label on the row**, worded as provenance rather than mechanism — "From a receipt",
    not "OCR". **Manual rows carry none**: it is the default and the overwhelming majority, and
    tagging every hand-typed row would bury the labels that carry information.
  - **A detail bottom sheet on row tap** — the app's first — showing every FR-TXN-001 field the
    transaction has, source included and spelled out even when it is "Manual". Deliberately **no
    nav route**: issue 3.6 owns editing and will want a real screen.
  - **A source filter chip row** that appears only when the window holds two or more distinct
    sources, so the all-manual profile every real user has today shows no chips and no labels at
    all. Filtered in the ViewModel over rows already loaded — FR-TXN-007's filter list (3.6's) does
    not include source, and keeping this out of SQL leaves that query 3.6's to design.
  - **`RECURRING_AUTO` added**, completing FR-TXN-009's five. Nothing writes it until issue 3.7; it
    exists so a row from a newer build renders rather than being dropped by the mapper — the exact
    failure that omitting `demo` caused in issue 3.1.
- **Corrected the generated backlog for the fourth issue running**, at source in
  `scripts/gen_issue_docs.py`: the criteria cited **AI-ARC-003**, which governs *engine result*
  provenance and has nothing to say about a transaction row (no engine writes one, so "creating
  engine/version where applicable" applied to nothing — and no such column was added); they asked
  for the source "in the detail view" when no detail view existed; and they asked for a backfill
  when `source` has been `TEXT NOT NULL` since schema v1 and every write path sets it. The
  no-op migration was replaced by an assertion that there is nothing to backfill.
- **Tests:** 920 passed, 0 skipped (+30). Two of the new tests failed first and both were real: a
  filtered-to-nothing list rendered *both* empty messages, so `isEmpty` gained a fourth clause;
  and `setContent` was called twice in one test, the mistake issue 3.3 had already recorded.
- **No `connectedDebugAndroidTest`** — the first Epic 3 issue with no schema change, so no migration
  to prove and no upgrade path to build. Emulator run covered the demo profile, a real profile
  onboarded from scratch, and a live reconciliation; whole session in airplane mode (P-04).

### [0.3.4] — Issue 3.4: Future-dated transactions  (2026-08-03)

- **Implemented:** a transaction can be booked on a future day, stays out of every actual until that
  day, and is readable by forecasts before it (**FR-TXN-010**; DB-003, TIM-001, TIM-002, MNY-001,
  ARC-004, ARC-005, SEC-002, P-04, P-08).
  - **Fixed a live defect the tests found before the feature existed.** Net worth has bounded on
    `booked_on_iso_date <= today` since issue 2.6, but the **account-balance** queries
    (`observeWithBalances`, `findWithBalance` — behind the accounts screen, the account editor and
    reconciliation) never did: they summed every live transaction whenever it happened. The first
    scheduled payment would have been subtracted on the accounts screen while net worth showed a
    different figure for the same money. Both are bounded now. `balancesForNetWorth`'s own doc
    comment had predicted this by name eight days earlier.
  - **Schema v7 → v8** — `transactions.posted_at_utc_millis`, nullable, **with a backfill**.
    `ADD COLUMN` alone gives every existing row `NULL`, which is exactly the value meaning "not
    posted", so an upgraded install would have shown the user's whole history in the Scheduled group.
  - **The date decides every figure; the stamp is only a record** (see
    [ADR-0010](docs/adr/0010-future-dated-posting.md)). `ScheduledTransactionWorker` runs daily and
    stamps what is due, idempotently — a second run the same day stamps nothing — but no balance
    depends on it having run. A job can be deferred by Doze, by a powered-off device, or by the app
    being locked (SEC-002); a date cannot.
  - **The add screen gained a Date row**, pre-filled with "Today" so FR-TXN-002's ≤ 3-tap expense is
    untouched. The picker will not offer a past day: back-dating would stale the `net_worth_snapshot`
    rows already written for those days, and issue 3.6 owns editing.
  - **The list gained a Scheduled group** above a Posted one, its day headers deliberately carrying
    no total — a day total is a statement about money that has moved. The two halves come from two
    repository flows whose windows abut at today, so a scheduled row is never in the list day totals
    are computed from and there is no filter for a later screen to forget.
  - **`observeUpcoming()`** is the seam Epic 6's cash-flow forecast and FR-HOME-001's 14-day
    obligations card will read — the "included in forecasts" half of the requirement.
  - **Zone- and DST-correct.** A future row's instant is the start of its own day via
    `Clock.startOfDay`, so it sorts after today's and lands on the first valid instant on a day whose
    local midnight does not exist (Chile, 2026-09-06 — a test case).
- **Corrected the generated backlog for the third issue running**, at source in
  `scripts/gen_issue_docs.py`: the criteria cited no requirement id at all (it is **FR-TXN-010**),
  and "on date rollover they post automatically (WorkManager), idempotently" appears nowhere in the
  SRS — it was authored in the generator. Where those criteria are more specific than the SRS section
  they cite, they are a guess.
- **Tests:** 890 passed, 0 skipped (+62 for this issue) · 12/12 instrumented on a device, incl. the
  7 → 8 backfill · emulator: **v7 installed, real data added, v8 installed over it** — net worth
  unchanged; then a ₹25,000 payment scheduled (net worth unmoved), then the device date advanced one
  day (net worth moved by exactly ₹25,000, **with nothing written**). Whole session in airplane mode.
- **Caught by lint, not by any test:** `LocalDate.EPOCH` requires API 34 and this app's minSdk is 26.
  Every unit test runs on the JVM, where the constant exists, so it compiled, passed 890 tests and
  would have crashed on a real phone.

### [0.3.3] — Issue 3.3: Split transactions across N category lines  (2026-08-02)

- **Implemented:** one purchase attributed across several categories — a ₹1,000 supermarket trip is
  groceries *and* household (**FR-TXN-004**; DB-002, DB-003, DB-004, MNY-001, ARC-004, ARC-005,
  P-03, P-04).
  - **Schema v6 → v7** — the new `transaction_splits` table. Purely additive, so unlike 5 → 6 there
    is nothing to backfill: every existing transaction is simply unsplit, which is the truth about it.
  - **`TransactionRepository.createSplit`** writes the parent and all its lines in one
    `withTransaction` (DB-004). **The exact-sum rule is enforced by refusal, never by adjustment** —
    lines that miss the parent by a single paise are rejected outright, because an app that quietly
    moves a user's figure to make a form balance is worse than one that says no. A single line, a
    zero line, and a line signed against its parent are refused too.
  - **A split moves the balance once.** The parent holds the amount and the lines only attribute it,
    so no balance query changed and no balance code was written. Verified on the emulator: a
    three-line ₹1,000 split moved Cash Wallet by ₹1,000, and deleting it moved it back by ₹1,000.
  - **Deleting a parent takes its lines** in the same transaction — a line whose parent is gone
    attributes an amount that no longer exists.
  - **The add screen gained an opt-in "Split into lines"** with a live **running remainder**: Save
    unlocks exactly when it reaches ₹0.00. **"Split evenly"** goes through `Money.split`, the one
    action that can always divide exactly — ₹1,000 across three lines is 333.34 / 333.33 / 333.33.
    The parent's category row is hidden while splitting; each line carries its own.
  - **The list marks a split parent** with its line count and one unchanged amount, so the money is
    never shown twice.
- **Corrected two stale instructions** in the generated backlog, at source in
  `scripts/gen_issue_docs.py`: `Money.splitExact` does not exist (the API is `split`/`allocate`), and
  **"distribute the remainder via HALF_EVEN" is wrong in principle** — HALF_EVEN rounds one value and
  cannot make N parts sum to a whole (three HALF_EVEN thirds of ₹1,000 give ₹999.99, the exact
  "rounding drift" FR-TXN-004 forbids). The existing largest-remainder rule is what satisfies it.
- **Recorded in [ADR-0009](docs/adr/0009-splits-as-a-child-table.md)** why splits are a child table
  while transfers are linked legs (ADR-0008): a transfer's legs both move money, so a parent row
  would hold no fact; split lines move none, so a child table keeps them out of every balance.
- **Refactors the structure forced, and they were fair:** the split editor became its own file when
  `AddTransactionScreen.kt` passed detekt's function ceiling, the split drafts and rules likewise
  left `TransactionRepository.kt`, and the six split interactions became a nested `SplitEvent` so the
  screen's main event handler stayed inside its complexity budget.
- **Tests:** 62 new, 0 skipped (6 model · 24 repository · 31 feature · 1 instrumented migration).
  The repository property test asserts the sum **on rows read back out of SQLite** rather than
  re-proving `Money` — `MoneySplitPropertyTest` already owns the in-memory guarantee.
  **FR-TXN-002's two-tap expense is untouched**: 3.1's tap-count assertions still pass unmodified.
  Verified by **upgrading, not installing fresh** — the v6 build was installed, given data, and v7
  installed over it, with net worth unchanged at ₹4,16,485 afterwards.
  `:core:database:connectedDebugAndroidTest` — 11/11 on the emulator, in airplane mode throughout.

### [0.3.2] — Issue 3.2: Transfers as a single logical record  (2026-08-02)

- **Implemented:** moving money between two of your own accounts, as **one** record
  (**FR-TXN-003**; DB-003, DB-004, MNY-001, TIM-002, ARC-004, ARC-005, P-02, P-03, P-04).
  - **Schema v5 → v6** — the first schema change since issue 1.6, and the **first migration that
    rewrites existing rows** rather than only adding empty columns. Adds §20.2's
    `transactions.type` and `transactions.transfer_id` + index, then backfills every existing row:
    `source = 'reconciliation'` → `adjustment`, otherwise the sign decides. Without that backfill a
    user's salary credits would all have read as spending.
  - **`TransactionRepository.createTransfer`** writes both legs inside one `withTransaction`
    (DB-004), sharing one `transfer_id`, one instant and one booked day. Neither leg carries a
    category — a transfer is not spending. Cross-currency transfers are **refused**, not converted:
    that needs FX rates no issue has built, and inventing one would be the app making up a number.
  - **`delete` removes both legs atomically** in a single `UPDATE` (FR-TXN-003's second clause), so
    there is no window where one leg is gone and the other is not. The screen passes whichever row
    the user tapped; the repository decides whether a sibling goes with it.
  - **The list collapses a transfer into one row** — "Transfer · HDFC Savings → Cash Wallet" — paired
    by `transfer_id`, never by matching amounts and dates. The day total is unaffected because the
    legs net to zero. A lone leg (its sibling outside the 30-day window) still renders.
  - **The add screen gained a third direction**, Expense · Income · **Transfer**, with a To-account
    picker that excludes the source and hides the category row. Expense remains the default, so
    FR-TXN-002's two-tap common expense is unchanged.
  - **A delete action on every row**, which is what makes the atomic both-legs delete observable.
- **Deviations recorded** in [ADR-0008](docs/adr/0008-transfers-as-linked-legs.md): no `transfers`
  parent table (§20.1) because it would hold no fact the legs don't; `source` carries
  `reconciliation` and `demo`, which §20.2's CHECK list omits; and §20.2's `CHECK(type IN …)` cannot
  exist on an upgraded SQLite table, so the invariant lives in a test instead.
- **Known cost, accepted:** `type` records direction a second time alongside the amount's sign. No
  caller supplies a type — it is derived at one site per write path — and a test walks every path
  asserting the two agree.
- **Fixed:** `TransactionDao.softDelete` had no `AND deleted_at_utc_millis IS NULL` guard, so a
  second delete matched the same row and reported success twice. Harmless until this issue added the
  delete UI that would have exposed it.
- **Tests:** 70 new, 0 skipped (10 model · 27 repository · 32 feature · 1 instrumented migration).
  **Verified on a device**: installed the v5 build, added a transaction, then installed v6 over it —
  every row survived with identical amounts and the salary credit backfilled as `income`, not
  `expense`. Then, in **airplane mode**: a ₹5,000 transfer moved HDFC ₹3,82,800 → ₹3,77,800 and Cash
  −₹4,236 → +₹764, rendered as one row leaving the day total unchanged, and deleting it reverted
  both balances exactly. `:core:database:connectedDebugAndroidTest` — 10/10 on the emulator.

### [0.3.1] — Issue 3.1: Add transaction ≤ 3 taps (FAB)  (2026-08-02)

- **Implemented:** the app's most-used flow — capture a transaction in **two taps** (FAB → Save)
  (**FR-TXN-002**, FR-TXN-001, FR-TXN-009; MNY-001, TIM-001, TIM-002, DB-001, DB-002, ARC-001,
  ARC-003, ARC-004, ARC-005, P-01, P-04, P-08).
  - **`TransactionRepository`** (`:data:repository`) — `create`, a 30-day `observeRecent`, and the
    categories the add screen offers. Amounts are signed `Money` paise and **nothing writes a
    balance**: the row it inserts *is* the balance update (DB-001, ADR-0007). The account is verified
    live before the write, so no orphan row can be stored. `TRANSACTION_ID_PREFIX` moved here from
    `AccountRepository`, unchanged, as that class's comment said it would.
  - **A global FAB** above the nav graph, hidden on onboarding and on the capture screen itself, so
    add-transaction is one tap from anywhere — FR-TXN-002's literal wording, without building the
    bottom nav (issue 5.1 owns that).
  - **The add screen** preselects the first account and autofocuses the amount, which is what makes
    the two taps real; the account picker hides when there is one account and the category row hides
    when the profile has none. The expense/income toggle becomes a **sign** before it reaches the
    store, so direction has exactly one representation below the UI.
  - **A recent-transactions list** replacing issue 1.10's placeholder — day-grouped with daily totals
    (FR-TXN-007's grouping half only; search, filters, paging and bulk edit remain issue 3.6's).
- **Corrected:** this issue's requirement id. The backlog cited **FR-TXN-004**, which is *split
  transactions* (issue 3.3); the ≤ 3-tap rule is **FR-TXN-002**. Fixed at source in
  `scripts/gen_issue_docs.py` and regenerated.
- **Fixed (found on the emulator, not by the build):**
  - Every **demo** transaction was invisible in the new list — `transactions.source` has held
    `"demo"` since issue 2.4, the first `TransactionSource` enum omitted it, and the mapper's
    forward-compatible `mapNotNull` silently dropped all of them. Regression test:
    `the demo's own history is inside the window`.
  - **Save was unreachable behind the keypad** on a profile with several accounts and categories —
    the screen drew edge-to-edge without consuming IME insets, so the form had nothing to scroll.
    Fixed with `imePadding()`.
  - A **double-tap booked the spend twice**: the write finishes fast enough that the second event
    arrives after `isSaving` clears, so `isSaved` is now guarded too.
- **Tests:** 85 passed, 0 skipped (11 model · 27 repository · 45 feature · 2 app) — repository
  (Room in-memory, profile isolation, the derived
  balance moving by exactly the amount, the profile-zone booked day), ViewModels (Turbine), and
  Compose tap-count tests split across `:feature:transactions` and `:app` that pin FR-TXN-002's
  budget at 1 + 1 = 2. Emulator run in **airplane mode** (P-04): ₹250 expense moved the balance
  −₹3,459 → −₹3,709; ₹60,000 income moved net worth +₹4,17,262 → +₹4,77,262.

## [0.2.0] — Epic 2: Onboarding, Security & Accounts

> First-run onboarding, the biometric app lock, and the accounts + net-worth core. Epic 1 built
> foundations; this is the first epic a user can see.

### [0.2.7] — Issue 2.7: Account reconciliation + the DB-001 integrity job  (2026-08-02)

- **Implemented:** a balance the app got wrong can finally be corrected — by *adding* to history,
  never by editing it (**FR-ACC-006**; DB-001, DB-002, MNY-001, TIM-001, TIM-002, SEC-002, ARC-004,
  ARC-005, P-02, P-03, P-04, P-08). **The last issue in Epic 2.**
  - **`AccountRepository.reconcile(accountId, statementBalance)`.** The user states what their bank
    says; the app derives what it holds, subtracts, and posts the difference as one adjustment
    transaction tagged `source = "reconciliation"`. The opening balance and every existing
    transaction are untouched — FR-ACC-006's words are "never silently mutated", and the only way
    to honour that is for the correction to be a new row the user can see and soft-delete like any
    other.
  - **A zero delta writes nothing**, and says so on screen. A zero-amount transaction records no
    fact and would have to be filtered by every engine downstream, so `adjustmentId` is `null` and
    the row is never minted — not even an id is drawn from the generator.
  - **The screen previews; the store decides.** The panel shows a delta computed against the balance
    the list last emitted, but `reconcile` re-derives the balance inside its own transaction and
    computes the delta again there. A transaction landing while the panel is open therefore cannot
    be absorbed into the correction — the same stale-versus-live split 2.6 shipped a defect on.
  - **The adjustment carries the account's own profile, not the active one.** Reconciling inside the
    demo must leave a row `DemoDao.deleteTransactions` can reach; the opposite is the residue
    ADR-0006 forbids, and it is the exact trap 2.6 fell into with `net_worth_snapshot`.
  - **DB-001's integrity job now exists** — `BalanceIntegrityWorker`, daily, the app's **second**
    background work. `account.current_balance_minor` had been a cache written once at create and
    never again, stale on every account with transactions; ADR-0007 said so in as many words —
    *"the two figures can disagree, and until issue 2.7 nothing notices."* One `UPDATE` re-derives
    every cache in the profile, and its trailing `<>` clause is what makes the row count mean
    something: it is how many were wrong, not how many exist. Nothing is written that was already
    correct.
  - **The new worker cannot crash a locked app either.** Same shape as 2.6's: `SessionLock` checked
    before anything injects, repository behind a `Provider`. Observed on hardware, by moving the
    device clock forward a day: `RETRY (locked) → SUCCESS` 30 seconds later.
  - **An inline panel, not a modal dialog.** It began as an `AlertDialog` and was changed for two
    reasons pointing the same way: Robolectric never drives a `Dialog`'s own window to idle, so all
    four rendered tests hung for sixty seconds each and the screen would have been covered only when
    an emulator happened to be attached; and a modal holding a field, five lines of copy and two
    buttons is the shape that gets clipped at 200% font — the defect class 2.5 found on a device and
    could not reproduce. Inline, it is checked on every `unitTests` run.
- **No schema change.** `transactions.source` and `account.current_balance_minor` both already
  existed; the database stays at **v5**. The first issue in Epic 2 needing neither a migration nor a
  migration test.
- **Found by running it, again.** On an untouched form the panel announced *"this adds one
  adjustment transaction"* while Confirm was disabled and nothing could be added — a promise about
  an action the screen was simultaneously refusing. No test caught it because every one had already
  typed an amount. Fixed, and now gated.
- **Verified on a device** (demo household, hand-checked arithmetic): HDFC `+₹3,82,800 → +₹3,83,300`
  and net worth `+₹4,17,262 → +₹4,17,762`, both exactly the ₹500 posted; the same statement a second
  time adjusted nothing; a credit card corrected *downwards* by `−₹1,250`; an overdrawn cash wallet
  corrected `−₹3,459 → −₹3,000` **with the radio off** (P-04). 622 unit tests across 21 modules.

### [0.2.6] — Issue 2.6: Net worth = assets − liabilities + daily snapshot  (2026-08-02)

- **Implemented:** the app's headline figure is computed rather than invented (**FR-ACC-005**;
  DB-001, DB-003, MNY-001, TIM-001, TIM-002, SEC-002, ARC-002, ARC-003, ARC-005, AI-ARC-003,
  AI-ARC-006, P-02, P-03, P-04, P-08).
  - **`:domain:engines:networth`** — the project's **second engine**, pure Kotlin. Partitions
    accounts by `AccountType.isLiability` (`credit_card`, `loan`, `payable` — `receivable` is an
    asset), sums each side, subtracts. The arithmetic never needed the partition: balances are
    already signed, so a plain sum *is* assets − liabilities. It exists so a screen can say "assets
    ₹5,02,800, you owe ₹82,079" instead of one unverifiable total (P-02).
  - **Classification is by type, never by sign** — the one judgement in the engine. An overdrawn
    bank account is an asset with a negative value; a card paid past zero is a liability with a
    positive one. Net worth is *identical* either way, so the error would surface only in the two
    subtotals a user checks against their own accounts. Both cases are pinned by tests, and the
    demo's cash wallet turned out to be overdrawn, so the first case is live.
  - **A daily snapshot that backfills** (schema **v5**, `net_worth_snapshot`). WorkManager job —
    the app's **first background work**. Ids are derived (`<profile>:networth:<date>`), so a second
    run in a day updates one row rather than leaving two figures for one date. Missed days are
    recomputed from the transactions booked on or before each of them, capped at 90 days a run; a
    first ever run writes today only, because inventing a history the user never had the app for
    would be fabricating data (P-03).
  - **The worker cannot crash a locked app.** `CoreModule.provideDatabase` *throws* when the session
    is locked (SEC-002), so the worker checks `SessionLock` first and injects the repository through
    a `Provider` — the graph is not built until that check passes. Observed on hardware:
    `SUCCESS → RETRY (locked) → SUCCESS` 30 seconds later.
  - **A second, date-bounded balance query.** Net worth reads `booked_on_iso_date <= :asOf`, unlike
    2.5's current-balance query — so a future-dated transaction (issue 3.4) will not be subtracted
    from today's figure, and the backfill can reconstruct a past day exactly. It also excludes
    archived (FR-ACC-007) and opted-out accounts.
  - **`account.include_in_networth`** (§20.2) with an editor toggle, for an account that is open and
    transacting but is not the user's to count.
  - **The dashboard's hardcoded ₹4,82,350.00 is gone.** Safe-to-Spend is now the only placeholder
    left. No snapshot yet renders as "not worked out yet", never as ₹0.
- **Tests:** **573** unit passed, 0 skipped, across 21 modules; 10 instrumented on `emulator-5554`.
  Counted from the new `unitTests` task after clearing stale results — earlier drafts of this entry
  said 578 because `build-logic`'s 5 (a separate composite CI runs on its own) were on disk.
  Highlights: a **golden-file test on a fixed account set** (the AC) and a seeded property test
  asserting `netWorth == assets − liabilities` *and* `== Σ signed balances` over generated
  portfolios (money math, 100% coverage); `migrate4To5` against real SQLite, asserting the new
  column defaults to **counting**; the worker's locked path. **Four gates were proven to fail
  before being trusted.**
- **Found by running it, not by a test:** the dashboard read the *stored* snapshot, so deleting a
  ₹1,20,000 account left the total unchanged — correct for a historical record, wrong for a headline
  figure. Split into `observeCurrent()` (live, what the screen shows) and `observeLatest()` (stored,
  what issue 6.6 will chart), both through the same filter so they cannot disagree about which
  accounts count. Two tests added that now red against the old behaviour.
- **Fixed a hole in the test gate itself — the biggest thing this issue found.** `./gradlew
  testDebugUnitTest`, the command CLAUDE.md, the workflow, every issue template **and CI** all named,
  is an Android *variant* task: it does not exist on the pure-Kotlin modules and **never reached
  `:lint` at all**. `:lint`'s fourteen tests are the only check on the five custom detectors that
  make MNY-001, TIM-001, ARC-006, the PII-logging ban and the hardcoded-string ban fail the build —
  so the enforcement layer's own tests **ran in no CI step**. Demonstrated rather than argued:
  disabling `MoneyDoubleDetector` outright left `testDebugUnitTest` reporting BUILD SUCCESSFUL.
  Added a root **`unitTests`** aggregate (matched by task name, so a new module is picked up without
  anyone registering it) and pointed CI, CLAUDE.md, `00-issue-workflow.md`, both issue templates and
  the generator at it. Same shape as audit G-01's vacuous coverage gate.
- **Also fixed:** the demo wipe did not reach `net_worth_snapshot` — a profile-scoped table the wipe
  misses is the residue ADR-0006 forbids, and the test written for it caught the gap immediately.
  `androidx.work`'s own lint rule caught a missing `WorkManagerInitializer` removal. And
  `scripts/gen_issue_docs.py` cited **`FR-NW-*`** for both 2.6 and 6.6 — a requirement ID that
  appears **zero times** in the SRS; the real one is FR-ACC-005. Third citation error in a row
  (2.4 cited §33, 2.5 cited §11), all fixed at the generator.

### [0.2.5] — Issue 2.5: Accounts CRUD (all types)  (2026-08-01)

- **Implemented:** a user can create, read, edit, close and delete an account of any type the SRS
  names, and **FR-ONB-001 is satisfied for the first time** (**FR-ACC-001**, **FR-ACC-007**,
  **FR-ONB-001** step 4; DB-001, DB-002, DB-003, MNY-001, TIM-001, ARC-003, ARC-005, P-02, P-03,
  P-08).
  - **All eleven account types, not six.** `AccountEntity`'s doc comment (issue 1.6) listed
    `bank | cash | wallet | card | loan | investment`; FR-ACC-001 names eleven and includes neither
    `wallet` nor `card`. `AccountType` in `:core:model` is now the single definition, carrying
    §20.2's exact stored strings. The demo dataset had been writing `"card"` — a value no type-aware
    query would ever have matched — and is corrected to `credit_card`; a new test asserts every demo
    account carries a type the enum recognises, which is the check that was missing.
  - **Balances are derived, never stated** ([ADR-0007](docs/adr/0007-account-balances-derived-not-stored.md)).
    DB-001 says the current balance "is derivable from opening balance + transactions" and is "never
    mutated ad hoc", so every read computes `opening + SUM(live transactions)` in one correlated
    subquery. `AccountDraft` deliberately cannot express a balance: correcting one is FR-ACC-006's
    reconciliation flow (issue 2.7), which posts an adjustment transaction. `current_balance_minor`
    stays as the cache DB-001's integrity job will check against.
  - **Archive is not delete (FR-ACC-007).** A closed account keeps every transaction it ever had and
    drops out of the active list; a deleted one is soft-deleted (DB-002) and the row survives. The
    two are independently observable, and the DAO's delete now filters `deleted_at IS NULL` so a
    second delete reports `NotFound` rather than confirming something that never happened.
  - **Onboarding's fourth step, three ADR-0002 updates later.** `OnboardingStep.ACCOUNT` lands
    exactly where that record said it would, and attaches the account to the recurring rules issue
    2.3 wrote with a null `account_id`. Skipping is a blank name — one representation, so it cannot
    disagree with itself. Skipping *quick setup* now has to be remembered rather than meaning
    "finish", because a step follows it.
  - **Schema v3 → v4**, purely additive: `account.institution` and `account.archived_at_utc_millis`.
    The first migration in this app that alters an existing table rather than creating new ones.
  - **`:feature:accounts`** — list and editor, and `CfoRoute.AccountEditor(accountId: String?)`, the
    first typed route in the app to carry an argument.
- **Tests:** 510 unit passed, 0 skipped (+124); 9 instrumented passed on `emulator-5554`.
  Highlights: a **seeded property test** over ~2 000 generated transactions asserting
  `balance == opening + sum` (money math, 100% coverage); `migrate3To4` against real SQLite; the
  full six-step onboarding flow; **an airplane-mode pass** — an account created with the radio off
  (P-04).
- **Found and fixed on the device, not in a test:** a plain `Row` squeezed the Delete action to 10px
  on an active row and to nothing on a closed one, where the label reads "Reopen account". Fixed
  with `FlowRow`. The regression test added alongside it **does not reproduce the defect** —
  Robolectric measures text with a stub, so it stays green against the broken layout; that was
  confirmed by reverting the fix, and the tracker records the device as the gate rather than
  claiming the test bites.
- **Also fixed:** the archived-accounts switch was not part of its own label's tap target;
  `AccountsUiState.isEmpty` rendered a failed read as a cheerful "no accounts yet"; the issue
  generator cited **§11** (the Investment Intelligence Module) for accounts and told every tracker to
  merge to `main`, which CLAUDE.md §7 forbids — both corrected at
  [`scripts/gen_issue_docs.py`](scripts/gen_issue_docs.py) rather than in the generated files.

### [0.2.4] — Issue 2.4: Demo mode with sample data  (2026-07-28)

- **Implemented:** the app can be explored on realistic sample data before any real figure is
  entered — clearly marked throughout, and erased without residue on the way out (**FR-ONB-004**;
  MNY-001, TIM-001, TIM-002, P-01, P-02, P-03, P-04, P-08).
  - **Reachable without creating a profile**, which is what FR-ONB-004 actually asks for. The offer
    sits on the *first* onboarding step, and taking it writes no time zone, no currency, no display
    name, no consent decision and no completion flag — only a `demo_mode_active` setting. A user who
    leaves the demo lands back on first-run onboarding rather than on an empty dashboard belonging to
    a profile they never made. Asserted, not assumed: `starting the demo creates no profile`.
  - **Isolated by profile id, erased by hard delete** ([ADR-0006](docs/adr/0006-demo-mode-profile-isolation-and-hard-delete.md)).
    Sample rows live under a second `demo` profile in the same encrypted database, so the
    per-profile scoping every query already has *is* the isolation. `DemoDao` is the one DAO in the
    app that deletes outright — a soft-delete tombstone would be exactly the residue the acceptance
    criterion forbids. **No schema change:** a DAO adds queries, not tables, so the database stays at
    version 3.
  - **A deterministic, seeded dataset.** Three months of an Indian salaried household — 4 accounts,
    12 categories, ~29 transactions a month, 3 budget envelopes, 2 recurring rules — from a fixed
    backbone of obligations plus discretionary spending jittered by a seeded `Random` (P-08). Fixed
    clock plus fixed seed gives byte-identical rows, which is what makes the golden test possible.
    Every account's closing balance is derived as opening + its own transactions, so the demo adds up.
  - **The demo's budget is computed, not typed** (P-03). It comes from the real `QuickSetupEngine`,
    so the sample dashboard carries the same `RULE-…` citations a real user's does.
  - **One banner labels every screen.** `CfoDemoBanner` is composed above the navigation graph, not
    inside a destination — a per-screen label is one forgotten screen away from showing fabricated
    figures with nothing saying so. It carries the exit action and is announced as a live region.
  - **Readers follow the active profile automatically**: `observeLatestEnvelopes()` resolves it from
    `DemoModeRepository`, so the dashboard swaps to the sample budget and back without importing
    demo mode at all — and every reader added later inherits that.
- **Also fixed:** `OnboardingFlowInstrumentedTest` **had not compiled since issue 2.3** — the
  ViewModel's constructor changed under it and nothing noticed, because `androidTest` is only
  compiled when a device is attached. Repaired and now verified running.
- **Refactored:** `OnboardingWriter` extracts the consent + profile writes (and the order they must
  happen in) out of `OnboardingViewModel`; `RepositoryModule` splits the `:data:repository` bindings
  out of `CoreModule`. Both were detekt limits reached, fixed by splitting along real seams rather
  than by raising a threshold.
- **Tests:** 386 unit passed (35 new), 8 instrumented passed, 0 skipped. **Counting basis, because
  earlier entries got this wrong:** each test once. A project-wide `testDebugUnitTest` also leaves
  `testReleaseUnitTest` results on disk from previous runs, and naively summing every
  `test-results/**` XML double-counts them — which is where the inflated figures in earlier
  changelog entries and `docs/memory.md` came from. Both new gates were **proven to fail before
  being trusted** —
  the golden dataset test reddened on a one-digit seed change, and the residue test reddened when one
  hard delete was swapped for a soft delete.
- **First emulator run in the project's history.** The app was built, installed and driven on an
  Android emulator: onboarding → demo → banner + sample budget → exit → back to onboarding, with the
  same flow repeated in **airplane mode** (P-04). The instrumented suite passes **8/8** on the
  device, which incidentally executed two paths that had never run anywhere: the **v1→v2 and v2→v3
  Room migrations** against real SQLite (DB-003 was previously taken on trust), and **SEC-002's
  Keystore PIN round trip** (every JVM test of it uses a fake `Mac`).

### [0.2.3] — Issue 2.3: Quick-setup seeds (income/rent/savings)  (2026-07-27)

- **Implemented:** the quick-setup step now *does* something. The three figures onboarding has been
  collecting since 2.1 become a budget, an emergency-fund target and the app's first real financial
  rows (FR-ONB-002, FR-BUD-001, FR-TXN-006; MNY-001, MNY-002, TIM-001, TIM-002, DB-003,
  AI-ARC-003, AI-ARC-006, P-02, P-03, P-04, P-08).
  - **The project's first engine.** `:domain:engines:quicksetup` is pure Kotlin (ARC-002) and turns
    income / rent / savings into needs-wants-savings envelopes, a 3-month emergency-fund target and
    an obligation verdict, citing `RULE-50-30-20`, `RULE-EMERG-FIRST`, `RULE-RUNWAY-M` and
    `RULE-EMI-40` by id **and version** in its provenance.
  - **The budget tells the truth when it does not fit.** RULE-50-30-20's metro flex raises the needs
    band to cover a high rent and takes it from *wants* — never from the savings floor. Past the 60%
    ceiling the envelope is left visibly short of the rent, beside a hard-fail verdict and a sentence
    saying so, rather than being balanced on paper by cancelling the user's saving.
  - **Envelopes total the income exactly**, proven over 800 seeded awkward amounts, because the
    split goes through `Money.allocate`'s largest-remainder algorithm rather than three separate
    divisions. There is no `Double` anywhere on the path; rates are integer basis points.
  - **Nothing is fabricated if skipped**, enforced independently at three layers: a blank field
    produces no engine output, Skip never calls the repository, and an empty plan writes no row at
    all — not even a profile.
  - **`budget` and `recurring_rule` arrive as schema v3**, additive only, along with the app's
    **first `profile` row** — every table is `profile_id`-scoped and until now nothing had ever
    written one. Ids are derived rather than generated, so re-running onboarding updates the same
    rows instead of duplicating them (P-08 applied to storage), and the whole write is one
    transaction.
  - **`EngineProvenance` lands in `:core:model`** (AI-ARC-003): engine id, version, instant and the
    rules that fired — the type every future engine result will carry.
  - **The dashboard's spending split is real.** `SAMPLE_NEEDS/WANTS/SAVINGS_MINOR` are gone,
    replaced by the persisted budget observed as a Flow. Safe-to-Spend and net worth remain
    placeholders — they need the engines issues 5.1/5.2 own. Skipping quick setup now shows an
    empty state rather than a bar of zeroes, because a zeroed chart is a number the app invented.
- **Deviations on record:** two, both deliberate.
  [ADR-0004](docs/adr/0004-quick-setup-persists-budgets-and-recurring-rules.md) — issue 2.3 defines
  `budget` and `recurring_rule` columns owned by issues 4.4 and 3.7, with every forward-looking
  foreign key nullable and a list of what each later issue is expected to add.
  [ADR-0005](docs/adr/0005-quick-setup-thresholds-deferred-rulebook-loader.md) — the four rulebook
  thresholds are typed Kotlin constants rather than rows loaded from `ai/`, because nothing in the
  app loads `ai/` yet; a drift test reads the real rulebook and fails the build if they diverge.
- **Tests:** 536 passed, 0 skipped (was 285). +251: the engine's golden, boundary, property and
  determinism suites, the rulebook drift guard, the repository against a real SQLite engine, the
  onboarding derive/persist/skip paths, and the dashboard's budget states.
- **Gates proven to fail before being trusted.** This project has shipped a vacuous gate before
  (audit G-01), so both new ones were made red on purpose first: a one-point threshold change turned
  the drift test red, and temporarily uncovered code turned `koverVerify` red on the new module.
- **Not verified:** nothing has run on a device — `adb` is not installed and no AVD exists, so
  `/run`, `/verify` and `connectedDebugAndroidTest` are **blocked, not skipped**. The 2 → 3 migration
  is proven structurally on the JVM but has never executed against real SQLite, and the repository
  is tested on unencrypted in-memory Room because SQLCipher needs a device.
  [The tracker](docs/issues/2.3-quick-setup-seeds-income-rent-savings-tracker.md) lists every gap.

### [0.2.2] — Issue 2.2: Biometric/PIN app lock (BiometricPrompt)  (2026-07-26)

- **Implemented:** the app lock — BiometricPrompt class 3 with a PIN fallback, gating the whole app
  on cold start and after an idle timeout (SEC-002, SEC-003, §23.1, FR-SET-001, FR-ONB-001 step 3;
  P-01, P-04, P-08, TIM-001, ARC-003, ARC-004, ARC-005, DB-003, §21.6).
  - **Fail-secure by default, not by code path.** The session flag starts closed and the UI state
    starts `CHECKING`, so a wrong PIN, a cancelled prompt, a Keystore that has stopped working and
    an unreadable settings file all leave the app locked without any branch having to say so. 21
    cases in `AppLockViewModelTest` assert the *negative* — that the session did not open.
  - **SEC-002's schedule, exactly.** 5 failures → 30 s, doubling, capped at an hour, pinned attempt
    by attempt. The counter lives in Proto DataStore, so force-stopping the app — the first thing
    anyone with a stolen phone would try — does not clear a lockout.
  - **A PIN cannot be brute-forced offline.** Four to six digits is a million candidates; no hash
    survives that. The credential is `salt || HMAC-SHA256(salt || pin)` under a key generated inside
    the Android Keystore, so the file on disk gives an attacker no oracle to test guesses against.
    Tink only (SEC-003). The PIN is never written to `SavedStateHandle`, which reaches disk.
  - **The lock gates the encrypted store with an assertion, not a promise.** The Hilt provider for
    `CfoDatabase` refuses to hand it to feature code while locked. The audit log is the single
    exemption — it must record *refused* unlocks — and that exemption is a Hilt qualifier
    (`@AuditDatabase`) visible at every injection site rather than a comment.
  - **`audit_log` arrives as schema v2** (§21.6) with the first real migration, additive only. Four
    columns, holding closed-enum codes and a timestamp: there is nowhere in the table to put PII,
    and a test asserts that against the real SQLite columns. First class in `:data:repository`.
  - **Onboarding gains its SECURITY step**, where [ADR-0002](docs/adr/0002-onboarding-step-order.md)
    said it would go — after the profile, and skippable.
- **Deviation on record:** SEC-001's "wrapped by a Keystore key **requiring user authentication**"
  clause is deliberately left open. Binding the key to device auth would permanently destroy the
  database if the user ever removed their lock screen, and there is no server copy.
  [ADR-0003](docs/adr/0003-app-lock-gate-and-deferred-user-auth-key.md) records that, and the
  related limit that the database gate is checked once per process rather than on every access.
  **SEC-001 is not closed by this issue.**
- **Tests:** 285 passed, 0 skipped (was 202). +83: the SEC-002 schedule, the PIN verifier, the
  app-lock store, the audit repository against a real SQLite engine, the fail-secure matrix, and the
  onboarding security step.
- **Not verified:** `BiometricPrompt` and the real Android Keystore have **never been executed** —
  no device or emulator exists on this machine, so `connectedDebugAndroidTest`, `/run` and `/verify`
  are blocked rather than skipped. The v1 → v2 migration is proven structurally on the JVM and its
  DDL diffed by hand against Room's generated SQL, but has not been run.
  [The tracker](docs/issues/2.2-biometric-pin-app-lock-biometricprompt-tracker.md) lists every gap.

### [0.2.1] — Issue 2.1: 4-step onboarding flow  (2026-07-25)

- **Implemented:** the first-run flow — welcome & privacy pledge → SMS-parsing opt-in → profile,
  currency and time zone → optional quick setup (FR-ONB-001, FR-ONB-002, FR-ONB-003; P-01, P-04,
  TIM-001, MNY-001, ARC-001, ARC-004). A new install now opens on onboarding and every launch after
  it on the dashboard.
  - **The profile time zone is finally written.** `ProfileZoneProvider` has been wired to `Clock`
    since issue 1.10, but nothing ever set the setting it reads, so every day boundary and month
    rollover in the app resolved in the *device* zone by fallback. Onboarding closes that seam.
  - **The consent ledger has its first caller** (P-01). The SMS opt-in is its own step, default
    **off**, with FR-ONB-003's required wording — what is read, that parsing is on-device only, and
    that it can be skipped. Declining writes **nothing**: a revocation record for a consent never
    granted would be a false entry in a ledger whose whole purpose is answering "since when?"
    truthfully.
  - **One atomic write.** `SettingsStore.completeOnboarding` writes the profile, the seeds and the
    completion timestamp in a single `updateData`. Writing them separately could mark the app
    onboarded with no time zone — after which every date resolves wrongly and nothing ever asks
    again. Nothing reaches disk until Finish, so an abandoned onboarding leaves no partial profile.
  - **`MoneyFormatter.parse`** (MNY-001) — the quick-setup amounts are the first the user types.
    Exact via `BigInteger`, never `Double`: `"0.07".toDouble() * 100` is `7.000000000000001`.
    Anything it cannot represent exactly — extra precision, out of `Long` range, a Devanagari digit
    — returns `null` rather than an amount that is nearly right.
- **Deviation on record:** the four steps are not FR-ONB-001's literal ordering — its steps 3
  (security) and 4 (first account) belong to issues 2.2 and 2.5, which this issue does not depend
  on. [ADR-0002](docs/adr/0002-onboarding-step-order.md) records it and fixes where those steps
  insert. **FR-ONB-001 is not closed by this issue alone.**
- **Tests:** 202 passed, 0 skipped (was 156). +31 JVM tests in `:feature:onboarding`, +10 money
  parsing, +5 settings-store, +3 `MainViewModel`. Plus one instrumented test driving the flow
  against real DataStore on a device, and an emulator pass covering fresh install, relaunch,
  process death, airplane mode, dark mode and a 200% font setting.

### Fixed
- **Three defects the tests and the device found, not review.** A consent row whose *label* was not
  tappable — only the switch was, which is the most common complaint about settings rows and costs a
  user with a motor impairment several attempts; the row is now `toggleable` with `Role.Switch`, one
  announced control with a full-width target. A duplicated Indian time zone: the emulator (and many
  real devices) report the legacy alias `Asia/Calcutta`, so `distinct()` on ids left `Asia/Calcutta`
  and `Asia/Kolkata` sitting in the list as two apparently different answers — in this app's primary
  market, on the one screen where the choice must be unambiguous; de-duplication is now by
  `ZoneId.rules`. And a device-only race where the instrumented test read the store before the write
  landed: on a real I/O dispatcher the composition goes idle while the file is untouched, which the
  JVM twin's unconfined dispatcher can never reveal.
- `UnusedPrivateMember` now ignores `@Preview` in `detekt.yml` — a preview has no caller by design,
  and the alternative was making it public, which would put it in the module's API.

---

## [Unreleased]

> **Epic 1 (Foundation & Core Platform) is complete** — issues 1.1–1.10. The app builds, has a
> themed shell with typed navigation, an encrypted database, a consent ledger, and five custom lint
> rules plus a coverage gate and screenshot tests that all fail when they should. What it has never
> had is a **CI run** (no git remote) or a **device run** (no emulator).

### Added
- **App shell, typed navigation and the Hilt object graph** (issue 1.10; ARC-001, ARC-003, ARC-004,
  ARC-006). `MainActivity` now hosts a real `NavHost` inside `CfoTheme` and edge-to-edge insets,
  instead of a placeholder `Text`. Routes are `@Serializable` objects in `:app` — the only module
  that knows more than one feature exists — so a destination cannot be reached with a mistyped
  string and features never import each other. `CoreModule` binds dispatchers, an application
  `CoroutineScope` (the injectable alternative that makes `GlobalScope` unnecessary), the `Clock`,
  and the stores from issues 1.6 and 1.9; a missing binding now fails at compile time.
- **The dashboard as the ARC-004 reference screen** — `DashboardUiState` (immutable),
  `DashboardEvent` (sealed), `DashboardViewModel` (one `StateFlow` out, one `onEvent` in), with
  Turbine tests asserting the whole state sequence including loading. Figures are placeholders until
  issues 5.1/5.2, but the shape is what every later screen copies. Plus a minimal transactions
  destination, so cross-feature navigation is exercised rather than asserted.
- **The `Clock` finally reads the user's time zone.** `ProfileZoneProvider` bridges the synchronous
  `Clock.zone()` that every engine depends on to the `Flow` the setting arrives on, closing the seam
  issue 1.3 deliberately left open. Every failure — unset, unreadable, or an unparseable zone id —
  falls back to the device zone, because an exception out of `Clock.zone()` would crash every engine
  at once.

### Fixed
- A test weakness found by deliberately breaking the zone fallback: three tests stayed green because
  a *crashed collector* leaves the same value they assert. Added a case that distinguishes "fell
  back" from "died", which now fails alongside them.
- `RoomDatabase` moved from `implementation` to `api` in `:core:database` — `CfoDatabase` extends
  it, so it is part of that module's public surface.
- **Settings and the per-feature consent ledger** (issue 1.9; SRS §21.3, P-01, TIM-001).
  `:core:datastore` now holds a real **Proto DataStore** — protobuf schema, generated types, no
  SharedPreferences anywhere. `ConsentStore` gates the four opt-in features (SMS parsing, market
  data, cloud LLM, cloud backup): each consent is **default off**, revocable, and carries the
  grant/revoke timestamps P-01 needs to answer "since when?" — a bare boolean cannot. Consents are
  keyed by a stable feature id, so adding one later is not a schema migration. `SettingsStore`
  carries the profile time zone (the seam `SystemClock` was built for in issue 1.3), currency,
  privacy blur and theme. Reads are `Flow` — a consent read once at startup could not be revoked —
  and everything runs on injected dispatchers, returning `Result<_, AppError>` rather than throwing.
  A corrupt file is `Err(Storage)`, never a silent reset to defaults, because resetting a consent
  ledger discards decisions the user made without telling them. 13 JVM tests.

### Fixed
- **Six tests were passing over writes that were failing.** The stores return
  `Result<Unit, AppError>` instead of throwing (§21.6), so a test that ignores the return value
  cannot fail — and the first version of these tests ignored all of them. Every write is now
  asserted with `assertWritten()`, which surfaced the real problem: DataStore's default storage
  cannot replace an existing file on Windows (`Unable to rename …tmp`), so every second write
  errored. Storage switched to `OkioStorage`, whose `atomicMove` works on every host, keeping test
  and production on the same code path. Android was never affected; the silent-green tests were the
  actual defect.

### Changed
- Version catalog gains `protobuf`/`protobuf-javalite`/`protoc`, the `com.google.protobuf` Gradle
  plugin, and `datastore-core-okio`.
- **Design system: M3 theme, tokens, components and chart primitives** (issue 1.8; SRS §24, §21.6,
  ACC-*). `:core:designsystem` now holds the colour/type/dimension tokens from `docs/Design.md`
  (seed `#00696E`, plus the `positive`/`negative`/`warning` roles Material has no slot for),
  `CfoTheme`, five components (`CfoCard`, `CfoButton`, `CfoSecondaryButton`, `CfoListRow`,
  `CfoAmountText`) and two chart primitives (`CfoProportionBar`, `CfoSparkline`). Accessibility is
  built in rather than reviewed in: 48dp is a token every clickable applies, charts **require** a
  `contentDescription`, and `CfoAmountText` always renders the sign so debit/credit never depends on
  colour alone (P-02). Copy stays out of the module — text and descriptions are parameters, because
  the wording belongs in the calling feature's `strings.xml`.
- **Screenshot tests that run without a device** (closes governance audit **G-02**). Paparazzi
  renders light, dark and 200%-font baselines on the JVM — the first visual coverage this project
  has had, and with no emulator the only way anyone sees what the UI looks like. The CI step,
  `/pre-merge` step 3 and the `settings.json` allowlist entries removed in issue 1.5 are restored.
  Proved by overwriting a baseline and watching `verifyPaparazziDebug` go red.
- **WCAG AA contrast asserted in a unit test** (partly closes **G-24**). Every token pair in both
  themes is computed against the 4.5:1 threshold, including amount colours on their own surfaces —
  turning "accessibility scan passes" from a claim in the DoD into arithmetic that runs on every
  build. The suite includes a deliberately failing pair so the formula itself is proved able to fail.

### Fixed
- **Amounts wrapped mid-number at 200% font** — `-₹2,450.00` broke with the final `0` on the next
  line, which reads as a different number. Caught by the new 200%-font screenshot on its first run;
  `CfoAmountText` is now `maxLines = 1, softWrap = false`, and `CfoListRow` lets the label wrap
  instead of the figure.

### Changed
- Paparazzi is pinned to **2.0.0-alpha02**: the 1.3.5 stable hooks a Gradle internal that moved and
  cannot run on Gradle 8.13. It is test-only tooling that ships in no APK; revisit when 2.0 is stable.
- `CfoHardcodedUiString` (issue 1.5) now covers `:core:designsystem` as well as `:feature:*`,
  closing the follow-up recorded in ADR-0001.
- `config/detekt/detekt.yml`: `MagicNumber` is excluded for `**/theme/**` and test sources. A design
  token file is the one place a literal is correct — that is what §21.6 means by "every colour from
  theme tokens" — and flagging it there would only teach contributors to suppress the rule.
- **Migration test harness — DB-003 enforced on every build, with no device** (issue 1.7; §21.5).
  Room's `MigrationTestHelper` needs hardware this project does not have, which would have left
  "destructive migrations are forbidden" enforced by nobody. But the exported schema JSON is data,
  and `androidx.room:room-migration` parses it in plain Kotlin — so `MigrationSafetyTest` now checks
  the structural half of DB-003 in ordinary unit tests: no table or column may be removed, no column
  may change SQL affinity, and no nullable column may become `NOT NULL` (which passes on an empty
  database and fails on real data). Additive changes stay allowed. It also asserts the schema-level
  money/time invariants where the data actually lives — `*_minor` and `*_utc_millis` are INTEGER,
  `*_iso_date` is TEXT, every table has soft delete and profile scoping — and that fixtures are
  contiguous from v1 and include the declared version, so a bump cannot skip its schema silently.
  The row-level half (`MigrationRoundTripTest`, device-only) and the per-version procedure are in
  `core/database/MIGRATIONS.md`.

  Proved by dropping a real column from `AccountEntity` and letting KSP export a genuine v2: the
  guard failed with *"migrating 1 -> 2 would destroy data: account: column 'current_balance_minor'
  was removed"*. Two things surfaced while doing that — a hand-edited schema JSON cannot fake a
  destructive change (KSP regenerates it), and the test task stayed `UP-TO-DATE` when only a schema
  file changed, so `schemas/` is now a declared test input; without that the guard could have
  reported a stale pass.
- **Encrypted persistence core** (issue 1.6; SRS §20/§23, SEC-003, DB-003, P-01/P-04): `:core:database`
  now holds `CfoDatabase` (Room v1) over **SQLCipher**, with the passphrase wrapped by a
  Keystore-backed Tink AEAD — a random passphrase is generated once and only its ciphertext touches
  disk, so the key that unwraps it never leaves the TEE. Base schema is the four tables the issue
  names — `profile`, `account`, `transactions`, `category` — each carrying the invariants that apply
  to every table: amounts as `Long` paise (MNY-001), instants as UTC epoch millis with user-picked
  dates as ISO strings (TIM-001/002), soft delete via `deleted_at_utc_millis`, and per-profile
  scoping on every row and every query. Schema exported to `core/database/schemas/` so issue 1.7's
  migration tests have a fixture. No `fallbackToDestructiveMigration` (DB-003) — with no server
  copy, a missing migration must fail loudly rather than drop tables. 12 unit tests cover the key
  path, including that an unwrap failure surfaces as an error rather than silently minting a new
  passphrase, which would present an unopenable database as an empty one.

### Known gaps
- **The encrypted round-trip is unproven.** SQLCipher and the Keystore exist only on a device, and
  this machine has none (`adb devices` empty, no AVD installed). `EncryptedDatabaseTest` — the
  ciphertext-on-disk check, the read-back and the reopen-with-the-same-key check — is written and
  compiles but **has never executed**. One `connectedDebugAndroidTest` run settles it.
- Database re-key (`PRAGMA rekey`) is intentionally not implemented: `rotateWithPrevious()` supplies
  both keys and is tested, but shipping an untested path that rewrites the whole encrypted file
  would be worse than the gap. It belongs with issue 11.1.
- **Custom lint: five rules that now fail the build** (issue 1.5 / task 1.1.5; SRS §21.3/§21.4/§21.6,
  MNY-001, TIM-001, ARC-006, P-01 — closes governance audit G-03). A new `:lint` module ships
  `CfoMoneyAsFloatingPoint` (a floating-point declaration with a monetary name), `CfoWallClockInDomain`
  (`System.currentTimeMillis()`/`now()` inside `:domain:*` or `:core:model`, with `:core:common`'s
  `SystemClock` exempt as the one sanctioned wall-clock read), `CfoGlobalScope`, `CfoHardcodedUiString`
  (a literal in a `:feature:*` `Text(...)`, `@Preview` exempt) and `CfoPiiInLogs` (a log line naming
  money or personal data). All at severity **error**, wired to every module — Android *and*
  pure-Kotlin, via the standalone `com.android.lint` plugin, because `Money` lives in a `java-library`
  module that lint would otherwise never visit — with **no baseline**, so nothing is grandfathered.
  Each rule was proved by seeding a real violation in a real module and watching the build go red,
  not by the fixture suite alone. 14 fixture tests cover every rule in both directions.
- **ADR-0001** — the repository's first architecture decision record: why `:lint` sits outside the
  §21.2 module graph, and the exact money/PII identifier lists with the false-positive stance behind
  them (partly closes audit G-12).

### Changed
- **`CLAUDE.md` now says "lint-enforced" because it is.** The `GlobalScope`, wall-clock,
  PII-logging and hardcoded-string entries named the rules as review-blocking with enforcement
  "landing in 1.1.5"; each now names the detector that blocks it.
- `config/detekt/detekt.yml`: `style.ReturnCount.excludeGuardClauses: true` — guard clauses are
  idiomatic Kotlin and the lint detectors are built from them; the rule's real target, tangled
  mid-function returns, still counts.

### Fixed
- Four `ExperimentalCoroutinesApi` opt-in warnings in `DispatcherProviderTest` (from issue 1.3),
  which earlier runs had missed because the compile task was up-to-date.
- **`Result<T, AppError>` error model** (issue 1.4 / task 1.1.4; SRS §21.6): the typed return every
  engine and repository will use, so no exception crosses a layer boundary and absence is modelled
  rather than nulled. `sealed interface Result` with `Ok`/`Err` and short-circuiting `map`,
  `flatMap`, `mapError`, `fold`, `getOrElse`, `getOrNull`, `errorOrNull`, `onOk`, `onErr` — a `when`
  over it is exhaustive with no `else`. `AppError` is a sealed hierarchy (`Validation`, `NotFound`,
  `Storage`, `Network(retryable)`, `Crypto`, `Unexpected`) carrying a stable `code` for the UI to
  map to `strings.xml` plus a non-localised fallback message. `runCatchingToResult { }` is the
  single sanctioned catch site: it converts I/O and crypto failures to `Err`, and deliberately
  rethrows `CancellationException` (swallowing it breaks structured concurrency, ARC-006),
  `IllegalState`/`IllegalArgumentException` (a failed `require`/`check` is a bug and §21.6 reserves
  crashes for those), and JVM `Error`s. **No PII by construction (P-01):** the only path from a
  `Throwable` to an `AppError` keeps the exception's class name and discards its message, which
  routinely carries paths, tokens or row data. 29 new tests; `:core:common` holds at 100% coverage.
- **Injected `Clock` + `DispatcherProvider`** (issue 1.3 / task 1.1.3; SRS §21.4 TIM-001/TIM-002,
  §21.2 ARC-006): `:core:common` now owns the time and concurrency seams every engine will inject.
  `Clock` answers `nowUtcMillis()` / `zone()` / `today()` in the **profile time zone** — so a spend
  at 23:30 IST belongs to that day's budget even though UTC has rolled over — with `startOfDay`,
  `endOfDay`, `isSameProfileDay` and `toProfileDate` as extensions, and `SystemClock` as the single
  sanctioned `System.currentTimeMillis()` call site in the codebase (TIM-001). The profile zone is
  read through a provider lambda on every call, which is the seam Proto DataStore settings (issue
  1.9) will plug into. `DispatcherProvider` exposes Main/IO/Default so no call site names
  `Dispatchers.IO` inline and `GlobalScope` is never needed (ARC-006). Uses `java.time` (native at
  minSdk 26, NFR-008) rather than adding `kotlinx-datetime`. `FakeClock` and `TestDispatchers` ship
  from a **`testFixtures`** artifact so later modules reuse one set of doubles. 20 tests covering the
  IST day/month rollover, UTC-midnight straddling, a DST transition, and virtual-time coroutines;
  `:core:common` measures **100%** line coverage.

### Verified
- The **85% coverage floor now bites a second module.** Issue 1.2 could only prove the gate on
  `:core:model`; `:core:common` measured 89.66% before the gaps were closed, so the floor is
  demonstrably measuring real code rather than passing vacuously. Also learned: Kover counts
  `testFixtures` classes, so published fixtures need their own tests.
- **`Money` value class** (issue 1.2 / task 1.1.2; SRS §21.4, MNY-001/MNY-002, NFR-012): the single
  monetary type, `Long` minor units (paise) end-to-end — `@JvmInline value class Money(val minor: Long)`
  in `:core:model` with overflow-checked `plus`/`minus`/`times` (`Math.*Exact`, so a wrong answer
  throws instead of wrapping), `percentOf(bps: Int)` using **HALF_EVEN** banker's rounding on integer
  basis points (MNY-002 — no `Double` rate), and `split(n)`/`allocate(weights)` using the
  largest-remainder method so parts **sum exactly** to the original, for refunds as well as payments.
  Plus `MoneyFormatter` rendering Indian 2,2,3 digit grouping (₹1,23,456.78) with the grouping written
  out rather than delegated, so output does not drift with JDK or Android locale data. 35 tests:
  the T1–T8 table, a seeded property sweep (P-08) over ~41 000 split combinations, and the Long
  extremes. No `Double`/`Float` touches a monetary value anywhere.
- **A coverage gate that actually blocks** (governance audit G-01): `configureCoverage()` in the
  `cfo.kotlin.library` convention plugin gives `koverVerify` its first real rules — line coverage
  ≥ 85% on pure-Kotlin modules and **100% on `:core:model`** (money math). Previously Kover was
  applied with zero rules and passed at any coverage, including 0%. Proved to bite twice before
  merging: an impossible 101% bound failed the build, and deleting one test dropped the measurement
  to 77.5% and failed it again.
- **Gradle multi-module skeleton** (issue 1.1; SRS §21.2/§21.3, ARC-001/ARC-002): the module graph
  made real and building green — `:app`; `:core:{model,common,database,datastore,network,crypto,
  designsystem}`; `:domain:engines:forecast` + `:domain:usecase`; `:data:repository`;
  `:ml:{ocr,llm}`; `:feature:{dashboard,onboarding,transactions}`; `:sync:backup`; `:widget`.
  Dependencies are one-way `feature → domain → data/core` (ARC-001); `:core:model`/`:domain:*` are
  pure Kotlin/JVM with a **Gradle-enforced ARC-002 guard** that fails the build (with a clear
  message) if an Android plugin is applied — proved by a Gradle TestKit test. A single version
  catalog (`gradle/libs.versions.toml`) pins the §21.3 stack (AGP 8.11 / Kotlin 2.1 / Gradle 8.13,
  compileSdk 36); `build-logic/` convention plugins (`cfo.kotlin.library`,
  `cfo.android.{library,application,compose,feature}`, `cfo.hilt`) keep module scripts tiny with
  shared JVM-17 + ktlint/detekt/Kover config. CI (`.github/workflows/ci.yml`) now runs the real
  tasks (convention/ARC-002 tests · ktlint/detekt/lint · unit + coverage · assemble) on
  `dev`/`stage`/`main`.
- **Reference-style backlog** (`docs/superpowers/specs/`): a planning-grade design spec
  (`2026-07-17-ai-personal-cfo-design.md`, 14 §-sections distilling the SRS) and its
  machine-readable index (`2026-07-17-issues.csv`) — **13 epics, 85 issues** mapped to the SRS
  roadmap (§26) and traceability (§28).
- **Full issue backlog** (`docs/issues/`): one rich `<id>-<slug>.md` + `<id>-<slug>-tracker.md`
  per issue (170 files), each with acceptance criteria, a label-driven Skill Rules table, guiding
  principles, three-tier workflow rules, a Definition-of-Done gate, and a Verification-Log tracker.
- **Issue-docs generator** (`scripts/gen_issue_docs.py`): single source of truth holding all 85
  issue records; emits the CSV + every issue/tracker file, idempotently.
- **Reference-format templates**: `_ISSUE_TEMPLATE.md` / `_TRACKER_TEMPLATE.md` rewritten to match
  the generated files.
- **Android use-case dev skills** (19, global `~/.claude/skills/`): `compose-ui`,
  `room-and-migrations`, `hilt-di`, `gradle-modules`, `ml-kit-ocr`, `on-device-llm`,
  `workmanager-jobs`, `datastore-consent`, `keystore-crypto`, `biometric-auth`,
  `retrofit-networking`, `glance-widget`, `paparazzi-screenshot-testing`, `proguard-r8-release`,
  `kotlin-multiplatform`, `compose-navigation`, `compose-performance`, `edge-to-edge`,
  `kotlin-coroutines-flow` — each a project-tailored playbook for the pinned §21.3 stack, citing the
  binding rules (ARC/AI-ARC/MNY/TIM/SEC, P-01…P-08). The last six are grounded in the official
  Google Android (R8 audit, Navigation 3, edge-to-edge, Compose performance / Baseline Profiles) and
  JetBrains/Kotlin (KMP, coroutines/Flow) agent-skill guidance rather than copied from third-party
  registries (skills.sh's mobile catalogue is React-Native/Firebase-centric and its cloud-auth skills
  conflict with P-01/P-04); reconciled line-by-line against those official SKILL.md sources, which
  also surfaced the coroutines/Flow-discipline and Compose-recomposition gaps the last three close.
  Wired into the generator's `LABEL_SKILLS` so each surfaces on the relevant issues — including
  crypto/backup, auth, market-data, integration, widget, designsystem, testing, release, `kmp`,
  `dashboard`/`core`, and the previously-unmapped `app`/`di`/`lint`/`accounts`/`transactions`/
  `notifications` labels. The 7 universal skills (`test-driven-development`, `security-review`,
  `ci-cd-and-automation`, …) were already installed globally — left untouched. `check_issue_docs.py`
  asserts every referenced skill path resolves.
- **`/run` and `/verify` commands** (`.claude/commands/`): the "real gate" (§9) — build + install +
  launch on an emulator, then drive the changed flow (incl. an airplane-mode leg) and confirm it works.
- **Top-level project docs** (`docs/`): `PRD.md`, `Architecture.md`, `Rules.md`, `phase.md`,
  `Design.md`, `memory.md` — thin, cross-referenced views of the SRS / design spec / `CLAUDE.md`
  for fast onboarding (the SRS and `CLAUDE.md` remain the sources of truth). `Design.md` proposes
  the initial Material 3 tokens (seed `#00696E`, Roboto, M3 type scale) pending issue 1.8;
  `memory.md` is the living progress tracker.

### Changed
- **Branch model → GitFlow-lite.** `CLAUDE.md` §7, `docs/issues/00-issue-workflow.md` (steps 8/10),
  and the design spec §9 now specify `feature/* → dev → stage → main` (was trunk-based), with
  `main` (releases) and `stage` (live testing) as **protected**, PR-only, CI-gated branches and
  `dev` as the integration branch.
- **`docs/features/`** repositioned as the deeper **sub-task** layer the issues link down into
  (kept; the 13-epic CSV is now the canonical epic/issue index). `00-issue-workflow.md` and
  `docs/features/README.md` point at the new spec + CSV.
- **Documentation no longer asserts gates that are not wired** (governance audit G-02/G-03/G-04;
  §21.6). `CLAUDE.md` now marks the `GlobalScope` (ARC-006), `System.currentTimeMillis()` (TIM-001)
  and PII/amount-logging bans as **review-blocking today, lint-enforced with task 1.1.5** instead of
  claiming an existing lint rule; detekt now sets `complexity.LongMethod.threshold: 40` so the
  documented 40-line limit is real (detekt's default 60 left it unenforced).

### Removed
- Superseded feature-level `docs/issues/1.1-project-skeleton.md` + tracker (replaced by issues
  1.1–1.5, which link down to the existing `docs/features/1.1-project-skeleton/tasks/` files).
- Dead `verifyPaparazzi*` invocations from `/pre-merge` and `.claude/settings.json` (audit G-02) —
  the task does not exist until Paparazzi lands with issue 1.8, so the DoD command was unrunnable.

## [0.1.0] — Epic 0: Foundations & AI blueprint  (2026-07-17)

### Added
- **AI subsystem files** the app loads at runtime (`ai/`): layered-pipeline architecture,
  Insight Orchestrator workflow + engine registry, RULE-KB rulebook + Financial Order of
  Operations, chat tool registry, LLM system prompt + numeric guardrail, and the
  classification / market-signal / tax / seasonality / vehicle-maintenance knowledge bases.
  (SRS §7, §8, §19, §29, §30, §36, §38.)
- **Agent development config** for writing & maintaining the code: `CLAUDE.md` (binding rules),
  project skills (`new-engine`, `add-rulebook-rule`, `money-time-audit`), slash-command
  workflows (`/new-feature`, `/pre-merge`), CI pipeline, PR template, `ENGINE.md` + ADR
  templates, `.editorconfig`. (SRS §4.2, §21.)
- **Issue workflow** (`docs/issues/`): master workflow + issue/tracker templates for driving
  backlog issues from the SRS.
- `VERSION` and this changelog.

### Notes
- No application (Kotlin/Gradle) code yet — this release is the spec-faithful scaffolding and
  the AI/agent configuration that the build will be written against.

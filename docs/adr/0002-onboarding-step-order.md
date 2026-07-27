# ADR-0002 — Onboarding's four steps are not FR-ONB-001's four steps

- **Status:** accepted
- **Date:** 2026-07-25
- **Deciders:** Harish G (solo), implementing issue 2.1
- **SRS refs:** §5.1 (FR-ONB-001, FR-ONB-002, FR-ONB-003), §23 (SEC-002), P-01, P-04, TIM-001

## Context

FR-ONB-001 is specific about what the four onboarding steps are:

> App MUST be usable after a 4-step onboarding: (1) welcome & privacy pledge, (2) create local
> profile + choose currency format, (3) set up security (biometric/PIN), (4) add first account with
> opening balance.

Issue 2.1 is the issue that builds it, and its own description lists a different four:
welcome → privacy/consent → profile & currency → quick setup.

The two cannot both be satisfied now, because **the SRS's steps 3 and 4 are other issues**:

| SRS step | Belongs to | Status |
|---|---|---|
| 3 — security (biometric/PIN) | issue **2.2**, BiometricPrompt + PIN (SEC-002) | **built 2026-07-26** — inserted as `SECURITY` after `PROFILE`, as planned below |
| 4 — first account + opening balance | issue **2.5**, Accounts CRUD, which needs a repository and a schema version | not built |

Issue 2.1's declared dependencies are **1.10 and 1.9 only** — not 2.2, not 2.5. The backlog is
topologically ordered by those dependencies, so 2.1 is scheduled to be buildable before either of
them exists. Building FR-ONB-001 literally today would mean shipping two steps that are dead
screens, and a first-run flow that asks for an account it cannot store.

Two smaller pressures point the same way. FR-ONB-003 requires the SMS-parsing opt-in to be "a
separate, skippable, clearly-explained opt-in" — *separate* is hard to honour if consent is a
paragraph inside the welcome step. And the handoff notes that `ProfileZoneProvider` (issue 1.10)
bridges the profile time zone to `Clock`, but **nothing in the app has ever written that setting**,
so every date in the app currently resolves in the device zone by fallback. Whatever else onboarding
does, it needs to write the time zone, and that lives in the SRS's step 2.

## Decision

**Build the four steps the issue specifies, in this order:**

1. Welcome & privacy pledge — FR-ONB-001 step 1, unchanged.
2. SMS-parsing consent — FR-ONB-003, promoted to its own step.
3. Profile: display name, currency, **time zone** — FR-ONB-001 step 2, plus the zone.
4. Quick setup: income / rent-EMI / typical savings — FR-ONB-002, skippable.

The step list is an ordered `OnboardingStep` enum, and the progress indicator derives its total from
`entries`, so **adding a step is one enum constant and one composable** — the indicator can never
say "Step 4 of 3".

**Where the deferred steps go when they land:**

- **Issue 2.2 (security)** inserts a `SECURITY` constant after `PROFILE`, matching FR-ONB-001's
  ordering — the profile exists by then, which is what a PIN or biometric is protecting. It must
  stay skippable in the same way: SEC-002 describes the lock, not a requirement to enable it during
  first run.
- **Issue 2.5 (first account)** inserts an `ACCOUNT` constant last, after `QUICK_SETUP`. It needs a
  repository and a new database schema version, so it cannot be earlier than the issue that adds
  them.

Neither insertion changes the steps decided here, and neither is blocked by this decision.

## Consequences

**Good.** Onboarding ships now rather than waiting on two unrelated issues, and every screen in it
does something real. The time-zone seam that issue 1.3 opened and 1.10 wired is finally closed by
something that writes it. FR-ONB-003's "separate" is honoured literally rather than argued about.

**Bad.** For as long as 2.2 and 2.5 are outstanding, the app's first run does not match FR-ONB-001
as written — a reader comparing the SRS to the running app will find a discrepancy, which is
precisely why this record exists. FR-ONB-001 is **not** satisfied until 2.2 and 2.5 have inserted
their steps; issue 2.1 alone must not be cited as closing it.

> **Update, 2026-07-26:** issue 2.2 has inserted its `SECURITY` step, exactly where this ADR said it
> would go and skippable as required. **FR-ONB-001 remains unsatisfied** — step 4 (first account with
> opening balance) still waits on issue 2.5.

**Neutral.** Requirement traceability (§28) now maps FR-ONB-001 to three issues rather than one.
The quick-setup step captures its figures here but does nothing with them; issue 2.3 reads them to
seed budgets and the emergency-fund target. They are stored as `int64` minor units (MNY-001) so that
consumption needs no migration.

> **Update, 2026-07-27:** issue 2.3 has closed that loop. The quick-setup step now derives a budget
> and an emergency-fund target from the figures as the user types, shows them with the rules that
> produced them, and persists them as `budget` and `recurring_rule` rows at schema v3
> ([ADR-0004](0004-quick-setup-persists-budgets-and-recurring-rules.md)) — along with the app's
> first `profile` row. The step order decided here is unchanged. **FR-ONB-001 remains unsatisfied**:
> step 4 (first account with opening balance) still waits on issue 2.5, which is also what will
> attach an account to the recurring rules 2.3 leaves unattached.

## Alternatives considered

**Build FR-ONB-001 literally, with steps 3 and 4 as placeholders.** Rejected: two of four steps
would be text telling the user to do something later, which makes a first-run flow feel broken and
teaches them to tap Next without reading — on the one flow where the privacy pledge and the consent
explanation are the things they most need to read.

**Defer issue 2.1 until 2.2 and 2.5 are done.** Rejected: it inverts the backlog's dependency
order, and it leaves the profile time zone unwritten for longer, which silently affects every date
computation in the app in the meantime.

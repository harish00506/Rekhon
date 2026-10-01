# ADR-0059 — The consents dashboard shows the record, not just the switch

- **Status:** accepted
- **Date:** 2026-10-01
- **Deciders:** Harish G (solo)
- **SRS refs:** §23, P-01 (explicit, per-feature, revocable consent), DPDP 2023 (purpose limitation,
  withdrawal, the right to know what is held); issue 11.3. Builds on issue 1.9's consent ledger.

## Context

The app has had a consent ledger since issue 1.9. It records, per feature, whether consent is in
force, **when it was given**, and **when it was withdrawn** — the proto comment says why: *"a bare
bool cannot answer 'when did I agree to this?' or 'when did I withdraw it?', which is exactly what
a privacy-first app must be able to show."*

It could not show it. The settings screen rendered four switches from a `Map<ConsentFeature,
Boolean>` and dropped both timestamps on the floor. P-01's promise was operable and not inspectable:
the user could flip a consent, but could not ask the app what they had agreed to, or when, or what
would break if they stopped.

## Decision

**1. A dashboard, on its own route, that answers three questions per consent.**
What it is **for**, what **stops** without it, and **when** it was given — plus the withdrawal date
when there is one. One card each, one tap to withdraw or allow.

**2. Three states, not two.** "Never given" and "withdrawn on the 4th" are different facts about a
person. A screen that showed both as "off" would misreport the user's own history back to them,
which is the opposite of what a consent record is for. The third state exists in the ledger
already; this is the first screen that distinguishes it.

**3. The consequence line is part of the control, not decoration.**
"Withdraw" on its own asks the user to guess what they are about to break, and a privacy control
people are afraid to use is one they leave switched on. Each sentence describes what the repository
that owns the feature actually does — *"no message is read again, and everything already read from
your messages is deleted"* is `SmsRepository`'s behaviour, not a reassurance.

**4. The dates are in the profile's zone.**
20:30 UTC is already tomorrow in Kolkata. Formatting the ledger in UTC would tell a user they
agreed to something the day before they did — a small wrongness in precisely the record that exists
to be precise (TIM-001).

**5. The settings switches stay, and the dashboard is added beside them.**
Two surfaces that write through the same store cannot disagree, and the switch is the right shape
for the common case ("turn this off now"). The dashboard is the right shape for the other one
("what did I agree to?"). Removing the switches would have been a bigger diff that made the common
case slower, to enforce a tidiness nobody asked for.

**6. `CLOUD_LLM` is listed and described as unbuilt.**
Nothing in the app sends anything to a cloud model — issue 10.5 shipped the payload type and not
the transport. The honest options were to hide the row or to say so; hiding it would make the list
an incomplete account of what the app may do, so the row reads *"It is not built yet, so nothing is
sent today whatever this says."*

**7. Granting is also one tap, and that is safe here because the card has already said what it
is for.** A grant starts a data flow, so the usual objection to a single tap applies — but the
purpose and the consequence are the two lines directly above the button, which is more than the
settings switch offers and more than most consent dialogs manage. A confirmation step would add
friction to the *correction* case (a user who withdrew something by mistake) without adding
information.

## What already enforced withdrawal, and what proves it

This issue is a screen. Revocation was already enforced where each data path lives, which is why
AC2 is satisfied by code that predates it — and each has a test that names the behaviour:

| Consent | Enforced in | Test |
|---|---|---|
| `SMS_PARSING` | `SmsRepository` + `SmsConsentWatcher` | *"revoking the consent erases pending drafts and resets the cursor"*, and *"…under every profile, not just the active one"* |
| `MARKET_DATA` | `MarketPriceRepository` | *"with consent revoked nothing is asked"*, *"an unreadable consent store is not a grant"* |
| `CLOUD_BACKUP` | `BackupRepository` + the settings screen dropping a sealed backup | *"a revoked consent stops the next backup"* |
| `CLOUD_LLM` | nothing — there is no transport | — |

The dashboard adds no enforcement of its own and deliberately does not re-derive any: a second
place that decided whether a consent was in force would be a second answer.

## What the review and the mutations found

- **A vacuous test, caught by a mutation.** "Every consent is listed, even the ones nobody has
  answered for" passed against an implementation that listed only the *recorded* entries — because
  the fake ledger seeded all four features. The fake now starts empty, like a real fresh install,
  and the mutation fails as it should. The fourth gate of this shape this project has found.
- **The row must not flip before the store agrees.** The state is derived from the ledger's own
  flow, so a failed write leaves the row showing what is still true. The lie a privacy switch must
  never tell is "off" over a feature that is still running, and there is a test for it.
- **A ledger that cannot be read is an error, not an empty list of permissions.** Falling back to
  "nothing is granted" would be comforting and wrong — the consents are still in force, and a
  screen that implied otherwise would invite the user to stop looking.

## Deferred, and why

- **An export of the consent history** (DPDP's "right to know" in a portable form). It belongs with
  issue 11.5's DPDP alignment, alongside the other rights, rather than being invented here.
- **A full audit trail** — every grant and withdrawal, not just the latest pair. The ledger keeps
  one of each by design (issue 1.9); turning it into an append-only log is a schema change and a
  retention decision, and neither is this issue's.
- **Reaching the dashboard from onboarding.** Onboarding asks for the SMS consent inline and says
  what it is for; sending a first-time user to a management screen before they have granted
  anything would be noise.

## Consequences

- A user can see what the app may use, what each thing is for, what stops without it, and the date
  they agreed — and withdraw any of it in one tap, with the effect stated plainly as immediate.
- The ledger's timestamps, recorded since the first release, finally reach a screen.
- Two places write consent. They write through the same store, so they cannot drift; a third would
  be one too many.

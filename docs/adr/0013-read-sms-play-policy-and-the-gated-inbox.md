# ADR-0013 — READ_SMS is a Play-policy risk we take with our eyes open

**Status:** Accepted · **Date:** 2026-08-07 · **Issue:** 3.9 · **SRS:** §18, §23; P-01

## Context

Issue 3.9 requires opt-in, on-device SMS transaction parsing: bank alerts become draft transactions
the user confirms. Reading the inbox on Android requires `READ_SMS`, a runtime permission in the
`SMS` group.

**Google Play restricts that permission.** Its SMS and Call Log Permissions policy allows an app to
request `READ_SMS` only when the app is the user-selected default SMS, phone or assistant handler,
or when it fits a short list of exceptions — backup and restore, enterprise device management,
connected-device companions, caller ID and spam detection, cross-device sync. **"Parse bank alerts
into transactions" is not on that list.** An app requesting `READ_SMS` outside those cases is
expected to be rejected at review, or to have the permission removed.

This is a constraint the SRS does not mention. §18 and §23 specify the feature and its privacy
properties; neither addresses distribution. So the decision is genuinely ours, and it was put to
the project owner rather than assumed.

Three options were considered:

1. **Build it and ship it gated.** Full SRS fidelity now; the policy risk is carried and recorded.
2. **Build it behind a `BuildConfig` flag, compiled out of release** until a Play declaration is
   approved. Safest at submission, but the feature ships dark and the §9 emulator gate only ever
   runs on a debug build.
3. **Parser only, no permission** — land `:domain:engines:sms` and a manual "paste an SMS" path, and
   defer the reader. Smallest diff, zero risk, but 3.9's acceptance criteria are half met.

## Decision

**Option 1.** The feature is built to the SRS, and the permission is requested — behind gates that
make it unreachable for anyone who has not asked for it. The risk is recorded here rather than
discovered at submission.

The mitigations are structural, not procedural:

- **`READ_SMS` is declared in exactly one module**, `:data:sms`, beside the one class that uses it.
  A reviewer — ours or Google's — establishes what the app can do with an inbox by reading one
  manifest, one interface and one query.
- **`RECEIVE_SMS` is not requested at all.** A broadcast receiver would double the policy exposure
  to buy latency nobody needs. New alerts are found by a daily `WorkManager` scan from a stored
  cursor (`SmsScanWorker`).
- **Two independent gates, in a fixed order.** The in-app consent (`ConsentFeature.SMS_PARSING`,
  default off, revocable) is checked *before* the OS permission is ever requested, so the system
  dialog is unreachable from a state the user has not opted into. `SmsDraftsUiState.stage` encodes
  that order and `SmsDraftsViewModelTest` asserts it.
- **One chokepoint.** `SmsRepository.scan()` checks the consent before the reader is constructed, so
  "disabled means zero SMS access" is a property of the architecture rather than a rule each future
  caller must remember. `SmsRepositoryTest` proves it with a fake reader that *throws* if called —
  an empty result would also be what a reader that was called and found nothing returns, and the
  difference between those two is the whole privacy claim.
- **The query reads four columns from the inbox only**, filtered in SQL, so messages the app is not
  interested in never enter its process.
- **Nothing is stored.** `sms_draft` has no `body` column; the row holds a conclusion and the inbox
  id it came from. `MigrationSafetyTest` asserts there is nowhere to put one.

**The fallback if Play refuses**, in order of preference: submit the permissions declaration form
describing the on-device, consent-gated use; if refused, move the reader behind a build flag and
ship the app without it (option 2, which this design already supports — the parser, the schema and
the review screen are independent of the reader); and, failing that, distribute a variant with the
feature outside Play. **Every other capture path works without this permission**, so the app remains
whole either way.

## Consequences

- 3.9 ships complete and is exercisable on a device, which is what §9's real gate requires.
- A Play submission may be rejected or asked to remove `READ_SMS`. That is a known, recorded cost,
  and the fallback is a build-flag change rather than a redesign.
- **A second exemption in `MigrationSafetyTest`.** `sms_draft` is the only table in this schema
  without `deleted_at_utc_millis`: revoking the consent **hard-deletes** every pending draft. A
  tombstone would mean the app still held what it had inferred from the user's inbox after being
  told to stop, which is what P-01's "revocable" exists to prevent. Accepted and dismissed rows
  survive — the first became a transaction the user saved and needs its provenance (AI-ARC-003), the
  second is a decision that must not be re-asked. Two tests pin this: the exemption list is
  enumerated, and `sms_draft` is separately asserted to keep `profile_id` and to have no tombstone.
- **Two defects the mandatory security review found, both fixed before merge**, and both of the
  same shape — a new profile-scoped table that existing device-wide machinery did not know about:
  - **The demo wipe did not reach `sms_draft`.** ADR-0006 makes the demo a second profile erased on
    exit, and `DemoDao` deletes eleven tables; the twelfth was missing. Because the "no residue"
    test asserts via `DemoDao.countRowsFor`, which enumerates *the same list*, **the assertion passed
    vacuously**. A demo session on a phone whose owner had opted in would have left drafts drawn from
    their real inbox behind, under a profile that no longer existed. Fixed by adding the delete, the
    count term, and a regression test verified to fail without it.
  - **Revocation was scoped to the active profile.** The consent is device-wide — one
    `ConsentFeature.SMS_PARSING` — so revoking while the demo was showing would have kept every
    pending draft under the real profile. `SmsDraftDao.deleteAllPending()` is now the **only
    deliberately unscoped query in this schema**, safe because of *what* it deletes (proposals
    nobody accepted) rather than where.
- The consent's revocation path now has teeth: `SmsRepository.onConsentRevoked()` erases pending
  drafts and resets the scan cursor. **Nothing calls it yet** — the consents dashboard that will is
  issue 5.x's. Until then a revocation stops all reading (the gate) but leaves existing pending
  drafts on disk until the next revoke-aware caller exists. This is recorded as a known gap in the
  3.9 tracker rather than left implicit.

## Alternatives rejected

- **`SmsRetriever` (no permission).** Only delivers messages containing an app-specific hash, which
  banks do not send. It solves OTP autofill and cannot see a transaction alert.
- **Asking the user to forward or paste alerts.** No permission, but it is manual entry with extra
  steps — it does not meet "automated capture", which is the epic this issue closes.
- **Becoming the default SMS handler.** Would make the permission compliant and is wildly
  disproportionate: a personal-finance app cannot reasonably ask to own the user's messaging.

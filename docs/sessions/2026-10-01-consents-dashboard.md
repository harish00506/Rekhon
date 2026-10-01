<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 11.3 — the consents dashboard, and the timestamps that had never reached a screen.
  Result: a reader can see why there are three states rather than two, why the rows are never
          flipped optimistically, and what a mutation found in this issue's own test.
  Changelog: 2026-10-01 — Created.
-->

# 2026-10-01 — What this app may use, and since when (issue 11.3, ADR-0059)

**Branch:** `feature/11-3-consents-dashboard-one-tap-revoke` off `dev` (`0e490d3`)
**Versions:**
- **VERSION** 0.10.9 → **0.10.10**
- **versionCode** 53 → 54
- **Schema** 29 → **29 (unchanged — the ledger already held everything this screen reads)**

---

## 1 · Decisions this session

The full argument for each is in [ADR-0059](../adr/0059-the-consents-dashboard-shows-the-record-not-just-the-switch.md).

- **A dashboard on its own route**, answering three questions per consent: what it is **for**, what
  **stops** without it, and **when** it was given. The ledger has recorded the last of those since
  issue 1.9 — the proto comment even says why — and the settings screen dropped it on the floor by
  rendering a `Map<ConsentFeature, Boolean>`.
- **Three states, not two.** "Never given" and "withdrawn on the 4th" are different facts about a
  person, and a screen that shows both as "off" misreports their own history back to them.
- **The consequence line is part of the control.** "Withdraw" alone asks the user to guess what
  they are about to break, and a privacy control people are afraid to use is one they leave on.
  Each sentence describes what the owning repository actually does.
- **Dates in the profile's zone.** 20:30 UTC is already tomorrow in Kolkata; formatting the ledger
  in UTC would misdate the one record that exists to be precise.
- **The rows are never flipped optimistically.** They are derived from the store's own flow, so a
  failed write leaves the screen showing what is still true. The lie a privacy switch must never
  tell is "off" over a feature that is still running.
- **A ledger that cannot be read is an error, not an empty list of permissions** — the consents are
  still in force, and implying otherwise would invite the user to stop looking.
- **The settings switches stay.** Two surfaces that write through the same store cannot disagree,
  and the switch is the right shape for "turn this off now" while the dashboard is the right shape
  for "what did I agree to?".
- **`CLOUD_LLM` is listed and described as unbuilt**, rather than hidden. Hiding it would make the
  list an incomplete account of what the app may do.
- **Deferred** (ADR-0059): a portable export of the consent history (it belongs with 11.5's DPDP
  work) and a full append-only audit trail rather than the latest pair.

**What this found.**

1. **A test that could not fail.** "Every consent is listed, even the ones nobody has answered for"
   passed against an implementation that listed only the *recorded* entries — because the fake
   ledger seeded all four features, so there were no unrecorded ones. The mutation that should have
   killed it survived. The fake now starts empty, like a real fresh install, and the mutation fails.
   That is the **fourth** gate of this shape this project has caught, after the drift test that
   never ran, the unreachable horizon filter, and the two lint tests held up to date by Gradle.
2. **AC2 was already satisfied by code that predates this issue**, which is worth saying rather
   than quietly claiming credit: revocation is enforced where each data path lives, and each has a
   test naming the behaviour (ADR-0059 tabulates them). The dashboard adds no enforcement and
   deliberately re-derives none.
3. **Device evidence is now UI dumps rather than screenshots** — a direct consequence of issue
   11.2, which made every screenshot of this app come back black.

## 2 · Flow changed this session

One new path — `FLOW.md` §2.22:

```
SettingsScreen → "Manage what the app may use" → ConsentsScreen
└─ ConsentsViewModel
   ├─ ConsentStore.observeAll()                 absent features as NOT_GRANTED, never missing rows
   ├─ Clock.toProfileDate(...)                  the user's day, not UTC's
   └─ onEvent → ConsentStore.revoke / grant     the row changes only when the store agrees
```

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `feature/settings/ConsentsUiState.kt` (new) | the rows, both dates, and the two events |
| `feature/settings/ConsentsViewModel.kt` (new) | reads the whole ledger, formats in the profile zone, writes straight through |
| `feature/settings/ConsentsScreen.kt` (new) | one card per consent: purpose, consequence, history, one tap |
| `feature/settings/SettingsScreen.kt` | the link to the dashboard; `label()` shared rather than private |
| `feature/settings/src/main/res/values{,-hi,-kn,-ta}/strings.xml` | 20 new strings × 4 languages — 10.8's coverage gate made that non-optional |
| `app/navigation/{CfoRoute,CfoNavHost}.kt` | the `Consents` destination |
| `feature/settings/src/test/**/{ConsentsViewModelTest,ConsentsScreenTest}.kt` (new) | 15 tests, 5 mutations |
| `feature/settings/build.gradle.kts` | the release-variant exclusion the rendered test needs |
| `docs/adr/0059-…`, `DECISIONS.md`, `FLOW.md` §2.22, `CHANGELOG.md`, `docs/memory.md` | the records |

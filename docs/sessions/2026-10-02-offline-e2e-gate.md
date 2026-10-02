<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 12.4 — the offline E2E gate, the archive round trip on real SQLCipher, and a check
        that was built, measured and deleted.
  Result: a reader can see what the offline gate proves, what it does not, and why a
          stronger-looking check was removed rather than shipped.
  Changelog: 2026-10-02 — Created.
-->

# 2026-10-02 — The offline promise is now actually tested (issue 12.4, ADR-0067)

**Branch:** `feature/12-4-instrumented-e2e-smoke-airplane-mode` off `dev` (`4d37ebc`)
**Versions:**
- **VERSION** 0.12.3 → **0.12.4**
- **versionCode** 61 → 62
- **Schema** 29 → **29 (unchanged)**

---

## 1 · Decisions this session

The full argument for each is in [ADR-0067](../adr/0067-airplane-mode-is-turned-on-by-a-test-and-a-check-that-cannot-fail-was-removed.md).

- **A test turns the radio off.** Until this issue **nothing in the repository had ever turned
  airplane mode on** — P-04 lived in three source comments (`MarketSignalRepository`, `SmsRepository`,
  `WidgetRefreshWorker`) and in trackers where a human toggled a setting and remembered to.
- **Through `executeShellCommand`**, which runs as the shell user and so holds
  `WRITE_SECURE_SETTINGS`. Granting that to the app would ship a permission to every user for a
  test's benefit.
- **Always restored in `@After`, including on failure.** A test that leaves a device offline makes
  every suite after it fail for an unrelated reason.
- **The archive round trip runs on real SQLCipher**, and deletes the database *and its wrapped key*
  before importing — re-opening generates a new key, so the import cannot be passing on data the old
  database retained. A second test asserts the failure that matters more: an incompatible archive is
  **refused and changes nothing**, because a wipe-then-fail loses everything the user had.
- **The fixture amount is ₹1,23,456.79**, not a round number, so a paise-vs-rupee slip lands somewhere
  visibly wrong rather than back on the same tidy figure (MNY-001).
- **`offlineSmoke` puts `:app:connectedDebugAndroidTest` in CI for the first time.** The Definition of
  Done named it; no job ran it. `restoreDrill` stays named separately because DRL-001 makes the backup
  drill a release gate in its own right.

**What this found.**

1. **The UI flow alone proves nothing about offline.** A mutation removing the toggle *and* the
   assertions left the test passing with the radio on, because the demo path never touches the
   network. The gate's strength is the toggle plus the two `isAirplaneModeOn()` assertions — verified
   by making the toggle a no-op and watching the test fail. The test's own documentation now says so,
   because deleting those two lines would silently turn it back into an ordinary smoke test.
2. **A check was built, measured, and deleted.** To strengthen the above I added an assertion that an
   outbound socket genuinely failed — reading `airplane_mode_on` only proves a setting was written.
   Then I measured it: with airplane mode **off**, the CI emulator still reports
   `ping 8.8.8.8 → Network is unreachable`, while the host reaches it fine. **The check can never fail
   on that image.** Removed rather than shipped: a check that always passes is worse than none,
   because it looks stronger and invites exactly the false confidence this repository has spent six
   issues removing. Recorded in the ADR so the next person to try it finds out.
3. **A survey claim corrected.** `CfoSmokeTest` appeared to mention airplane/export/forecast 13 times;
   they were all `import` lines. It has two tests and covers none of this issue's criteria.
4. **One emulator death**, when Gradle held ~10 GB and the OS killed the VM. Not a code failure —
   stopped the daemon, rebooted with a smaller heap, re-ran. Recorded rather than hidden.

## 2 · Flow changed this session

`FLOW.md` §2.30:

```
./gradlew offlineSmoke                   release path only; needs an emulator
├─ :app:connectedDebugAndroidTest        ← ran in NO CI job before 12.4
│  └─ OfflineEndToEndTest
│     ├─ @Before airplane ON   (shell; the app never gets WRITE_SECURE_SETTINGS)
│     ├─ assert it is off, launch → demo → ₹ figures → transactions, assert still off
│     └─ @After  RESTORED, even on failure
└─ ArchiveRoundTripDeviceTest
   ├─ seed → export → delete the DB AND its key → reopen → import → every row, to the paise
   └─ an incompatible archive ⇒ REFUSED, existing data untouched
```

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `app/src/androidTest/.../OfflineEndToEndTest.kt` (new) | the core flow with the radio genuinely off, and restored afterwards |
| `data/repository/src/androidTest/.../ArchiveRoundTripDeviceTest.kt` (new) | the archive round trip and its refusal path, on real SQLCipher |
| `build.gradle.kts` | the `offlineSmoke` aggregate task |
| `.github/workflows/ci.yml` | `offlineSmoke` joins the emulator job; the job renamed to say what it now covers |
| `docs/adr/0067-…`, `DECISIONS.md`, `FLOW.md` §2.30, `CHANGELOG.md`, `docs/memory.md`, `VERSION` | the records |

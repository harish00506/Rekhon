<!--
  Why:  CLAUDE.md §5 — any decision or deviation from the SRS needs an ADR.
  What: issue 12.4 — the offline end-to-end gate, the archive round trip on real SQLCipher, and the
        reachability check that was built, measured and then deleted.
  Result: a reader can see what the offline gate proves, what it deliberately does not, and why a
          stronger-looking check was removed.
  Changelog: 2026-10-02 — Created.
-->

# ADR-0067 — Airplane mode is turned on by a test, and a check that could not fail was removed

**Status:** Accepted · **Date:** 2026-10-02 · **Issue:** 12.4 · **SRS:** §21.5, P-04

## Context

P-04 says every core feature works in airplane mode. **Until this issue nothing in the repository had
ever turned airplane mode on.** The claim appears in three source comments — `MarketSignalRepository`,
`SmsRepository`, `WidgetRefreshWorker` all say "works in airplane mode" — and in every issue tracker's
verification log, where it was checked by a human toggling a setting by hand and remembering to.

Two more gaps: the plain JSON archive (§5.10, the only copy a user can read) had never been exported
and re-imported through real SQLCipher — `ArchiveRepositoryTest` runs on unencrypted in-memory Room,
and `BackupRestoreDeviceTest` covers the *encrypted backup*, a different file and code path. And
**`:app:connectedDebugAndroidTest` ran in no CI job at all**: the Definition of Done named it, the
`restore-drill` job ran only `:data:repository`'s.

## Decision

### 1 · A test turns the radio off, drives the flow, and always restores it

`OfflineEndToEndTest` toggles airplane mode through `UiAutomation.executeShellCommand`, which runs as
the shell user and so holds `WRITE_SECURE_SETTINGS`. The instrumentation process does not, and
granting it to the app would ship a permission to every user for a test's benefit.

It restores the radio in `@After`, **including on failure**. A test that leaves a device offline makes
every suite after it fail for a reason that has nothing to do with them.

### 2 · What it proves, and what it does not

**Proves:** with the system reporting airplane mode, the app launches, builds its real Hilt graph,
opens SQLCipher, seeds a household, renders real figures and navigates — without crashing, hanging or
erroring. A library added later that blocks on a network call during start-up, or a repository that
starts returning `Err(Network)` where it used to serve cache, fails here. Nothing else in the
repository catches that.

**Does not prove** the app behaves well on a network that is reachable but slow or failing. That is a
different scenario needing a proxy, and claiming it would be overstating the gate.

### 3 · A reachability check was built, measured, and deleted

A mutation exposed something uncomfortable: stripping the toggle and the assertions left the UI flow
**passing with the radio on**, because the demo path never touches the network. "The flow worked" says
nothing about P-04 by itself.

The obvious strengthening was to assert that an outbound socket genuinely fails, on the theory that
reading `airplane_mode_on` only proves a setting was written. It was implemented — and then measured:
on the CI emulator image **that check can never fail**, because the emulator has no route to the
internet even with the radio on (`ping 8.8.8.8` → `Network is unreachable`, while the host reaches it
fine).

So it was **removed**. A check that always passes is worse than no check, because it looks stronger
and invites exactly the false confidence this repository has spent six issues removing. What makes
this a gate is the toggle plus the explicit assertions, and that was verified the only way worth
trusting: by making the toggle a no-op and watching the test fail.

Recording this rather than quietly deleting the code, because the reasoning is the useful part — the
next person to think "assert the socket fails" should find out here that it was tried.

### 4 · The archive round trip runs on real SQLCipher

`ArchiveRoundTripDeviceTest` seeds, exports, **deletes the database and its wrapped key**, reopens
clean, imports, and asserts every row back with amounts exact to the paise. Deleting the key matters:
re-opening generates a new one, so the import cannot be passing on data the old database retained.

A second test asserts the failure that matters more than a clean round trip: an archive from an
incompatible schema is **refused and changes nothing**. A wipe-then-fail would lose everything the
user had.

The fixture amount is ₹1,23,456.79 rather than a round number, so a paise-vs-rupee slip or a
floating-point round trip anywhere in the chain lands somewhere visibly wrong instead of back on the
same tidy figure (MNY-001).

### 5 · `offlineSmoke` puts `:app`'s instrumented tests in CI for the first time

One named task over both modules, added to the emulator job beside `restoreDrill`. The overlap on
`:data:repository` is deliberate: DRL-001 makes the backup drill a release gate in its own right
(issue 8.3), and one task shadowing another's stated purpose is worse than running a module's
instrumented tests twice in a job that already has an emulator up.

## Alternatives rejected

| Alternative | Why not |
|---|---|
| Keep verifying P-04 by hand in each tracker | A promise checked only when somebody remembers is the gate shape this repo has found unenforced five times. |
| Grant the app `WRITE_SECURE_SETTINGS` | A permission shipped to every user for a test's benefit. `executeShellCommand` already runs as shell. |
| Keep the socket-reachability assertion | Measured: it can never fail on the CI emulator, which has no route even with the radio on. A check that always passes is worse than none. |
| Assert only the flow, without the toggle | A mutation showed the flow passes with the radio on — the demo path never touches the network. |
| Skip restoring airplane mode | Every later suite fails for an unrelated reason. |
| Round-trip the archive on in-memory Room | That is `ArchiveRepositoryTest` and it already exists. This issue's criterion is the device path: SQLCipher's driver, the migration chain, `withTransaction` on an encrypted connection. |
| Fold `restoreDrill` into `offlineSmoke` | DRL-001 names the drill as its own release gate; hiding it inside another task weakens a documented obligation. |

## Consequences

- P-04 is checked by the build rather than asserted in a comment, on every promotion to `stage` or `main`.
- The archive round trip and its refusal path are release gates.
- `:app:connectedDebugAndroidTest` runs in CI for the first time.
- The emulator job is slower. Accepted: it only runs on the release path.
- **The offline gate's strength rests on two assertions.** Delete them and it silently becomes an
  ordinary smoke test. That is stated in the test's own documentation, and is the honest limit of what
  this design can enforce.

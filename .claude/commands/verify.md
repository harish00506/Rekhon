---
description: Drive the changed screen/flow on the running app and confirm the behaviour actually works — the second half of the "real gate" (§9). Passing tests and a green build do NOT close an issue; the observed app does.
argument-hint: "[the behaviour to verify, e.g. 'add-txn ≤ 3 taps' or 'forecast updates after new income']"
---

# /verify — drive the change and confirm it works

Exercise the changed behaviour against the app launched by `/run`, and confirm the acceptance
criteria hold on a real device. Behaviour to verify: $ARGUMENTS.

Report each check as ✅ / ⚠️ / ❌ with the evidence (what you did, what you observed, screenshot
or logcat line), then a one-line **WORKS / DOES NOT WORK** verdict.

1. **Restate the acceptance criteria** for this change (from the issue file) as concrete,
   observable steps.
2. **Drive the flow** on the device (`adb shell input`, or manual taps described step by step):
   e.g. open the FAB → enter amount → pick category → save, and confirm **add-txn ≤ 3 taps**;
   or add income and confirm the **forecast/Safe-to-Spend** number changes correctly.
3. **Check the numbers came from math (P-03)** and match the engine output; any advisor/insight
   shows its trace (P-02). No unverified figure on screen (guardrail, AI-ARC-004).
4. **Offline leg (P-04).** Toggle airplane mode and confirm the core flow still works
   (`adb shell cmd connectivity airplane-mode enable` / `disable`), degrading network features to
   cached data + a staleness label.
5. **Edge/error path.** Try one invalid/empty input and confirm it fails safe (no crash, clear
   error).
6. **Instrumented E2E for core flows.** Where the change is core, run the smoke on the device:
   `!./gradlew connectedDebugAndroidTest`
   (onboard → add data → dashboard/forecast → export/import round-trip → airplane-mode pass).

Notes:
- Only `SKIP — <reason>` a step when no device is genuinely available; never skip for convenience.
- Log every command + observation in the tracker **Verification Log** (UTC+5:30) with
  `OK` / `SKIP — reason` / `FAIL — reason`. This log is what lets the issue be marked Done.

---
description: Build, install, and launch AI Personal CFO on an emulator or connected device so the change can be exercised by hand. The first half of the "real gate" (§9) — a green build is not enough; the app must run.
argument-hint: "[optional: screen/flow to open, e.g. 'add transaction' or a deep-link route]"
---

# /run — build, install, launch

Get the app running on a device so the change can be observed. Target of this run:
$ARGUMENTS (if empty, launch the app to its start destination).

Work through this and report each step with its result; stop and surface the first failure.

1. **Find a device.** `!adb devices`
   - If none is attached, start an emulator: `!emulator -list-avds` then
     `emulator -avd <name>` (background it). If no AVD exists, say so and ask the user to create
     one — do not fabricate a run.
2. **Build + install the debug app.**
   `!./gradlew installDebug`
   (or `assembleDebug` then `adb install -r` if a specific APK is needed).
3. **Launch it.** Start the launcher activity, or deep-link to the screen named in `$ARGUMENTS`
   via `adb shell am start` with the typed route.
4. **Confirm it's up.** Note the process is running and the target screen rendered
   (`adb logcat` for the app tag; no crash/ANR). Capture a screenshot if useful
   (`adb exec-out screencap -p > run.png`).
5. **Hand off to `/verify`** to actually drive the changed behaviour.

Notes:
- Everything must work **offline** — do not rely on the backend being up (P-04). If the change
  touches the thin backend, run it locally too and confirm the app still behaves with it absent.
- If the Gradle build doesn't exist yet (greenfield), say so plainly and stop — don't invent a
  successful run.
- Log the launch command + result in the issue's tracker **Verification Log** (UTC+5:30).

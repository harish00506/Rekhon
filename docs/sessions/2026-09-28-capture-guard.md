<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 11.2 — FLAG_SECURE, always on, and the app-switcher thumbnail.
  Result: a reader can see why there is no allowlist, why the flag moved out of the composition,
          and what the guard deliberately does not cover.
  Changelog: 2026-09-28 — Created.
-->

# 2026-09-28 — A screenshot of this app is blank (issue 11.2, ADR-0058)

**Branch:** `feature/11-2-flag-secure-screen-capture-guard` off `dev` (`0cc0583`)
**Versions:**
- **VERSION** 0.10.8 → **0.10.9**
- **versionCode** 52 → 53
- **Schema** 29 → **29 (unchanged — this issue stores nothing)**

---

## 1 · Decisions this session

The full argument for each is in [ADR-0058](../adr/0058-every-screen-is-capture-guarded-because-every-screen-is-financial.md).

- **`FLAG_SECURE` is always on, and there is no allowlist.** Every screen in this app is an amount,
  a list of what someone bought, a forecast of what they will run out of, or the PIN that protects
  all three. "Which screens are sensitive?" would have to be re-answered for every screen anyone
  adds, by someone who may not be thinking about it, and its failure mode is silent — a screenshot
  that works.
- **Set in `onCreate`, before `setContent`.** A flag applied from inside a composition arrives a
  frame late, and the first frame of this app is the lock screen with a PIN being typed into it.
- **Android 13+ is told explicitly not to take a recents screenshot**, rather than relying on
  `FLAG_SECURE`'s side effect. It is the API the requirement names and it keeps working if the
  side effect ever changes.
- **The privacy blur loses its capture half and keeps its masking half.** They were coupled in 5.3
  so the two could not disagree; with the flag always on there is nothing to coordinate.
- **No setting turns it off.** A switch whose only use is to disable a security control earns its
  place only against a concrete user need, and there is not one yet.
- **Written down because it is easy to assume otherwise:** this does nothing about a camera pointed
  at the screen, and it does not reach the home-screen widget, which the launcher draws. The
  widget's amounts are masked by the blur (5.5) and that remains its only cover.

**What this found.** The guard that shipped in issue 5.3 was **off by default.** It armed
`FLAG_SECURE` only while the privacy blur was on, and the blur is off unless the user turns it on —
so the protection existed only for someone who had already asked for a different one, and a plain
screenshot of the dashboard worked. `PrivacyCaptureGuard` was deleted rather than left in place:
had it stayed, the blur toggle would have *cleared* the flag that `onCreate` sets, which is worse
than either design alone.

## 2 · Flow changed this session

One line, above everything — `FLOW.md` §2.21, and §2.9's blur path lost its capture branch:

```
MainActivity.onCreate
└─ SecureWindow.applyTo(activity)                 before super.onCreate and setContent
   ├─ window.addFlags(FLAG_SECURE)                screenshot · record · cast · share = blank
   └─ API 33+ setRecentsScreenshotEnabled(false)  the switcher takes no thumbnail at all
      ⇣ never cleared
```

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `app/SecureWindow.kt` (new) | the policy: the flag, the recents opt-out, and the reasons for both |
| `app/MainActivity.kt` | applies it in `onCreate`; the conditional guard is gone from `AppContent` |
| `app/PrivacyCaptureGuard.kt` | **deleted** — superseded, and dangerous if left (it cleared the flag) |
| `app/src/test/**/SecureWindowTest.kt` (new) | 4 tests: the flag, idempotence, the API-33 branch, the older-Android branch |
| `app/src/androidTest/**/CaptureGuardDeviceTest.kt` (new) | the flag read back from the real launched window |
| `app/build.gradle.kts` | the release-variant exclusion the ComponentActivity tests need |
| `docs/adr/0058-…`, `DECISIONS.md`, `FLOW.md` §2.21 + §2.9, `CHANGELOG.md`, `docs/memory.md` | the records |

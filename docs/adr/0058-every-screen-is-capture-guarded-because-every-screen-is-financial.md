# ADR-0058 — Every screen is capture-guarded, because every screen is financial

- **Status:** accepted
- **Date:** 2026-09-28
- **Deciders:** Harish G (solo)
- **SRS refs:** §23, FR-PRIV-*, NFR-010; Epic 11's fail-secure rule; issue 11.2. Supersedes the
  capture half of [ADR-0022](0022-privacy-blur-masks-text-and-sets-flag-secure.md) (issue 5.3).

## Context

Issue 5.3 gave the app a privacy blur — a one-tap toggle that masks every amount on screen — and,
because masking text does nothing about a screenshot, it also set `FLAG_SECURE` **while the toggle
was on**. That code said so about itself: *"issue 11.2 still owns the always-on policy and the
recents-thumbnail guard."* This is that issue.

The shape 5.3 shipped has a defect that is only visible when you ask what the default is. The blur
is **off** by default, so the window was capturable by default, and the only thing that armed the
guard was a user deliberately turning on a feature meant for reading over shoulders. A guard that
is off unless asked for is not a guard.

## Decision

**1. `FLAG_SECURE` is always on. There is no allowlist.**
Set once in `MainActivity.onCreate`, never cleared.

The alternative — mark the sensitive screens — is the wrong shape for this app. "Is this screen
sensitive?" would have to be answered again for every screen anyone adds, by someone who may not be
thinking about it, and **the failure mode is silent**: a screenshot that works. There is no screen
here that is not an amount, a list of what someone bought, a forecast of what they will run out of,
or the PIN that protects all three. One forgotten annotation is a leak; one over-broad flag is a
screenshot a user cannot take.

**2. In `onCreate`, before `setContent` — not from inside the composition.**
The first frame of this app is the lock screen, the one with a PIN being typed into it. A flag
applied by a `DisposableEffect` arrives a frame late, and the recents thumbnail of a
just-backgrounded app is exactly the sort of thing that gets captured in that window.

**3. The app-switcher is told explicitly, not just implicitly.**
`FLAG_SECURE` already blanks the thumbnail. On Android 13+ the app also calls
`setRecentsScreenshotEnabled(false)`, which is the API the requirement names and the one that keeps
working if the flag's side effects ever change. Below 33 the flag alone does it — verified on the
emulator: the switcher card is solid black while the screen itself renders normally.

**4. The privacy blur keeps its other half, and loses this one.**
It still masks text for the person standing behind you, which is a different problem from capture
and is the one a user toggles per moment. It no longer touches `FLAG_SECURE`, because there is
nothing left to coordinate: the flag is always on, so the two controls cannot disagree.
`PrivacyCaptureGuard` is deleted rather than left inert — leaving it would have meant the toggle
*clearing* the flag `onCreate` had set, which is worse than either design on its own.

## What this does not do

- **It does not stop a camera pointed at the screen**, and nothing can. The blur is the control for
  that, which is part of why it stays.
- **It does not protect the widget.** A home-screen widget is drawn by the launcher, not by this
  app's window, so `FLAG_SECURE` does not reach it. Issue 5.5 already masks amounts there when the
  blur is on; that remains the widget's only defence and is recorded here so it is not assumed
  otherwise.
- **It cannot be turned off.** No setting is offered, deliberately: an "allow screenshots" switch
  is a security control whose only use is to disable a security control, and a user who wants to
  share a figure has the export path. Revisit only with a concrete user need.

## Consequences

- A screenshot, a screen recording, a cast and a shared call all show black, from the first frame
  after launch — proved on a device, with the screen rendering normally at the same time.
- The app-switcher shows a blank card rather than the dashboard.
- Paparazzi and Robolectric are unaffected: they render compositions, not an Activity window.
- Screenshots of the app can no longer be taken for documentation. The per-locale baselines
  (issue 10.8) come from Paparazzi rather than a device, so nothing in the build depends on it —
  but a developer wanting a screen grab now has to use a rendered test, and that is the trade.

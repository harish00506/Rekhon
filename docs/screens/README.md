# The two documents about this project

| File | What it is |
|------|------------|
| [`../AI_Personal_CFO_screens.pdf`](../AI_Personal_CFO_screens.pdf) | 51 pages — every screen, with the engine and rules behind each figure |
| [`../AI_Personal_CFO_story.pdf`](../AI_Personal_CFO_story.pdf) | 38 pages — the pitch, the method, the build story, one page per stakeholder, and the decisions still open |

`build_pdf.py` composes the first; `build_story_pdf.py` composes the second. Both read `shots/`.

## The screen-by-screen guide

[`../AI_Personal_CFO_screens.pdf`](../AI_Personal_CFO_screens.pdf) is a 51-page walk through every
screen in the app: the screenshot, what the screen is for, and the engine and rule IDs that produced
its numbers. It was written for someone who has not run the app — a reviewer, a new contributor, or
anyone being shown what this thing actually does.

**Why it is a rendered PDF and not Markdown:** the point of the document is the screens themselves.
A reader needs the picture and the explanation side by side on one page, and needs it to survive
being emailed.

## Rebuilding it

`build_pdf.py` composes the pages with ImageMagick (`magick`) and the DejaVu fonts. It reads
`shots/<name>.png` — the device screenshots, which are **not** committed: 50 phone PNGs are ~11 MB
of binary, and they go stale the moment a screen changes. Capture a fresh set instead.

```bash
# 1. boot the emulator and install the app
~/Android/Sdk/emulator/emulator -avd CfoTest -no-snapshot-save -no-boot-anim &
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. drive the app, capturing each screen you want
adb exec-out screencap -p > shots/02-dashboard-top.png

# 3. rebuild
python3 build_pdf.py
```

Navigating by hand is slow; `adb shell uiautomator dump /sdcard/u.xml` prints every visible node with
its bounds, which is enough to tap a control by its label from a script.

The screens are captured in **airplane mode**, on the app's own demo profile, so the document shows
what a reader can reproduce without entering any real data. The two simulator pages are the
exception — they need a loan on file — and their captions say so.

## Editing the words

Every page's title, kicker, body and footnote is one entry in the `SECTIONS` list in
`build_pdf.py`. Adding a screen means adding a screenshot and one entry; the page numbering and the
contents page are the only things that then need checking by eye.

## The project document

`build_story_pdf.py` builds the second PDF the same way, from the `PAGES_SPEC` list: each entry is
either a full-width prose page or a screenshot-plus-text page, built from blocks tagged `h`
(sub-heading), `p` (paragraph), `q` (pulled-out statement) or `c` (monospaced).

**Its numbers go stale.** The "Where it stands today" page states the issue count, the module and
line counts, the test count, and the rulebook version as they were on the day it was written. Before
reshipping it, re-read them from the repository — the commands are one-liners over `docs/issues/`,
`find … -name "*.kt"`, the last full gate's test total, and `ai/rules/rules-kb.json`. A stale figure
in a document that argues for honesty about figures is the one mistake it cannot afford.

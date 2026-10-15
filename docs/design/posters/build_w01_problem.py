"""
Why:        a first post has one thing to say that nobody else can say: here is what I could not do
            with my own money, and here is the thing I built so I could. This sheet is that, and
            nothing else — no screenshot to look at instead of reading, no series furniture.
What:       writes the problem-and-feature sheet (1200x1280) in both themes — three problems in
            plain words, each answered by the feature that exists because of it.
Result:     w01-problem.html and w01-problem-dark.html, ready for Chrome to screenshot.
Changelog:  2026-10-12 — Created, replacing the feature-list poster.
            2026-10-12 — Parameterised by theme; the light ground is the requested #fefefe.
            2026-10-12 — Type floor of 24px for every content line (header and footer labels capped
            at 18px); the series counter, the "India-first" tag and the footer sentence removed.
            2026-10-12 — Device screenshot dropped; the sheet is one column, and the freed width
            went into the type.
            2026-10-12 — Headline up to 78px; the problem now outranks the feature name instead of
            matching it (42px against 26px); numerals became circular markers; the footer moved to
            the sheet edge and faded back.
            2026-10-12 — Headline to 100px over three lines; the gap between pairs cut from 170px to
            32px; the sheet back to square, since the tighter rows no longer need 1500px.
            2026-10-12 — Adopted the fixed type scale: 48 title / 32 heading / 26 subheading /
            22 text. The sheet is 1200x1000, because the smaller scale left a square one a third
            empty.
            2026-10-12 — The golden ratio moved off the spacing and onto the type: the scale is now
            22 text / 36 subheading / 58 heading / 94 title, each step 1.618x the one below it,
            read from the bottom up. Spacing went back to plain values; the sheet is 1200x1280 to
            hold the larger scale.
            2026-10-12 — Heading down to 36, subheading to 24; markers halved to 38px; 32px above
            and below every row; side padding to 56px; "Three things" set in the accent; the URL
            left-aligned. The problems got their full sentences back, since 36px no longer
            constrains them.
            2026-10-12 — Two faces only: Bricolage for the title, Instrument Sans for everything
            else (the monospace is gone). "Building in public" removed, and the brand subtitle is
            now "Personal finance app".
            2026-10-13 — Heading to 32; subtitle to "Personal financial adviser"; a soft two-blob
            gradient wash in the accent behind the sheet, top-right and bottom-left.
            2026-10-13 — Wash roughly doubled and spread wider; side padding to 72px; the dark
            sheet's hairlines dropped from #233230 to #182221, which were reading louder than the
            light sheet's once the wash came up behind them.
            2026-10-13 — Wash pulled in tight to the corners at the same strength, and the numeral
            rings thickened from 1.5px to 2.5px, with the digits set bold to carry the same weight
            as the ring around them.
Input:      none. All content is literal below; tokens from docs/design/design-language-v2.html.
Output:     writes both HTML files, prints each path.

Note: a one-off asset generator, not app code — kept flat and small on purpose.
"""

FONTS = "file:///home/harish/.claude/skills/canvas-design/canvas-fonts"

# The light ground is the #fefefe that was asked for; everything else is the matching token set
# from docs/design/design-language-v2.html. The dark sheet is the same drawing on the app's own
# dark canvas, so the pair reads as one hand.
THEMES = {
    "w01-problem": dict(
        canvas="#fefefe", ink="#0f1f1e", ink_2="#4e6160", ink_3="#9aabaa",
        line="#e2e8e7", accent="#00696e",
        glow="rgba(0, 105, 110, 0.26)", glow2="rgba(0, 105, 110, 0.15)",
    ),
    "w01-problem-dark": dict(
        canvas="#0b1211", ink="#e2ecea", ink_2="#9bb0ae", ink_3="#5d7271",
        line="#182221", accent="#4fd8de",
        glow="rgba(79, 216, 222, 0.26)", glow2="rgba(79, 216, 222, 0.14)",
    ),
}

# Problem in the first person, then the feature that exists because of it. Each problem is one the
# concept brief already states (docs/concept-brief.md §Problem), so nothing here is invented for
# the poster.
PAIRS = [
    ("I knew what I spent, not what was left.",
     "Safe to Spend",
     "What the month has not already committed, divided by the days left."),
    ("Budgets told me on the 31st. Too late.",
     "Pace, not a month-end report",
     "Mid-month it shows where this month lands at today's rate."),
    ("Every app wanted my bank login first.",
     "Nothing leaves the phone",
     "No account, no server, no sign-in. Receipts are read on the device."),
]


def pairs_html():
    """
    Why:        the argument of the sheet is the pairing, so problem and answer are built as one
                block and can never drift apart.
    What:       the three rows — a numeral in the left gutter, the problem and its answer beside it,
                hairline separated.
    Result:     an HTML fragment for the body of the sheet.
    Changelog:  2026-10-12 — Created.
                2026-10-12 — Became a two-column grid when the screenshot was dropped.
    Input:      none.
    Output:     str of HTML.
    """
    rows = []
    for i, (problem, name, how) in enumerate(PAIRS, start=1):
        rows.append(
            f'<div class="pair">'
            f'<p class="num mono"><span>{i}</span></p>'
            f'<div class="body">'
            f'<p class="prob">{problem}</p>'
            f'<div class="ans"><span class="tick"></span>'
            f'<div><p class="fname">{name}</p><p class="how">{how}</p></div></div>'
            f'</div></div>'
        )
    return "\n      ".join(rows)


def sheet(theme):
    """
    Why:        the two sheets must be one drawing in two grounds, not two designs, so the layout is
                written once and only the tokens change.
    What:       the whole 1200x1500 document — header, headline, the three pairs, footer.
    Result:     a complete standalone HTML string.
    Changelog:  2026-10-12 — Created when the dark theme was added.
                2026-10-12 — Single column; screenshot and its caption removed.
    Input:      theme (dict from THEMES).
    Output:     str of HTML.
    """
    t = theme
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Rekhon</title>
<style>
  @font-face {{ font-family: "Bricolage"; src: url("{FONTS}/BricolageGrotesque-Bold.ttf"); font-weight: 700; }}
  @font-face {{ font-family: "Instrument"; src: url("{FONTS}/InstrumentSans-Regular.ttf"); font-weight: 400; }}
  @font-face {{ font-family: "Instrument"; src: url("{FONTS}/InstrumentSans-Bold.ttf"); font-weight: 700; }}
  * {{ margin: 0; padding: 0; box-sizing: border-box; }}
  html, body {{ width: 1200px; height: 1280px; background: {t["canvas"]}; color: {t["ink"]};
                font-family: "Instrument", system-ui, sans-serif; }}
  .sheet {{ position: relative; width: 1200px; height: 1280px; overflow: hidden; padding: 0 72px;
            background:
              radial-gradient(760px 560px at 96% -2%, {t["glow"]}, transparent 64%),
              radial-gradient(620px 480px at -4% 104%, {t["glow2"]}, transparent 66%),
              {t["canvas"]}; }}
  .mono {{ letter-spacing: 0.13em; text-transform: uppercase; }}

  header {{ display: flex; align-items: baseline; padding-top: 68px; }}
  .brand {{ font-weight: 700; font-size: 25px; letter-spacing: -0.015em; }}
  .brand span {{ font-weight: 400; font-size: 16px; letter-spacing: 0.13em;
                 text-transform: uppercase; color: {t["ink_3"]}; margin-left: 16px; }}
  .rule {{ height: 1px; background: {t["line"]}; margin-top: 18px; }}

  h1 em {{ font-style: normal; color: {t["accent"]}; }}
  h1 {{ font-family: "Bricolage", sans-serif; font-weight: 700; font-size: 94px; line-height: 1.055;
        letter-spacing: -0.036em; margin-top: 58px; }}

  .pairs {{ margin-top: 56px; }}
  .pair {{ display: grid; grid-template-columns: 72px 1fr; padding: 32px 0 32px;
           border-top: 1px solid {t["line"]}; }}
  .pair:first-child {{ border-top: none; }}
  .num {{ padding-top: 6px; }}
  .num span {{ display: flex; align-items: center; justify-content: center; width: 38px;
               height: 38px; border-radius: 50%; border: 2.5px solid {t["accent"]};
               color: {t["accent"]}; font-size: 18px; font-weight: 700; letter-spacing: 0; }}
  .prob {{ font-size: 32px; line-height: 1.26; letter-spacing: -0.018em; max-width: 1000px; }}
  .ans {{ display: flex; gap: 18px; margin-top: 20px; }}
  .tick {{ flex: 0 0 auto; width: 3px; height: 62px; background: {t["accent"]}; border-radius: 2px;
           margin-top: 3px; opacity: 0.45; }}
  .fname {{ font-weight: 700; font-size: 24px; letter-spacing: -0.008em;
            color: {t["accent"]}; }}
  .how {{ font-size: 22px; line-height: 1.5; color: {t["ink_2"]}; margin-top: 8px; max-width: 820px; }}

  footer {{ position: absolute; left: 72px; right: 72px; bottom: 46px;
            display: flex; justify-content: flex-start; }}
  footer .r {{ font-size: 16px; color: {t["ink_3"]}; opacity: 0.75; }}
</style></head>
<body><div class="sheet">
  <header>
    <div class="brand">Rekhon<span>Personal financial adviser</span></div>
  </header>
  <div class="rule"></div>

  <h1><em>Three things</em> I<br>could not answer<br>about my own money.</h1>

  <div class="pairs">
      {pairs_html()}
  </div>

  <footer>
    <div class="mono r">github.com/harish00506/Rekhon</div>
  </footer>
</div></body></html>
"""


for name, theme in THEMES.items():
    with open(f"{name}.html", "w", encoding="utf-8") as fh:
        fh.write(sheet(theme))
    print(f"{name}.html")

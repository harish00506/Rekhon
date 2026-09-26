#!/usr/bin/env python3
"""
Builds the "AI Personal CFO — every screen, explained" PDF.

Why:  the app is 20-odd screens deep and every figure on them is produced by a named engine and a
      versioned rule. A reader who has not run it needs to see the screen and be told, beside it,
      what it is for, where its numbers come from and which golden rule shapes it.
What: renders one A4 page per screen — title, device screenshot, explanation — plus a cover, a
      contents page and a closing map of screens to engines, then concatenates them into a PDF.
Result: `AI_Personal_CFO_screens.pdf` in the output directory.
Changelog: 2026-09-26 — Created.

Input:  the screenshots in `shots/` (1080x2340 PNGs captured from the CfoTest emulator — see
        README.md beside this file for how they are taken; they are deliberately not committed,
        because 50 device PNGs are 11 MB of binary that would live in the repo for ever).
Output: one PDF; exit code 0 on success.

Run it from a directory holding `shots/`, with ImageMagick and the DejaVu fonts installed:
    python3 build_pdf.py
"""

import os
import subprocess
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SHOTS = os.path.join(HERE, "shots")
PAGES = os.path.join(HERE, "pages")
OUT = os.path.join(HERE, "AI_Personal_CFO_screens.pdf")

W, H = 1240, 1754                      # A4 at 150 dpi
SANS = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
INK, MUTED, TEAL, PAPER = "#15201f", "#5b6b69", "#0d4d4b", "#ffffff"

SHOT_W = 560                           # screenshot column
TEXT_X, TEXT_W = 672, 512


def run(cmd):
    """Runs one ImageMagick command and fails loudly. Input: argv list. Output: none."""
    subprocess.run(cmd, check=True)


def text_block(body, width, size, font=SANS, fill=INK, leading=1.35):
    """
    Renders wrapped text to a PNG and returns its path.
    Why:    `caption:` wraps at a fixed width, which is what a two-column page needs. The text is
            passed inline rather than as `caption:@file`, because ImageMagick's default security
            policy forbids indirect file reads.
    Input:  [body] text; [width] px; [size] pointsize; [font]; [fill]; [leading].
    Output: a temp file path the caller deletes.
    """
    out = tempfile.mkstemp(suffix=".png")[1]
    run(["magick", "-background", "none", "-fill", fill, "-font", font,
         "-pointsize", str(size), "-interline-spacing", str(int(size * (leading - 1))),
         "-size", f"{width}x", f"caption:{body}", out])
    return out


def page(number, title, kicker, shot, body, footnote=None):
    """
    Composes one screen page: header band, screenshot, explanation, and a footnote under the text.
    Result: the page's PNG path. Input: page [number]; [title]; [kicker] (one line under the title);
            [shot] file name in shots/ or None; [body] explanation; [footnote] small print.
    Output: a path under pages/.
    """
    out = os.path.join(PAGES, f"p{number:03d}.png")
    run(["magick", "-size", f"{W}x{H}", f"xc:{PAPER}",
         "-fill", TEAL, "-draw", f"rectangle 0,0 {W},128",
         "-font", BOLD, "-pointsize", "40", "-fill", "white", "-annotate", "+56+62", title,
         "-font", SANS, "-pointsize", "21", "-fill", "#bfe0dd", "-annotate", "+56+100", kicker,
         "-fill", MUTED, "-font", SANS, "-pointsize", "17",
         "-annotate", f"+56+{H - 40}", "AI Personal CFO — every screen, explained",
         "-annotate", f"+{W - 120}+{H - 40}", str(number),
         out])
    if shot:
        run(["magick", out,
             "(", os.path.join(SHOTS, shot), "-resize", f"{SHOT_W}x",
             "-bordercolor", "#d5dedd", "-border", "2", ")",
             "-geometry", "+56+180", "-composite", out])
    text_x = TEXT_X if shot else 56
    text_w = TEXT_W if shot else W - 112
    body_png = text_block(body, text_w, 23)
    body_h = int(subprocess.run(["magick", "identify", "-format", "%h", body_png],
                                capture_output=True, text=True, check=True).stdout)
    run(["magick", out, body_png, "-geometry", f"+{text_x}+186", "-composite", out])
    os.unlink(body_png)
    if footnote:
        top = min(186 + body_h + 44, H - 200)
        note = text_block(footnote, text_w, 19, fill=MUTED)
        run(["magick", out,
             "-fill", "#c9d6d4", "-draw",
             f"rectangle {text_x},{top - 24} {text_x + 120},{top - 22}",
             note, "-geometry", f"+{text_x}+{top}", "-composite", out])
        os.unlink(note)
    return out


def cover(number):
    """The title page. Result: its path. Input: page [number]. Output: a path under pages/."""
    out = os.path.join(PAGES, f"p{number:03d}.png")
    run(["magick", "-size", f"{W}x{H}", f"xc:{TEAL}",
         "-font", BOLD, "-pointsize", "76", "-fill", "white",
         "-annotate", "+90+560", "AI Personal CFO",
         "-font", SANS, "-pointsize", "40", "-fill", "#9fd3cf",
         "-annotate", "+90+640", "Every screen, explained",
         "-fill", "#7fbcb8", "-draw", "rectangle 90,700 420,704",
         out])
    blurb = (
        "An Android-first personal-finance app for India that behaves like a full-time CFO: it "
        "classifies what you spend, forecasts what is coming, ranks where the next rupee should go, "
        "and explains every figure it shows.\n\n"
        "Everything runs on the device. Nothing about your money leaves the phone unless you switch "
        "a specific feature on, and the whole app works in airplane mode.\n\n"
        "Captured on a Pixel-class emulator (API 36) in airplane mode, on the app's own sample "
        "household unless a page says otherwise. Version 0.10.2."
    )
    blurb_png = text_block(blurb, 900, 26, fill="#d8ecea")
    run(["magick", out, blurb_png, "-geometry", "+90+780", "-composite", out])
    os.unlink(blurb_png)
    return out


SECTIONS = [
    # (screenshot, title, kicker, body, footnote)
    (None, "How to read this", "What each page shows, and the rules the app never bends",
     """Each page holds one screen, a short description of what it is for, and the names of the engines and rules that produced its numbers.

THE EIGHT RULES THE APP IS BUILT ON

P-01  Privacy first.  No financial data leaves the device without explicit, revocable, per-feature consent. The default is fully offline.

P-02  Show the work.  Every recommendation shows its inputs, the rule that fired and a plain-language reason. You will see rule names such as RULE-STS or FOO.KILL_FIRE_DEBT printed under the figures on almost every screen — that is deliberate.

P-03  Numbers from math, words from AI.  Deterministic engines compute every amount, score and forecast. A language model may only put them into sentences; it is never allowed to produce a figure.

P-04  Offline-first.  Every core feature works in airplane mode. Online features degrade to cached data with a staleness label.

P-07  Advice, never orders.  The app recommends and simulates. It never moves money.

P-08  Deterministic and testable.  The same inputs always give the same outputs.

HOW THE NUMBERS ARE HELD

Money is stored as whole paise in 64-bit integers — never as a floating-point number — and rates are integer basis points. Amounts are grouped the Indian way (Rs 1,23,456.78). Timestamps are UTC; anything about a day, a month or a due date is resolved in your own time zone.

WHERE THE THRESHOLDS LIVE

Every financial threshold — the 40% EMI ceiling, the emergency-fund multiplier, the concentration limits, the cooling-off period — is a versioned data row, not a number typed into code. Each row is cited by name in the evidence line under the figure it shaped.""",
     None),

    (None, "Contents", "The app in the order you meet it",
     """SETTING UP                 pages 4 – 12
     The privacy pledge, the consent question, who you are, the app lock and its PIN, a head start, your first account, the dashboard that results, and the lock screen you meet from then on.

THE DASHBOARD              pages 13 – 17
     Safe to spend, net worth, where your next rupee should go, the 90-day forecast, your financial health score, this month's cash flow, and recent activity.

YOUR MONEY, RECORDED       pages 18 – 25
     The transaction ledger and the repeats it spots for you, categories, accounts, investments, holdings, concentration, and the account editor.

PLANS AND TARGETS          pages 26 – 34
     Budgets suggested from what you actually spend, goals and what each one takes a month, one goal's evidence, the emergency fund and its working, and the order of operations that ranks the lot.

DECISIONS                  pages 35 – 43
     The Purchase Advisor and its seven gates, the buy list that interviews you about a wish, and the two what-if simulators.

CONTROL AND CAPTURE        pages 44 – 50
     Settings and consents, encrypted backup and restore, adding a transaction, scanning a receipt, and bank-message drafts.

BEHIND THE SCREENS         page 51
     Which engine produces which figure.""",
     None),

    ("41-onboarding-1-privacy.png", "Setup 1 of 6 — Your money, on your phone",
     "The privacy pledge comes before anything is asked of you",
     """The first thing the app does is tell you where your data will live, before it asks for a single figure. Everything is stored encrypted on the device; nothing about your money is sent anywhere unless you turn a feature on yourself, and you can turn it back off at any time.

The second card offers the sample household. Demo mode runs the whole app on a seeded, deterministic sample profile that is kept completely separate from yours, is labelled on every screen while you browse, and is hard-deleted the moment you leave it.

Most of the screenshots in this document were taken in that demo, which is why the orange "Sample data — none of this is yours" banner sits above them.""",
     "P-01 Privacy first. FR-ONB-004 (demo mode)."),

    ("42-onboarding-2-sms.png", "Setup 2 of 6 — Read bank messages?",
     "The first of exactly two consent questions",
     """If you turn this on, the app reads transaction alerts from banks and UPI apps and turns them into drafts you approve. It reads the amount, the merchant, the date and the account, and only from messages sent by banks and payment apps.

The reading happens on the phone. No message is uploaded, and the app keeps only what it worked out — not the message.

It is off by default, and the screen says so plainly. Consent in this app is per-feature and revocable; there is no single "accept everything" step anywhere in setup.""",
     "P-01. Consent is asked once per feature and can be withdrawn."),

    ("43-onboarding-3-about-you.png", "Setup 3 of 6 — About you",
     "A name, a currency and — importantly — a time zone",
     """Only three things are asked, and all three stay on the device.

The time zone is not cosmetic. Every calendar decision in the app — when your day rolls over, when a month ends, when a card payment is due — is resolved in the time zone you pick here, through an injected clock rather than the phone's wall clock. That is what keeps a late-night transaction in the right month and a due-date warning honest when you travel.

The currency is INR, and every amount in the app is formatted the Indian way, with lakh and crore grouping.""",
     "TIM-001: all calendar logic uses the profile time zone."),

    ("44-onboarding-4-lock.png", "Setup 4 of 6 — Lock the app",
     "A PIN, or your fingerprint, before your finances open",
     """The lock is offered during setup rather than buried in a settings screen, because a finance app that is unlocked by default on a shared phone is a problem you only discover once.

Your PIN never leaves the device and is never stored as you typed it — it is bound to the phone's hardware keystore. If the phone has a fingerprint or face sensor, that works too, with the PIN as the fallback.

Repeated wrong attempts trigger an escalating lockout, and every security-relevant event is written to an audit log inside the encrypted database.""",
     "SEC-002: class-3 biometric with PIN fallback."),

    ("45-onboarding-4-pin.png", "Setup 4 of 6 — Choosing the PIN",
     "Four to six digits, and no recovery",
     """Turning the switch on reveals the two fields. The warning is exact: there is no way to recover this PIN. The app cannot reset it, because it never holds anything that could.

That is the honest consequence of a database encrypted with a key the device holds and the user unlocks — the same trade-off that appears later on the backup screen, where a forgotten passphrase means a backup nobody can ever open.""",
     "The unlock screen this produces is on page 11."),

    ("46-onboarding-5-head-start.png", "Setup 5 of 6 — A head start",
     "Three rough figures, and the app can already be useful",
     """Income, rent or EMI, and what you typically save. Estimates are fine and the whole step can be skipped.

From these three numbers the app seeds a budget split across needs, wants and savings, sets a first emergency-fund target, and can compute a safe-to-spend figure on day one — before a single transaction has been recorded.

Everything seeded here is a suggestion you can change later, and each one is marked as coming from setup rather than from your actual spending.""",
     "FR-ONB-002: the quick-setup seeds."),

    ("47-onboarding-6-first-account.png", "Setup 6 of 6 — Your first account",
     "One account now, the rest whenever",
     """Add the account you use most, or leave the name blank and skip.

For a credit card or a loan, what you owe is entered as a negative opening balance — the same convention the net-worth engine uses to subtract liabilities.

Note what is being asked for: what the account held when you started tracking it. Balances in this app are never stored as a figure that can drift; the current balance is always derived by replaying your transactions over that opening figure.""",
     "DB-001 / ADR-0007: balances are derived, not stored."),

    ("48-dashboard-fresh.png", "The first dashboard", "What a brand-new profile looks like",
     """Straight after setup, with one account and three estimates, the app already has something to say: what is safe to spend this month, what you are worth, and what the next rupee should do — here, completing the emergency fund.

The 90-day forecast is honest about what it cannot yet know: "No spending history yet, so everyday spending is not predicted. Income counts only repeats you have confirmed."

That sentence is the pattern for the whole app. A figure it cannot support is not guessed at; it is named as missing, with what would fill the gap.""",
     "Compare with the sample household's dashboard on page 12."),

    ("49-app-lock.png", "The lock screen", "Where every session starts once a PIN is set",
     """The database is encrypted at rest and the key is released only after this screen is satisfied. Until then the app holds nothing readable — which is also why the home-screen widget caches its own small summary rather than reading the database while locked.

Wrong attempts escalate: a delay first, then a longer lockout. Each attempt is recorded in the audit log.""",
     "SEC-001 / SEC-002."),

    ("02-dashboard-top.png", "Dashboard — Safe to spend", "The headline figure, with its arithmetic shown",
     """Safe to spend is not "what is in your account". It is income, less what is already spent, less what is committed, less a buffer held back, less what you planned to save — and every one of those lines is printed under the figure rather than folded into it.

Under it, net worth: what you own less what you owe, derived from your accounts rather than typed in.

Then "Your next best rupee" — the single highest-value thing to do with spare money this month, taken from the order-of-operations waterfall on page 31. Here it is paying off a high-interest card, and the card's missing interest rate is called out rather than assumed away.

Every card carries the rule that produced it: RULE-STS for safe-to-spend, FOO.KILL_FIRE_DEBT for the recommendation.""",
     "Engines: AI-STS (safe to spend), AI-NW (net worth), AI-FOO (order of operations)."),

    ("03-dashboard-forecast.png", "Dashboard — This month", "Where the money actually went",
     """The bar splits the month into needs, wants and savings against the plan, and the line below states what is fixed, what is semi-fixed and what is genuinely flexible — the part you can still change.

"Every month, typically" is computed from your own history rather than from the budget you set, so the two can disagree, and the difference is the point.

The classification behind it is a learned, on-device model with a rule layer over it: it decides whether a transaction is fixed, semi-fixed or flexible, and it is retrained from your corrections, not from anyone else's data.""",
     "Engine: AI-CLS (classification), AI-BUD (budgets)."),

    ("04-dashboard-insights.png", "Dashboard — The next 90 days",
     "A forecast that names its lowest point",
     """The forecast projects your balance day by day for 90 days and reports the worst day ahead, with a likely range around it rather than a single confident number.

It counts only income repeats you have confirmed, and it says so when the history is too thin to predict everyday spending.

The green line underneath is the one that matters day to day: whether you stay above the buffer you set, every day, between now and then.

Below it the financial health score — 988 of 1000 here — which is the subject of the next page.""",
     "Engines: AI-FCT (forecast), AI-SEAS (seasonality), AI-FHS (health score)."),

    ("05-dashboard-actions.png", "Dashboard — Financial health",
     "One score, five pillars, and every weight visible",
     """The score is built from five pillars — liquidity and emergency cover, debt, spending discipline, what you keep of your income, and protection and growth — each with its points, its weight, and, where the data is thin, a plain statement that it is thin.

"Not enough data yet (its 20.0% weight is shared among the others)" is the app refusing to invent a pillar it cannot measure.

At the bottom, the biggest lever: the single change that would move the score most, quantified.

Under that, "What needs attention" — the insight feed, which ranks what the engines noticed this week and lets you dismiss or defer each item.""",
     "Engines: AI-FHS (score), AI-ORCH (insight orchestrator)."),

    ("06-dashboard-health.png", "Dashboard — Cash flow and recent activity",
     "This month in two numbers, then the ledger",
     """Received against spent for the month, then the last few transactions with their merchant, date and amount.

Below the activity list sit the navigation actions — every other screen in this document is one tap from here: budgets, accounts, transactions, goals, the emergency fund, the Purchase Advisor, the simulators, settings, and export or import.""",
     "The action list is the app's whole surface: nine destinations, flat."),

    ("08-transactions.png", "Transactions — Repeats the app spotted",
     "Confirm it, or say it is not a repeat",
     """Before the ledger, the app shows what it believes are recurring payments it found on its own: the landlord on the 3rd, the electricity board on the 7th, a fund SIP on the 5th — each with the dates it based that on.

Nothing is assumed. Each one is a question with two answers, and only a confirmed repeat is allowed into the 90-day forecast as expected income or expense.

This is the pattern everywhere in the app: the engine proposes with its evidence, the person disposes.""",
     "Engine: AI-REC (recurring detection). A confirmed repeat feeds AI-FCT."),

    ("09-transactions-list.png", "Transactions — The ledger",
     "Everything recorded, newest first, with a running daily total",
     """Each row is one transaction: merchant, date, amount, and the category it was put in. Days are totalled.

Search is full-text across merchant and note. The icon beside it opens filters.

Amounts are held as whole paise end to end — database, engine and screen — so no rounding error can creep in between what you entered and what a forecast reads.""",
     "MNY-001: money is a 64-bit integer count of paise everywhere."),

    ("10-categories.png", "Categories", "The vocabulary the rest of the app reasons in",
     """Each category carries more than a name: it is marked a need or a want, and that single flag is what lets the app split a month into needs, wants and savings, compute a safe-to-spend figure, and tell a budget suggestion from a lifestyle question.

The defaults are seeded at setup and can be edited or added to. Renaming one does not lose the history attached to it.""",
     "Used by AI-BUD, AI-STS and the emergency-fund engine's essentials figure."),

    ("11-accounts.png", "Accounts", "Everything you own and owe, in one list",
     """Bank accounts, cash, credit cards, loans and investments, each showing its type, its institution, what it opened with and what it is worth now.

The current figure is always derived from the opening balance plus every transaction since — never a stored number that could quietly drift from the ledger.

"Reconcile" is for the day the bank disagrees with the app: you enter what the bank says and the app records the difference as an explicit adjustment rather than silently rewriting history.

Closing an account keeps its history; deleting it is a separate, deliberate action.""",
     "DB-001 / ADR-0007. Engine: AI-NW for the totals."),

    ("12-accounts-investments.png", "Accounts — Investments", "What you hold, and what it is worth",
     """An investment account gains two extra actions: its holdings, and the allocation view.

The prompts are the app asking for what it is missing rather than guessing: "Add this card's limit and dates", "Add what this account holds". A figure it cannot derive is requested, never invented.""",
     "P-03: the app does not fabricate an input it was not given."),

    ("13-holdings.png", "Holdings", "What a fund, stock or deposit is actually worth",
     """Each holding records what you bought, when, and at what price; the app computes what it is worth now and what it has returned, in both absolute and annualised terms.

The disclaimer at the foot of the screen is deliberate and permanent: this screen analyses and explains what you already own. It does not recommend securities or funds, and it is not SEBI-registered investment advice.

Empty here, because the sample household holds its investment as a single folio rather than itemised lots.""",
     "Engine: AI-INV. Every figure is computed from what you entered."),

    ("14-allocation.png", "Allocation", "Concentration, flagged with the threshold that flagged it",
     """The portfolio split by asset class and by holding, with two warnings raised here: equity is 100% of the portfolio against a 70% ceiling for one asset class, and a single folio is 100% against a 15% ceiling for one holding.

Both ceilings are data rows, not numbers in code, and the rule that holds them — RULE-CONC-15-70 — is printed under the warning it produced. Changing the threshold is a change to that row; the screen would then flag differently and say so.""",
     "Rule: RULE-CONC-15-70, versioned and cited."),

    ("15-account-editor.png", "Account editor", "Where a card's terms and a loan's schedule are entered",
     """Beyond the name and opening balance, an account can carry its own terms: a card's credit limit, statement day and due day; a loan's principal, interest rate, tenure and first EMI date.

The loan fields are needed together — without all four there is no schedule to work out — and the screen says exactly that. Leave the EMI blank and the app computes it; enter your bank's own figure and the app uses yours.

"Count towards net worth" exists for an account you use but do not own, such as a company card.

What is entered here is what the simulators on pages 39 to 41 read: the rate comes from the loan itself, never reverse-engineered from an instalment.""",
     "FLT-004: a loan's current effective rate is the one that is used."),

    ("16-budgets.png", "Budgets", "Suggested from what you actually spend",
     """Each suggestion names the figure it came from: "You usually spend about Rs 1,155.00 a month here". Nothing changes until you accept one, and the banner says so.

Suggestions are computed from your own history by a rule — RULE-BUD-SUGGEST — printed under each card. A budget you accept becomes yours and stops being re-suggested.

When a budget is live, its overspend is what the notification policy weighs before deciding whether it is worth interrupting you about.""",
     "Engines: AI-BUD (budgets), AI-NTF (whether to notify)."),

    ("17-goals.png", "Goals", "What you are saving for, and whether the plan reaches it",
     """The top card is the honest part: you have Rs 19,000 spare a month, and all of it is already claimed by the starter buffer, high-interest debt and the emergency fund — leaving nothing for goals this month.

The app says that outright rather than letting you distribute money it has already allocated elsewhere. The rule that ordered those claims, RULE-EMERG-FIRST, is named.

The footnote is fixed: these are plans worked out from what you entered — a target, a date, what you have saved — and not advice about any particular investment.""",
     "Engines: AI-GOAL, ranked against AI-FOO's waterfall."),

    ("18-goal-editor.png", "Adding a goal", "A target, a date, and what you have already put by",
     """Four fields, one of them optional: leave the date blank and the app still tells you what the goal would take each month.

Note the wording under "Saved so far": only the part you have not linked to a real movement in your accounts. The rest is linked from the goal's own screen, so that progress you can prove and progress you have merely claimed are never added into one number.""",
     "ADR-0036: evidenced progress and declared progress never merge."),

    ("19-goals-with-goal.png", "Goals — with a goal in flight",
     "What it takes, what you planned, and the gap between them",
     """The Japan trip needs Rs 18,214.29 a month; you planned Rs 8,000; the shortfall — Rs 10,214.29 — is stated rather than left to subtraction. At your own pace you arrive in May 2029, twenty months after the date you set. The status is simply "Behind".

"Three ways to close the gap" offers the only three levers that exist: save more, aim lower, or move the date. The app proposes a lower target of Rs 45,000 and quantifies each option.

The horizon rule matters too: under three years, this is savings, a deposit or debt repayment — not equity. That is RULE-HORIZON, printed on the card.""",
     "Rules: RULE-EMERG-FIRST, RULE-HORIZON."),

    ("20-goal-detail.png", "One goal's evidence",
     "How much of this progress can actually be proved",
     """Rs 45,000 of Rs 3,00,000 saved — of which nothing is backed by movements in your accounts. The screen says both halves out loud: Rs 0.00 is evidenced, Rs 45,000.00 is a figure you typed in.

You can link a real transaction to the goal, fund it from an account so future movements attach automatically, or clear the typed figure entirely.

This separation is why goal progress in this app cannot quietly become fiction: the claimed part is always visible as claimed.""",
     "ADR-0036. Rule: RULE-PAY-FIRST."),

    ("21-emergency-fund.png", "Emergency fund", "Months of cover, not a round number",
     """The target is derived from what you actually spend on essentials and how stable your income is — here Rs 2,85,000 — and you are above it, at 7.1 months of cover.

The verdict is specific rather than congratulatory: the fund covers its target, so anything saved beyond it could go to goals instead. A fully funded emergency fund releases the next stage of the waterfall.""",
     "Engine: AI-EMF. Rules: RULE-EMF-MULT, RULE-EMF-COACH."),

    ("22-emergency-fund-working.png", "Emergency fund — the working",
     "Every input to the target, listed",
     """Tap "Show the working" and the target decomposes: a month of essentials at Rs 47,500 — the monthly needs you entered at setup; a six-month multiplier, with the reason it is six rather than more ("not enough income history to tell yet"); what counts as essential (everything categorised as a need); and what counts as spendable today (savings and cash only).

The last line is a caveat the app volunteers: deposits and investments are not counted, so your real cover may be longer than this.

This is P-02 in its purest form — not a justification after the fact, but the actual inputs, in the order the engine used them.""",
     "P-02: show the inputs, the rule, and the reason."),

    ("23-order-of-operations.png", "Where your money should go",
     "Eight stages, filled in order",
     """The waterfall that produces the dashboard's "next best rupee". Each step is filled before the next one gets anything, and each shows its state — Done, Do this, Can't check yet, or Doesn't apply to you — with the amount it needs and the rule that governs it.

Step 1, the starter buffer, is done. Step 2, EPF and VPF, cannot be checked because the app cannot see payroll. Step 3, the high-interest card, is where this month's Rs 19,000 goes — and the card has no rate recorded, so the app treats it as high-interest, says that it is doing so, and tells you exactly where to add the rate.

The banner is the whole philosophy in one line: the amounts are suggestions — nothing moves unless you move it.""",
     "Engine: AI-FOO. Rules: FOO.STARTER_BUFFER, FOO.CAPTURE_EPF_VPF, FOO.KILL_FIRE_DEBT."),

    ("24b-order-of-operations-later-stages.png", "Where your money should go — later stages",
     "Tax, goals, mid-rate debt, and what to do with the rest",
     """Stages 4 to 8: complete the emergency fund, use your tax-saving limits, fund your goals, consider paying down mid-rate debt, and decide between prepaying low-rate debt and investing.

Each carries its own rules — FOO.TAX_ADVANTAGED, FOO.GOAL_INVESTING with RULE-HORIZON, FOO.GREY_ZONE_DEBT, FOO.LOW_RATE_DEBT with RULE-PREPAY-VS-INVEST — and each states plainly when it does not apply to you.

The last two stages are questions of arithmetic rather than principle, which is why they hand over to the simulators on pages 39 to 41.""",
     "The waterfall is data: stages and thresholds are versioned rows."),

    ("25-purchase-advisor.png", "Can I afford this?", "Four inputs, seven checks",
     """What it is, what it costs, how you would pay — cash, card or EMI — and how soon you need it.

How you would pay changes the answer materially: an EMI is weighed against your existing obligations, a card against the statement it would land on, cash against liquidity now.

Urgency can lift a verdict by one step but can never turn a hard failure into a pass. An advisor that could be argued out of a refusal would be a rubber stamp for exactly the purchases it exists to slow down.""",
     "Engine: AI-PA. Rule: RULE-PA-GATES."),

    ("26-advisor-verdict.png", "The verdict", "A stretch — and here is why",
     """A Rs 85,000 laptop against this household: you can do this, with something to accept below.

Then the trace, gate by gate. The first is money available: Rs 3,41,600 now, Rs 2,56,600 left afterwards, against an emergency fund of Rs 2,85,000 — so the purchase eats into the fund. That is marked "Worth knowing" rather than a refusal, because §13 of the specification says crossing the emergency floor warns; only a price the money cannot cover actually fails.

Three verdicts exist: comfortable, a stretch, and not now. The worst gate wins.""",
     "AI-PA takes the worst outcome across seven gates."),

    ("27-advisor-gates.png", "The seven gates", "Each check, its figures, and its own verdict",
     """The next 90 days (does this create a crunch day that was not already coming?), EMIs and rent against a 40% ceiling, your goals' arrival dates, this month's budget, what the money would be worth if invested instead over 5 and 10 years, and timing.

Gate 2 is careful about blame: it separates crunch days the purchase would add from days that were already going to be tight. Without that separation, everyone already under pressure would be told "not now" for everything.

The opportunity-cost gate shows what the money could become but never fails a purchase on its own — it is information, not a veto.""",
     "Rules: RULE-FCT-CRUNCH, RULE-EMI-40, RULE-PA-OPPCOST, RULE-COOL-OFF."),

    ("28-advisor-gates2.png", "What would change the answer",
     "The price that would pass, and the date it becomes comfortable",
     """Two concrete answers rather than a shrug: at Rs 8,000 nothing would object, and from 26 January 2027 you could pay for this without touching your emergency fund.

Under it, what this purchase moves: your money from Rs 3,41,600 to Rs 2,56,600, your runway from 7.2 months to 5.4.

The evidence line names the engine, its version and every rule that fired. The verdict is kept, so a decision can be reread months later exactly as it was made — the rules that produced it are stored with it.""",
     "AI-ARC-006: results are stored with the engine and rule versions that made them."),

    ("29-buy-list.png", "Your buy list", "Somewhere to put a wish that is not a decision",
     """Add what you want and roughly what it costs, and it is parked rather than bought. A wish starts at a want score of 50 — neither wanted nor rejected.

A Rs 25,000 wish is classed "a very big one" and is told it must be slept on: this one waits 24 hours before it can be bought. The cooling-off period is a rule row, not a number in the code.

Then the app begins to ask about it — starting with whether it is a need or a want.""",
     "Engine: AI-PA-INT. Rules: RULE-PAI-LADDER, RULE-COOL-OFF."),

    ("30-buy-list-interview.png", "The interview", "Questions in proportion to the price",
     """How many questions a wish gets depends on its price as a share of your monthly income: a small wish gets one, a large one gets the full set, and an instalment purchase is always treated as heavy.

Only the questions you have not answered are asked, one at a time. Answering "a want" moved the score from 50 to 45, and the next question — how often would you use it — is now offered.

Two things this never does: it never asks the same question twice, and it never removes a wish by itself. A low score changes what the app says, not what is on your list; removing is always your tap, with your own answers quoted back as the reason.""",
     "The per-answer weights are sized so no single answer can decide an outcome."),

    ("31-simulators.png", "What if?", "Two questions with exact answers",
     """Whether to put spare money against a loan or invest it, and which debt to clear first.

Shown here on the sample household, which has no loan and whose credit card has no interest rate recorded — so both simulators say so rather than producing a plan. A card with no APR, no statement balance or no minimum due is left out of the payoff plan entirely; a guessed rate would put a fabricated number into a plan someone might act on.

The last line on the screen is permanent: nothing has been paid or moved — this is arithmetic.""",
     "Engine: AI-SIM. P-03 and P-07 in one screen."),

    ("32-simulators-prepay-answer.png", "Prepay, or invest?",
     "Both sides, over the same horizon, with the point where the answer flips",
     """On a real profile with a Rs 20,00,000 home loan at 9%: putting Rs 2,00,000 against it saves Rs 4,32,934.85 of interest and ends the loan 28 months sooner, while the same money invested at 12% less 30% tax would leave Rs 6,04,935.74 — so investing is ahead, by Rs 1,72,000.89.

Both sides are judged over the same number of months. Comparing a saving that ends in nine years against a return compounding for twenty is the commonest way this comparison lies.

And then the figure that actually decides it: the answer flips at a return of about 10% a year. Below that, pay the loan.

This page is in the light theme; every screen supports both.""",
     "Rule: RULE-PREPAY-VS-INVEST. The breakeven is found by a fixed 40-step search, so it is reproducible."),

    ("33-simulators-dark.png", "Which debt first?",
     "Dearest-first against smallest-first, over the same money",
     """The avalanche strategy clears the highest rate first and costs less; the snowball clears the smallest balance first and is easier to stick to. The app runs both over the same spare money and reports the difference, because that difference is the only honest basis for choosing.

With Rs 15,000 spare a month this household is debt-free in 75 months either way — the dearest debt is also the smallest, so the strategies coincide, and the app says exactly that rather than reporting a saving of Rs 0.

A cleared debt's minimum payment rolls into the next one; that rollover is what makes either plan accelerate.

Every figure here uses the same monthly-interest arithmetic as the loan schedule on the accounts screen, so the two can never disagree about a rupee.""",
     "Rule: RULE-PAYOFF-ORDER (§40.2, CRD-005). Shown in the dark theme."),

    ("34-settings.png", "Settings — Your monthly money",
     "The three figures the whole app leans on",
     """Income, rent or EMI, and what you usually save — the same three from setup, changeable whenever they change.

The app splits income into needs, wants and savings and uses that split for safe-to-spend, for budget suggestions and for the emergency-fund target. Changing a figure here re-derives all of them; nothing has to be recalculated by hand.""",
     "Engines: AI-STS, AI-BUD, AI-EMF all read this profile."),

    ("35-settings-consents.png", "Settings — What the app may use",
     "Three switches, all off until you turn them on",
     """Reading bank messages on the device; fetching gold, crypto and fund prices; and asking a cloud assistant.

The paragraph above them is the contract: each is off until you turn it on, each can be turned back off here at any time, and nothing about your money leaves the device unless one of them is on.

The two network features degrade rather than break. With prices off — or in airplane mode — holdings show their last known value with a staleness label instead of an error.""",
     "P-01 and P-04. Consent is per-feature and revocable."),

    ("36-settings-bottom.png", "Settings — Encrypted backup and restore",
     "A copy only you can open",
     """A backup is a single encrypted file holding everything in the app, locked with a passphrase only you know. You can keep it anywhere — a memory card, a computer, a cloud drive — because without the passphrase it cannot be read, by anyone.

The checkbox is not decoration: you must confirm you understand that a forgotten passphrase means the backup can never be opened, not by you and not by the app. There is no reset, because there is nothing held anywhere that could perform one.

Restore is the mirror image, for a new phone or a fresh start. The restore path is exercised on a real device as a release gate — a backup that cannot be restored is not a backup.""",
     "SEC-005: Argon2id key derivation, AES-256-GCM. DRL-001: the restore drill."),

    ("37-add-transaction.png", "Add transaction", "The most-used screen in the app, in three taps",
     """Amount, direction — expense, income or transfer — account, category, date and an optional merchant and note.

The target is three taps for a typical entry, and the layout exists to hit it: the amount field has focus on open, the date defaults to today, and categories are chips rather than a dropdown.

Entry is never blocked on a category: an uncategorised transaction is recorded and classified later, because a missed record is worse than an unsorted one.""",
     "FR-TXN-002. The screen is reachable from every destination in the app."),

    ("38-add-transaction-bottom.png", "Add transaction — categories and capture",
     "Two faster ways in, at the bottom",
     """Below the categories sit the two capture routes: scan a receipt, and pull from your bank messages.

Both are ways of filling this same form. Neither posts a transaction on its own — they produce a draft on this screen for you to accept, which is the same "propose with evidence, let the person decide" pattern the recurring detector uses.""",
     "FR-TXN-004 (receipts), and the SMS drafts on page 48."),

    ("39-receipt-capture.png", "Check the receipt", "Read on the phone; the photo never leaves it",
     """Take a photo or pick one you already have, and the app extracts the merchant, the date, the total and, where the receipt is legible, the line items.

The text recognition runs entirely on the device. The sentence on the screen — "Read on this phone. The photo never leaves it." — is the whole reason a cloud OCR service was never an option: it would put a picture of your shopping on someone else's server.

What comes back is a draft you check and correct, not a posted transaction.""",
     "FR-TXN-004. On-device text recognition only (P-01)."),

    ("40-sms-drafts.png", "From your bank messages", "Off, and saying so",
     """When reading bank alerts is on, this screen lists what was parsed from them: amount, merchant, date and account, each as a draft to accept or discard.

Here it is off — the choice made during setup — and the screen states that plainly instead of showing an empty list that could be mistaken for "no messages found".

An empty state that explains itself is the difference between a feature that looks broken and one that is simply switched off.""",
     "P-01. Parsing runs on the device; only the extracted fields are kept."),

    (None, "Behind the screens", "Which engine produces which figure",
     """Every number in this document came from a named, versioned, deterministic engine. Nothing on any screen is produced by a language model; a model may only put figures into sentences, and even then its output is checked against the figures before it is shown.

AI-CLS    classification — is this fixed, semi-fixed or flexible; need or want
AI-REC    recurring detection — the repeats offered for confirmation
AI-FCT    the 90-day cash-flow forecast and its crunch days
AI-SEAS   seasonality — the monthly factor the forecast applies
AI-STS    safe to spend, and the breakdown printed under it
AI-NW     net worth
AI-BUD    budgets and the suggestions drawn from your own spending
AI-EMF    the emergency-fund target, its working and its coaching line
AI-GOAL   what each goal takes a month, and whether the plan arrives
AI-INV    holdings, returns, and concentration against RULE-CONC-15-70
AI-CARD   card cycles, utilisation and minimum dues
AI-FHS    the financial health score, its five pillars and the biggest lever
AI-FOO    the order of operations — where the next rupee should go
AI-ORCH   the insight feed: what is worth surfacing this week
AI-NTF    whether an insight is worth interrupting you about
AI-GRD    the guardrail: no figure is shown in a sentence unless it matches the computed one
AI-PA     the Purchase Advisor's seven gates and its verdict
AI-PA-INT the buy-list interview and the want score
AI-SIM    the two what-if simulators

Each result is stamped with the engine, its version, the window of data it read, and the rules it cited — so an answer given today can be reread and reproduced exactly a year from now.

Every financial threshold on every screen is a versioned data row rather than a constant in code: to change what the app considers a high concentration, a heavy EMI or a sufficient emergency fund, you change the row, and the screens that cite it change with it.""",
     None),
]


def main():
    """Renders every page and concatenates them. Result: the PDF path, printed. Output: none."""
    os.makedirs(PAGES, exist_ok=True)
    built = [cover(1)]
    for index, (shot, title, kicker, body, note) in enumerate(SECTIONS, start=2):
        built.append(page(index, title, kicker, shot, body.strip(), note))
        print(f"page {index}: {title}")
    run(["magick"] + built + ["-quality", "92", OUT])
    print(OUT)


if __name__ == "__main__":
    main()

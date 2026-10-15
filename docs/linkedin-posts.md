<!--
  Why:  the 16-week plan says what each post is about; this file is the copy. Drafting all sixteen
        before the first one goes out is what decides whether the run lasts sixteen weeks or four.
  What: every post in full — hook, body, asset spec, first comment, hashtags, and the repo file that
        proves each claim.
  Result: a weekly 45 minutes of screenshot + fact-check, not of writing from a blank page.
  Changelog: 2026-10-10 — Created. Companion to linkedin-showcase-plan.md.
             2026-10-11 — Reframed for a first-ever LinkedIn post: W01 opens on it, W15 calls back
             to it, and the standing rules now assume a brand-new account with no following.
             2026-10-12 — Rebuilt as a story that starts small and gets deeper. New W01 (why I
             started) makes it sixteen weeks; the rules post moved to W04 and the AI block to
             W10–W14. Voice lowered from manifesto to someone a few years in, posting their work
             for the first time: em dashes cut from 47 to a handful, aphorisms kept only in the
             last third, and the process (what I got wrong, what I am unsure of) added early.
-->

# The sixteen posts, in full

**How to use this.** Each week: copy the fenced block, take the asset, fact-check the one number
against the proof link, post Tuesday 09:30 IST, paste the first comment immediately, reply to
everything for 24 hours.

**Standing rules** (from the plan, §2.2 and §1.5): under 250 words · hook in the first 210
characters · one asset · ends in a question · three hashtags · link in the first comment, not the
body · demo data only in every screenshot · never imply it is on the Play Store · never a rupee of
advice.

**Standing rules for the assets.** One type scale for every poster in the run:

| Role | Size | On the W01 sheet |
|---|---|---|
| Title | **94** | the headline, three lines, its first two words in the accent |
| Heading | **32** | each problem |
| Subheading | **24** | each feature name |
| Text | **22** | the line explaining the feature |

Header and footer labels sit outside the scale and are capped at **18px** (16 in practice). The
title is the only golden-ratio step left in the scale; heading, subheading and text sit close
together on purpose, so the three rows read as one block under one headline.

**Two faces, no more.** Bricolage Grotesque sets the title and nothing else; Instrument Sans sets
everything else, including the brand lockup, the labels and the numerals. The monospace is gone.

**Frame and rhythm:** 72px of side padding, 32px above and below every row, a 38px circular marker
in a 72px gutter, and the repo URL left-aligned at the foot. The sheet is **1200x1280**. The header
is the brand lockup alone — "Rekhon · Personal financial adviser" — with nothing on the right.
Behind it all, a two-blob wash in the accent at 26% and 14%, off the top-right and bottom-left
corners. The dark sheet's hairlines are `#182221`, not the `--line` token: once the wash came up
behind them they read louder than the light sheet's, so they step back.

> **Watch the word "adviser."** The plan's own risk table (§1.5) says never to frame this as
> financial advice: the app is not SEBI-registered, and "personal financial adviser" is the phrase a
> regulated adviser uses. The product rule is P-07, advice never orders. If a post ever pairs that
> subtitle with a specific rupee recommendation, that is the line crossed.

No series counter ("01 / 16"), no "India-first" tag, and no "one post every Tuesday" line on the
sheet: that belongs in the post copy, where it costs nothing.

**The arc.** The run is deliberately sequenced shallow to deep, because nobody arrives ready for
the architecture. W01–W04: the problem, then the two rules a non-specialist can feel, then the
rulebook. W05–W09: what went wrong, and what the app does for a person. W10–W14: the AI subsystem,
in increasing depth. W15–W16: the licence argument and the ask.

**Length follows the arc.** W01 is 140 words and W02–W03 stay under 190, with no jargon and no
em dashes. W04–W09 sit around 215–235. W10–W16 may use the full 250. The point is that the posts
get denser as the run goes on, not that any week hits a number.

**Voice.** Write as what I am: a few years into the job, building something large alone, posting it
for the first time. Concretely that means no lines that legislate for other people's codebases, no
closing aphorism before W10, and at least one sentence per post in W01–W05 about the part I got
wrong or still am not sure about. The closing question still asks about *their* system.

**Starting from zero.** W01 is my first post on LinkedIn at all, not just the first of this series.
I have never posted here before. Three consequences that apply to all sixteen:

1. **Say it once, in W01.** The first post names it plainly, which is both honest and the reason a
   stranger reads the second line. No other post opens with "I'm new here"; after W01 the work is
   the credential, and repeating it reads as apology.
2. **Every post stands alone.** Nobody has read the previous one. No post may open with "as I said
   last week", assume W01 for context, or need the series to make sense. Re-state the one line of
   setup each time (*"a personal-finance app that runs entirely on the phone"*) and move on.
3. **Early reach will be small, and that is not a signal.** A new account with no following gets
   tens of impressions, not thousands. Judge the first four posts on whether they are true and
   clear, not on numbers, and treat distribution as the separate job it is (plan §2.6).

**Voice check before posting:** would an engineer who has built something hard read this and think
*"yes, that is how it goes"*? If it reads like a brochure, cut the adjectives and add a number.

---

## W01 · Tue 13 Oct 2026 · Why I started building a finance app for one person

**Pillar:** Product · **Job of this post:** give the reader the problem before any of the
engineering. Also the first thing I have ever posted on LinkedIn, so it says so once and then
spends its words on the why. No list, no jargon.

**The body and the poster carry the same three beats, in the same order** — what was left, the
month-end budget, the bank login — so the image reads as the trailer and the post as the thing
itself. Change one and change the other, or they stop shaking hands.

```
I spent the last three months solving my own problem. I have never posted here before,
so I will start with it.

I wanted to know whether I could afford something, and I could not answer it. Three
things were in the way. I knew what I had spent, not what was left. My budget told me on
the 31st, when it was too late to change the month. And every app that offered to fix
that wanted my bank login, my SMS inbox, or a cloud account holding my whole ledger
first. That is a lot to hand over for a chart.

So I built the one I wanted. It runs entirely on the phone. No account, no server, no
sign-in. Android and Kotlin, not on the Play Store yet.

What does your money app know about you?
```

**Asset:** `docs/design/posters/w01-problem.png` (ground `#fefefe`) or `w01-problem-dark.png`
— 1200x1280, the same sheet in both grounds, regenerated by
`python3 docs/design/posters/build_w01_problem.py` and screenshotted in headless Chrome at
1200x1280, `--force-device-scale-factor=2`.

It is the post in one image: the headline, then three problems in my own words, each answered by
the feature that exists because of it. No screenshot, no series counter, no device. Every content
line follows the standing scale above (94 / 32 / 24 / 22) and the header and footer labels are 16px.
Each problem is numbered with a circular marker, and the repo URL sits at the sheet edge, faded,
because it is the last thing anyone needs to read.

Post the **dark** one — it cuts against the feed's white background. The light sheet is for slides,
print, or a light profile.

`phone-today-dark.png` / `-light` (1170x1830) are the bare prototype screen, kept for **replies**,
not for the post image: when someone asks to see the app, that is what you send. They come from
`docs/design/v2-prototype.html` (Today tab, seeded demo data, fixed "today" of Tue 13 Oct) and are
the **design prototype**, not the Android build — never call them a screenshot of the shipped app.

**First comment:** "The whole thing is public, including the decision records and the bugs I found
in my own gates: <repo link>"
**Hashtags:** `#AndroidDev #Kotlin #BuildInPublic`
**Proof:** `docs/concept-brief.md` §Problem
**Watch out:** the "I wanted to know whether I could afford something" line is the honest general
version, taken from the project's own problem statement. If there is a sharper real moment behind
starting this, swap it in; it will outperform the general version. Do not invent one.
**First-post housekeeping (W01 only):** the profile must be finished *before* this goes out, or a
first post sends people to an empty profile (plan §2.4). Say "I have never posted on LinkedIn
before" exactly once, as above: not "long-time lurker", not an apology for being new, and never
"I'm not sure anyone will read this", which invites nobody to. Expect single-digit comments; send
the post to ten people who would genuinely find it interesting rather than hoping the feed does it.
After posting, pin it to the profile's featured section. For the next sixteen weeks it is the post
that explains all the others to a first-time visitor.

---

## W02 · Tue 20 Oct 2026 · Money is a `Long`, and `Double` fails my build

**Pillar:** Discipline · **Job:** the most relatable rule to any engineer who has touched money.
Second post, so it has to be understandable cold.

```
0.1 + 0.2 != 0.3. In a finance app that is not a curiosity, it is a bug you ship and
hear about from a user angrier than the amount involved.

So money in my app is a Long. Integer paise everywhere: the database column, the
engine, the screen, the export. Wrapped in a Money value class, never a bare number.

Division is where it got interesting. Splitting ₹1,000.00 across three categories is not
₹333.3333. It is HALF_EVEN rounding plus explicit remainder distribution, so the parts
always sum back to the whole. A property test runs that over hundreds of random splits,
which is the only reason I trust it.

And the rule is enforced rather than documented. I wrote a custom Android Lint detector:
declare a Double or Float with a monetary name (amount, total, balance, paise) and the
build fails with the reason. The word list lives in a decision record, not in my head.

What rule in your codebase should be a build failure and currently isn't?
```

**Asset:** terminal screenshot of the lint error. The real message, red, with the file and line.
**First comment:** "The detector and its tests: <repo link>. Six of these now guard money, time,
coroutine scopes, PII in logs, hardcoded strings and hand-rolled crypto."
**Hashtags:** `#Kotlin #AndroidDev #SoftwareEngineering`
**Proof:** ADR-0001, `:lint`, `Money.allocate`
**Watch out:** if there is a real first attempt worth naming here (the detector that caught too
much, the split that failed), one sentence of it belongs after the property-test line:
`[what did you try first?]`. Leave it out rather than invent it.

---

## W03 · Tue 27 Oct 2026 · ₹1,23,456.78 is not a formatting detail

**Pillar:** Product · **Job:** show that India-first is a design stance, not a locale file. Readable
by someone who does not write code.

```
Most apps show me ₹123,456.78. I read it three times before it means anything, because
nobody in India writes a number that way.

Mine writes ₹1,23,456.78. Which sounds like a preference until the question underneath
it: when the app says a figure in words, which other ways of writing the same number
count as the same number?

So I wrote that down as a rule the code checks. Rounding stops at the rupee: ₹1,23,456.78
may be shown as ₹1,23,457, which hides less than a rupee. Lakh wording is allowed only
where it is exact: ₹1,50,000 may be called ₹1.5 lakh, but ₹1.5 lakh may never become
"about ₹2 lakh", because that is a third of the figure gone and the reader cannot see it
go. Western grouping my app does not produce at all, so if ₹123,456.78 turns up in text,
something upstream rewrote a number.

A formatter turned out to be a safety rule, because there is a language model in the
app, and a model that regroups an amount will eventually round a lakh.

What does your app do with ₹1,50,000, and who decided?
```

**Asset:** side-by-side of the two groupings on the real dashboard (demo data).
**First comment:** "The allowlist of 'ways to write the same figure' is one class, driven by a
versioned rule row: <repo link>"
**Hashtags:** `#AndroidDev #Fintech #India`
**Proof:** `MoneyFormatter`, `AllowedRenderings`

---

## W04 · Tue 03 Nov 2026 · Seven rules I wrote before I wrote any code

**Pillar:** Product · **Job:** plant the position, now that three posts have earned it. This is the
post W05–W16 are evidence for.

```
Before I wrote any code, I wrote seven rules my personal-finance app is not allowed to
break. It runs entirely on the phone: Android, Kotlin, no server. Six custom lint rules
now fail the build when code breaks one of the seven.

1. Numbers come from math, words come from AI. An LLM never computes an amount.
2. Privacy first. Nothing about your money leaves the device without explicit,
   per-feature, revocable consent. Default: fully offline.
3. Offline first. Every core feature works in airplane mode. No server.
4. Advice, never orders. It recommends and simulates. It never moves money.
5. Show the work. Every recommendation shows its inputs and the rule that fired.
6. Deterministic and testable. Fixed input, fixed output. Randomness is injected.
7. Exact money. Rupees are integer paise end to end. No floating point, ever.

Rules like these are easy to write and easy to abandon in week six, when one of them is
the only thing standing between me and a feature I want. So four of the seven are
enforced by tooling instead of by memory. Type a Double with a monetary name and the
build fails. Read the system clock in a domain module and the build fails.

The other three I still have to hold myself to, and that is the part I am least sure
about.

Which of these seven would you have dropped first?
```

**Asset:** the seven rules as one clean image (dark background, mono font, numbered). A screenshot
of a markdown preview is fine.
**First comment:** "The rules live in the repo's own contributor file, so they are binding on me and
on any AI agent that touches the code: <repo link>"
**Hashtags:** `#AndroidDev #Kotlin #BuildInPublic`
**Proof:** `CLAUDE.md` §1 · **Watch out:** say *six lint rules*, and *four rules enforced by
tooling*. Both are true as written; do not round up to "all seven". The first-post framing has
moved to W01, so this post does not mention being new here.

---

## W05 · Tue 10 Nov 2026 · The gate that never ran

**Pillar:** War story · **Job:** the post most likely to be shared. Do not soften it. Straight
narrative, no bullets, no em dashes.

```
My CI was green for weeks. Then I looked at what it was actually running.

The coverage gate had no rules in it. Kover was applied, koverVerify ran, CI went green,
and it would have passed at 0% coverage, because nobody had configured a minimum. I had
been reading that green tick as a coverage number the whole time.

Then the test command. Everything in the project said "run testDebugUnitTest", which is
an Android variant task, so it does not exist on pure-Kotlin modules. That is exactly
where the money math and all 30 engines live. It also never reached the custom lint
module, whose tests cover the detectors that enforce those rules.

Then the screenshot baselines, which were verified by nothing. Paparazzi only compares
against recorded images when its verify task is in the graph. A UI change shipped against
a stale baseline and every gate stayed green.

And one I found this week, fix still in progress: CI runs lintDebug. On a pure-Kotlin
module the task is lintJvm. So the detectors that enforce "money is integer paise" and
"no wall clock in domain code" had never once run against the domain modules they exist
to police.

The shape is the same every time. Something asserts that the pipeline passed, and nothing
asserts what it measured.

Go and check one gate in your pipeline. I'll wait.
```

**Asset:** the two CI lines side by side (`lintDebug` vs the module's actual `lintJvm` task), or the
coverage config with zero rules.
**First comment:** "Each of these is written up in a session record in the repo, including the fix:
<repo link>"
**Hashtags:** `#CI #SoftwareEngineering #Testing`
**Proof:** `docs/sessions/`, issue 2.6, `ProjectExtensions.configureCoverage`
**Watch out:** the `lintJvm` finding is still open at the time of this post. Say so. "Found this
week, fix in progress" is more credible than implying it is done.

---

## W06 · Tue 17 Nov 2026 · Your receipts never leave your phone

**Pillar:** Privacy · **Job:** the one that travels beyond engineers.

```
A receipt says where you were, when, and what you bought. It is the most personal
document a finance app will ever hold. Mine never leaves the device, and the reason is
structural rather than a promise.

The receipt OCR uses ML Kit's bundled text recogniser, not the thin client. The thin one
is smaller and downloads its model from Play Services on first use, which would put a
network fetch on a core path and leave the scanner broken in airplane mode. The bundled
one ships the model inside the APK. That choice costs megabytes and buys the guarantee.

Bank-SMS reading is opt-in, default off, and revocable. The consent record keeps the
grant and the revoke timestamp, so the dashboard can show you what you allowed and when
you took it back. A switch on its own tells you the state, not the history.

Conversations with the assistant are stored on the device, excluded from backups, and
hard-deleted. No tombstone row: when you ask for a turn to be gone, keeping your words
and the app's answer in the table would not be gone.

And "works offline" is not a claim in a README. A test turns airplane mode on and drives
the app.

Which of the apps on your phone right now would survive airplane mode?
```

**Asset:** the consents dashboard screen, with switches plus the grant/revoke record.
**First comment:** "The consent ledger, the dashboard, and the airplane-mode test: <repo link>"
**Hashtags:** `#Privacy #AndroidDev #Fintech`
**Proof:** ADR-0059, issue 3.9, `offlineSmoke`

---

## W07 · Tue 24 Nov 2026 · Your SIP's return is roughly double what your app shows

**Pillar:** Product · **Job:** the one a non-engineer can act on. Most likely to be shared outside
your network.

```
If your portfolio screen shows a monthly SIP's return as (value − cost) / cost, it is
understating what you actually earned, often by about half.

The reason is timing. Money you invested last month has been working for one month; money
from three years ago has been working for three years. Cost-to-value treats every rupee
as though it arrived on the same day, and for a staggered investment that is simply the
wrong question.

The right one is a money-weighted return, XIRR, computed over dated lots. Same data, a
very different number, and the one that answers "did this do better than the FD I didn't
buy".

Three more places I found the same shape of problem while building:

A credit card's real billing cycle, not the calendar month. Your statement period,
utilisation against the limit, and the actual due date.

A loan's EMI split into principal and interest, amortised to the rupee, because "how much
of this payment is interest" is the question that changes behaviour.

A budget that tells you the truth mid-month: spent versus planned, but also pace (what
even spending would have been by today) and a projection of where the month actually
lands. Alerts at 80% and 100%, once each. An app that pings you about every coffee is one
you learn to mute.

What does your portfolio app show you, cost/value or XIRR?
```

**Asset:** the allocation screen, or the pace-vs-projection chart, on demo data.
**First comment:** "The investment engine computes XIRR over dated lots; the engine's contract and
assumptions are written up alongside it: <repo link>"
**Hashtags:** `#Fintech #PersonalFinance #India`
**Watch out:** "roughly double" / "about half" is the right register, because it depends on the
schedule. Do not state a precise multiple. And no fund, stock or product is ever named.
**Proof:** `:domain:engines:investment`, `:domain:engines:budget`, `:domain:engines:card`,
`:domain:engines:loan`

---

## W08 · Tue 01 Dec 2026 · Every verdict names the rule that fired

**Pillar:** Product · **Job:** the philosophical one. Ties the first four posts together.

```
A score you cannot check is a verdict. I did not want to build a verdict machine, so
every number in my app carries its own paperwork.

Every engine result ships with provenance: which engine produced it, which version of
that engine, the window of data it read, when it was computed, a confidence, and the
evidence list shown to the user. The version matters more than it sounds. Results are
stored with the engine version that made them, so an insight from six months ago stays
reproducible after the algorithm changes.

And the thresholds are not in the code. "Flag a concentration above X", "an EMI burden
over Y": those live as versioned rows in checked-in data files with ids. Changing the
app's financial opinion is a data edit, reviewed like any other, and the insight that
results cites the row id and version that produced it.

So the screen can say: here is the figure, here is the rule that fired, here is what it
measured and what it measured against.

It also means the app can give the honest answer. When the guardrail cannot verify a
figure, it says it could not answer, rather than showing a number it cannot stand behind.
I would rather ship a refusal than one invented rupee.

Would you trust a financial health score more if it showed you its inputs?
```

**Asset:** an evidence card from the app: inputs, rule id, plain-language reason.
**First comment:** "The rulebook is data, with every row versioned and cited by id: <repo link>"
**Hashtags:** `#ProductEngineering #Fintech #ExplainableAI`
**Proof:** `ai/rules/`, AI-ARC-003, AI-ARC-006

---

## W09 · Tue 08 Dec 2026 · "Erase everything" should destroy the key, not the files

**Pillar:** Privacy · **Job:** the most quietly impressive engineering in the project.

```
Deleting a file on flash storage does not necessarily delete anything. The controller may
have written your data somewhere the filesystem can no longer address. So when my app
erases everything, it destroys the key first.

The database is encrypted with SQLCipher under a key held in the Android Keystore,
StrongBox where the hardware has it, and the key never leaves the secure element. Erase
destroys that key. The ciphertext that remains is unrecoverable, which is both faster and
far stronger than overwriting files.

Three decisions in the order of operations:

Keys before files, chosen for the interrupted case. If the process dies halfway,
keys-first leaves ciphertext nobody can read. Files-first would leave a live key beside a
half-deleted database.

A failed shred deletes nothing. Partial destruction is the worst outcome, so it is all or
none.

The deletion is verified, not assumed, because the platform's deleteEntry can return
without having removed the key.

The screen also says what the erase cannot reach: a backup archive you exported yourself.
Narrowing the promise out loud costs me a line of UI and saves the claim from being
quietly false.

Running it on a real device then found two more gaps: a plaintext cache the home-screen
widget keeps, and nine background workers that outlived the erase and would have started
collecting again.

Does your delete verify, or does it just call delete?
```

**Asset:** the erase confirmation screen, with the "what this cannot reach" line visible.
**First comment:** "The ordering argument and the two device-found gaps are in the decision record:
<repo link>"
**Hashtags:** `#Security #AndroidDev #Privacy`
**Proof:** ADR-0060, ADR-0057

---

## W10 · Tue 15 Dec 2026 · Numbers from math, words from AI

**Pillar:** AI · **Job:** the architectural idea people will remember you for. First post of the
deep block, so the register can firm up here.

```
An LLM in a finance app will eventually state a confident, wrong rupee figure. Mine
cannot, and not because I asked it nicely in a system prompt.

The assistant in my app (a personal-finance app that runs entirely on the phone) is six
layers. Data, analytics, rules, prediction, decisions, and only then language. When you
ask "where did my money go this month", here is what actually happens:

1. A deterministic engine decides which of the registered tools to call. Keyword matching
   over a table that lives in a JSON file, not a when-block in code.
2. The repository runs those tools. Engines compute every figure. Maximum two tools per
   question, so one question cannot read five tools' worth of your data.
3. The model is handed the intent and the finished figures, already formatted —
   ₹61,427.00, Indian grouping and all. It never sees a transaction, a merchant name, or
   your question history.
4. It returns one sentence. A guardrail then parses every number in that sentence and
   checks it against the same tool results.
5. If a figure cannot be verified, the reply is dropped along with its figures, so no
   screen can reassemble the sentence the guardrail refused.

The model is structurally incapable of being the source of a number. The only numbers it
has are the ones the engines produced.

Where would you draw the line between the model and the math?
```

**Asset:** the L1→L6 stack as a simple diagram, or the `FLOW.md` §2.17 call-path block as a
screenshot (it reads well as-is).
**First comment:** "The pipeline and the guardrail rules are both checked-in data rather than code —
rulebook here: <repo link>"
**Hashtags:** `#AI #OnDeviceAI #Fintech`
**Proof:** `ai/architecture/ai-architecture.md`, `FLOW.md` §2.17
**Watch out:** this post must not lean on any earlier one. Almost nobody who sees it saw W04 or
W01, so "Mine cannot" has to carry its own setup, and the second line names what the app is.

---

## W11 · Tue 22 Dec 2026 · I put a real LLM on the phone and gave it no numbers

**Pillar:** AI · **Job:** the technically impressive one. This is the post a hiring manager
screenshots.

```
There is a language model running inside my finance app. It has never seen a transaction,
and it cannot do arithmetic.

What it is: Qwen2.5-0.5B-Instruct, int8, 521 MB, running through MediaPipe's LLM
Inference API. Apache-2.0 weights, chosen over a Gemma-class model of the same size for
exactly one reason: an app whose whole claim is privacy should not hand its users a
licence question to go and read.

What it does: takes an intent and a list of already-computed, already-formatted figures,
and writes one sentence. That is the entire job.

What the app does NOT do: download it. Half a gigabyte cannot go in an APK, and a core
path in this app may not make a network call. So the weights are installed deliberately,
by a person, with a script, and the presence of a verified file is the opt-in. There is
no setting. Nobody puts 521 MB on their phone by accident, which makes it a more honest
signal of consent than a toggle in a settings screen.

What happens on the phones without the weights, which is most phones: a deterministic
template verbaliser answers instead, in English, Hindi, Kannada or Tamil. Same figures,
plainer sentence, and the reply names which model wrote it.

Would you install 521 MB for a better-worded sentence about your own money?
```

**Asset:** the chat screen with the provenance line visible (`on-device:qwen2.5-0.5b-instruct-q8`),
demo data.
**First comment:** "The decision record covers why MediaPipe over its own successor, and why the
weights are sideloaded rather than fetched: <repo link>"
**Hashtags:** `#OnDeviceAI #LLM #AndroidDev`
**Proof:** ADR-0079, `scripts/install_llm_model.py`
**Watch out:** at the time of writing the model has not yet run on physical hardware. Either run it
before this post, or say "the device run is next" in a comment. Do not let the post imply a
measurement you have not taken.

---

## W12 · Tue 29 Dec 2026 · A truncated download is not a model

**Pillar:** Discipline · **Job:** the practical, useful one. Shipping an on-device model has
problems nobody writes about.

```
Half a gigabyte of model weights fails inside a native library in ways no Kotlin can
explain to a user. So the app checks the file before it loads it.

Two checks, because they fail differently and the fix differs.

Length first. It is cheap, and a wrong length almost always means a download that stopped
early, which the user can resume.

Then SHA-256, against the digest published alongside the weights. Streamed in 1 MB
blocks, because the file does not fit in memory on the phones this app targets. A pass is
remembered in a small receipt keyed on length and timestamp, so a cold start does not
re-hash 521 MB, and replacing the file invalidates the receipt.

Then the part I did not see coming. I measured the release build afterwards: the LLM
runtime ships 19–33 MB of native library per ABI. Across four ABIs that is 109 MB, and
the universal APK went from 62 MB to 171 MB.

The fix is one line, the App Bundle's ABI split, so a device downloads only its own
architecture, about 27 MB extra. The split is on by default. I wrote it down explicitly
anyway, because the line above it in my build file turns the language split OFF, and a
reader could reasonably conclude this project turns splits off.

What is the largest thing in your APK? When did you last look?
```

**Asset:** the APK composition table (per-ABI native library sizes).
**First comment:** "The verification and the install script are both in the repo; the APK numbers
are in the decision record: <repo link>"
**Hashtags:** `#AndroidDev #OnDeviceAI #AppSize`
**Proof:** `LlmModelStore`, ADR-0079 consequences

---

## W13 · Tue 05 Jan 2027 · My safety check was stricter than my safety gate

**Pillar:** War story · **Job:** credibility. Anyone can post a win; this is the one that reads as
real. Narrative, no labelled beats.

```
I shipped a check that silently switched off the feature it was protecting. Nothing went
red. That is the part worth telling.

The language model in my app is handed formatted figures and asked for a sentence. Two
things then inspect that sentence: a sanitiser, which refuses any digit it was not given,
and then the real guardrail, which verifies every figure against the engine results.

The sanitiser compared the reply against the literal strings in the prompt. The prompt
shows ₹61,427.00. A small model very often writes ₹61,427, and my guardrail has always
accepted that, because the app itself writes amounts that way. So the inner check was
stricter than the gate it was guarding.

The failure mode is not an error. A refused reply falls back to the plainer deterministic
sentence, which looks exactly like a phone with no model installed. Somebody who had
deliberately installed 521 MB of weights would have seen them used for almost nothing,
and no test, no log and no screen would have told them why.

The fix was to stop copying and start publishing. The guardrail's own definition of "the
same figure, written differently" is now a public function, and both layers read it. One
definition, two readers.

Have you ever shipped a check that was too strict to notice?
```

**Asset:** the before/after diff of the comparison, or the eval report line showing the rate move.
**First comment:** "Written up as a decision record, including why the sanitiser stays deliberately
'kind-blind' and leaves that job to the guardrail: <repo link>"
**Hashtags:** `#SoftwareEngineering #AI #Debugging`
**Proof:** ADR-0080

---

## W14 · Tue 12 Jan 2027 · How do you test a feature that writes sentences?

**Pillar:** AI · **Job:** the answer to the question every team with an LLM feature is currently
asking badly.

```
You cannot unit-test a language model. You can absolutely unit-test what you do with its
output, and that is where the risk actually lives.

So the AI feature in my finance app is gated by a frozen set of 46 cases. Six kinds:

- faithful — the figures, stated plainly. Must be accepted.
- rendering — the same figure written another way the app itself uses: ₹61,427 for
  ₹61,427.00, "₹1.5 lakh" for exactly ₹1,50,000, "3 Nov 2026" for 2026-11-03. Accepted.
- shape — markdown, a preamble, a follow-up question, three sentences. Cleaned, not
  refused.
- drift — a rounded, regrouped or shifted figure. Refused.
- invented — an average, a per-week figure, a trend percentage the model computed itself.
  Refused.
- silent — fluent and empty. Refused, and the deterministic verbaliser answers instead.

Two floors, both 100%, and the second is the interesting one. Nothing drifted or invented
may be accepted, obviously. But no faithful reply may be refused either, because a false
refusal is invisible: the user silently gets the plainer sentence every turn and nothing
anywhere says the feature is off.

I wrote the set before the code it measures. It caught four real defects, including my
favourite: handed a list of figures, the model replied with my own list back. Every figure
in it was correct, so every figure check passed, and the user would have been shown my
prompt's scaffolding as an answer.

How do you gate a non-deterministic feature in production?
```

**Asset:** the harness report line — `drift 8/8 · faithful 10/10 · invented 6/6 · rendering 8/8 ·
shape 10/10 · silent 4/4`.
**First comment:** "The set is versioned data in the repo, read by the test at build time, and a
regression blocks the merge: <repo link>"
**Hashtags:** `#Testing #AI #MLOps`
**Proof:** `ai/eval/verbaliser-eval.json`, `VerbaliserEvalTest`

---

## W15 · Tue 19 Jan 2027 · Open source as proof, not marketing

**Pillar:** Business · **Job:** sets up W16. Shows you think about licensing like an adult.

```
My app's central claim is that nothing about your money leaves your phone. You cannot
verify that from a store listing. So the source is public, not as a growth tactic, but
because publishing the code is what makes the claim checkable.

How it is licensed, and why:

AGPL-3.0, free for anyone including commercial use. Distribute a modified version, or run
one as a network service, and you publish your changes under the same licence.
Improvements come back.

With a narrow linking exception. Google's ML Kit, the on-device receipt recogniser, is
proprietary and cannot be AGPL. Shipping an APK containing both would otherwise be an
unresolvable conflict rather than a licensing choice. The exception permits that one link
and weakens nothing else. The coupling is one class behind one interface, so a fully free
build with an open-source recogniser is a contained change.

Plus a commercial licence, for anyone who wants to build on it without opening their own
source.

And contributors keep their copyright while granting a relicensing right, because dual
licensing only works while one party can grant both licences, and saying that up front is
fairer than discovering it later.

The consequence I had to write down: a build of this repo is not a wholly free artefact
even though its source is. A fork inherits Google's terms as well as mine.

AGPL or Apache for a privacy-first app, what would you have picked?
```

**Asset:** the licence section of the README, as an image.
**First comment:** "Licence, the exception, and third-party attribution: <repo link>"
**Hashtags:** `#OpenSource #Licensing #Fintech`
**Proof:** `LICENSE`, `NOTICE`, `CONTRIBUTING.md`

---

## W16 · Tue 26 Jan 2027 · Sixteen weeks in public, and what I am looking for

**Pillar:** Close · **Job:** convert attention into conversations. This is the only post with an ask
in the body.

```
Sixteen weeks ago I made my first-ever LinkedIn post. It said I could not answer a simple
question about my own money, and that I was building the app I wanted instead. Here is
what that cost, what it taught me, and what I want next.

The numbers: 60 Gradle modules. 30 deterministic engines, each with a written contract.
3,827 unit tests. 79 architecture decision records. Six custom lint rules that fail the
build. Two frozen AI evaluation sets that block merges. Four languages.

Three things I do differently now:

A standard nobody checks is a standard that lasts until the first deadline. Four of my
seven rules are enforced by tooling, and those are the four that have never been broken.

Assert what the gate measured, not that it passed. I found a coverage gate that would
have passed at 0%, a test command that skipped whole modules, and a lint step that had
never run on the modules it exists to police.

Watch for the invisible failures. The worst bug I shipped in six months was a check that
was too strict. It silently disabled the feature it was protecting, and nothing anywhere
went red.

What is next: a Play Store beta, and the on-device model on real hardware.

What I am looking for: beta testers who want a private, offline CFO on their phone, and
freelance work in Android/Kotlin, on-device ML, or fintech where correctness is the
product. My DMs are open.
```

**Asset:** a single recap image: the 16 post titles, or the numbers as a plain table.
**First comment:** "Everything above is in the repo, including the parts that went wrong:
<repo link>. Happy to talk about any of it."
**Hashtags:** `#AndroidDev #FreelanceDeveloper #BuildInPublic`
**Proof:** the repo
**Watch out:** update every number the morning you post. If the Play beta has not started, say
"next" and not "launching". Sixteen weeks is not long enough to have an audience, so do not imply
one: no follower counts, and no "thanks to everyone following along" when it was a handful of
people. The credible close for a first series is the work plus the ask; the repo link carries the
rest.
**Scheduling note:** 26 Jan 2027 is Republic Day. A post with a freelance ask lands badly on a
national holiday, so Wed 27 Jan 09:30 IST is the better slot if you are willing to break the
Tuesday grid once, at the end.

---

# Reusable bits

## The hook patterns that did the work

| Pattern | Used in | Why it works |
|---|---|---|
| A fact that sounds wrong, then the consequence | W02, W07 | `0.1 + 0.2 != 0.3` makes an engineer stop |
| A confession in the first five words | W01, W05, W13 | "I have never posted on LinkedIn before", "My CI was green for weeks" — both buy the next line |
| A number nobody expects | W11, W12 | "521 MB", "62 MB to 171 MB" |
| A definition flipped | W08 | "A score you cannot check is a verdict" |
| A question the reader also cannot answer | W01 | "I wanted to know whether I could afford something, and I could not answer it" |

## Closing question patterns

Ask about *their* system, never about your app. "What does your app do with…", "When did you last
look at…", "Have you ever shipped…". A question about you gets compliments; a question about them
gets comments.

## If a post underperforms

Do not change the plan on one data point. Three in a row with weak engagement means the format, not
the topic: shorten the hook to one line, cut the post to 120 words, and put the asset first.

For the first four posts, there is no "underperforming" to diagnose. A brand-new account has no
distribution, so the first numbers measure reach, not writing. Changing the copy in week two
because W01 got forty impressions is optimising the wrong variable. The fix in weeks 1–4 is always
distribution: comment on other people's posts, and send each post directly to people who would
want it.

## What never goes in a post

Real balances, real merchants, real transaction data, a screenshot with a live account in it, a
rupee-denominated recommendation, a claim to be SEBI-registered or regulated, a comparison that
names a competitor unfavourably, or a status claim that is one week ahead of the truth.

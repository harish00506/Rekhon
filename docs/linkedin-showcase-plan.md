<!--
  Why:  the project's own executive goal is "show case as work i can do / for freelance" — and a
        repository nobody reads is not a portfolio. This is the report that says what there is to
        show, and the plan that shows it over sixteen weeks without exaggerating any of it.
  What: Part 1 — what the project actually is right now, with the numbers. Part 2 — sixteen weekly
        LinkedIn posts, each with its hook, its beats, its asset and its proof link.
  Result: one post a week, every Tuesday, from 13 Oct 2026 to 26 Jan 2027, each one true.
  Changelog: 2026-10-10 — Created.
             2026-10-11 — Calibrated for a standing start: W01 is the author's first-ever LinkedIn
             post, so the cadence rationale, the pre-week-1 checklist and the metrics table no
             longer assume an existing following.
             2026-10-12 — Resequenced shallow-to-deep and extended to sixteen weeks. A new W01
             ("why I started") carries the problem before any engineering, the rules post moved to
             W04, and the AI block to W10–W14. §2.6's checkpoint moved with it.
-->

# Rekhon on LinkedIn — report and a 16-week plan

---

# Part 1 · The report

## 1.1 · What this is

**Rekhon** (working name of AI Personal CFO) is an Android-first, India-first personal-finance app
that behaves like a full-time CFO: it classifies, forecasts, advises, and **explains** — on the
device, with no account and no server on any core path.

The thing worth showing is not "another expense tracker". It is that **every number has a provable
origin**. Deterministic engines compute; a language model only puts the result into a sentence; a
guardrail checks the sentence against the engines before anyone reads it. That constraint is
enforced by the build, not by good intentions.

## 1.2 · What exists, measured (2026-10-10)

| | |
|---|---|
| Gradle modules | **60** (`:core:*`, 30 `:domain:engines:*`, `:data:*`, `:ml:*`, 13 `:feature:*`, `:widget`, `:sync:*`, `:lint`) |
| Kotlin source files | **450** main, **383** test |
| Unit tests | **3,827** in 347 classes, green (`./gradlew unitTests`) |
| Engines with a written contract | **30**, each with an `ENGINE.md` |
| Architecture Decision Records | **79** |
| Issue specs / session records | **90** / **62** |
| Custom lint detectors that fail the build | **6** (float money, wall-clock in domain, `GlobalScope`, PII in logs, hardcoded UI strings, hand-rolled crypto) |
| Frozen AI evaluation sets | **2** — guardrail (28 cases), verbaliser fidelity (46 cases), both gating merges |
| Database schema version | **33**, every bump with a migration test; destructive migrations forbidden |
| Languages | English + **Hindi, Kannada, Tamil** |
| Licence | **Dual** — AGPL-3.0 with a narrow linking exception, or commercial |
| On-device model | **Qwen2.5-0.5B-Instruct int8 (Apache-2.0)**, sideloaded, SHA-256 verified |
| Release artefact | AAB **77 MB**; universal APK 171 MB (the LLM runtime is 109 MB across four ABIs) |

**Built and verified:** foundation, onboarding and accounts, transactions and capture (receipt OCR +
opt-in SMS), categorisation and budgets, dashboard and export, wealth through investment allocation,
goals, forecasting, health score, advisor, the chat assistant, market signals, security hardening,
compliance, and the Epic 13 "design-for" foundations (household, appliances, insurance, tax,
business, Account Aggregator, a KMP spike).

**Not done:** not on the Play Store, no real users yet, the on-device model has never been run on a
physical device, and iOS is a documented spike rather than a port.

## 1.3 · What makes it postable

Four things, in descending order of how rare they are on a feed:

1. **Enforcement, not aspiration.** "Money is integer paise" is a *build failure* if you type
   `Double`. Six detectors, each with its own tests. Most portfolios claim standards; this one can
   show the red build.
2. **The AI is caged on purpose.** An LLM that is structurally unable to state a number, measured by
   a frozen set in both directions — including the direction nobody measures (a correct answer
   wrongly refused).
3. **Honest failure stories.** The repository records defects found *by its own gates*: a coverage
   gate that passed at 0%, a wipe that silently skipped sixteen tables, a verifier stricter than the
   thing it verified. This is the content engineers actually stop for.
4. **A licence that is an argument.** The source is public *because* "nothing leaves your device" is
   not checkable from a store listing.

## 1.4 · Audience and goal

- **Primary:** Indian product engineers, Android/Kotlin folks, and the hiring managers and agencies
  who read their feeds. They are the ones who can turn a post into a freelance conversation.
- **Starting position: zero.** W01 is the first thing I will have posted on LinkedIn, on an account
  with no posting history and no following. The audience above is who the posts are *for*, not who
  will see them in week one — reaching them is a separate, manual job for the first month (§2.6).
  Nothing in this plan should be read as assuming an existing feed.
- **Secondary:** privacy-minded professionals who might install it.
- **Goal from the concept brief:** showcase capability for freelance work; 100 installs and 25
  weekly-active in the first three months after launch.
- **Therefore the posts sell the engineering, not the app.** The app is the evidence.

## 1.5 · Risks to manage in public

| Risk | Rule for every post |
|---|---|
| Overclaiming shipped status | Never imply it is on the Play Store before it is. "Building" / "not shipped yet" stays in the copy |
| Financial-advice framing | Never a rupee recommendation, never a stock or fund. It explains *your* money; it is not SEBI-registered and the posts say so where relevant |
| Leaking real data | Every screenshot uses seeded demo data. No real balance, merchant, or transaction ever appears |
| Licence sloppiness | AGPL-3.0 **with a linking exception**, dual-licensed. ML Kit is proprietary; MediaPipe and the model weights are Apache-2.0. Get these right or say nothing |
| Sounding like a brochure | One post in three is a failure story. Numbers always, superlatives never |

---

# Part 2 · The plan

## 2.1 · The spine

> **"I am building a finance app that cannot lie to you — and the build fails when it tries."**

Every post is one piece of evidence for that sentence. Sixteen weeks is enough to establish a
position, not just make noise. The sentence is where the run *ends up*, though, not where it opens:
W01 gives the reader the problem, and the claim is earned over W02–W04.

## 2.2 · Cadence and format

- **One post every Tuesday, 09:30 IST**, for sixteen weeks (13 Oct 2026 to 26 Jan 2027). Same slot
  every week — from a standing start, predictability is most of what builds a following at all.
- **Each post stands alone.** With no following yet, each post reaches a different handful of
  people, so none may depend on the previous one. The "first time posting" framing is stated once,
  in W01, and never again.
- **150–250 words**, and length follows the arc: W01 at ~140, W02–W03 under 190, W04–W09 around
  215–235, W10–W16 up to the full 250. Hook in the first line (LinkedIn truncates at ~210
  characters). One idea.
- **One asset:** a screenshot, a code snippet image, a terminal output, or a hand-drawn diagram.
  Never a wall of text alone.
- **End with a question**, not a pitch. Reply to every comment within 24 hours — that hour matters
  more than the post.
- **Three hashtags, maximum.** `#AndroidDev #Kotlin` plus one topical.
- **Link in the first comment**, not the body (reach), except for the two posts where the repo *is*
  the point (W04, W15).
- **Voice.** A few years into the job, building something large alone, posting it for the first
  time. W01–W05 show the process and the open questions; the register firms up after that. No line
  that legislates for other people's codebases, and no closing aphorism before W10.

## 2.3 · The sixteen posts

**Full copy for every post:** [`linkedin-posts.md`](linkedin-posts.md) — hook, body, asset
spec, first comment, hashtags and proof link for each of the sixteen.

**The order is the argument.** The run goes shallow to deep on purpose, because nobody arrives
ready for the architecture. W01–W04 give the reader the problem, then the two rules a
non-specialist can feel, then the rulebook. W05–W09 are what went wrong and what the app does for
a person. W10–W14 are the AI subsystem in increasing depth. W15–W16 are the licence argument and
the ask. The voice follows the same curve: the first five posts show the process and the parts I am
unsure about, and the register firms up as the run earns it.

| # | Date | Pillar | Post |
|---|---|---|---|
| 1 | Tue 13 Oct 2026 | Product | Why I started building a finance app for one person |
| 2 | Tue 20 Oct 2026 | Discipline | Money is a `Long`, and `Double` fails my build |
| 3 | Tue 27 Oct 2026 | Product | ₹1,23,456.78 is not a formatting detail |
| 4 | Tue 03 Nov 2026 | Product | Seven rules I wrote before I wrote any code |
| 5 | Tue 10 Nov 2026 | War story | The gate that never ran |
| 6 | Tue 17 Nov 2026 | Privacy | Your receipts never leave your phone |
| 7 | Tue 24 Nov 2026 | Product | Your SIP's return is roughly double what your app shows |
| 8 | Tue 01 Dec 2026 | Product | Every verdict names the rule that fired |
| 9 | Tue 08 Dec 2026 | Privacy | "Erase everything" should destroy the key, not the files |
| 10 | Tue 15 Dec 2026 | AI | Numbers from math, words from AI |
| 11 | Tue 22 Dec 2026 | AI | I put a real LLM on the phone and gave it no numbers |
| 12 | Tue 29 Dec 2026 | Discipline | A truncated download is not a model |
| 13 | Tue 05 Jan 2027 | War story | My safety check was stricter than my safety gate |
| 14 | Tue 12 Jan 2027 | AI | How do you test a feature that writes sentences? |
| 15 | Tue 19 Jan 2027 | Business | Open source as proof, not marketing |
| 16 | Tue 26 Jan 2027 | Close | Sixteen weeks in public, and what I am looking for |

One post in three is still a failure story (W05, W13) or a defect found by my own gates (W12), and
the two posts a non-engineer can read all the way through are W03 and W07.

---

### W01 · Why I started building a finance app for one person
**Hook:** "I have never posted on LinkedIn before. I spent three months building instead, so I will
start with why." — the first thing on the account, so it says so once and spends the rest of its
words on the problem.
**Beats:** the question I could not answer about my own money → what every app wanted in exchange
for answering it (bank login, SMS inbox, a cloud account holding the whole ledger) → so I built the
one I wanted, and it runs entirely on the phone with no account and no server → one line of what it
is (Android, Kotlin, three months, not on the Play Store). The cadence promise was cut from the
copy — the run earns attention by continuing, not by announcing itself.
**Asset:** the dashboard on demo data, Safe-to-Spend visible. No annotations.
**Proof:** `docs/concept-brief.md` §Problem · **CTA:** "What does your money app know about you?"

### W02 · Money is a `Long`, and `Double` fails my build
**Hook:** "`0.1 + 0.2 != 0.3`. In a finance app that is not a curiosity. It is a bug you ship and
hear about from a user angrier than the amount involved."
**Beats:** paise as `Long` everywhere (column, engine, screen, export) → the `Money` value class,
HALF_EVEN with remainder distribution so splits always sum, and the property test that is the only
reason I trust it → a custom lint detector that flags a floating-point declaration with a monetary
name → the word list lives in an ADR, not in my head.
**Asset:** terminal screenshot of the lint error.
**Proof:** ADR-0001, `:lint` · **CTA:** "What rule in your codebase should be a build failure and
isn't?"

### W03 · ₹1,23,456.78 is not a formatting detail
**Hook:** "Most apps show me ₹123,456.78. I read it three times before it means anything, because
nobody in India writes a number that way."
**Beats:** Indian digit grouping, lakh and crore → the rule that a figure may be rounded to the
rupee but **never** to the nearest lakh ("₹2 lakh" for ₹1.5 lakh is a third of the figure) → exact
lakh wording allowed, inexact refused → why a formatter ended up being a safety rule, since there is
a language model downstream of it.
**Asset:** side-by-side of both groupings on a real screen.
**Proof:** `MoneyFormatter`, `AllowedRenderings` · **CTA:** "What does your app do with ₹1,50,000?"

### W04 · Seven rules I wrote before I wrote any code
**Hook:** "Before I wrote any code, I wrote seven rules my personal-finance app is not allowed to
break."
**Beats:** the seven rules (numbers from math / privacy first / offline first / advice never orders
/ show the work / deterministic / exact money) → why rules beat features for a money app → the four
that are machine-enforced today, and the three I still have to hold myself to → what I am building,
in one line.
**Asset:** the rules table, as an image.
**Proof:** `CLAUDE.md` §1 · **CTA:** "Which of these would you have cut first?"

### W05 · The gate that never ran
**Hook:** "My CI was green for weeks. Then I looked at what it was actually running."
**Beats:** a coverage gate applied with zero rules — it passed at 0% → `testDebugUnitTest` silently
skipping every pure-Kotlin module → screenshot baselines verified by nothing → and the one I found
this week: CI runs `lintDebug`, which does not exist on a JVM module, so the detectors that enforce
money and time rules **had never run against the domain modules they police** → the shape they share:
something asserts the pipeline passed, nothing asserts what it measured.
**Asset:** the two CI lines, side by side.
**Proof:** `docs/sessions/`, issue 2.6 · **CTA:** "Go and check one gate in your pipeline. I'll
wait."

### W06 · Your receipts never leave your phone
**Hook:** "A receipt says where you were, when, and what you bought. It is the most personal
document a finance app will ever hold. Mine never leaves the device."
**Beats:** ML Kit's **bundled** recogniser, not the thin client, because the thin one fetches a model
at first use → opt-in SMS parsing, default off, revocable, with the grant and revoke timestamps kept
→ a consent dashboard that shows the record, not just a switch → chat turns hard-deleted with no
tombstone row → the whole thing works in airplane mode, and a test turns airplane mode on.
**Asset:** the consents dashboard screen.
**Proof:** ADR-0059, issue 3.9 · **CTA:** "Which of your apps could survive airplane mode?"

### W07 · Your SIP's return is roughly double what your app shows
**Hook:** "If your portfolio screen shows a monthly SIP's return as (value − cost) / cost, it is
understating what you actually earned, often by about half."
**Beats:** why cost/value is wrong for staggered investments → money-weighted return (XIRR) with
dated lots → the same rigour applied to a credit card's real billing cycle and a loan's
principal/interest split, amortised to the rupee → budgets that show *pace* and a projection
mid-month, not just spent-versus-planned.
**Asset:** the allocation screen, or the pace-vs-projection chart.
**Proof:** `:domain:engines:investment`, `:domain:engines:budget` · **CTA:** "What does your
portfolio app show — cost/value or XIRR?"

### W08 · Every verdict names the rule that fired
**Hook:** "A score you cannot check is a verdict. I did not want to build a verdict machine."
**Beats:** every engine result carries provenance — engine id, version, input window, computed-at,
confidence, evidence → thresholds live as versioned rows in `ai/`, never as constants in code →
changing a rule is a data edit with an id, so an answer from six months ago stays reproducible →
and the app would rather refuse than show a figure it cannot verify.
**Asset:** an evidence card: inputs, rule id, plain-language reason.
**Proof:** `ai/rules/`, AI-ARC-003 · **CTA:** "Would you trust a health score that showed you its
inputs?"

### W09 · "Erase everything" should destroy the key, not the files
**Hook:** "Deleting a file on flash storage does not necessarily delete anything. So the erase
destroys the key first."
**Beats:** SQLCipher under a key in the Android Keystore / StrongBox → crypto-shredding: kill the
key and the ciphertext is unrecoverable → key-first ordering chosen for the *interrupted* case → the
deletion is verified, not assumed, because `deleteEntry` can return without removing anything → the
screen says what the erase cannot reach, and a real device then found two more gaps.
**Asset:** the erase confirmation screen.
**Proof:** ADR-0060, ADR-0057 · **CTA:** "Does your delete verify, or just call delete?"

### W10 · Numbers from math, words from AI
**Hook:** "An LLM in a finance app will eventually state a confident wrong rupee figure. Mine
cannot — not because I asked it nicely."
**Beats:** the six-layer stack (data → analytics → rules → prediction → decision → language) → the
model receives *already-formatted* figures and no raw data → a guardrail re-checks every number in
its reply → a blocked reply shows nothing, not a half-answer.
**Asset:** the L1→L6 diagram.
**Proof:** `ai/architecture/ai-architecture.md`, `FLOW.md` §2.17 · **CTA:** "Where would you put the
boundary between model and math?"

### W11 · I put a real LLM on the phone and gave it no numbers
**Hook:** "There is a language model running inside my finance app. It has never seen a
transaction, and it cannot do arithmetic."
**Beats:** Qwen2.5-0.5B-Instruct, Apache-2.0, chosen over Gemma on licence grounds → weights are
installed by the user, never downloaded by the app (no network call on a core path) → the model gets
intent + formatted figures, nothing else → deterministic template verbaliser still answers on every
phone without the weights, and on Hindi/Kannada/Tamil devices.
**Asset:** the chat screen, with the provenance line naming the model.
**Proof:** ADR-0079 · **CTA:** "Would you install 521 MB for a better sentence?"

### W12 · A truncated download is not a model
**Hook:** "Half a gigabyte of weights fails inside a native library in ways no Kotlin can explain to
a user. So I check the file before I load it."
**Beats:** length *and* SHA-256 against a published digest, with a receipt so it is hashed once →
why the app never downloads it itself → the surprise: the runtime added 109 MB of native library
across four ABIs and took the universal APK from 62 MB to 171 MB → the App Bundle ABI split, stated
explicitly rather than assumed.
**Asset:** the APK composition table.
**Proof:** `LlmModelStore`, `scripts/install_llm_model.py` · **CTA:** "What is the largest thing in
your APK? Have you looked?"

### W13 · My safety check was stricter than my safety gate
**Hook:** "I shipped a check that silently switched off the feature it was protecting. Nothing went
red. That is the part worth telling."
**Beats:** the sanitiser compared the model's reply to the *literal* strings in the prompt →
`₹61,427` was refused for an amount the guardrail itself accepts → the failure is a fallback, which
looks exactly like a phone with no model → fixed by *publishing* the guardrail's own rendering rules
instead of copying them → one definition, two readers.
**Asset:** the diff, or the eval report line.
**Proof:** ADR-0080 · **CTA:** "Ever shipped a check that was too strict to notice?"

### W14 · How do you test a feature that writes sentences?
**Hook:** "You cannot unit-test a language model. You can absolutely unit-test what you do with its
output."
**Beats:** 46 frozen cases — faithful, rendering, shape, drift, invented, silent → two floors, both
100%: no invented figure accepted **and** no faithful reply refused → the set was written before the
code and caught four real defects, including a reply that read my own prompt back as an answer →
thresholds block merges.
**Asset:** the per-kind report line: `drift 8/8 · faithful 10/10 · invented 6/6 · rendering 8/8 ·
shape 10/10 · silent 4/4`.
**Proof:** `ai/eval/verbaliser-eval.json` · **CTA:** "How do you gate a non-deterministic feature?"

### W15 · Open source as proof, not marketing
**Hook:** "My app's central claim is that nothing about your money leaves your phone. You cannot
verify that from a store listing. So the source is public."
**Beats:** AGPL-3.0 with a narrow linking exception, dual-licensed with a commercial option → the
exception exists because ML Kit is proprietary and cannot be AGPL; the coupling is one class behind
one interface → contributors keep their copyright and grant a relicensing right → a build of this
repo is not a wholly free artefact even though its source is.
**Asset:** the licence section of the README.
**Proof:** `LICENSE`, `NOTICE`, `CONTRIBUTING.md` · **CTA:** "AGPL or Apache for a privacy-first
app?"

### W16 · Sixteen weeks in public, and what I am looking for
**Hook:** "Sixteen weeks ago I made my first-ever LinkedIn post. It said I could not answer a simple
question about my own money, and that I was building the app I wanted instead."
**Beats:** the numbers (modules, tests, ADRs, defects found by my own gates) → three things I do
differently now → what is next: Play Store beta, the on-device model on real hardware → **the ask**:
beta testers, and freelance Android/Kotlin + on-device-AI work.
**Asset:** a single-image recap of the sixteen weeks.
**Proof:** the repo · **CTA:** "If you are hiring for Android, on-device ML, or fintech
correctness — my DMs are open."
**Scheduling note:** 26 Jan 2027 is Republic Day. Wed 27 Jan is the better slot if you will break
the Tuesday grid once, at the end.

## 2.4 · Before week 1 (do this once, ~3 hours)

- [ ] **Fix the README status section.** It says the chat assistant is not built; it is. The first
      thing a reader checks must be accurate.
- [ ] **Add the `DECISIONS.md` rows for ADR-0077 and ADR-0078** (currently missing). A visitor who
      checks the index should not find a gap.
- [ ] **Capture 8–10 screenshots** on seeded demo data: dashboard, add-transaction, budgets with
      pace, allocation, chat with provenance, consents dashboard, evidence card, widget.
- [ ] **Profile, before W01 — this is a brand-new posting account.** A first post sends curious
      readers to the profile, and an empty one ends the visit. Headline → "Android/Kotlin engineer ·
      on-device AI · building Rekhon, a privacy-first personal CFO"; featured section → the repo;
      About → the seven rules in two sentences; a real photo; experience and skills filled in.
- [ ] **Line up the first ten readers.** Send W01 directly to ten people who would actually find it
      interesting. An account with no following gets no feed distribution in week one, and ten
      genuine readers beat waiting for an algorithm that does not know you exist yet.
- [ ] **Decide the handle for the repo link** and keep it identical in every post.
- [ ] **Draft W01–W03 in full** before publishing W01. A one-post buffer is the difference between a
      16-week run and a 4-week run.

## 2.5 · The weekly routine (45 minutes)

| Minutes | Step |
|---|---|
| 0–10 | Pick the week's post from the table. Pull the one number or line that is the evidence |
| 10–25 | Write it: hook, three beats, question. Cut to under 250 words |
| 25–35 | Make the asset. A terminal screenshot at 2× is a perfectly good asset |
| 35–40 | Fact-check against the repo. If a claim needs a hedge, hedge it |
| 40–45 | Schedule for Tuesday 09:30 IST. Put the link in the first comment |

Then: **reply to every comment for the first 24 hours.** That is not optional; it is where the
freelance conversations start.

## 2.6 · What to track, and what to do about it

Log five numbers after each post, in one row of a spreadsheet: impressions, reactions, comments,
profile views, DMs.

| What you see after ~4 posts | What it means | What to do |
|---|---|---|
| Double-digit impressions, almost no engagement | Normal for a new account's first month — this is a distribution problem, not a writing problem | Keep the copy; do the manual work: comment on 3 larger posts a day, send each post to 10 relevant people |
| War stories out-perform product posts 2:1 | The audience is engineers, as intended | Swap a product post for a war story; there are more in `docs/sessions/` |
| Product posts out-perform | The audience is users, not peers | Move the Play beta earlier and add a waitlist line to every post |
| Good impressions, no DMs | The ask is missing | Add one line of availability to every third post, not just W16 |
| Flat across the board | Distribution, not content | Post in two relevant groups, and comment on 3 bigger posts a day for a fortnight |

**Decision point at W09:** if DMs are still zero *and* impressions have grown past the first
month's double digits, rewrite W10–W16 to lead with the freelance positioning rather than the
engineering. If impressions are still tiny, the posts are not being seen and rewriting them fixes
nothing — the problem is distribution, and the fortnight of commenting is the fix. The content is
the means; the conversations are the goal.

## 2.7 · Repurposing (free leverage, no extra writing)

- Each post → a short X/Twitter thread the same afternoon.
- W10, W11, W13, W14 → one long-form article, "Putting an LLM in a finance app without letting it
  lie" — the single best portfolio artefact this project can produce.
- W05 + W13 → a conference or meetup talk, "The gates that never ran".
- All sixteen → the case-study page in a freelance portfolio.

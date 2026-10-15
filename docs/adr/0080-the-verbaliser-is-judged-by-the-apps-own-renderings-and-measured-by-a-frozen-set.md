<!--
  Why:  CLAUDE.md §5 — ADR-0079 put a model in the app; this is the record of tuning it to what the
        app actually needs and measuring whether it does it.
  What: the model judged by the guardrail's own renderings rather than by the prompt's literal
        strings, a prompt tuned with the app's own sentences, and a frozen set that measures
        fidelity in both directions.
  Result: a reader can see why the first version silently disabled itself, and why "accuracy" here
          means two rates rather than one.
  Changelog: 2026-10-10 — Created.
-->

# ADR-0080 — The verbaliser is judged by the app's own renderings, and measured by a frozen set

**Status:** Accepted · **Date:** 2026-10-10 · **Extends:** [ADR-0079](0079-the-on-device-model-is-real-and-its-weights-are-apache-2-0.md), [ADR-0054](0054-the-guardrail-is-measured-against-a-frozen-set-with-an-absolute-threshold.md) · **SRS:** §19 (§19.4), §21.5, §7.1 (AI-ARC-004) · **Rules:** P-03, P-02, P-04, MNY-001 · **Issue:** 10.5

## Context

ADR-0079 shipped the model. What it did not do was check whether the model is *usable* for the one
job this app has for it, and the first version had a defect of exactly the kind that never gets
noticed in production.

`DraftSanitiser` compared the reply against **the literal strings the prompt showed**. The prompt
shows `₹61,427.00`. A small model told that very often writes `₹61,427` — and AI-GRD, one step
later, would have accepted it: `AllowedRenderings` has permitted the whole-rupee short form since
issue 9.7, because the app itself writes amounts that way.

So the sanitiser was **stricter than the guardrail it protects**, and the result was not a visible
error. It was a refusal, which falls back to the template sentence, which looks exactly like a phone
with no model installed. A user who had sideloaded 521 MB of weights would have seen them used for
almost nothing, and no test, log or screen would have said why.

## Decision

### 1 · One definition of "the same figure, written differently"

`AllowedRenderings` already answers this, from `RULE-GRD-TRANSFORMS` in the rulebook. It is now
published as `FigureRenderings.of(evidence, rules)` and the sanitiser reads it, so the two layers
cannot disagree. The mapping from tool figures to guardrail evidence moved to `ChatEvidence` for the
same reason — it had been private to `IntentRoutingChatEngine`, and a copy in `:ml:llm` would have
been a second thing to drift.

The published set is deliberately **kind-blind**. The guardrail checks a claim against values *of
its kind* and must keep doing so; the sanitiser asks the weaker question that comes first — *is this
digit one the engines produced at all?* A sanitiser that also enforced kinds would be a second
guardrail, and a second guardrail is a second thing to maintain and disagree.

What this buys, measured: `₹61,427`, `₹61,427.0`, `₹ 61,427.00`, `₹1.5 lakh` for exactly ₹1,50,000,
`₹1,23,457` for ₹1,23,456.78, and `3 Nov 2026` for `2026-11-03` are all now accepted. `about
₹61,000`, `₹2 lakh` for ₹1.5 lakh, `₹61427.00` (western grouping, which this app does not produce)
and `₹1,23,456` for an amount with paise are still refused.

### 2 · The prompt is tuned with the app's own sentences, as data

`ai/chat/system-prompt.md`'s verbaliser section gained four worked examples — taken from
`TemplateLlmEngine`'s own `strings.xml` sentences, because those *are* the specification for how
this app says each intent — and the rules the measured failure modes asked for: copy the ₹ and the
commas, no derived per-week or per-transaction figure, no follow-up question, second person.

It stays data (§6). It is 1,514 characters, about 380 tokens of the shipped model's 1,280-token
window, and the drift test now pins a 2,000-character ceiling with the token arithmetic written
next to it, so the next example added has to argue with that number.

### 3 · "Accuracy" here is two rates, and neither may be traded for the other

`ai/eval/verbaliser-eval.json` — 46 frozen cases, six kinds — measures **only** verbalisation
fidelity: does the reply state exactly the figures it was given, in a form this app writes, in one
sentence? It is explicitly not a language benchmark. Fluency, helpfulness and reasoning are not what
an L6 verbaliser is for, and a score for them would tell this project nothing it could act on.

| Rate | Floor | Why that floor |
|---|---|---|
| `fidelity_refused_pct` | **100** | A drifted or invented figure reaching the user is the failure this app cannot survive |
| `faithful_accepted_pct` | **100** | A false refusal is invisible: the user silently gets the plainer sentence every turn and the weights are never used |

The second rate is the one this ADR exists for. `GuardrailEvalTest` has a false-block rate too and
argues it may be relaxed in a commit; here it starts at 100 because it is achievable and because the
defect it guards against is the one that already happened.

### 4 · The set was written before the code it measures, and found two defects

**First run: 39 of 43.** Every failure was the set being right.

- **VE-S07 / VE-S08 were accepted.** A model handed `- total: ₹61,427.00` often replies with the
  list it was given. Every figure in it is correct, so every figure check passes — the digit rule,
  and the guardrail after it — and the user would have been shown the app's own prompt scaffolding
  as though it were an answer. It is not a figure failure at all, which is why nothing caught it.
  `DraftSanitiser` gained an is-this-prose rule: two or more list lines, the prompt's own scaffold
  words, or a sentence that does not begin with a letter (`chat.draft.shape`).
- **VE-D02 / VE-D08 were refused for the wrong reason.** "about ₹2 lakh" for ₹1.5 lakh states no
  *permitted* figure, so the weaker "said nothing" rule fired before the digit rule and reported a
  misstated amount as silence. Two refusals with the same effect and very different meanings. The
  digit check now runs first.

**Second run: 43 of 43**, both rates 100%.

Then three more cases were added, for failures the first batch had not imagined — and all three
failed, which is the only reason to add a case:

- **VE-S09**, the figure list read back **on one line** (`total: ₹61,427.00, count: 23`). The
  list-line rule cannot see it and every figure in it is correct. The shape rule now refuses a reply
  that reads a figure **key** back at the user, which is a sharper version of the same idea.
- **VE-S10**, a stray `Answer:` label. Stripped rather than refused: the sentence underneath it is
  right, and refusing would cost a good answer over a habit. The cue regex went from `Sentence:` to
  any single short leading label.
- **VE-R08**, `23 transactions, ₹61,427.00 spent.` — refused, because the first shape rule required
  the sentence to begin with a letter. A terse sentence that opens with a figure is perfectly true,
  and refusing it is **this ADR's own mistake made a second time**. That rule is gone; the keys and
  the scaffold are what mark a non-sentence, not the first character.

**Final: 46 of 46** — drift 8/8 · faithful 10/10 · invented 6/6 · rendering 8/8 · shape 10/10 ·
silent 4/4.

### 5 · The real model is measured on a device, and that measurement is not a merge gate

The frozen set measures the layer that *judges* a reply, against replies written by hand in the
style a 0.5B model produces. That is honest about what it is, and it is the part that can run in CI.
It is not the same claim as *this model, on this phone, gets it right often enough to be worth 521
MB*.

`OnDeviceVerbaliserAccuracyTest` is that measurement: the same intents and figures asked of the real
runtime, the acceptance rate printed, with a floor of **67%** and no expected text — a model's
wording is its own, and asserting it would be asserting a sampler. It `assumeTrue`s the weights and
therefore skips on every CI runner and most devices. A run below the floor means the prompt needs
tuning, which is a data edit, not a code change.

## Consequences

**Good.** The sanitiser can no longer silently switch the feature off, because the rate that would
show it is a gate. There is one definition of a figure's renderings, in the module that owns the
rule, reached by both layers. `aiEval` now names six frozen sets instead of five.

**Accepted costs.**

- **The frozen replies are hand-written, not sampled.** They are modelled on what small instruction
  models do, by someone who has read a lot of their output — not drawn from the shipped weights,
  because no device in the session that wrote them had the weights. The `_meta` says so plainly, and
  the device test is the honest version of that measurement.
- **Kind-blindness in the sanitiser.** A model could state a count where an amount belongs and pass
  this layer; AI-GRD then catches it, because it checks kinds. Two layers, two jobs.
- **`FigureRenderings` is public API on an engine module.** Argued in the ENGINE.md contract rather
  than left as a convenience: the alternative was a copy, and the copy is what caused this ADR.
- **The 67% floor is a judgement, not a measurement.** Nothing has run the real model yet. The first
  real run may argue it up or down, and the ADR will say so when it does.

## Alternatives rejected

| Option | Why not |
|---|---|
| Leave the sanitiser comparing literal prompt strings | It is stricter than the guardrail, so it disables the neural path invisibly — the defect this ADR fixes |
| Drop the sanitiser and let AI-GRD be the only check | The guardrail passes a three-paragraph answer with markdown and a follow-up question. Those are not figure failures and would reach the screen |
| Copy `AllowedRenderings` into `:ml:llm` | Two definitions of the same rulebook row, drifting apart silently — this project has found that failure mode five times |
| Make the sanitiser kind-aware too | A second guardrail. The first one already works and is measured |
| Fine-tune the weights (LoRA) to the app's sentence shapes | A real option later, and a different kind of work: a training set, a GPU, a conversion back to `.task`, and a derived model this project would then have to license and host. Prompt tuning is reversible, reviewable, free, and lives in `ai/` where §6 says behaviour belongs |
| Measure fluency or helpfulness as well | Not what the model is for here. P-03 means it writes words around numbers it did not choose, and a benchmark of anything else would be a number nobody could act on |

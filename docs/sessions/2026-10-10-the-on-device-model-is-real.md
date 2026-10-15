<!--
  Why:  CLAUDE.md §10 — one session file per working session, holding the full reasoning the root
        records only point at.
  What: §19's on-device model, finally present: which runtime, which weights, why they are
        sideloaded, and the two things that had to change around it (a lint exemption and a prompt
        that lives in `ai/`).
  Result: a reader can see why a maintenance-only runtime was chosen over its own successor, and
          why the template verbaliser is still the default rather than a fallback.
  Changelog: 2026-10-10 — Created.
-->

# 2026-10-10 — The on-device model is real (ADR-0079)

On `dev` (`0e366b5`). Version 0.13.9 → **0.13.10** (versionCode 74). Schema unchanged.
Not an issue: it closes what ADR-0053's decision 5 recorded as absent.

---

## 0 · The question that started it

*"Is the AI local there or not? If not, add it — and take it from an open source repo."*

**It was not.** `:ml:llm` existed, `LlmEngine` existed, `:feature:chat` existed, the guardrail
existed and had been measured against a frozen set — and the thing behind the port was
`TemplateLlmEngine`: ten sentences in `strings.xml` with slots. ADR-0053 was honest about it
(decision 5: *"there is not one in this build, and there cannot be"*), but the README's
"AI-driven" read thicker than the code was. The hole was the size of one class.

---

## 1 · Decisions this session

### 1.1 · MediaPipe `tasks-genai`, pinned — over its own successor

Three runtimes were real options. The table is in
[ADR-0079](../adr/0079-the-on-device-model-is-real-and-its-weights-are-apache-2-0.md) §2; the
short version is that **LiteRT-LM is where Google's development is** (`litertlm-android` 0.18.0,
updated four days ago, twenty-five releases behind it) and MediaPipe's LLM Inference API is
explicitly **maintenance-only**. Choosing the deprecated one needs a reason.

The reason is the model file. The Apache-2.0 weights this app uses are published **in MediaPipe's
`.task` format by Google's own LiteRT community**; in LiteRT-LM's `.litertlm` format the same
weights exist only in third-party mirrors (`warped-community/*`), or via a conversion step the user
would have to run and trust. For an app whose central claim is that nothing about your money leaves
your device, "download 521 MB of weights from a stranger's mirror" is the wrong instruction to print
in a README.

A frozen API is a cost a solo project can carry. An unverifiable model file is not.

The bet is hedged rather than taken on faith: `MediaPipeLlmRuntime` implements an internal
`LlmRuntime` — prompt in, text out, no logic — so the migration is one class and one catalog line.
Nothing else in the app knows which library runs the model.

### 1.2 · Qwen2.5-0.5B-Instruct, on licence grounds before technical ones

§19's prose names a **Gemma-class** model and AI-ARC-007 repeats it. Gemma 3 1B would run as well
or better. It also comes with Google's own terms of use and a redistribution policy — and this
repository is AGPL-3.0 with a narrow linking exception, published *because* the privacy claim
cannot be verified from a store listing. Adding a model whose terms the reader then has to go and
check undoes some of that.

Apache-2.0 weights can be pointed at, redistributed, and shipped by anyone who forks this, with no
further permission. 0.5B at int8 is 521 MB and loads on a mid-range phone, which is the phone this
app is for. The deviation from "Gemma-class" is argued in the ADR rather than quietly taken.

### 1.3 · The weights are installed by a person, and that act is the consent

Three ways to get 521 MB onto a phone: bundle it (impossible — Play's limit is 200 MB), download it
in-app (a network call on a feature whose whole point is that the app does not make them), or have
the user install it.

The third one is also the cleanest consent story in the app. There is **no setting**:
`LlmModelStore.status()` reads the file system, and the presence of a verified file *is* the opt-in.
Nobody carries half a gigabyte onto their phone by accident, and that is a stronger signal than a
toggle in a settings screen nobody reads. It also means there is no fetch code that could later
grow a telemetry parameter.

What the store refuses matters as much: a wrong length (a stopped download — the likeliest failure)
and a wrong digest (a different or damaged file), because a half model fails *inside* the native
library in ways no Kotlin can explain to a user. A pass is remembered in a sidecar receipt keyed on
length and timestamp, so the 521 MB hash is not recomputed on every cold start — and replacing the
file invalidates the receipt, which a test proves by rewriting the bytes and restoring the
timestamp.

### 1.4 · The model gets rendered figures, not figures

This is the part that keeps P-03 true. `VerbalisationPrompt.of()` is a pure function that renders
every `ToolFigure` — amounts through `MoneyFormatter`, dates as ISO, counts as integers — and emits
the instruction, the intent, and one `- key: value` line per figure. **That is the whole of the
model's input**, which is why a unit test can assert it line by line: four lines plus the
instruction, and a figure with no value for its kind is dropped rather than rendered as `null`
(text a model would happily reuse).

Then `DraftSanitiser` holds the reply to those values: delete every supplied value from the text,
longest first, and if a digit remains the reply is refused. `₹61,000` next to a supplied
`₹61,427.00` is refused — a rounded figure is a different claim. So is `230 payments` when `23` was
supplied.

**One bug, found by test rather than in the wild.** The first `firstSentence` split on the first
`.` — which is inside every amount this app says. It truncated `₹61,427.00` to `₹61,427.`, the
figure check then refused the mangled sentence, and the neural path would have been silently dead
for every answer that mentions money. A full stop between two digits is not the end of a sentence;
the test for it is named after the regression.

### 1.5 · The template verbaliser stays the default, and three conditions say when it does not

`SelectingLlmEngine` uses the neural path only with verified weights, an English-language device,
and fewer than three consecutive failures.

- **Language** is not a hedge: the template's sentences are translated into Hindi, Kannada and Tamil
  (issue 10.8) and this model is English-tuned, so on a Hindi device the deterministic engine is the
  *better* answer, not the weaker one.
- **The failure count** exists because a wedged native library would otherwise cost every question
  its full 12-second timeout before falling back. A good reply resets it.
- **`id` reports which model actually answered.** It is read by the repository after `verbalise`
  returns, so it is deliberately mutable state behind a `@Volatile`: a provenance line naming a
  model that did not write the words is a lie, and P-02 is the entire reason that field exists.

On a phone with no weights the app behaves exactly as it did yesterday. That was the design
constraint, not a side effect.

### 1.6 · The prompt is data, and it is a *new* prompt

§6 says the AI's behaviour is data. So the instruction lives in `ai/chat/system-prompt.md`, in a new
**VERBALISER PROMPT** section, mirrored into `:ml:llm`'s assets with a drift test holding the two
together (ADR-0017's pattern — a copy nothing checks is a copy that drifts).

It is a new section rather than the existing system prompt because the existing one is written for a
model that plans and calls tools. **This model does neither** — the engine does both. The published
build carries a 1,280-token KV cache; handing it a 900-token instruction set it cannot act on would
spend the window on instructions and leave no room for the answer. The drift test also pins the two
clauses the sanitiser depends on ("never change" a value, nothing "not in the list") and asserts the
prompt stays under 1,600 characters.

### 1.7 · SEC-003's third exemption, by file name on purpose

Verifying the model file means `MessageDigest` in production code, which `CfoHandRolledCrypto`
(ADR-0063) bans outright. The exemption is one named file, `LlmModelStore.kt`.

The argument: there is no key, no secret, no ciphertext and no authentication claim — it is a
checksum against a digest the publisher published. Tink exposes no file-hashing API, so the
alternatives were hand-rolling an *alternative* (worse) or **loading unverified weights** (worse
still). The exemption buys a security control rather than spending one.

**By file name rather than by a content heuristic**, because `digest(key + message)` as a homemade
MAC would satisfy any "no key material in this file" test, and that is the exact construction
SEC-003 exists to stop. Three lint tests pin the shape: the named site is clean, the same code under
another name is flagged, and a `Cipher` at the named site is still flagged. `docs/security/`'s audit
gained a §6 recording the re-audit — triggered by its own §5, which said to re-audit when the rule
acquired a third exemption.

### 1.8 · `scripts/` is now a declared test input

`ModelManifestDriftTest` reads `scripts/install_llm_model.py` to hold the installer's manifest to
the app's. An undeclared runtime read is how this repository has four times had a drift gate that
Gradle held up to date (issues 7.2, 11.5, 11.7, 13.1), so `scripts` joined `CHECKED_DATA_PATHS` in
the same change rather than being the fifth.

### 1.9 · The runtime costs 109 MB, and the fix is a one-line bundle setting

Measured after the fact, because it was not obvious from a dependency line:
`libllm_inference_engine_jni.so` is 19–33 MB **per ABI**, and the universal release APK went from
62 MB to **171 MB**. The ML Kit OCR pipeline, the previous biggest thing in the APK, is 7–12 MB per
ABI by comparison.

`bundle { abi { enableSplit = true } }` is now stated in `app/build.gradle.kts` instead of left to
Gradle's default. The default is already `true`, which is exactly why it is worth writing down: the
difference between stated and assumed here is a 27 MB download and a 171 MB one, and the language
split immediately above it is set to `false` on purpose — someone reading that block could
reasonably conclude this project turns splits off.

Built and checked: AAB 77 MB, `assembleRelease` green through R8 and `lintVital`, and
`verify_release_log_stripping.py` still clean on the minified APK.

---

## 1b · Then it was tuned to the app, and measured (ADR-0080)

*"Fine tune it to match the requirements of the app, and test the accuracy in that direction only."*

### 1.10 · The sanitiser was stricter than the guardrail, which is the invisible kind of wrong

`DraftSanitiser` compared the reply against **the literal strings the prompt showed**. The prompt
shows `₹61,427.00`. A small model told that writes `₹61,427` about as often as not — and AI-GRD,
one step later, has accepted the whole-rupee short form since issue 9.7, because the app itself
writes amounts that way.

So the check guarding the gate was stricter than the gate. And the failure is not an error: a
refusal falls back to the template sentence, which is indistinguishable from a phone with no model
installed. Someone who had sideloaded 521 MB would have seen them used for almost nothing, and
**no test, log or screen would have said why.**

Fixed by publishing rather than copying. `AllowedRenderings` → `FigureRenderings.of(evidence,
rules)` in the guardrail module; `IntentRoutingChatEngine.evidenceOf` → `ChatEvidence.of(figures)`
in the chat module. One definition of `RULE-GRD-TRANSFORMS`, two readers.

The published set is **kind-blind on purpose**. The guardrail checks a claim against values of its
kind and must keep doing so; the sanitiser asks the weaker question that comes first — *is this
digit one the engines produced at all?* A sanitiser that enforced kinds too would be a second
guardrail, and a second guardrail is a second thing to drift.

Now accepted: `₹61,427`, `₹61,427.0`, `₹ 61,427.00`, `₹1.5 lakh` for exactly ₹1,50,000, `₹1,23,457`
for ₹1,23,456.78, `3 Nov 2026` for `2026-11-03`. Still refused: `about ₹61,000`, `₹2 lakh` for
₹1.5 lakh, `₹61427.00` (western grouping, which this app does not produce), and `₹1,23,456` for an
amount that has paise.

### 1.11 · The prompt now teaches with the app's own sentences

Four worked examples, lifted from `TemplateLlmEngine`'s `strings.xml` — those sentences *are* the
specification for how this app says each intent, so there was nothing better to teach from — plus
the rules the measured failures asked for: copy the ₹ and the commas, no derived per-week or
per-transaction figure, no follow-up question, second person.

Still data (§6). 1,514 characters, about 380 tokens of the shipped model's 1,280-token window. The
drift test's ceiling went from 1,600 to 2,000 with the token arithmetic written beside it, so the
next example added has to argue with that number rather than silently crowd the answer out.

### 1.12 · Accuracy here is two rates, and the second one is the point

`ai/eval/verbaliser-eval.json` — 46 cases, six kinds — measures **only** whether the reply states
exactly the figures it was given, in a form this app writes, in one sentence. Not fluency, not
helpfulness, not reasoning: none of those is what an L6 verbaliser does, and a score for them would
be a number nobody could act on.

| Rate | Floor | |
|---|---|---|
| `fidelity_refused_pct` | 100 | a drifted or invented figure reaching the user is the failure the app cannot survive |
| `faithful_accepted_pct` | 100 | a false refusal is invisible — the plainer sentence every turn, and the weights never used |

`GuardrailEvalTest` argues its false-block rate may be relaxed in a commit. This one starts at 100
because the defect it guards against had already happened, in §1.10.

### 1.13 · The set was written first, and it caught two things review had not

**First run: 39 of 43.** Both failures were real.

- **VE-S07 / VE-S08 were ACCEPTED.** Handed `- total: ₹61,427.00`, a model often replies with the
  list it was given. Every figure is correct, so the digit rule passes, and the guardrail after it
  passes — and the user is shown the app's own prompt scaffolding as an answer. It is not a figure
  failure at all, which is exactly why nothing caught it. New rule: two or more list lines, the
  prompt's scaffold words, or a sentence that does not start with a letter → `chat.draft.shape`.
- **VE-D02 / VE-D08 were refused for the wrong reason.** "about ₹2 lakh" for ₹1.5 lakh states no
  *permitted* figure, so the weaker "said nothing" rule fired first and called a misstated amount
  silence. The digit check now runs before the figure check. A case that passes by failing
  differently proves nothing, so the harness asserts the reason too.

**Second run: 43 of 43**, both rates 100%.

Then three more cases, for failure modes the first batch had not imagined. All three failed, which
is the only reason to add a case:

- **VE-S09** — the figure list read back *on one line*: `total: ₹61,427.00, count: 23`. The
  list-line rule cannot see it; every figure is correct. The shape rule now refuses a reply that
  reads a figure **key** back, which is the sharper version of the same idea.
- **VE-S10** — a stray `Answer:` label. Stripped, not refused: the sentence under it is right.
- **VE-R08** — `23 transactions, ₹61,427.00 spent.` was refused, because the first shape rule
  demanded a letter first. That is a true, terse sentence, and refusing it is §1.10's mistake made
  a second time in the same session. The letter rule is gone.

**Final: 46 of 46** — `drift 8/8 · faithful 10/10 · invented 6/6 · rendering 8/8 · shape 10/10 ·
silent 4/4`. Wired into `aiEval`, which now names six frozen sets, and into
`./gradlew verbaliserEval` for running it alone.

### 1.14 · The real model's own accuracy is a device measurement, and it is honest about that

The frozen replies are **hand-written** in the style a 0.5B model produces — they are not samples
from the shipped weights, because no device in this session had them. The `_meta` says so in as
many words, because a frozen set that implies it was sampled from the model is a lie that gets
quoted later.

`OnDeviceVerbaliserAccuracyTest` is the real measurement: the same intents and figures asked of the
real runtime, the acceptance rate printed, floor **67%**, no expected text (a model's wording is its
own; asserting it would be asserting a sampler). It `assumeTrue`s the weights, so it skips on every
CI runner. The floor is a judgement, not a measurement — the first real run may argue it either way.

### 1.15 · What was not done: no weights were fine-tuned

"Fine tune" was read as *tune the verbaliser to the app's requirements*, not *train the weights*.
LoRA on Qwen2.5-0.5B is a real option and a different kind of work — a training set, a GPU, a
conversion back to `.task`, and a derived model this project would then have to license and host.
Prompt tuning is reversible, reviewable, free, and lives in `ai/` where §6 says behaviour belongs.
It is recorded as a rejected alternative in ADR-0080 rather than left as an implied promise.

---

## 1c · And a design pass on the app itself

Asked for a friendlier design for the app, expressed in HTML, plus a skill to carry it.

### 1.16 · v2 is a hierarchy change, not a repaint

The Material 3 trust-teal seed (`#00696E`), every semantic role and Roboto all stay — changing the
seed would invalidate the token set and every Paparazzi baseline for a preference, which is not a
trade worth making. What v1 actually got wrong was **what the screen leads with**: the dashboard put
net worth, Safe-to-Spend, budgets, insights and a forecast at the same weight, so none of them
arrived first.

Seven moves, in `docs/design/design-language-v2.html` as six phone screens on demo data: one answer
per screen · a meaning line under every figure, built from the same engine result ("₹1,180 a day
until 31 October, after rent, EMIs and your emergency floor") · the work as an **inline** disclosure
with the rule id and version, because v1's separate evidence screen was a screen nobody reached ·
pace drawn as one bar carrying spent, the even-pace marker and the overshoot · one filled button per
screen, 44dp chips instead of dropdowns · trust in the chrome (`offline · all local`, consent rows
showing grant *and* revoke dates, the erase stating what it cannot reach) · two surface levels and
hairlines instead of shadows.

The page doubles as the token table, so there is one place to read the figure sizes and radii.

### 1.17 · The skill exists so the next screen is not designed from scratch

`.claude/skills/screen-design/SKILL.md` applies the spec: the seven moves in order, the
non-negotiables that outrank any preference (colour never alone, provenance within one tap, every
string a resource, money through `MoneyFormatter`, no fixed heights), how to build a screen against
`CfoTheme`, an eight-point review checklist, and the rule that changing a token means editing
`Color.kt`/`Type.kt`/`CfoDimens.kt` **and** the spec page in the same commit — two copies of a token
drift, which this repository has now watched happen five times elsewhere.

Deliberately narrow: it decides what a screen says and in what order. The state/event plumbing and
the Paparazzi test stay with the existing `compose-ui` skill.

**Not built:** no Compose code changed. This is a spec and a skill; implementing it is per-screen
work behind the existing screenshot baselines.

---

## 2 · Flow changed this session

§2.17's verbalise step was one line and is now a branch:

```
ChatRepository.ask()
└─ LlmEngine.verbalise(intent + results)                :ml:llm — ADR-0079 picks the model
   SelectingLlmEngine                      verified weights + English + < 3 failures?
     ├─ yes → OnDeviceLlmEngine            id = on-device:qwen2.5-0.5b-instruct-q8
     │          VerbalisationPrompt.of()   intent + ALREADY-FORMATTED figures (pure, P-03)
     │          MediaPipeLlmRuntime        local file, no network, 12s cap (P-01, P-04)
     │          DraftSanitiser             one sentence; no digit that was not supplied
     │            Err → the template answers this turn
     └─ no  → TemplateLlmEngine            strings.xml, translated, offline — id = template
              ⇡ LlmModelStore.status(): Absent | Rejected(length/digest) | Ready
```

Out of band, and deliberately not in the app:

```
scripts/install_llm_model.py
└─ download_url() → curl/urllib → verify(length, SHA-256)
   └─ adb push → /data/local/tmp → run-as <app> cat > files/models/<name>.task
```

---

## 3 · Code changed this session

| Path | What it does now |
|---|---|
| `ml/llm/LlmModelStore.kt` | **New.** `LlmModelManifest.PUBLISHED` (name, 546,660,344 bytes, SHA-256, Apache-2.0, source) and the check: `Absent` / `Rejected(WRONG_LENGTH\|WRONG_DIGEST)` / `Ready`, with a sidecar receipt so the hash runs once |
| `ml/llm/VerbalisationPrompt.kt` | **New.** Pure: draft → prompt + the list of values the reply may contain. `null` when there is nothing to say |
| `ml/llm/DraftSanitiser.kt` | **New.** Pure: strips decoration, keeps the first sentence (a decimal point is not a terminator), refuses an unsupplied digit, a figure-free reply, an empty one and an essay |
| `ml/llm/LlmRuntime.kt` | **New.** The one-method seam the MediaPipe → LiteRT-LM migration will replace |
| `ml/llm/MediaPipeLlmRuntime.kt` | **New.** Lazy `LlmInference` over the verified path, a session per call, topK 1 / temperature 0, one worker thread, 30s first call / 12s after. Untested on purpose, like `MlKitReceiptTextRecognizer` |
| `ml/llm/OnDeviceLlmEngine.kt` | **New.** prompt → runtime → sanitiser. Never asks the model about a draft with no figures |
| `ml/llm/SelectingLlmEngine.kt` | **New.** Which model answers, and `id` reporting which one did |
| `ml/llm/VerbaliserInstruction.kt` | **New.** Cached read of the mirrored prompt asset |
| `ml/llm/LlmEngineFactory.kt` | **New.** The ARC-003 seam `:app` binds; the only place the model choice is made |
| `ml/llm/TemplateLlmEngine.kt` | Unchanged code; the doc no longer says this build has no neural model |
| `ml/llm/assets/chat/verbaliser-prompt.txt` | **New.** The mirror of `ai/chat/system-prompt.md`'s new section |
| `ai/chat/system-prompt.md` | **New VERBALISER PROMPT section** — what the on-device model is told, as data |
| `app/di/RepositoryModule.kt` | `provideLlmEngine` → `LlmEngineFactory.create(context)` |
| `app/build.gradle.kts` | versionCode 74, and `bundle { abi { enableSplit = true } }` stated explicitly — the runtime adds 109 MB of native library across four ABIs |
| `domain/engines/guardrail/FigureRenderings.kt` | **New (ADR-0080).** Publishes `AllowedRenderings`' allowlist, so the sanitiser is never stricter than the guardrail |
| `domain/engines/chat/ChatEvidence.kt` | **New (ADR-0080).** The figure→evidence mapping, lifted out of `IntentRoutingChatEngine` so both layers read one copy |
| `ml/llm/VerbalisationPrompt.kt` | Carries the accepted renderings as well as the canonical values |
| `ml/llm/DraftSanitiser.kt` | Judges figures by `FigureRenderings`; new `chat.draft.shape` rule; digit check before the figure check |
| `ai/eval/verbaliser-eval.json` | **New.** 46 frozen cases measuring verbalisation fidelity in both directions |
| `ml/llm/VerbaliserEvalTest.kt` | **New.** The harness and the two rates; prints the per-kind report |
| `ml/llm/androidTest/OnDeviceVerbaliserAccuracyTest.kt` | **New.** The real model on a real device, skipped without weights |
| `build.gradle.kts` | `aiEval` now names six frozen sets; `:ml:llm` gains `verbaliserEval` |
| `domain/engines/guardrail/ENGINE.md` | The new public API, argued in the contract, with a version-log row |
| `lint/HandRolledCryptoDetector.kt` | A third argued exemption: `MessageDigest` at one named integrity site |
| `build-logic/.../ProjectExtensions.kt` | `scripts` added to `CHECKED_DATA_PATHS` |
| `gradle/libs.versions.toml`, `ml/llm/build.gradle.kts` | `mediapipe-tasks-genai` pinned at 0.10.35 |
| `scripts/install_llm_model.py` + `test_install_llm_model.py` | **New.** Fetch, verify, push — and 12 tests over the URL shape, the verdicts and the adb sequence |
| `docs/security/sec-003-crypto-audit.md` | §2 gains the new call site; **§6** records the re-audit |
| `docs/design/design-language-v2.html` | **New.** The v2 design language — six screens, the token table, the seven moves |
| `.claude/skills/screen-design/SKILL.md` | **New.** How to design or review a screen against that spec |
| `docs/design/v2-prototype.html` | **New.** A clickable prototype: 40 screens on one seeded ledger, a stack per tab, every screen reached from the content that justifies it and checked by `verify.js` |
| `docs/design/verify.js`, `checks.js` | **New.** The reachability, back-stack and no-shelf checks — 15 of them, run with `node verify.js` from `docs/design/` |
| `NOTICE` | Two attributions added — the runtime (Apache-2.0, genuinely open source, so it needs no part of the linking exception) and the weights (Apache-2.0, in neither the repository nor the APK) |
| `DECISIONS.md`, `FLOW.md`, `CHANGELOG.md`, `VERSION`, `docs/releases.md` | The records for 0.13.10 |

**Tests:** 68 new in `:ml:llm` — model store, prompt, sanitiser, on-device engine, selection, two
drift tests, and the six-test fidelity harness over 46 frozen cases — plus 3 in `:lint`, 12 in
`scripts/`, and two device-side accuracy tests that skip without weights. `:ml:llm` now has **76**
unit tests, and `./gradlew unitTests aiEval` is green.

---

## 4 · What is not done

- **`MediaPipeLlmRuntime` has never run.** There is no device or emulator in this session, and the
  weights are a 521 MB download. Everything around it is tested against a fake; the native call
  itself — model load, session creation, the API shape of `tasks-genai` 0.10.35 beyond compiling —
  is unexercised. The `/run` and `/verify` gates (§9) are **owed** before this is called done.
- **No screenshot or UI test changed**, because no UI changed: `:feature:chat` renders whatever the
  reply says and already shows the model id.
- **The real model's accuracy is still unmeasured.** ADR-0080 built both halves of the measurement;
  only the half that does not need weights has run. `OnDeviceVerbaliserAccuracyTest` will print the
  first real number, and the 67% floor is a judgement until it does.
- **The frozen replies are hand-written, not sampled from the model.** Said plainly in the set's own
  `_meta`. Replacing them with recorded model output is a better set and needs a device.
- **ADR-0077 and ADR-0078 have no `DECISIONS.md` index row.** Found while adding 0079's; pre-existing
  and left alone rather than summarised second-hand.

<!--
  Why:  CLAUDE.md §5 — §19's default engine is an on-device compact instruction model, ADR-0053
        shipped a template verbaliser in its place, and this is the record of the real one arriving.
  What: which runtime, which weights, how they reach the device, and what stops the model being
        trusted with a figure.
  Result: a reader can see why the weights are sideloaded rather than bundled or downloaded, why
          Qwen2.5 and not Gemma, and why the template verbaliser is still the default path.
  Changelog: 2026-10-10 — Created.
-->

# ADR-0079 — The on-device model is real, and its weights are Apache-2.0

**Status:** Accepted · **Date:** 2026-10-10 · **Extends:** [ADR-0053](0053-chat-plans-tools-and-the-model-only-gets-words.md), [ADR-0063](0063-sec-003-is-enforced-by-a-lint-rule-with-two-argued-exemptions.md) · **SRS:** §19 (§19.1, §19.4), §7.1 (AI-ARC-004, AI-ARC-007), §6 · **Rules:** P-01, P-03, P-04, P-08, P-02, SEC-003, MNY-001 · **Issue:** 10.5

## Context

§19 names the assistant's default engine: *an on-device compact instruction model*. ADR-0053 could
not ship one and said so plainly — the weights are hundreds of megabytes that cannot live in a
repository, and AICore needs hardware most of the phones this app targets do not have. What shipped
was `TemplateLlmEngine`: ten fixed sentences from `strings.xml`, every slot filled from a tool
figure. It works offline on a cheap phone, it is deterministic, and it is honest.

It is also the reason the README's "AI-driven" claim was thinner than it read. The pipeline that
ADR-0053 built — plan, execute tools, verbalise, guardrail — had a model-shaped hole in step three
with a `when` over ten templates in it. Everything needed to put a real model there existed: the
port (`LlmEngine`), the guardrail (AI-GRD), the registry, the executor. Nothing had.

## Decision

### 1 · A real model goes behind the existing port, and changes nothing above it

`OnDeviceLlmEngine` implements the same one-method `LlmEngine` interface the template verbaliser
does. `AI-CHAT` still plans, the repository still executes the tools, AI-GRD still checks every
figure. The model is handed `VerbalisationDraft` — the intent and the tool figures, and nothing
else (§19.4, P-01) — and asked for a sentence.

**The model still cannot be the source of a number.** It is not given anything to compute: the
figures arrive already rendered, Indian grouping and all (`₹1,23,456.78`), by a pure function
(`VerbalisationPrompt`) that a test asserts over character by character. Its reply then passes two
checks, in this order: `DraftSanitiser`, which refuses any digit that is not inside a value the
prompt supplied, and then AI-GRD, which does what it always did. A rounded figure is refused as
firmly as an invented one — `₹61,000` is a different claim from `₹61,427.00` (P-03).

### 2 · MediaPipe's LLM Inference API, pinned, behind one more seam

`com.google.mediapipe:tasks-genai:0.10.35` (Apache-2.0) runs the weights. Two alternatives were
real:

- **LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android:0.18.0`) is Google's successor to it
  and is where the development is. It is also pre-1.0 with twenty-five releases behind it, and the
  Apache-2.0 model in its `.litertlm` format is published by **third-party mirrors**, not by
  Google's own LiteRT community — so taking it would have meant either trusting a mirror or asking
  every user to run a conversion.
- **llama.cpp** (MIT) is the most open option and would read the widest range of weights. It
  publishes no Android artifact, so it means an NDK build and a JNI layer this project would then
  own and have to keep building for four ABIs.

MediaPipe was chosen for one reason that outweighs its maintenance-only status: **the Apache-2.0
weights are published in its format by the people who built the runtime**, so nothing has to be
converted or trusted on the way. A frozen API is a cost a solo project can carry; an unverifiable
model file is not.

That is a bet, so it is hedged. `MediaPipeLlmRuntime` implements an internal `LlmRuntime` interface
— prompt in, text out — with no logic of its own. Moving to LiteRT-LM is one new implementation of
that interface and one catalog line. Nothing else in the app knows which library runs the model.

### 3 · Qwen2.5-0.5B-Instruct, because the licence is the first question anyone asks

`Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task`, 521 MB, from
[litert-community](https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct), **Apache-2.0**.

A Gemma-class model of the same size would run just as well and is the one §19's prose names. It
comes with Google's own terms of use and a redistribution policy, and this repository is AGPL-3.0
with a narrow linking exception — the licence page is the first thing a privacy-first finance app
gets read on. Apache-2.0 weights can be pointed at, redistributed and shipped by anyone who forks
this, with no further permission. The model is also small enough to load on a mid-range phone,
which is the phone this app is for.

### 4 · The weights are installed by a person, not bundled and not downloaded

521 MB cannot go in an APK (Play's limit is 200 MB), and the app **does not download them**. A core
path may make no network call (P-04), and a finance app that reaches out on its own is precisely
what P-01 promises it will not do — so there is no fetch, no progress bar, no consent screen for
one, and no code that could grow one.

Instead `scripts/install_llm_model.py` downloads the file on a machine with a connection, verifies
it, and pushes it into app-private storage. **The presence of a verified file is the opt-in.** There
is no setting to flip: putting half a gigabyte on the device is a deliberate act and a more honest
signal of consent than a toggle nobody reads.

`LlmModelStore` then refuses anything that is not the published model — wrong length (a stopped
download), wrong digest (a different or damaged file) — because a half model fails inside the native
library in ways no Kotlin can explain to a user. A pass is remembered in a sidecar receipt keyed on
length and timestamp, so the 521 MB hash is not recomputed on every cold start.

### 5 · The template verbaliser is still the default, and is not a fallback in the apologetic sense

`SelectingLlmEngine` uses the neural path when three things hold: verified weights, an
English-language device, and no recent run of failures. Otherwise the template answers.

- **Language.** The template's sentences are translated into Hindi, Kannada and Tamil (issue 10.8).
  This model is English-tuned. On a Hindi device the deterministic engine is the *better* answer, not
  the weaker one.
- **Failures.** A wedged native library would otherwise cost every question its full timeout before
  falling back, so after three failures the process stops asking. A good reply resets the count.
- **Provenance.** `LlmEngine.id` reports the model that actually wrote the reply — `template` or
  `on-device:qwen2.5-0.5b-instruct-q8`. A provenance line naming a model that did not answer is a
  lie, and P-02 is the whole reason that field exists.

On a phone with no weights the app behaves exactly as it did before this ADR. That is the point: the
second model must not make the first one worse, and most phones will only ever have the first.

### 6 · The prompt is data, like every other thing the AI is told

§6 says the AI's behaviour is data, not code. The instruction the model is given therefore lives in
`ai/chat/system-prompt.md`, in a new **VERBALISER PROMPT** section, and is mirrored into
`:ml:llm`'s assets with `VerbaliserPromptDriftTest` holding the two together — ADR-0017's pattern,
because a copy nothing checks is a copy that drifts.

It is a *new* section rather than the existing system prompt because the existing one is written for
a model that plans and calls tools. This model does neither; the engine does both. Handing a 0.5B
model with a 1,280-token context window a 900-token instruction set it cannot act on would spend the
window on instructions and leave no room for the answer.

### 7 · SEC-003 gets a third exemption, argued and narrow

Verifying the model file means a `MessageDigest` in production code, which `CfoHandRolledCrypto`
(ADR-0063) bans. The exemption is one named file, `LlmModelStore.kt`, and the argument is in
`docs/security/sec-003-crypto-audit.md` §6: there is no key, no secret and nothing being protected
— it is a checksum against a digest the publisher published, Tink has no API for it, and the
alternative is loading unverified weights, which is worse for security than the rule it would
satisfy. By file name rather than by a content heuristic, because `digest(key + message)` as a
homemade MAC would satisfy any "no key material here" test and that is the construction SEC-003 most
needs to stop.

## Consequences

**Good.** §19's default engine exists. The assistant can answer in sentences shaped for the figures
at hand instead of ten fixed forms, without being trusted with a single digit. The migration to
LiteRT-LM is one class. The weights are Apache-2.0, so a fork inherits no licence problem.

**Accepted costs.**

- **A maintenance-only runtime.** MediaPipe's LLM Inference API will not gain features. Mitigated by
  the pin, the `LlmRuntime` seam, and the fact that Maven artifacts do not disappear.
- **The runtime is 109 MB of native library.** `libllm_inference_engine_jni.so` is 19–33 MB per
  ABI, and it took the universal release APK from 62 MB to **171 MB** (measured, not estimated).
  The *download* does not grow by that much: Play splits an App Bundle by ABI, so an arm64 device
  takes about 27 MB more, and the AAB itself is 77 MB. `bundle { abi { enableSplit = true } }` is
  now stated explicitly in `app/build.gradle.kts` rather than relied on as a default, because with
  it off the download would be the 171 MB. The universal APK remains a local artefact for the
  log-strip gate.
- **Most users will never see it.** The weights are a deliberate 521 MB install and MediaPipe's own
  guidance is that the API is tuned for high-end phones. This is the honest shape of on-device
  inference in 2026, and it is why §5's default stays the template.
- **The neural path is not bit-reproducible.** Temperature and top-k are pinned at their least
  creative settings, but a language model is not a deterministic engine and P-08 is not claimed for
  it. That is exactly why `DraftSanitiser` and AI-GRD both run on its output, and why the golden
  chat tests run against the template engine.
- **One untested class.** `MediaPipeLlmRuntime` needs a device, and is left untested for the same
  reason `MlKitReceiptTextRecognizer` is. Every decision around it is tested against a fake.
- **A third crypto exemption**, with the re-audit recorded rather than the rule quietly widened.

## Alternatives rejected

| Option | Why not |
|---|---|
| Bundle the weights in the APK | 521 MB against Play's 200 MB limit. Not possible, not only unwise |
| Download them in-app, behind a consent | A network call the app does not need, on a feature that is meant to prove the app does not make them (P-01, P-04). The script does it on a machine that is already online |
| Gemma 3 1B instead | Runs equally well; its licence terms would be the most-read page in an AGPL repository that promises privacy. Apache-2.0 removes the question |
| LiteRT-LM now | Pre-1.0 with heavy API churn, and the Apache-2.0 weights in its format come from third-party mirrors. Revisit when Google publishes them |
| llama.cpp via JNI | Most open, widest format support, and an NDK build plus four ABIs for this project to own |
| Let the model see the ledger and do its own arithmetic | P-03. The entire architecture above this layer exists to make it structurally impossible |
| Drop the template verbaliser once the model works | It is the path for every phone without weights, every non-English device, and every failure. Deleting it would make the assistant worse for almost everyone |

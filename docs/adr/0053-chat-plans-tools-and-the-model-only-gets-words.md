# ADR-0053 — The assistant plans with data, reads through tools, and the model only ever gets words to write

- **Status:** accepted
- **Date:** 2026-09-27
- **Deciders:** Harish G (solo)
- **SRS refs:** §19 (§19.1 pipeline, §19.2 tool registry, §19.3 behaviour rules CHT-001..005, §19.4
  context pack), §7.1 (AI-ARC-004, AI-ARC-007), §6 (data, not code), P-01, P-03, P-04, P-07, P-08,
  MNY-001, §21.6, ADR-0017 (mirrors and drift tests), ADR-0048 (AI-GRD, which this layer exists
  behind); issue 10.5.

## Context

§19 asks for a chat assistant that is "a narrow financial copilot over the user's own, on-device
financial data" — not a general chatbot. The danger is specific and well known: a language model
will state a confident wrong rupee figure, and a finance app that does that once is finished.
Issue 9.7 already built the numeric guardrail (AI-GRD) for exactly this moment; nothing had asked
it anything yet.

Two things had to be decided that the SRS does not settle: **what the model is allowed to be**, and
**what happens when the model the SRS names does not exist in this build**.

## Decision

**1. The pipeline is plan → execute → verbalise → verify, and the engine owns the first and last.**
`AI-CHAT` decides *which* registry tools to call; the repository executes them (only a repository
may read this household's data, ARC-005); the model is handed the results and asked for words; and
the engine puts those words through AI-GRD before anything is shown. The model is structurally
incapable of being the source of a figure — not by instruction, but because the only numbers in the
allowlist are the ones the tools returned.

**2. Routing is keyword matching over the registry, and the routing table is data.**
The intents, their keywords, their tools and their chips moved into `tool-registry.json` (1.0 →
1.1), beside two routing numbers and the out-of-scope triggers. It was going to be a `when` in an
engine, and a `when` is not something a reviewer can audit, a translator can extend, or a
non-engineer can change. Boring and inspectable beats clever here: a question that reaches the
wrong tool is a wrong answer however fluent it reads.

**3. The out-of-scope check runs *before* the intent match.**
"Can I afford to file my taxes with a lawyer?" contains an intent keyword **and** two refusal
triggers. Matching first would answer the half it understands — which is the dangerous half
(CHT-002). A test pins the order.

**4. A turn may call at most two tools.**
`max_tools_per_turn` is in the registry. One question must not walk the whole registry and read
five tools' worth of a household's data because it happened to mention five things.

**5. The model the app ships with is a deterministic template verbaliser, and that is the offline
path rather than a stand-in.**
§19 names an on-device compact instruction model (Gemma-class via MediaPipe/AICore). There is not
one in this build, and there cannot be: the weights are hundreds of megabytes that cannot live in a
repository, and AICore needs hardware most of the phones this app targets do not have. So `:ml:llm`
ships `TemplateLlmEngine` — one sentence per intent, from string resources, every slot filled from
a tool figure. It is what makes the assistant work in airplane mode on a cheap phone (P-04), and it
is what a neural model would *replace*, not what it would make redundant: the port, the guardrail,
the registry and the executor all stay exactly as they are.

**6. The sentences are string resources, so the assistant can be translated.**
The templates live in `:ml:llm`'s `strings.xml` with ICU plurals — which is why `:ml:llm` is an
Android library rather than a pure-Kotlin one. Issue 10.8's Hindi pass therefore reaches the
assistant's own words and not only the screens' chrome.

**7. Refusals are keys, not sentences.**
An engine that returned "I can't help with that" would be writing user-visible text in the wrong
layer (§21.6) and in one language. `RefusalReason` has five entries and the feature module has five
strings; a `when` makes a new reason fail to compile until it has words.

**8. A blocked reply shows nothing, and carries no figures.**
When AI-GRD refuses, the reply is dropped **with its figures** — including the ones the tools really
did return — so no screen can reassemble the sentence the guardrail refused. The screen says which
kind of refusal it was, rather than a vague apology: the user is entitled to know the app caught
itself, and "sorry, something went wrong" is what a crash says.

**9. The conversation is stored, excluded from backups, and hard deleted (CHT-004).**
Schema 28's `chat_message` is the one profile-scoped table the archive deliberately skips, and the
only one with no tombstone column: a soft-deleted turn would keep the user's words and the app's
answer in the table after they asked for both to go. Both exemptions are argued in the places the
invariants live (`ProfileSnapshot.EXCLUDED` and `MigrationSafetyTest`'s exemption map).

**10. A stored turn keeps the words, not the figures.**
A figure verified against today's data cannot honestly be re-shown next month without being checked
again. The conversation is a record of what was said, not a cache of what is true.

**11. `get_vehicle_status` was added to the registry rather than routed around.**
The VEHICLE intent was first pointed at `get_forecast`, which answers a different question — "when
is the car service due?" would have come back with the ninety-day low. Detekt found the symptom (an
unused executor function); the cause was a routing table describing a capability the app did not
have. Issue 10.4 shipped AI-VEH, so the honest fix was a registry entry, not a `when`.

## Deferred, and why

- **A neural on-device model.** The port, the prompt (`ai/chat/system-prompt.md`), the guardrail and
  the registry are all in place; what is missing is weights and a runtime. It is a binding change.
- **Cloud assist.** `VerbalisationDraft` **is** §19.4's context pack — the whole payload a cloud
  model would ever receive, which is why it is a type rather than a string built at a call site. The
  transport, the consent wiring to the existing "Ask a cloud assistant" switch, and CHT-005's
  per-call log are not built. Nothing leaves the device today.
- **Nine of the fifteen tools.** `query_spend`, `get_balance`, `get_forecast`, `get_goals`,
  `get_health_score`, `review_buylist` and `get_vehicle_status` are executed. `get_budget_status`,
  `purchase_check`, `explain_insight`, `simulate`, `create_txn_draft`, `market_status`,
  `opportunity_check`, `add_to_buylist` and `convert_currency` fail honestly — the assistant says it
  cannot answer rather than approximating. Two of them (`purchase_check`, `simulate`) need an amount
  parsed out of the question, which is its own piece of work and its own way to be wrong.
- **`query_spend`'s transaction count.** It reads AI-STS's own `SPENT` line, so the figure the
  assistant states is the one the dashboard shows. Nothing publishes a transaction count as an
  engine result, so the two-slot sentence falls back to the one that claims nothing rather than
  counting rows in the chat layer (§19.2, P-03).
- **Multi-turn context.** Each question is planned on its own. "And last month?" is not understood,
  and a follow-up that silently inherited the wrong context would be worse than a refusal.
- **Regeneration.** AI-GRD's `Regenerate` verdict is treated as a block. Asking the model again is
  only useful once there is a model whose second attempt might differ.

## Consequences

- The assistant can answer seven kinds of question about the household's own data, and every figure
  it states was computed by an engine that publishes the same figure elsewhere in the app.
- A fabricated figure cannot reach the user: six deliberate mutations of the engine were each
  watched go red, including removing the guardrail call and collapsing the figure kinds.
- What the assistant can ever see is one type in one file — the executor — which is the surface a
  reviewer reads to answer "what does chat have access to?".
- Teaching it a new phrase, or a new intent, is an edit to `tool-registry.json` and its mirror,
  held together by a drift test with the file declared a test input.

# AI-CHAT — the assistant's planner and composer

> `:domain:engines:chat` · version **1.0** · layer **L6** · issue 10.5 · SRS **§19** ·
> [ADR-0053](../../../docs/adr/0053-chat-plans-tools-and-the-model-only-gets-words.md)

## Contract

One public interface, two calls — deliberately separated, because they fail differently.

```kotlin
ChatEngineFactory.create().plan(ChatRequest)         : Result<ChatPlan, AppError>
ChatEngineFactory.create().compose(ChatComposition)  : Result<ChatReply, AppError>
```

**`plan`** — in: the user's text and a clock reading. Out: the matched `ChatIntent`, the `ToolCall`s
to run, the chips to offer, and a `RefusalReason` when the app will not answer. `Err` only for empty
input (`chat.text`).

**`compose`** — in: the plan, what the tools returned, the model's draft and which model wrote it.
Out: a `ChatReply` that is either **answered** (checked text, its figures, its citations) or carries
a refusal and nothing else.

The model sits behind a second interface in this module:

```kotlin
interface LlmEngine { val id: String; fun verbalise(draft: VerbalisationDraft): Result<String, AppError> }
```

`VerbalisationDraft` is §19.4's context pack: the intent, the tool results, the attempt number. It
is a type rather than a string built at a call site because **it is the whole payload a cloud model
would ever receive** (P-01).

## How a turn works

```
question → plan()  ── refusal? ──────────────────────────────→ reply with no words, chips kept
             │
             └─ tool calls → (repository executes them) → results
                                                            │
                                    LlmEngine.verbalise(intent + results)  ← never the ledger
                                                            │
                               compose() → AI-GRD.verify(draft, allowlist from results)
                                                            │
                                      Pass → the words          anything else → GUARDRAIL_BLOCKED
```

### Routing

Keyword matching over `ai/skills/tool-registry.json`'s `intents`. **The out-of-scope check runs
first** — "can I afford to file my taxes with a lawyer?" contains an intent keyword and two refusal
triggers, and answering the half it understands would be answering the dangerous half (CHT-002).
The best match is the intent with the most keyword hits; ties keep registry order. At most
`max_tools_per_turn` (2) tools are called, so one question cannot walk the whole registry.

### The allowlist

`ToolFigure`s become `GuardrailEvidence`, **split by kind**. A count of 12 and ₹12 are different
claims; collapsing them would let a model state one as the other, and a test pins it.

### Refusals

| Reason | When |
|--------|------|
| `OUT_OF_SCOPE` | CHT-002 — stock tips, tax filing, legal advice. Reads no data at all. |
| `NOT_UNDERSTOOD` | Nothing in the registry matched. Chips are still offered. |
| `NO_DATA` | A tool could not answer. Better than a zero. |
| `NO_MODEL` | The model was unavailable, so there are no words to check. |
| `GUARDRAIL_BLOCKED` | AI-ARC-004 fired. The reply is dropped **with its figures**. |

They are enum entries, not sentences: §21.6 keeps user-visible words in `strings.xml`, and a
refusal written by an engine could not be translated.

## Assumptions

- **A question is planned on its own.** There is no multi-turn context; "and last month?" is not
  understood, and a follow-up that silently inherited the wrong context would be worse than a
  refusal (ADR-0053).
- **`Regenerate` is treated as a block.** Asking again is only useful once there is a model whose
  second attempt might differ.
- **The engine cannot execute anything.** It names tools; a repository runs them (ARC-005). That
  separation is what makes "tools are the only way chat touches data" checkable.

## Data it reads

`ai/skills/tool-registry.json` **v1.1**, mirrored as `ToolRegistry.BUNDLED` and held to the file by
`ChatRegistryDriftTest` — with the file declared a test input in `build.gradle.kts`, without which
Gradle leaves the gate `UP-TO-DATE` and it passes without running (issue 10.4's lesson).

## Tests

| Suite | What it holds |
|-------|---------------|
| `ChatEngineTest` (17) | routing, every intent reachable, both refusal orders, the tool cap, the guardrail blocking an invented figure, a blocked reply carrying nothing, kinds not collapsing, provenance, determinism |
| `ChatGoldenTest` (1) | fourteen questions, line for line against `golden/chat_oracle.py` — an **independent** Python implementation of the routing from the registry |
| `ChatPropertyTest` (6 × 300) | the tool cap; only declared tools; a refusal reads nothing; **an invented figure is never answered**; a refusal never carries a figure; determinism |
| `ChatRegistryDriftTest` (9) | every tool and access level, every intent's tools/keywords/chips, the routing numbers, the out-of-scope triggers, that no tool writes without the user, and that CHT-001/002 still say what the code assumes |

Six deliberate mutations were each watched go red: skipping the guardrail, checking out-of-scope
after the intent match, removing the tool cap, treating a failed tool as empty, keeping figures on a
blocked reply, and collapsing the figure kinds. Three registry drifts were watched go red too.

## Version log

| Version | Issue | What changed |
|---------|-------|--------------|
| 1.0 | 10.5 | Created. Registry-driven routing, refusal-first ordering, the two-tool cap, the `LlmEngine` port, and AI-GRD between every draft and the user. |

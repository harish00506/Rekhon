<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 10.5 — AI-CHAT, §19's assistant: intent → registry tools → a guardrailed reply.
  Result: a reader can see why the model is structurally unable to be the source of a number, why
          the routing table is data, and what the device run caught that 4,800 tests did not.
  Changelog: 2026-09-27 — Created.
-->

# 2026-09-27 — Ask your CFO (issue 10.5, ADR-0053)

**Branch:** `feature/10-5-chat-assistant-on-device-llm-tool-registry` off `dev` (`8d57e64`)
**Versions:**
- **VERSION** 0.10.3 → **0.10.4**
- **versionCode** 47 → 48
- **Schema** 27 → **28** (`chat_message`)
- **tool-registry.json** 1.0 → **1.1** (routing as data; the new `get_vehicle_status`)
- `AI-CHAT` 1.0 (new)

---

## 1 · Decisions this session

The full argument for each is in ADR-0053.

- **The model cannot be the source of a number, structurally.** The turn is plan → execute →
  verbalise → verify: the engine names the tools, a repository runs them, the model is handed only
  the results, and AI-GRD checks every figure in its words against those same results. Not an
  instruction to the model — an allowlist it never gets to write.
- **Routing is data.** Intents, keywords, chips, the two-tool cap and the out-of-scope triggers
  moved into the registry (1.1). It was going to be a `when`, and a `when` is not a table a reviewer
  can audit or a translator can extend.
- **The out-of-scope check runs before the intent match.** "Can I afford to file my taxes with a
  lawyer?" contains an intent keyword and two refusal triggers; matching first would answer the
  dangerous half (CHT-002).
- **A turn may read at most two tools**, so one question cannot walk the registry.
- **The model that ships is a deterministic template verbaliser, and that is the offline path** —
  not a stub. §19 names a Gemma-class on-device model; the weights cannot live in a repository and
  AICore needs hardware this app's users largely do not have. The port, the guardrail, the registry
  and the executor all stay exactly as they are when a neural model arrives.
- **Its sentences are string resources**, which is why `:ml:llm` is an Android library: 10.8's Hindi
  pass has to reach the assistant's own words, not only the screens' chrome.
- **Refusals are enum keys, not sentences** — five reasons, five strings, a `when` that will not
  compile until a new reason has words.
- **A blocked reply is dropped with its figures**, so no screen can reassemble what the guardrail
  refused; and the screen names *which* refusal it was, because "sorry, something went wrong" is
  what a crash says.
- **The conversation is never backed up and its delete is hard** (CHT-004). Both exemptions are
  argued where the invariants live rather than being quietly added to a list.
- **A stored turn keeps the words, not the figures.** A figure checked against today's data cannot
  honestly be re-shown next month.
- **Deferred:** a neural model; cloud assist (the payload type exists, the transport does not);
  nine of the fifteen registered tools, which fail honestly; multi-turn context; regeneration.

**What the device run caught that 4,800 tests did not.** Asked "how am I doing financially?", the
app said: *"I had an answer, but it contained a figure I could not trace to an engine — so I did not
show it."* The guardrail was right. My template sentence read `%1$d / 1000`, and **1000 is a claim
too** — no tool had produced it. Every unit test passed because none of them ran the real template
against the real allowlist. The fix is the one the design demands: the health tool now publishes
`scoreMax` from AI-FHS's own rules, and the sentence slots it in like any other figure. Verified on
the device afterwards: *"Your financial health score is 694 / 1000."*

**A design gap detekt found sideways.** An unused private function in the executor turned out to
mean the VEHICLE intent was routed at `get_forecast` — so "when is the car service due?" would have
answered with the ninety-day low. The cause was a registry describing a capability the app did not
have; issue 10.4 shipped AI-VEH, so the fix was a registry entry (`get_vehicle_status`), not a
`when`.

## 2 · Flow changed this session

```
DashboardScreen → "Ask your CFO" → ChatScreen
└─ ChatRepository.ask()
   ├─ ChatEngine.plan()           out-of-scope FIRST, then keyword match, ≤ 2 tools
   ├─ ChatToolExecutor.run()      the complete list of what chat may see (§19.2)
   ├─ LlmEngine.verbalise()       intent + tool results only — §19.4's context pack
   └─ ChatEngine.compose()        AI-GRD over the draft; anything unverifiable is dropped
   → chat_message                 kept, never backed up, hard deleted (CHT-004)
```

`FLOW.md` §2.17 holds the full chain.

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `ai/skills/tool-registry.json` | 1.1 — routing as data, and `get_vehicle_status` so the vehicle question reaches AI-VEH |
| `domain/engines/chat/` (new) | AI-CHAT 1.0: the engine, the `LlmEngine` port, the registry mirror, `ENGINE.md`, 33 tests, a golden file and its Python oracle |
| `ml/llm/` | the shipped model: `TemplateLlmEngine` over translatable strings, 8 tests |
| `core/database/**` | schema 28: `chat_message`, `ChatDao`, `MIGRATION_27_28`, the round-trip case, the argued soft-delete exemption |
| `data/repository/ChatRepository.kt` (new), `RepositoryFactory.kt`, `ProfileSnapshot.kt` | the pipeline, the executor, and the archive exclusion |
| `feature/chat/` (new), `feature/dashboard/**` | the screen, its 17 tests, and the dashboard action |
| `app/.../di/RepositoryModule.kt`, `CfoRoute.kt`, `CfoNavHost.kt` | AI-CHAT, the model and the executor provided; the route |
| `docs/adr/0053-…`, `DECISIONS.md`, `FLOW.md` §2.17, `ai/orchestrator/engine-registry.yaml`, `CHANGELOG.md`, `docs/memory.md` | the records |

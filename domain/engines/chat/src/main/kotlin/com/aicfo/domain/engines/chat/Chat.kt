package com.aicfo.domain.engines.chat

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.guardrail.GuardrailClaim

/**
 * AI-CHAT — the layer that turns a question into tool calls and a checked answer (issue 10.5; §19).
 *
 * Why:  §19 wants a copilot over the user's own data, not a chatbot. The danger is precise: a
 *       language model will happily state a confident wrong rupee figure, and a finance app that
 *       does that once is finished. So this engine is built so the model **cannot** be the source
 *       of a number. It decides which registry tools to call; the caller executes them; the model
 *       is handed the results and asked only for words; and the guardrail (AI-GRD, issue 9.7)
 *       checks every figure in those words against the same results before anyone sees them.
 * What: two steps. [plan] reads the question and says which tools to call, or refuses.
 *       [compose] takes the tool results and the model's draft and returns a reply that has passed
 *       the guardrail — or the refusal that replaces it.
 * Result: a [ChatReply] in which every figure came from a tool (CHT-001).
 * Changelog: 2026-09-26 — Created for issue 10.5.
 *
 * Pure (P-08): no clock, no I/O, no model. The model is the caller's, behind [LlmEngine].
 */
interface ChatEngine {
    /**
     * Reads a question and decides what to look up.
     * Why:    planning is separated from answering because the two fail differently. A question the
     *         app cannot serve should be refused **before** any data is read, and a plan is the
     *         thing a test can assert without a database.
     * Result: `Ok(ChatPlan)` — with `refusal` set for an out-of-scope ask (CHT-002) or a question
     *         that matches nothing; `Err(AppError.Validation("chat.text"))` for empty input.
     * Input:  [request] — the user's words and the clock reading to stamp.
     * Output: `Result<ChatPlan, AppError>`.
     */
    fun plan(request: ChatRequest): Result<ChatPlan, AppError>

    /**
     * Turns tool results and the model's draft into a reply that has been checked.
     * Why:    this is where AI-ARC-004 actually bites. The draft is text from a model; the figures
     *         are from tools; the guardrail decides whether the two agree. Anything it cannot
     *         verify means the reply is not shown — the app says it could not answer rather than
     *         showing a number it cannot stand behind.
     * Result: `Ok(ChatReply)`. A reply is either `answered` with the checked text, or carries a
     *         [RefusalReason] and no figures at all.
     * Input:  [composition] — the plan, what the tools returned, the model's draft and how many
     *         attempts have been made.
     * Output: `Result<ChatReply, AppError>`.
     */
    fun compose(composition: ChatComposition): Result<ChatReply, AppError>
}

/**
 * The model port (§19, AI-ARC-007).
 *
 * Why:  the model is the one part of this pipeline that is neither deterministic nor testable, so
 *       it sits behind an interface with exactly one method and no access to anything but the tool
 *       results it is asked to verbalise. Swapping an on-device model for another, or for the
 *       template verbaliser the app ships with, changes this binding and nothing else.
 * What: one call — draft words for these figures.
 * Result: text. Never numbers of its own: whatever it returns is checked by the guardrail.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
interface LlmEngine {
    /**
     * Which model answered, for provenance and for the screen to name it (P-02).
     * Result: a short stable id — `template`, `on-device`, `cloud`. Input: none. Output: [String].
     */
    val id: String

    /**
     * Drafts the words for a set of tool results.
     * Result: `Ok(text)`; `Err` when the model is unavailable — the caller then refuses rather
     *         than inventing a reply.
     * Input:  [draft] — the intent, the results, and nothing else. **Never the raw ledger**: the
     *         model sees aggregates a tool returned (§19.2, P-01).
     * Output: `Result<String, AppError>`.
     */
    fun verbalise(draft: VerbalisationDraft): Result<String, AppError>
}

/**
 * Builds the engine (ARC-003 — the implementation stays `internal`).
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
object ChatEngineFactory {
    /**
     * Result: §19's chat engine, over the bundled registry mirror and AI-GRD.
     * Input: [registry] — overridable in tests only. Output: [ChatEngine].
     */
    fun create(registry: ToolRegistry = ToolRegistry.BUNDLED): ChatEngine = IntentRoutingChatEngine(registry)
}

/**
 * One question.
 * Input:  [text] — as typed; [nowUtcMillis] — stamped into provenance; [registry] — the mirror.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
data class ChatRequest(
    val text: String,
    val nowUtcMillis: Long,
)

/**
 * What the app should look up to answer a question.
 * Input:  [intent] — the matched intent, or `null` when nothing matched; [calls] — in order, at most
 *         the registry's `max_tools_per_turn`; [chips] — what to offer next (CHT-003);
 *         [refusal] — set when the app will not answer at all.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
data class ChatPlan(
    val intent: ChatIntent?,
    val calls: List<ToolCall>,
    val chips: List<ChatIntent>,
    val refusal: RefusalReason? = null,
)

/**
 * One tool to call, with what to call it with.
 * Input:  [tool]; [arguments] — parameter name to value, as strings, because the registry's
 *         parameters are declared that way and the executor knows their types.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
data class ToolCall(
    val tool: ToolName,
    val arguments: Map<String, String> = emptyMap(),
)

/**
 * What one tool returned — the only place a figure in a reply may come from (CHT-001).
 * Input:  [tool]; [figures] — every number this tool produced, in engine units; [citations] — the
 *         rules or engines behind them, for the evidence line (P-02); [failed] — true when the tool
 *         could not answer, so the composer can say so instead of implying a zero.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
data class ToolResult(
    val tool: ToolName,
    val figures: List<ToolFigure> = emptyList(),
    val citations: List<String> = emptyList(),
    val failed: Boolean = false,
)

/**
 * One figure a tool produced.
 * Why:    the guardrail checks a claim against values of a kind, so a figure has to carry its kind
 *         as well as its value — a count of 12 and ₹12 are not the same claim.
 * Input:  [key] — what it is, for the template to slot it in (`total`, `balance`, `months`);
 *         [kind]; [amount] — paise when the kind is an amount; [number] — for counts and percents;
 *         [isoDate] — for a date. Exactly one of the three is set for a given kind.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
data class ToolFigure(
    val key: String,
    val kind: FigureKind,
    val amount: Money? = null,
    val number: Int? = null,
    val isoDate: String? = null,
)

/**
 * What kind of number a figure is — the same split AI-GRD verifies against.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
enum class FigureKind {
    /** Rupees, held as paise (MNY-001). */
    AMOUNT,

    /** A whole percentage as the user reads it. */
    PERCENT,

    /** A count of things: transactions, months, days. */
    COUNT,

    /** A date, ISO `yyyy-MM-dd` (TIM-002). */
    DATE,
}

/**
 * What the model is handed. Deliberately small.
 * Why:    §19.4's context pack, and P-01's line: the model gets the intent and the figures a tool
 *         already returned — never a transaction, never a merchant list, never the user's text
 *         history. When the cloud binding eventually exists, **this is the whole payload**, which
 *         is why it is a type rather than a string built at the call site.
 * Input:  [intent]; [results] — what the tools returned; [attempt] — 0 on the first try, higher
 *         when the guardrail asked for another.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
data class VerbalisationDraft(
    val intent: ChatIntent?,
    val results: List<ToolResult>,
    val attempt: Int = 0,
)

/**
 * Everything [ChatEngine.compose] needs.
 * Input:  [request]; [plan]; [results]; [draft] — the model's words, or `null` when no model
 *         answered; [modelId] — which model produced them; [attemptsMade].
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
data class ChatComposition(
    val request: ChatRequest,
    val plan: ChatPlan,
    val results: List<ToolResult>,
    val draft: String?,
    val modelId: String,
    val attemptsMade: Int = 0,
)

/**
 * What the user sees.
 * Input:  [text] — the checked words, empty when refused; [figures] — every figure the reply is
 *         allowed to contain, so the screen can render evidence; [chips]; [citations];
 *         [refusal] — why there are no words, when there are none; [verified] — the claims the
 *         guardrail checked, for the trace; [provenance].
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
data class ChatReply(
    val text: String,
    val figures: List<ToolFigure>,
    val chips: List<ChatIntent>,
    val citations: List<String>,
    val refusal: RefusalReason?,
    val verified: List<GuardrailClaim>,
    val provenance: EngineProvenance,
) {
    /** Whether the app has something to say. */
    val answered: Boolean get() = refusal == null && text.isNotBlank()
}

/**
 * Why the app is not answering — a key, never a sentence: §21.6 keeps the words in `strings.xml`,
 * and a refusal written by an engine would be a user-visible string in the wrong layer.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
enum class RefusalReason {
    /** CHT-002: stock tips, tax filing, legal advice — offer what the assistant can do instead. */
    OUT_OF_SCOPE,

    /** Nothing in the registry matches the question. */
    NOT_UNDERSTOOD,

    /** The tools could not answer — no data yet, or a read failed. */
    NO_DATA,

    /** The model was unavailable, so there are no words to check. */
    NO_MODEL,

    /**
     * AI-ARC-004 fired: the draft contained a figure no tool produced. The reply is **not** shown.
     * This is the one refusal that means the app caught itself, and it is logged as such.
     */
    GUARDRAIL_BLOCKED,
}

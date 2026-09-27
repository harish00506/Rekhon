package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.flatMap
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.ChatMessageEntity
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.chat.ChatComposition
import com.aicfo.domain.engines.chat.ChatEngine
import com.aicfo.domain.engines.chat.ChatIntent
import com.aicfo.domain.engines.chat.ChatPlan
import com.aicfo.domain.engines.chat.ChatReply
import com.aicfo.domain.engines.chat.ChatRequest
import com.aicfo.domain.engines.chat.FigureKind
import com.aicfo.domain.engines.chat.LlmEngine
import com.aicfo.domain.engines.chat.RefusalReason
import com.aicfo.domain.engines.chat.ToolCall
import com.aicfo.domain.engines.chat.ToolFigure
import com.aicfo.domain.engines.chat.ToolName
import com.aicfo.domain.engines.chat.ToolResult
import com.aicfo.domain.engines.chat.VerbalisationDraft
import com.aicfo.domain.engines.forecast.ForecastRules
import com.aicfo.domain.engines.healthscore.HealthRules
import com.aicfo.domain.engines.safetospend.SafeToSpendComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The chat pipeline over the household's own data (issue 10.5; §19, AI-ARC-001, ARC-005).
 *
 * Why:  AI-CHAT is pure: it decides *which* tools to call but cannot call them, because a tool is a
 *       read of this household's data and only a repository may do that (ARC-005). This class is
 *       the executor — and it is deliberately the narrow waist of the whole feature: **the tools
 *       here are the complete list of what chat can ever see**. Adding a capability means adding an
 *       executor here, which is a reviewable act, rather than the model finding its own way in.
 * What: plan → run the tools → hand the results to the model → compose through the guardrail →
 *       keep the turn.
 * Result: a [ChatTurn] a screen can render. **Nothing is written to the ledger** (P-07): the only
 *         writes are the conversation's own messages.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
interface ChatRepository {
    /**
     * The conversation, oldest first.
     * Result: re-emits on every turn and on a clear. Input: none. Output: `Flow<List<ChatTurn>>`.
     */
    fun observeConversation(): Flow<List<ChatTurn>>

    /**
     * Answers one question.
     * Why:    the whole pipeline is one call because the steps are only meaningful together — a
     *         plan nobody executed, or a draft nobody checked, is not an answer.
     * Result: `Ok(ChatTurn)` — answered or refused, both of which are kept. `Err` only when the
     *         question itself is unusable.
     * Input:  [text] — as typed. Output: `Result<ChatTurn, AppError>`.
     */
    suspend fun ask(text: String): Result<ChatTurn, AppError>

    /**
     * Forgets the conversation (CHT-004).
     * Result: `Ok(Unit)`; a **hard** delete — a conversation the user asked to forget must not sit
     *         in the table behind a tombstone.
     * Input:  none. Output: `Result<Unit, AppError>`.
     */
    suspend fun clear(): Result<Unit, AppError>
}

/**
 * One exchange: what was asked, and what the app said back.
 * Input:  [id]; [question]; [reply] — the checked reply, or the refusal; [askedAtUtcMillis].
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
data class ChatTurn(
    val id: String,
    val question: String,
    val reply: ChatReply,
    val askedAtUtcMillis: Long,
)

/**
 * [ChatRepository] over the engines and repositories already in the app (issue 10.5).
 *
 * Input:  [database]; [engine] — AI-CHAT; [llm] — the model port; [tools] — the executor;
 *         [clock]; [dispatchers]; [activeProfileId]; [idGenerator].
 * Output: the repository.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
@Suppress("LongParameterList") // the database, two engines, the executor and four seams
internal class PipelineChatRepository(
    private val database: CfoDatabase,
    private val engine: ChatEngine,
    private val llm: LlmEngine,
    private val tools: ChatToolExecutor,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
    private val idGenerator: IdGenerator,
) : ChatRepository {
    override fun observeConversation(): Flow<List<ChatTurn>> =
        activeProfileId.flatMapLatest { profileId ->
            database.chatDao().observeMessages(profileId).map { rows -> rows.map(::turnOf) }
        }

    override suspend fun ask(text: String): Result<ChatTurn, AppError> =
        withContext(dispatchers.io) {
            val request = ChatRequest(text = text, nowUtcMillis = clock.nowUtcMillis())
            engine.plan(request).flatMap { plan ->
                runCatchingToResult { answer(request, plan) }
            }
        }

    override suspend fun clear(): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                // The row count is not a question with a consequence; that the rows are gone is.
                database.chatDao().deleteAll(activeProfileId.first())
                Unit
            }
        }

    /**
     * Runs the plan and keeps the turn.
     * Why:    the model is asked **after** the tools have answered and is handed only their
     *         results (§19.4, P-01). A refused plan skips the tools and the model entirely, so an
     *         out-of-scope question reads nothing at all.
     * Result: the stored turn. Input: [request]; [plan]. Output: [ChatTurn].
     */
    private suspend fun answer(
        request: ChatRequest,
        plan: ChatPlan,
    ): ChatTurn {
        val results = if (plan.refusal == null) plan.calls.map { execute(it) } else emptyList()
        val draft =
            if (plan.refusal != null || results.any { it.failed }) {
                null
            } else {
                when (val spoken = llm.verbalise(VerbalisationDraft(plan.intent, results))) {
                    is Ok -> spoken.value
                    is Err -> null
                }
            }
        val reply =
            engine.compose(
                ChatComposition(
                    request = request,
                    plan = plan,
                    results = results,
                    draft = draft,
                    modelId = llm.id,
                ),
            )
        val value =
            when (reply) {
                is Ok -> reply.value
                is Err -> error("compose refused: ${reply.error}")
            }
        return store(request, value)
    }

    /**
     * Result: one tool's result, or a failed one when it could not answer.
     * Input:  [call]. Output: [ToolResult].
     */
    private suspend fun execute(call: ToolCall): ToolResult = tools.run(call)

    /** Result: the turn, written to the conversation. Input: [request]; [reply]. Output: the turn. */
    private suspend fun store(
        request: ChatRequest,
        reply: ChatReply,
    ): ChatTurn {
        val turn =
            ChatTurn(
                id = idGenerator.newId("chat"),
                question = request.text,
                reply = reply,
                askedAtUtcMillis = request.nowUtcMillis,
            )
        database.chatDao().upsert(
            ChatMessageEntity(
                id = turn.id,
                profileId = activeProfileId.first(),
                question = turn.question,
                answer = reply.text,
                intent = reply.chipsIntent(),
                refusal = reply.refusal?.name,
                modelId = llm.id,
                citations = reply.citations.joinToString(","),
                askedAtUtcMillis = turn.askedAtUtcMillis,
                createdAtUtcMillis = turn.askedAtUtcMillis,
                updatedAtUtcMillis = turn.askedAtUtcMillis,
            ),
        )
        return turn
    }

    /**
     * Rebuilds a turn from its row.
     * Why:    a stored turn keeps the **words and the refusal**, not the figures. The figures were
     *         checked when the reply was composed; re-showing them from a row would be asserting
     *         them again without a guardrail, months later, against data that has since changed.
     * Result: the turn as it can honestly be re-shown. Input: [row]. Output: [ChatTurn].
     */
    private fun turnOf(row: ChatMessageEntity): ChatTurn =
        ChatTurn(
            id = row.id,
            question = row.question,
            reply =
                ChatReply(
                    text = row.answer,
                    figures = emptyList(),
                    chips = emptyList(),
                    citations = row.citations.split(",").filter { it.isNotBlank() },
                    refusal = row.refusal?.let { name -> RefusalReason.entries.firstOrNull { it.name == name } },
                    verified = emptyList(),
                    provenance =
                        com.aicfo.core.model.EngineProvenance(
                            engineId = "AI-CHAT",
                            engineVersion = "1.0",
                            computedAtUtcMillis = row.askedAtUtcMillis,
                            inputWindow = "model=${row.modelId}",
                        ),
                ),
            askedAtUtcMillis = row.askedAtUtcMillis,
        )

    /** Result: the intent to store, as a name. Input: the receiver. Output: `String?`. */
    private fun ChatReply.chipsIntent(): String? = chips.firstOrNull()?.name
}

/**
 * The complete list of what chat may read (issue 10.5; §19.2).
 *
 * Why:  a separate type from the repository, because this is the surface that decides what the
 *       assistant can ever see — and a reviewer should be able to read it in one screen and say
 *       "yes, that and nothing else".
 * What: one function, over the repositories the app already has.
 * Result: a [ToolResult] with the figures a reply may contain, or a failed one.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
interface ChatToolExecutor {
    /**
     * Runs one registered tool.
     * Result: its figures and citations; `failed = true` when it has nothing to say, which the
     *         engine turns into an honest refusal rather than a zero.
     * Input:  [call]. Output: [ToolResult].
     */
    suspend fun run(call: ToolCall): ToolResult
}

/**
 * The executor over this app's repositories (issue 10.5).
 *
 * Why:  every figure the assistant can state is produced here, by an engine that already published
 *       it elsewhere in the app — which is what makes "the chat never computes" true rather than
 *       aspirational (P-03). Where a tool has no source yet, it fails honestly instead of
 *       approximating.
 * Input:  the repositories it reads. Output: the executor.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
@Suppress("LongParameterList") // one per tool this executor can serve; a wrapper would hide the list
internal class RepositoryChatToolExecutor(
    private val safeToSpend: SafeToSpendRepository,
    private val forecast: ForecastRepository,
    private val goals: GoalRepository,
    private val health: HealthScoreRepository,
    private val buyList: BuyListRepository,
    private val vehicles: VehicleRepository,
) : ChatToolExecutor {
    override suspend fun run(call: ToolCall): ToolResult =
        when (call.tool) {
            ToolName.GET_BALANCE -> balance()
            ToolName.GET_FORECAST -> forecast()
            ToolName.GET_GOALS -> goals()
            ToolName.GET_HEALTH_SCORE -> health()
            ToolName.REVIEW_BUYLIST -> buyList()
            ToolName.QUERY_SPEND -> spend()
            ToolName.GET_VEHICLE_STATUS -> vehicle()
            else -> ToolResult(call.tool, failed = true)
        }

    /** Result: what is safe to spend now, from AI-STS. Input: none. Output: [ToolResult]. */
    private suspend fun balance(): ToolResult {
        val figure = safeToSpend.observeSafeToSpend().first()
        return if (figure == null) {
            ToolResult(ToolName.GET_BALANCE, failed = true)
        } else {
            ToolResult(
                tool = ToolName.GET_BALANCE,
                figures = listOf(ToolFigure("liquid", FigureKind.AMOUNT, amount = figure.amount)),
                citations = figure.provenance.evidence.map { it.ruleId },
            )
        }
    }

    /**
     * The horizon's worst day, from AI-FCT.
     * Why:    it publishes **the length of the horizon as well as the day**. "In the next 90 days"
     *         is a claim about a number, and the frozen eval set caught the template asserting it
     *         with nothing behind it — the same shape of bug a device run found in the health
     *         sentence's "/ 1000". If a sentence says a number, an engine has to have produced it.
     * Result: the lowest day, its date and the window, or a failed result.
     * Input:  none. Output: [ToolResult].
     */
    private suspend fun forecast(): ToolResult {
        val result = forecast.observeForecast().first()
        val lowest = (result as? Ok)?.value?.lowest
        return if (lowest == null) {
            ToolResult(ToolName.GET_FORECAST, failed = true)
        } else {
            ToolResult(
                tool = ToolName.GET_FORECAST,
                figures =
                    listOf(
                        ToolFigure("horizonDays", FigureKind.COUNT, number = ForecastRules().horizonDays),
                        ToolFigure("lowest", FigureKind.AMOUNT, amount = lowest.p50),
                        ToolFigure("lowestOn", FigureKind.DATE, isoDate = lowest.date.toString()),
                    ),
                citations = listOf("RULE-FCT-METHOD"),
            )
        }
    }

    /** Result: what the goals need each month, from AI-GOAL. Input: none. Output: [ToolResult]. */
    private suspend fun goals(): ToolResult =
        when (val required = goals.requiredMonthlyTotal()) {
            is Err -> ToolResult(ToolName.GET_GOALS, failed = true)
            is Ok ->
                ToolResult(
                    tool = ToolName.GET_GOALS,
                    figures = listOf(ToolFigure("required", FigureKind.AMOUNT, amount = required.value)),
                    citations = listOf("RULE-HORIZON"),
                )
        }

    /**
     * The health score, from AI-FHS.
     * Why:    it publishes **the top of the scale as well as the score**. "988 out of 1000" contains
     *         two claims, and the second one is as much an engine's figure as the first — the
     *         guardrail blocked the sentence on a device until the tool started returning it, which
     *         is the gate doing exactly what it exists for (AI-ARC-004).
     * Result: the score and the scale, or a failed result before there is one.
     * Input:  none. Output: [ToolResult].
     */
    private suspend fun health(): ToolResult {
        val score = (health.observeHealthScore().first() as? Ok)?.value?.score
        return if (score == null) {
            ToolResult(ToolName.GET_HEALTH_SCORE, failed = true)
        } else {
            ToolResult(
                tool = ToolName.GET_HEALTH_SCORE,
                figures =
                    listOf(
                        ToolFigure("score", FigureKind.COUNT, number = score),
                        ToolFigure("scoreMax", FigureKind.COUNT, number = HealthRules().scoreMax),
                    ),
                citations = listOf("RULE-FHS-PILLARS"),
            )
        }
    }

    /** Result: how many things are on the buy list, from AI-PA-INT. Input: none. Output: result. */
    private suspend fun buyList(): ToolResult {
        val items = buyList.observeList().first()
        return ToolResult(
            tool = ToolName.REVIEW_BUYLIST,
            figures = listOf(ToolFigure("count", FigureKind.COUNT, number = items.size)),
            citations = listOf("RULE-PAI-SCORE"),
        )
    }

    /**
     * What has gone out so far this month.
     * Why:    it reads **AI-STS's own `SPENT` line**, not the ledger. §19.2 is explicit that chat
     *         gets aggregates from a tool, never a transaction list — and the figure the assistant
     *         states is then the same one the dashboard shows, because it is literally the same
     *         figure. The transaction count is deliberately absent: nothing publishes it as an
     *         engine result, so the template's two-slot sentence falls back to the one that claims
     *         nothing rather than counting rows here (P-03). ADR-0053 records it.
     * Result: the month's spend, or a failed result before there is a figure.
     * Input:  none. Output: [ToolResult].
     */
    private suspend fun spend(): ToolResult {
        val figure = safeToSpend.observeSafeToSpend().first()
        val spent = figure?.lines?.firstOrNull { it.component == SafeToSpendComponent.SPENT }
        return if (spent == null) {
            ToolResult(ToolName.QUERY_SPEND, failed = true)
        } else {
            ToolResult(
                tool = ToolName.QUERY_SPEND,
                figures = listOf(ToolFigure("total", FigureKind.AMOUNT, amount = spent.amount)),
                citations = figure.provenance.evidence.map { it.ruleId },
            )
        }
    }

    /**
     * What the vehicles will cost before their next service (AI-VEH).
     * Result: the total and how many items make it up, or a failed result when nothing is recorded.
     * Input:  none. Output: [ToolResult].
     */
    private suspend fun vehicle(): ToolResult {
        val outflows = vehicles.observePredictedOutflows().first()
        return if (outflows.isEmpty()) {
            ToolResult(ToolName.GET_FORECAST, failed = true)
        } else {
            ToolResult(
                tool = ToolName.GET_FORECAST,
                figures =
                    listOf(
                        ToolFigure(
                            "cost",
                            FigureKind.AMOUNT,
                            amount = Money(outflows.sumOf { it.outflow.amount.minor }),
                        ),
                        ToolFigure("dueOn", FigureKind.DATE, isoDate = outflows.first().outflow.isoDate),
                    ),
                citations = listOf("VEH-KB.service"),
            )
        }
    }
}

/** The intents this executor can actually serve today, for the screen's chips. */
internal val SERVED_INTENTS =
    setOf(ChatIntent.BALANCE, ChatIntent.FORECAST, ChatIntent.GOALS, ChatIntent.HEALTH, ChatIntent.BUYLIST)

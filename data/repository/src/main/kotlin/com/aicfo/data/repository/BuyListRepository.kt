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
import com.aicfo.core.database.entity.InterviewAnswerEntity
import com.aicfo.core.database.entity.WishlistItemEntity
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.purchase.AnswerKeys
import com.aicfo.domain.engines.purchase.InterviewAnswer
import com.aicfo.domain.engines.purchase.InterviewAssessment
import com.aicfo.domain.engines.purchase.InterviewInput
import com.aicfo.domain.engines.purchase.InterviewQuestion
import com.aicfo.domain.engines.purchase.PaymentMethod
import com.aicfo.domain.engines.purchase.PurchaseInterviewEngine
import com.aicfo.domain.engines.purchase.PurchaseRequest
import com.aicfo.domain.engines.purchase.PurchaseVerdictCard
import com.aicfo.domain.engines.purchase.Urgency
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withContext

/**
 * The buy list and the interview behind it (issue 10.2; §13.3, AI-PA-INT).
 *
 * Why:  §13.3's idea is that a wish goes on a list instead of into a basket, and that the list
 *       **remembers what the user said**. So the answers are rows, the score is recomputed from
 *       them on every read, and nothing is ever deleted on the app's say-so: a wish that scores
 *       badly is *suggested* for removal, with the user's own answers as the reason, and one tap
 *       each way (P-07).
 * What: adding a wish, answering one question at a time, changing a wish's status, and asking the
 *       Purchase Advisor (10.1) what it says about a wish today.
 * Result: a ViewModel sees [BuyListEntry]s and never a DAO (ARC-005).
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
interface BuyListRepository {
    /**
     * The live list, newest first, each wish with its interview as it stands.
     * Why:    the assessment is recomputed on every emission rather than stored, because the ladder
     *         depends on income — a raise moves a wish down a band, and a stored band would go
     *         stale silently. The score's *inputs* are stored; the arithmetic is cheap and pure.
     * Result: re-emits on every write and whenever income changes. Input: none.
     * Output: `Flow<List<BuyListEntry>>`.
     */
    fun observeList(): Flow<List<BuyListEntry>>

    /**
     * Adds a wish.
     * Result: `Ok(id)`; the wish starts parked at the rulebook's opening score, which is what a list
     *         is for — neither kept nor condemned until it has been asked about.
     * Input:  [name]; [price]; [method]; [monthlyEmi] — required for an instalment; [urgency];
     *         [categoryId]. Output: `Result<String, AppError>`.
     */
    @Suppress("LongParameterList") // one per field of a wish; the alternative is a second type
    suspend fun add(
        name: String,
        price: Money,
        method: PaymentMethod = PaymentMethod.CASH,
        monthlyEmi: Money? = null,
        urgency: Urgency = Urgency.ROUTINE,
        categoryId: String? = null,
    ): Result<String, AppError>

    /**
     * Records one answer and re-scores the wish.
     * Why:    one answer at a time, because that is how the screen asks — and because replacing a
     *         single answer must not disturb the others. Changing your mind overwrites that
     *         question's row rather than adding a second (the unique index enforces it).
     * Result: the wish's assessment after the answer. Input: [itemId]; [answer].
     * Output: `Result<BuyListEntry, AppError>`.
     */
    suspend fun answer(
        itemId: String,
        answer: InterviewAnswer,
    ): Result<BuyListEntry, AppError>

    /**
     * Moves a wish to another status — the user's decision, never the app's (§13.3, P-07).
     * Result: `Ok(Unit)`. Input: [itemId]; [status]. Output: `Result<Unit, AppError>`.
     */
    suspend fun setStatus(
        itemId: String,
        status: BuyListStatus,
    ): Result<Unit, AppError>

    /**
     * Asks the Purchase Advisor about a wish as things stand today (issue 10.1).
     * Why:    this is the re-evaluation §13.3 promises: the same wish gets a different verdict in a
     *         tight month than in a flush one. The card is kept like any other, and the wish
     *         remembers which card was its last.
     * Result: the verdict card. Input: [itemId]. Output: `Result<PurchaseVerdictCard, AppError>`.
     */
    suspend fun advise(itemId: String): Result<PurchaseVerdictCard, AppError>
}

/** Where a wish has got to (§13.3). Changelog: 2026-09-26 — Created for issue 10.2. */
enum class BuyListStatus {
    /** Kept and watched for a good moment to buy. */
    WATCHING,

    /** On the list, waiting for the next interview. */
    PARKED,

    /** The user removed it. Never the app's doing. */
    REMOVED,

    /** Bought. */
    BOUGHT,

    ;

    /** Result: the value as stored. */
    val stored: String get() = name.lowercase()

    companion object {
        /** Result: the status stored as [stored], or [PARKED] for anything unrecognised. */
        fun fromStored(stored: String): BuyListStatus = entries.firstOrNull { it.stored == stored } ?: PARKED
    }
}

/**
 * One wish, with its interview as it stands (issue 10.2).
 * Input:  [id]; [name]; [price]; [method]; [monthlyEmi]; [urgency]; [status]; [assessment] — the
 *         band, what is still to ask, the score, the outcome and the evidence; [lastTraceId] — the
 *         last advisor card about this wish, when one has been asked for.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
data class BuyListEntry(
    val id: String,
    val name: String,
    val price: Money,
    val method: PaymentMethod,
    val monthlyEmi: Money?,
    val urgency: Urgency,
    val status: BuyListStatus,
    val assessment: InterviewAssessment,
    val lastTraceId: String? = null,
)

/**
 * [BuyListRepository] over `wishlist_item` and `interview_answer` (issue 10.2).
 * Input:  [database]; [engine]; [advisor] — 10.1, for the re-evaluation; [monthlyIncome] — what the
 *         ladder weighs a price against; [clock]; [dispatchers]; [activeProfileId]; [idGenerator].
 * Output: the repository.
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
@Suppress("LongParameterList") // the store, the engine, the advisor and five seams
internal class StoredBuyListRepository(
    private val database: CfoDatabase,
    private val engine: PurchaseInterviewEngine,
    private val advisor: PurchaseAdvisorRepository,
    private val monthlyIncome: Flow<Money>,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
    private val idGenerator: IdGenerator,
) : BuyListRepository {
    override fun observeList(): Flow<List<BuyListEntry>> =
        activeProfileId.flatMapLatest { profileId ->
            val dao = database.buyListDao()
            combine(
                dao.observeItems(profileId),
                dao.observeAnswers(profileId),
                monthlyIncome,
            ) { items, answers, income ->
                items.map { item -> entry(item, answers.filter { it.itemId == item.id }, income) }
            }
        }

    override suspend fun add(
        name: String,
        price: Money,
        method: PaymentMethod,
        monthlyEmi: Money?,
        urgency: Urgency,
        categoryId: String?,
    ): Result<String, AppError> =
        withContext(dispatchers.io) {
            val profileId = activeProfileId.first()
            val now = clock.nowUtcMillis()
            runCatchingToResult {
                val id = idGenerator.newId("wish")
                database.buyListDao().upsertItem(
                    WishlistItemEntity(
                        id = id,
                        profileId = profileId,
                        name = name.trim(),
                        estPriceMinor = price.minor,
                        categoryId = categoryId,
                        method = method.name,
                        monthlyEmiMinor = monthlyEmi?.minor,
                        urgency = urgency.name,
                        wantScore = assessment(price, method, monthlyEmi, emptyList(), Money.ZERO).wantScore,
                        status = BuyListStatus.PARKED.stored,
                        createdAtUtcMillis = now,
                        updatedAtUtcMillis = now,
                    ),
                )
                id
            }
        }

    override suspend fun answer(
        itemId: String,
        answer: InterviewAnswer,
    ): Result<BuyListEntry, AppError> =
        withContext(dispatchers.io) {
            val profileId = activeProfileId.first()
            val income = monthlyIncome.first()
            runCatchingToResult { record(profileId, itemId, answer, income) }
                .flatMap { entry -> entry?.let { Ok(it) } ?: Err(AppError.Validation(FIELD_ITEM)) }
        }

    override suspend fun setStatus(
        itemId: String,
        status: BuyListStatus,
    ): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            val profileId = activeProfileId.first()
            runCatchingToResult {
                val dao = database.buyListDao()
                dao.findItem(profileId, itemId)?.let { item ->
                    dao.upsertItem(item.copy(status = status.stored, updatedAtUtcMillis = clock.nowUtcMillis()))
                }
                Unit
            }
        }

    override suspend fun advise(itemId: String): Result<PurchaseVerdictCard, AppError> =
        withContext(dispatchers.io) {
            val profileId = activeProfileId.first()
            val item =
                database.buyListDao().findItem(profileId, itemId)
                    ?: return@withContext Err(AppError.Validation(FIELD_ITEM))
            advisor.advise(
                PurchaseRequest(
                    item = item.name,
                    price = Money(item.estPriceMinor),
                    method = PaymentMethod.valueOf(item.method),
                    urgency = Urgency.valueOf(item.urgency),
                    monthlyEmi = item.monthlyEmiMinor?.let(::Money),
                    categoryId = item.categoryId,
                ),
            )
        }

    /**
     * Stores one answer and re-scores its wish.
     * Why:    the points are stored **as they were when the answer was given**, so an old score
     *         stays explicable after the rulebook moves (AI-ARC-006); the live score is recomputed
     *         from today's rules, and the two can be compared rather than silently conflated.
     * Result: the wish afterwards, or `null` when there is no such wish. Input: [profileId];
     *         [itemId]; [answer]; [income]. Output: `BuyListEntry?`.
     */
    private suspend fun record(
        profileId: String,
        itemId: String,
        answer: InterviewAnswer,
        income: Money,
    ): BuyListEntry? {
        val dao = database.buyListDao()
        val item = dao.findItem(profileId, itemId) ?: return null
        val now = clock.nowUtcMillis()
        val price = Money(item.estPriceMinor)
        val method = PaymentMethod.valueOf(item.method)
        val emi = item.monthlyEmiMinor?.let(::Money)
        val stored = dao.answersFor(profileId, itemId).filterNot { it.question == answer.question.name }
        val assessment = assessment(price, method, emi, restore(stored) + answer, income)
        val points = assessment.deltas.firstOrNull { it.question == answer.question }?.points ?: 0
        dao.upsertAnswer(entityFor(profileId, itemId, answer, points, now))
        val updated =
            item.copy(
                wantScore = assessment.wantScore,
                lastInterviewedAtUtcMillis = now,
                updatedAtUtcMillis = now,
            )
        dao.upsertItem(updated)
        return entry(updated, dao.answersFor(profileId, itemId), income)
    }

    /** Result: the row for one answer. Input: [profileId]; [itemId]; [answer]; [points]; [now]. */
    private fun entityFor(
        profileId: String,
        itemId: String,
        answer: InterviewAnswer,
        points: Int,
        now: Long,
    ) = InterviewAnswerEntity(
        id = "$itemId:${answer.question.name}",
        profileId = profileId,
        itemId = itemId,
        question = answer.question.name,
        answerKey = AnswerKeys.of(answer),
        points = points,
        amountMinor = (answer as? InterviewAnswer.TotalCostOfOwnership)?.monthlyExtra?.minor,
        answeredAtUtcMillis = now,
        createdAtUtcMillis = now,
        updatedAtUtcMillis = now,
    )

    /** Result: one wish as the screen sees it. Input: [item]; [answers]; [income]. */
    private fun entry(
        item: WishlistItemEntity,
        answers: List<InterviewAnswerEntity>,
        income: Money,
    ) = BuyListEntry(
        id = item.id,
        name = item.name,
        price = Money(item.estPriceMinor),
        method = PaymentMethod.valueOf(item.method),
        monthlyEmi = item.monthlyEmiMinor?.let(::Money),
        urgency = Urgency.valueOf(item.urgency),
        status = BuyListStatus.fromStored(item.status),
        assessment =
            assessment(
                Money(item.estPriceMinor),
                PaymentMethod.valueOf(item.method),
                item.monthlyEmiMinor?.let(::Money),
                restore(answers),
                income,
            ),
        lastTraceId = item.lastTraceId,
    )

    /** Result: the engine's view of a wish. Input: the wish's figures, its answers and [income]. */
    private fun assessment(
        price: Money,
        method: PaymentMethod,
        monthlyEmi: Money?,
        answers: List<InterviewAnswer>,
        income: Money,
    ): InterviewAssessment {
        val input =
            InterviewInput(
                price = price,
                method = method,
                monthlyEmi = monthlyEmi,
                monthlyIncome = income,
                answers = answers,
                nowUtcMillis = clock.nowUtcMillis(),
            )
        return when (val result = engine.assess(input)) {
            is Ok -> result.value
            // AI-PA-INT refuses only an impossible interview — a negative price, or two answers to
            // one question. This repository writes neither (the unique index sees to the second),
            // so a refusal here is a bug in this class, and §21.6 says a bug crashes rather than
            // becoming a user-facing error.
            is Err -> error("AI-PA-INT refused an input this repository built: ${result.error}")
        }
    }

    /** Result: stored rows read back as answers, skipping any this build no longer knows. */
    private fun restore(rows: List<InterviewAnswerEntity>): List<InterviewAnswer> =
        rows.mapNotNull { row ->
            InterviewQuestion.entries.firstOrNull { it.name == row.question }
                ?.let { question -> AnswerKeys.toAnswer(question, row.answerKey, row.amountMinor?.let(::Money)) }
        }

    private companion object {
        const val FIELD_ITEM = "buylist.item"
    }
}

package com.aicfo.feature.advisor

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.data.repository.BuyListEntry
import com.aicfo.data.repository.BuyListRepository
import com.aicfo.data.repository.BuyListStatus
import com.aicfo.domain.engines.purchase.InterviewAnswer
import com.aicfo.domain.engines.purchase.InterviewAssessment
import com.aicfo.domain.engines.purchase.InterviewOutcome
import com.aicfo.domain.engines.purchase.InterviewQuestion
import com.aicfo.domain.engines.purchase.PaymentMethod
import com.aicfo.domain.engines.purchase.PurchaseVerdictCard
import com.aicfo.domain.engines.purchase.PurchaseWeight
import com.aicfo.domain.engines.purchase.Urgency
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A stand-in for the buy list's data side (issue 10.2).
 *
 * Why:  the screen's tests are about what it draws and what it asks for. The ladder and the score
 *       are proven in `:domain:engines:purchase`, and the storage in `:data:repository`; a fake
 *       makes the wish under test explicit.
 * Result: whatever list the test sets, and a record of what was asked of it.
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
class FakeBuyListRepository : BuyListRepository {
    /** The list the screen shows. */
    val list: MutableStateFlow<List<BuyListEntry>> = MutableStateFlow(emptyList())

    /** Wishes added, as (name, price). */
    val added: MutableList<Pair<String, Money>> = mutableListOf()

    /** Answers recorded, as (item, answer). */
    val answered: MutableList<Pair<String, InterviewAnswer>> = mutableListOf()

    /** Status changes, as (item, status). */
    val moved: MutableList<Pair<String, BuyListStatus>> = mutableListOf()

    /** Wishes the advisor was asked about. */
    val advised: MutableList<String> = mutableListOf()

    /** When set, every call fails with it. */
    var failure: AppError? = null

    override fun observeList(): Flow<List<BuyListEntry>> = list

    override suspend fun add(
        name: String,
        price: Money,
        method: PaymentMethod,
        monthlyEmi: Money?,
        urgency: Urgency,
        categoryId: String?,
    ): Result<String, AppError> {
        added += name to price
        return failure?.let { Err(it) } ?: Ok("wish:${added.size}")
    }

    override suspend fun answer(
        itemId: String,
        answer: InterviewAnswer,
    ): Result<BuyListEntry, AppError> {
        answered += itemId to answer
        return failure?.let { Err(it) } ?: Ok(entry(id = itemId))
    }

    override suspend fun setStatus(
        itemId: String,
        status: BuyListStatus,
    ): Result<Unit, AppError> {
        moved += itemId to status
        return failure?.let { Err(it) } ?: Ok(Unit)
    }

    override suspend fun advise(itemId: String): Result<PurchaseVerdictCard, AppError> {
        advised += itemId
        return failure?.let { Err(it) } ?: Ok(FakePurchaseAdvisorRepository.card(item = "Standing desk"))
    }

    companion object {
        /**
         * Result: one wish a test can put on the list, parked and half-asked.
         * Input:  [id]; [name]; [price]; [assessment] — built by [assessment], or copied from it.
         */
        fun entry(
            id: String = "wish:1",
            name: String = "Standing desk",
            price: Money = Money(8_000_00L),
            assessment: InterviewAssessment = assessment(),
        ) = BuyListEntry(
            id = id,
            name = name,
            price = price,
            method = PaymentMethod.CASH,
            monthlyEmi = null,
            urgency = Urgency.ROUTINE,
            status = BuyListStatus.PARKED,
            assessment = assessment,
        )

        /**
         * Result: an interview part way through; a test that needs more says so with `.copy(...)`.
         * Input:  [score]; [outcome]; [nextQuestion] — `null` when the band is finished.
         */
        fun assessment(
            score: Int = 50,
            outcome: InterviewOutcome = InterviewOutcome.PARK,
            nextQuestion: InterviewQuestion? = InterviewQuestion.NEED_OR_WANT,
        ) = InterviewAssessment(
            weight = PurchaseWeight.SIGNIFICANT,
            questionsToAsk = listOfNotNull(nextQuestion),
            wantScore = score,
            outcome = outcome,
            isComplete = nextQuestion == null,
            deltas = emptyList(),
            coolingOffRequired = false,
            coolingOffHours = 24,
            provenance = EngineProvenance("AI-PA-INT", "1.0", 0L),
        )
    }
}

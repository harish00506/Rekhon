package com.aicfo.domain.engines.purchase

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/**
 * AI-PA's promises, over many generated households (issue 10.1; §21.5, P-07, P-08).
 *
 * Why:  the example tests pin one household each. These pin the things that must hold for *every*
 *       household, and they are the claims a user would feel betrayed by: that asking about a dearer
 *       thing never gets a kinder answer, that the price the card calls comfortable really is, and
 *       that saying "it's urgent" never makes the app pretend an unaffordable purchase is fine.
 * What: 300 seeded households per property.
 * Result: a broken promise names its case.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
class PurchasePropertyTest {
    private val engine = PurchaseAdvisorEngineFactory.create()

    @Test
    fun `a dearer purchase never gets a kinder verdict`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val signals = household(random)
            val cheap = Money(random.nextLong(1L, 50_000_00L))
            val dear = Money(cheap.minor + random.nextLong(1L, 5_00_000_00L))

            val first = advise(signals, cheap).verdict
            val second = advise(signals, dear).verdict

            assertTrue("case $case: $cheap gave $first but $dear gave $second", rank(second) >= rank(first))
        }
    }

    @Test
    fun `the price the card calls comfortable is comfortable`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val signals = household(random)
            val card = advise(signals, Money(random.nextLong(1L, 5_00_000_00L)))
            val comfortable = card.alternatives.comfortablePrice ?: return@repeat

            assertEquals("case $case: at $comfortable", Verdict.COMFORTABLE, advise(signals, comfortable).verdict)
        }
    }

    @Test
    fun `urgency never makes a verdict worse`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val signals = household(random)
            val price = Money(random.nextLong(1L, 3_00_000_00L))

            val routine = advise(signals, price, Urgency.ROUTINE).verdict
            val urgent = advise(signals, price, Urgency.URGENT).verdict

            assertTrue("case $case", rank(urgent) <= rank(routine))
        }
    }

    @Test
    fun `a purchase that breaks something is never called comfortable, however urgent`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val card = advise(household(random), Money(random.nextLong(1L, 9_00_000_00L)), Urgency.URGENT)

            if (card.hardFail) assertEquals("case $case", Verdict.NOT_NOW, card.verdict)
        }
    }

    @Test
    fun `every card answers all seven gates, each citing a rule`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val card = advise(household(random), Money(random.nextLong(0L, 9_00_000_00L)))

            assertEquals("case $case", GateId.entries.size, card.gates.size)
            assertEquals("case $case", GateId.entries, card.gates.map { it.gate })
            assertTrue("case $case: a gate cited nothing", card.gates.all { it.citations.isNotEmpty() })
        }
    }

    @Test
    fun `the same question is answered the same way twice`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val signals = household(random)
            val price = Money(random.nextLong(1L, 3_00_000_00L))

            assertEquals("case $case", advise(signals, price), advise(signals, price))
        }
    }

    /** Result: a household whose figures are internally coherent. Input: [random]. */
    private fun household(random: Random): PurchaseSignals {
        val liquid = Money(random.nextLong(10_000_00L, 10_00_000_00L))
        val income = Money(random.nextLong(20_000_00L, 5_00_000_00L))
        return PurchaseSignals(
            liquidFunds = liquid,
            emergencyFloor = Money(random.nextLong(0L, liquid.minor)),
            monthlyEssentials = Money(random.nextLong(1_000_00L, income.minor)),
            safeToSpend = Money(random.nextLong(0L, income.minor)),
            // The forecast watches the same account, so its low point never exceeds today's balance.
            forecastLowest = Money(random.nextLong(0L, liquid.minor)),
            forecastBuffer = Money(random.nextLong(0L, 20_000_00L)),
            forecastCrunchDays = random.nextInt(0, 4),
            monthlyIncome = income,
            monthlyObligations = Money(random.nextLong(0L, income.minor)),
            goalContributionsMonthly = Money(random.nextLong(0L, 50_000_00L)),
            categoryRemaining = if (random.nextBoolean()) Money(random.nextLong(0L, 50_000_00L)) else null,
            // No cheaper month: the timing gate warns on its own, which would mask the properties
            // about price and urgency that these cases are actually about.
            cheaperMonth = null,
        )
    }

    private fun advise(
        signals: PurchaseSignals,
        price: Money,
        urgency: Urgency = Urgency.ROUTINE,
    ): PurchaseVerdictCard =
        engine.advise(
            PurchaseInput(
                request = PurchaseRequest("Item", price, PaymentMethod.CASH, urgency),
                signals = signals,
                today = TODAY,
                nowUtcMillis = 0L,
            ),
        ).expectOk()

    /** Result: how bad a verdict is, so two can be compared. */
    private fun rank(verdict: Verdict): Int =
        when (verdict) {
            Verdict.COMFORTABLE -> 0
            Verdict.STRETCH -> 1
            Verdict.NOT_NOW -> 2
        }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val CASES = 300
        val TODAY: LocalDate = LocalDate.parse("2026-09-25")
    }
}

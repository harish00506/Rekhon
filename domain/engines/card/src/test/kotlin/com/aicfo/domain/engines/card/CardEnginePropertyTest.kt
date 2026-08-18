package com.aicfo.domain.engines.card

import com.aicfo.core.common.Ok
import com.aicfo.core.model.CreditCard
import com.aicfo.core.model.Money
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/**
 * Identities that must hold for every card, on every day (issue 6.1; §21.5, P-08).
 *
 * Why:  the golden file pins fifteen cases somebody thought of. These are the statements that have
 *       to be true for the cases nobody thought of — the ones a real user's dates and amounts will
 *       find. Each is chosen because breaking it is *plausible* and would not fail any example
 *       test: an unclamped `withDayOfMonth` throws only in February, a sign slip in the due-date
 *       comparison only shows on cards whose due day precedes their statement day, and a ratio that
 *       overflows only does so at limits nobody writes a fixture for.
 * What: 1 000 seeded cards across 1 000 dates, checked against five invariants.
 * Result: the arithmetic holds off the tested path, and holds identically on every run.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 *
 * **Seeded, never `Random()`** (P-08). A property test that generates a different thousand cases
 * each run is a flake generator: it fails once on somebody's machine, passes on the rerun, and
 * teaches the team to rerun.
 */
class CardEnginePropertyTest {
    private val engine = CardEngineFactory.create()

    /**
     * Input:  1 000 generated card-and-date pairs.
     * Output: asserts the five identities, naming the case that broke if one does.
     */
    @Test
    fun `the identities hold across a thousand generated cards`() {
        generated().forEach { (card, today, outstanding) ->
            val case = "limit=${card.creditLimit.minor} stmt=${card.statementDay} due=${card.dueDay} on=$today"
            val status = (engine.status(CardStatusInput(card, today, outstanding)) as Ok).value
            val cycle = status.cycle

            // 1. A statement is always cut before its payment falls due. The constructor's `require`
            //    enforces it, so this is really asserting the *resolver* never builds a bad cycle —
            //    a due day before the statement day is the case that would.
            assertWithMessage("statement after due — %s", case)
                .that(cycle.statementDate < cycle.dueDate).isTrue()

            // 2. The cycle contains today. `statementDate` walks backwards to the statement already
            //    cut, so today can never precede it, and the next statement is always ahead.
            assertWithMessage("today outside its own cycle — %s", case)
                .that(cycle.statementDate <= today && today < cycle.nextStatementDate).isTrue()

            // 3. A billing cycle is a month, so the payment is never more than two months out — a
            //    resolver that added a month too many would still look plausible in isolation.
            assertWithMessage("due date implausibly far away — %s", case)
                .that(cycle.daysUntilDue in -MAX_CYCLE_DAYS..MAX_CYCLE_DAYS).isTrue()

            // 4. Utilisation and availability describe the same balance from two sides, so they
            //    cannot disagree about whether the limit has been reached.
            val atOrOverLimit = status.live.ratioBps!! >= BPS_FULL
            assertWithMessage("available money on an exhausted card — %s", case)
                .that(atOrOverLimit && status.available > Money.ZERO).isFalse()

            // 5. Unbilled is spend since the statement, so it can never exceed everything owed.
            assertWithMessage("unbilled exceeds the whole balance — %s", case)
                .that(status.unbilled <= outstanding).isTrue()
        }
    }

    /**
     * Input:  a card and two balances one paise apart.
     * Output: asserts utilisation never falls as the balance rises. Monotonicity is the property a
     *         rounding change would break, and no single example test would notice.
     */
    @Test
    fun `utilisation never decreases as the balance rises`() {
        val random = Random(MONOTONIC_SEED)
        repeat(CASES) {
            val limit = Money(random.nextLong(1_00, 50_00_000_00))
            val lower = Money(random.nextLong(0, limit.minor))
            val higher = Money(lower.minor + random.nextLong(1, 10_000_00))

            val a = CardUtilisations.ratioBps(lower, limit)!!
            val b = CardUtilisations.ratioBps(higher, limit)!!
            assertWithMessage("utilisation fell from %s to %s at limit %s", lower.minor, higher.minor, limit.minor)
                .that(b >= a).isTrue()
        }
    }

    /**
     * Input:  the same generated cases, twice.
     * Output: asserts byte-identical results. The engine reads no clock and no random source, so
     *         two runs must agree — this is what makes the golden file's promise meaningful.
     */
    @Test
    fun `the same inputs give the same answers twice`() {
        val first = generated().map(::alertFor)
        val second = generated().map(::alertFor)

        assertThat(first).isEqualTo(second)
    }

    /** Result: the engine's answer for one generated case. Input: [case]. Output: the result. */
    private fun alertFor(case: Triple<CreditCard, LocalDate, Money>) =
        engine.alert(CardAlertInput(case.first, case.second, case.third))

    /**
     * Builds the generated cases.
     * Why:    one generator, used by three tests, so "a thousand cards" means the same thousand in
     *         each — and so the determinism test above is comparing like with like.
     * Result: card, date and outstanding balance triples. Input: none. Output: the cases.
     */
    private fun generated(): List<Triple<CreditCard, LocalDate, Money>> {
        val random = Random(SEED)
        return List(CASES) {
            val limit = Money(random.nextLong(1_00, 50_00_000_00))
            val outstanding = Money(random.nextLong(0, limit.minor * 2))
            val card =
                CreditCard(
                    accountId = "account:$it",
                    creditLimit = limit,
                    statementDay = random.nextInt(1, 32),
                    dueDay = random.nextInt(1, 32),
                    lastStatement = if (random.nextBoolean()) Money(random.nextLong(0, limit.minor)) else null,
                    minimumDue = null,
                )
            // Five years of dates, so February, leap February and every month length are covered.
            val today = LocalDate.of(2026, 1, 1).plusDays(random.nextLong(0, 5 * 365))
            Triple(card, today, outstanding)
        }
    }

    private companion object {
        const val CASES = 1_000

        /** Fixed, so a failure is reproducible by rerunning rather than by luck (P-08). */
        const val SEED = 20_260_817L
        const val MONOTONIC_SEED = 20_260_818L

        /** A statement plus a due day is at most two month-lengths apart. */
        const val MAX_CYCLE_DAYS = 62
    }
}

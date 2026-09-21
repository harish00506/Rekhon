package com.aicfo.domain.engines.notification

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * AI-NTF's promises, over many generated days (issue 9.6; §21.5, NTF-001, P-08).
 *
 * Why:  the caps are a promise about the *whole* day, not about one decision, and the exemption is
 *       a promise about every critical event. Those are properties, not examples: the policy could
 *       pass every boundary test and still, on some combination, deliver three in a day or hold a
 *       bill that is due tomorrow.
 * What: 300 seeded days per property — a random clock, a random history, random candidates.
 * Result: a broken promise names its case.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
class NotificationPropertyTest {
    private val engine = NotificationPolicyEngineFactory.create()

    @Test
    fun `the policy never delivers more than the day's ration has left`() {
        // What is asserted is what the policy controls: how many it *adds* today. A history that
        // already exceeds the cap is possible — the caps can be lowered, and a day can be imported
        // — and the right answer to that is to send nothing more, which `remaining` expresses.
        cases { input ->
            val plan = engine.decide(input).expectOk()
            val alreadyToday =
                input.history.count { it.sentAt.toLocalDate() == input.now.toLocalDate() && !it.kind.critical }
            val remaining = maxOf(0, input.rules.dailyMax - alreadyToday)
            val delivered = plan.deliverable.count { !it.kind.critical }
            assertTrue("$delivered delivered with $remaining left of the day", delivered <= remaining)
        }
    }

    @Test
    fun `nor more than the window's ration has left`() {
        cases { input ->
            val plan = engine.decide(input).expectOk()
            val windowStart = input.now.minusDays(input.rules.windowDays.toLong())
            val already = input.history.count { it.sentAt > windowStart && !it.kind.critical }
            val remaining = maxOf(0, input.rules.weeklyMax - already)
            assertTrue(plan.deliverable.count { !it.kind.critical } <= remaining)
        }
    }

    @Test
    fun `a critical money event is always delivered under the shipped rules`() {
        cases { input ->
            val plan = engine.decide(input).expectOk()
            val sentKeys = input.history.map { it.key }.toSet()
            input.candidates.filter { it.kind.critical && it.key !in sentKeys }.forEach { critical ->
                assertEquals(
                    "a critical event was not delivered: $critical",
                    NotificationOutcome.DELIVER,
                    plan.decisions.first { it.candidate == critical }.outcome,
                )
            }
        }
    }

    @Test
    fun `nothing already sent is sent again, and nothing is decided twice`() {
        cases { input ->
            val plan = engine.decide(input).expectOk()
            val sentKeys = input.history.map { it.key }.toSet()
            assertTrue(plan.deliverable.none { it.key in sentKeys })
            assertEquals(input.candidates, plan.decisions.map { it.candidate })
        }
    }

    @Test
    fun `a held message names when the wait ends, and that is later than now`() {
        cases { input ->
            engine.decide(input).expectOk().decisions.forEach { decision ->
                if (decision.outcome == NotificationOutcome.WAIT_FOR_QUIET_HOURS) {
                    assertTrue("${decision.deliverAfter} !> ${input.now}", decision.deliverAfter!! > input.now)
                } else {
                    assertEquals(null, decision.deliverAfter)
                }
            }
        }
    }

    @Test
    fun `the same day decided twice decides the same way`() {
        cases { input -> assertEquals(engine.decide(input).expectOk(), engine.decide(input).expectOk()) }
    }

    private fun cases(check: (NotificationInput) -> Unit) {
        repeat(CASES) { case ->
            val input = generate(Random(case.toLong()))
            try {
                check(input)
            } catch (failure: AssertionError) {
                throw AssertionError("case $case: ${failure.message}", failure)
            }
        }
    }

    private fun generate(random: Random): NotificationInput {
        val now = LocalDateTime.parse("2026-09-20T00:00").plusMinutes(random.nextLong(0, 60 * 24 * 30))
        val kinds = NotificationKind.entries
        val history =
            (0 until random.nextInt(0, 12)).map {
                SentNotification(
                    key = "sent-$it",
                    kind = kinds[random.nextInt(kinds.size)],
                    sentAt = now.minusMinutes(random.nextLong(1, 60 * 24 * 10)),
                )
            }
        val candidates =
            (0 until random.nextInt(0, 6)).map {
                // Some candidates deliberately repeat a key already in the history.
                val key = if (random.nextInt(5) == 0 && history.isNotEmpty()) history[0].key else "candidate-$it"
                NotificationCandidate(key, kinds[random.nextInt(kinds.size)])
            }
        return NotificationInput(candidates, history, now, 0L)
    }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val CASES = 300
    }
}

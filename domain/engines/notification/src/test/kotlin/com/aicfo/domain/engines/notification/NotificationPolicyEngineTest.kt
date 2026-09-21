package com.aicfo.domain.engines.notification

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * AI-NTF's policy, one decision per test (issue 9.6; §17.2 NTF-001/002, the acceptance criterion
 * "covered at cap and quiet-hour boundaries").
 *
 * Why:  every rule here is a boundary, and a boundary is where an off-by-one is invisible: a
 *       notification at 21:59 versus 22:00, the second of the day versus the third, the eighth of
 *       the week versus the ninth, a send eight days ago that should no longer count. Each one
 *       either wakes someone at night or silently swallows the message that mattered.
 * What: the quiet-hour edges and where the wait ends; both caps and their edges; the exemptions;
 *       the ration spent in order within one plan; duplicates; refusals; provenance; the rules seam.
 * Result: a regression in any of them names itself.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
class NotificationPolicyEngineTest {
    private val engine = NotificationPolicyEngineFactory.create()

    @Test
    fun `nothing to say is not a decision to make`() {
        val plan = plan(candidates = emptyList())

        assertTrue(plan.decisions.isEmpty())
        assertTrue(plan.deliverable.isEmpty())
    }

    @Test
    fun `an ordinary notification in ordinary hours is delivered, citing both rules`() {
        val decision = plan(candidates = listOf(budget("b1"))).decisions.single()

        assertEquals(NotificationOutcome.DELIVER, decision.outcome)
        assertNull(decision.deliverAfter)
        assertEquals(listOf(NotificationRules.BUDGET, NotificationRules.QUIET), decision.citations)
    }

    @Test
    fun `the quiet window starts at its hour and not a minute before`() {
        assertEquals(NotificationOutcome.DELIVER, at("2026-09-20T21:59").decisions.single().outcome)
        assertEquals(NotificationOutcome.WAIT_FOR_QUIET_HOURS, at("2026-09-20T22:00").decisions.single().outcome)
    }

    @Test
    fun `the quiet window ends at its hour, and the wait is until that hour`() {
        assertEquals(NotificationOutcome.WAIT_FOR_QUIET_HOURS, at("2026-09-21T07:59").decisions.single().outcome)
        assertEquals(NotificationOutcome.DELIVER, at("2026-09-21T08:00").decisions.single().outcome)
        assertEquals(LocalDateTime.parse("2026-09-21T08:00"), at("2026-09-21T07:59").decisions.single().deliverAfter)
        // Late at night the wait runs to the next morning, not to one that has already passed.
        assertEquals(LocalDateTime.parse("2026-09-21T08:00"), at("2026-09-20T23:30").decisions.single().deliverAfter)
    }

    @Test
    fun `a critical money event is said at any hour`() {
        val decision = at("2026-09-21T03:00", candidate = critical("c1")).decisions.single()

        assertEquals(NotificationOutcome.DELIVER, decision.outcome)
    }

    @Test
    fun `the day's ration is two, and the third folds into the digest`() {
        val plan =
            plan(
                candidates = listOf(budget("b1")),
                history = listOf(sent("earlier-1", "2026-09-20T09:00"), sent("earlier-2", "2026-09-20T09:30")),
            )

        assertEquals(NotificationOutcome.FOLD_INTO_DIGEST, plan.decisions.single().outcome)
        assertEquals(listOf(NotificationRules.BUDGET), plan.decisions.single().citations)
    }

    @Test
    fun `one sent today leaves room for exactly one more`() {
        val plan =
            plan(
                candidates = listOf(budget("b1"), budget("b2")),
                history = listOf(sent("earlier-1", "2026-09-20T09:00")),
            )

        assertEquals(
            listOf(NotificationOutcome.DELIVER, NotificationOutcome.FOLD_INTO_DIGEST),
            plan.decisions.map { it.outcome },
        )
    }

    @Test
    fun `yesterday's notifications do not count against today`() {
        val plan =
            plan(
                candidates = listOf(budget("b1")),
                history = listOf(sent("y1", "2026-09-19T23:00"), sent("y2", "2026-09-19T23:30")),
            )

        assertEquals(NotificationOutcome.DELIVER, plan.decisions.single().outcome)
    }

    @Test
    fun `the week's ration is eight, counted over the rule's window`() {
        assertEquals(
            NotificationOutcome.FOLD_INTO_DIGEST,
            plan(listOf(budget("b1")), fullWeek()).decisions.single().outcome,
        )
        // Move the oldest of the eight outside the seven-day window and there is room again.
        val aged = fullWeek().drop(1) + sent("aged", "2026-09-11T09:00")
        assertEquals(NotificationOutcome.DELIVER, plan(listOf(budget("b1")), aged).decisions.single().outcome)
    }

    @Test
    fun `a critical event ignores both caps`() {
        assertEquals(NotificationOutcome.DELIVER, plan(listOf(critical("c1")), fullWeek()).decisions.single().outcome)
    }

    @Test
    fun `a message already sent is not said twice, and costs nothing from the ration`() {
        val plan =
            plan(
                candidates = listOf(budget("b1"), budget("b2")),
                history =
                    listOf(
                        SentNotification(
                            "b1",
                            NotificationKind.BUDGET_DISCIPLINE,
                            LocalDateTime.parse("2026-09-20T09:00"),
                        ),
                    ),
            )

        assertEquals(
            listOf(NotificationOutcome.ALREADY_SENT, NotificationOutcome.DELIVER),
            plan.decisions.map { it.outcome },
        )
        assertTrue(
            "a repeat cites no rule; it is not the policy that stopped it",
            plan.decisions.first().citations.isEmpty(),
        )
    }

    @Test
    fun `candidates spend the ration in the order they were offered`() {
        val plan = plan(candidates = listOf(budget("b1"), budget("b2"), budget("b3")))

        assertEquals(
            listOf(NotificationOutcome.DELIVER, NotificationOutcome.DELIVER, NotificationOutcome.FOLD_INTO_DIGEST),
            plan.decisions.map { it.outcome },
        )
        assertEquals(listOf("b1", "b2"), plan.deliverable.map { it.key })
    }

    @Test
    fun `a message held for the morning does not spend the day's ration`() {
        val plan = at("2026-09-20T23:00", candidates = listOf(budget("b1"), critical("c1"), budget("b2")))

        assertEquals(
            listOf(
                NotificationOutcome.WAIT_FOR_QUIET_HOURS,
                NotificationOutcome.DELIVER,
                NotificationOutcome.WAIT_FOR_QUIET_HOURS,
            ),
            plan.decisions.map { it.outcome },
        )
    }

    @Test
    fun `impossible inputs are refused by field`() {
        assertEquals(AppError.Validation("notification.key"), error(plan = input(candidates = listOf(budget(" ")))))
        assertEquals(
            AppError.Validation("notification.history"),
            error(plan = input(history = listOf(sent("future", "2026-09-21T09:00")))),
        )
    }

    @Test
    fun `provenance names the engine, its two rules and the day it decided`() {
        val provenance = plan(candidates = listOf(budget("b1"))).provenance

        assertEquals("AI-NTF", provenance.engineId)
        assertEquals("1.0", provenance.engineVersion)
        assertEquals(NOW_MILLIS, provenance.computedAtUtcMillis)
        assertEquals(listOf(NotificationRules.BUDGET, NotificationRules.QUIET), provenance.evidence)
        assertEquals("2026-09-13..2026-09-20", provenance.inputWindow)
    }

    @Test
    fun `the caps and the window are the rulebook's, not the engine's`() {
        val silent = NotificationRules(dailyMax = 0, weeklyMax = 0)
        assertEquals(
            NotificationOutcome.FOLD_INTO_DIGEST,
            plan(candidates = listOf(budget("b1")), rules = silent).decisions.single().outcome,
        )

        val strict = NotificationRules(criticalExempt = false, criticalMayBypassQuietHours = false)
        assertEquals(
            NotificationOutcome.FOLD_INTO_DIGEST,
            plan(listOf(critical("c1")), fullWeek(), rules = strict).decisions.single().outcome,
        )
        assertEquals(
            NotificationOutcome.WAIT_FOR_QUIET_HOURS,
            at("2026-09-21T03:00", candidate = critical("c1"), rules = strict).decisions.single().outcome,
        )
    }

    // --- fixtures -----------------------------------------------------------------------------------

    /** Eight sends inside the window ending "now", one of them today — the week's whole ration. */
    private fun fullWeek(): List<SentNotification> =
        listOf(
            sent("w1", "2026-09-14T09:00"),
            sent("w2", "2026-09-15T09:00"),
            sent("w3", "2026-09-16T09:00"),
            sent("w4", "2026-09-17T09:00"),
            sent("w5", "2026-09-18T09:00"),
            sent("w6", "2026-09-19T09:00"),
            sent("w7", "2026-09-19T13:00"),
            sent("w8", "2026-09-20T09:00"),
        )

    private fun budget(key: String) = NotificationCandidate(key, NotificationKind.BUDGET_DISCIPLINE)

    private fun critical(key: String) = NotificationCandidate(key, NotificationKind.CRITICAL_MONEY)

    private fun sent(
        key: String,
        at: String,
    ) = SentNotification(key, NotificationKind.BUDGET_DISCIPLINE, LocalDateTime.parse(at))

    private fun input(
        candidates: List<NotificationCandidate> = listOf(budget("b1")),
        history: List<SentNotification> = emptyList(),
        now: String = "2026-09-20T10:00",
        rules: NotificationRules = NotificationRules(),
    ) = NotificationInput(candidates, history, LocalDateTime.parse(now), NOW_MILLIS, rules)

    private fun plan(
        candidates: List<NotificationCandidate> = listOf(budget("b1")),
        history: List<SentNotification> = emptyList(),
        rules: NotificationRules = NotificationRules(),
    ): NotificationPlan = engine.decide(input(candidates, history, rules = rules)).expectOk()

    private fun at(
        now: String,
        candidate: NotificationCandidate = budget("b1"),
        candidates: List<NotificationCandidate> = listOf(candidate),
        rules: NotificationRules = NotificationRules(),
    ): NotificationPlan = engine.decide(input(candidates, emptyList(), now, rules)).expectOk()

    private fun error(plan: NotificationInput): AppError =
        when (val result = engine.decide(plan)) {
            is Ok -> throw AssertionError("expected Err, got ${result.value}")
            is Err -> result.error
        }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val NOW_MILLIS = 1_789_800_000_000L
    }
}

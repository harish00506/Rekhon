package com.aicfo.domain.engines.notification

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * The golden-file gate for AI-NTF (issue 9.6; §21.5).
 *
 * Why:  the policy's value is that it is the **same** policy every day, and its rules interact:
 *       quiet hours before caps, a held message costing nothing, a repeat costing nothing, a
 *       critical event ignoring both. Four fixed days are compared with an **independent** oracle
 *       (`golden/notification_oracle.py`), which reads the rulebook and re-applies §17.2 itself.
 * What: an ordinary morning with room to spare; a late evening; a day whose ration is spent; and a
 *       week that is full, with a repeat in it.
 * Result: a change to any rule or to the order they are applied in fails naming the line.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
class NotificationGoldenTest {
    private val golden: List<String> by lazy {
        (
            javaClass.classLoader.getResource("golden/notification.txt")?.readText()
                ?: throw AssertionError("golden/notification.txt is missing")
        ).lines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    @Test
    fun `every fixed day decides exactly as the oracle does`() {
        val actual = SCENARIOS.flatMap { scenario -> lines(scenario) }

        assertEquals(golden, actual)
    }

    private fun lines(scenario: Scenario): List<String> {
        val input =
            NotificationInput(
                candidates = scenario.candidates,
                history = scenario.history,
                now = LocalDateTime.parse(scenario.now),
                nowUtcMillis = 0L,
            )
        val plan =
            when (val result = NotificationPolicyEngineFactory.create().decide(input)) {
                is Ok -> result.value
                is Err -> throw AssertionError("${result.error}")
            }
        return plan.decisions.map {
            "decision ${scenario.name} ${it.candidate.key} ${it.outcome} ${it.deliverAfter ?: "-"}"
        }
    }

    /** One fixed day: the clock, what has been said already, and what the app would like to say. */
    private data class Scenario(
        val name: String,
        val now: String,
        val history: List<SentNotification>,
        val candidates: List<NotificationCandidate>,
    )

    private companion object {
        private fun sent(
            key: String,
            kind: NotificationKind,
            at: String,
        ) = SentNotification(key, kind, LocalDateTime.parse(at))

        /** The oracle's `SCENARIOS`, line for line. */
        val SCENARIOS =
            listOf(
                Scenario(
                    name = "morning_quiet_week",
                    now = "2026-09-20T09:15",
                    history =
                        listOf(
                            sent("w1", NotificationKind.BUDGET_DISCIPLINE, "2026-09-15T09:00"),
                            sent("w2", NotificationKind.AI_INSIGHT, "2026-09-17T18:00"),
                        ),
                    candidates =
                        listOf(
                            NotificationCandidate("crunch:2026-10-05", NotificationKind.CRITICAL_MONEY),
                            NotificationCandidate("budget:dining", NotificationKind.BUDGET_DISCIPLINE),
                            NotificationCandidate("insight:seasonal-2026-10", NotificationKind.AI_INSIGHT),
                            NotificationCandidate("goal:kerala", NotificationKind.GOAL_EVENT),
                        ),
                ),
                Scenario(
                    name = "late_evening",
                    now = "2026-09-20T22:30",
                    history = emptyList(),
                    candidates =
                        listOf(
                            NotificationCandidate("crunch:2026-10-05", NotificationKind.CRITICAL_MONEY),
                            NotificationCandidate("budget:dining", NotificationKind.BUDGET_DISCIPLINE),
                        ),
                ),
                Scenario(
                    name = "ration_spent",
                    now = "2026-09-20T11:00",
                    history =
                        listOf(
                            sent("a", NotificationKind.BUDGET_DISCIPLINE, "2026-09-20T08:10"),
                            sent("b", NotificationKind.AI_INSIGHT, "2026-09-20T09:40"),
                        ),
                    candidates =
                        listOf(
                            NotificationCandidate("budget:fuel", NotificationKind.BUDGET_DISCIPLINE),
                            NotificationCandidate("crunch:2026-10-05", NotificationKind.CRITICAL_MONEY),
                        ),
                ),
                Scenario(
                    name = "repeat_and_week_full",
                    now = "2026-09-20T12:00",
                    history =
                        listOf(
                            "2026-09-14T09:00",
                            "2026-09-15T09:00",
                            "2026-09-16T09:00",
                            "2026-09-17T09:00",
                            "2026-09-18T09:00",
                            "2026-09-19T09:00",
                            "2026-09-19T13:00",
                            "2026-09-20T09:00",
                        ).mapIndexed { index, at -> sent("w${index + 1}", NotificationKind.AI_INSIGHT, at) },
                    candidates =
                        listOf(
                            NotificationCandidate("w3", NotificationKind.AI_INSIGHT),
                            NotificationCandidate("insight:lever", NotificationKind.AI_INSIGHT),
                            NotificationCandidate("bill:electricity", NotificationKind.CRITICAL_MONEY),
                        ),
                ),
            )
    }
}

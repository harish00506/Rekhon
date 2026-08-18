package com.aicfo.domain.engines.card

import com.aicfo.core.common.Ok
import com.aicfo.core.model.CreditCard
import com.aicfo.core.model.Money
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

private val TODAY: LocalDate = LocalDate.of(2026, 3, 22)

/** A ₹2,00,000 card, statement on the 5th, due on the 25th, ₹70,000 on the last statement (35%). */
private fun card(
    limit: Long = 2_00_000_00,
    statement: Long? = 70_000_00,
    minimumDue: Long? = 3_500_00,
    statementDay: Int = 5,
    dueDay: Int = 25,
) = CreditCard(
    accountId = "account:1",
    creditLimit = Money(limit),
    statementDay = statementDay,
    dueDay = dueDay,
    lastStatement = statement?.let(::Money),
    lastStatementIsoDate = statement?.let { "2026-03-05" },
    minimumDue = minimumDue?.let(::Money),
    aprBps = 42_00,
)

/**
 * Boundary tests for [CardEngine] (issue 6.1; FR-ACC-002, §17.1).
 *
 * Why:  every threshold here is read from [CardRules] rather than written as a literal, so moving a
 *       rulebook number moves the test with it — the point of injecting the rules at all. What the
 *       assertions pin is the *shape* of each decision, which is where the mistakes live:
 *
 *       the reminder window is inclusive at both ends (three days out **and** the day itself);
 *       it closes the moment the date passes; the utilisation band is `>=`, because the rule says
 *       30% and not "past 30%"; and the alert reads the **statement** figure while the screen reads
 *       the **live** one — swap those two and the app nags on every coffee.
 * What: the window edges, the band edge, both utilisation bases, and the absent-figure cases.
 * Result: the two rules fire exactly where their rows say they do.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
class CardEngineTest {
    private val engine = CardEngineFactory.create()
    private val rules = CardRules()

    // --- the reminder window (RULE-CC-DUE) ------------------------------------------------------

    /** Input: exactly `remind_days_before` from the due date. Output: asserts the window is inclusive. */
    @Test
    fun `the reminder fires on the first day of the window`() {
        val today = LocalDate.of(2026, 3, 25).minusDays(rules.remindDaysBefore.toLong())

        val alerts = engine.alerts(CardAlertInput(card(), today, Money(70_000_00)))

        assertThat(alerts.map { it.kind }).contains(CardAlertKind.DUE_SOON)
        assertThat(alerts.first { it.kind == CardAlertKind.DUE_SOON }.daysUntilDue)
            .isEqualTo(rules.remindDaysBefore)
    }

    /**
     * Input: one day earlier than the window.
     * Output: asserts silence. The off-by-one on the far edge is the one that turns a three-day
     * reminder into a four-day one and nobody notices.
     */
    @Test
    fun `the reminder is silent one day before the window opens`() {
        val today = LocalDate.of(2026, 3, 25).minusDays(rules.remindDaysBefore + 1L)

        val alerts = engine.alerts(CardAlertInput(card(), today, Money(70_000_00)))

        assertThat(alerts.map { it.kind }).doesNotContain(CardAlertKind.DUE_SOON)
    }

    /** Input: the due date itself. Output: asserts `remind_on_due_day` is honoured. */
    @Test
    fun `the reminder fires again on the due day`() {
        val alerts = engine.alerts(CardAlertInput(card(), LocalDate.of(2026, 3, 25), Money(70_000_00)))

        assertThat(alerts.map { it.kind }).contains(CardAlertKind.DUE_SOON)
    }

    /** Input: the due day with the rule turned off. Output: asserts the flag actually gates it. */
    @Test
    fun `the due-day reminder obeys its rule flag`() {
        val alerts =
            engine.alerts(
                CardAlertInput(
                    card = card(),
                    today = LocalDate.of(2026, 3, 25),
                    outstanding = Money(70_000_00),
                    rules = rules.copy(remindOnDueDay = false),
                ),
            )

        assertThat(alerts.map { it.kind }).doesNotContain(CardAlertKind.DUE_SOON)
    }

    /**
     * Input: the day after the due date.
     * Output: asserts no reminder. An overdue card needs a different message, and inventing one
     * here would ship copy that no rule row authorised.
     */
    @Test
    fun `an overdue card gets no reminder from this rule`() {
        val alerts = engine.alerts(CardAlertInput(card(), LocalDate.of(2026, 3, 26), Money(70_000_00)))

        assertThat(alerts.map { it.kind }).doesNotContain(CardAlertKind.DUE_SOON)
    }

    /**
     * Input: a card inside the window that has been paid off.
     * Output: asserts silence, per `skip_when_nothing_due` — the reminder to pay ₹0 is what teaches
     * a user to mute the one channel that must still work in eleven months.
     */
    @Test
    fun `a settled card is not reminded`() {
        val alerts = engine.alerts(CardAlertInput(card(), LocalDate.of(2026, 3, 24), Money.ZERO))

        assertThat(alerts.map { it.kind }).doesNotContain(CardAlertKind.DUE_SOON)
    }

    /** Input: a card with no statement recorded. Output: asserts there is nothing to remind about. */
    @Test
    fun `a card with no statement is not reminded`() {
        val alerts = engine.alerts(CardAlertInput(card(statement = null), LocalDate.of(2026, 3, 24), Money(9_000_00)))

        assertThat(alerts).isEmpty()
    }

    // --- the utilisation band (RULE-CC-UTIL) ----------------------------------------------------

    /** Input: a statement exactly at the threshold. Output: asserts `>=` — the boundary is inside. */
    @Test
    fun `the utilisation alert fires exactly at the threshold`() {
        val limit = 2_00_000_00L
        val atLine = limit * rules.maxUtilisationPct / 100

        val alerts = engine.alerts(CardAlertInput(card(limit = limit, statement = atLine), TODAY, Money(atLine)))

        assertThat(alerts.map { it.kind }).contains(CardAlertKind.UTILISATION)
    }

    /**
     * Input:  a limit the ratio cannot be inverted back to — ₹2,00,000 against a ₹97,637 statement
     *         is 48.81…%, which truncates to 4 881 bps.
     * Output: asserts the alert carries the **actual** limit.
     *
     * The regression this locks: `CardAlertNotifier` used to recover the limit as
     * `amount x 10 000 / ratioBps`, and that arithmetic put "₹2,00,034.82" on a real phone during
     * issue 6.1's device run. The guardrail passed it, because it was handed the same wrong number
     * — which is the whole reason P-03 says the engine emits every figure and the words never
     * re-derive one.
     */
    @Test
    fun `the alert carries the real limit, not one recovered from the ratio`() {
        val limit = 2_00_000_00L
        val statement = 97_637_00L

        val alert =
            engine.alerts(CardAlertInput(card(limit = limit, statement = statement), TODAY, Money(statement)))
                .single { it.kind == CardAlertKind.UTILISATION }

        assertThat(alert.creditLimit).isEqualTo(Money(limit))
        // The inversion that produced the bug, asserted as *not* the answer.
        assertThat(Money(alert.amount.minor * 10_000 / alert.ratioBps!!.toLong())).isNotEqualTo(Money(limit))
    }

    /** Input: one paise below the threshold. Output: asserts silence — never a rupee early. */
    @Test
    fun `the utilisation alert is silent one paise below the threshold`() {
        val limit = 2_00_000_00L
        val justUnder = limit * rules.maxUtilisationPct / 100 - 1

        val alerts = engine.alerts(CardAlertInput(card(limit = limit, statement = justUnder), TODAY, Money(justUnder)))

        assertThat(alerts.map { it.kind }).doesNotContain(CardAlertKind.UTILISATION)
    }

    /**
     * Input: a card whose **live** balance is far over the line but whose statement is under it.
     * Output: asserts no alert. This is the decision the whole feature turns on: alerting on the
     * live figure would fire on the way home from the shops and re-fire all cycle.
     */
    @Test
    fun `the alert reads the statement figure, not the live one`() {
        val alerts = engine.alerts(CardAlertInput(card(statement = 10_000_00), TODAY, Money(1_90_000_00)))

        assertThat(alerts.map { it.kind }).doesNotContain(CardAlertKind.UTILISATION)
    }

    /** Input: a card both due and over the line. Output: asserts two alerts, due first. */
    @Test
    fun `a card can be both due and over the line`() {
        val alerts = engine.alerts(CardAlertInput(card(), LocalDate.of(2026, 3, 24), Money(70_000_00)))

        assertThat(alerts.map { it.kind })
            .containsExactly(CardAlertKind.DUE_SOON, CardAlertKind.UTILISATION)
            .inOrder()
    }

    /** Input: any alert. Output: asserts each cites exactly the one rule that decided it (P-02). */
    @Test
    fun `each alert cites the rule that fired it and no other`() {
        val alerts = engine.alerts(CardAlertInput(card(), LocalDate.of(2026, 3, 24), Money(70_000_00)))

        val due = alerts.first { it.kind == CardAlertKind.DUE_SOON }
        val utilisation = alerts.first { it.kind == CardAlertKind.UTILISATION }
        assertThat(due.provenance.evidence).containsExactly(CardRules.DUE)
        assertThat(utilisation.provenance.evidence).containsExactly(CardRules.UTILISATION)
    }

    /**
     * Input: any alert.
     * Output: asserts the claim key is the cycle's statement date. It is what makes "once per
     * cycle" enforceable by a UNIQUE index rather than by a flag someone has to remember to set.
     */
    @Test
    fun `an alert is keyed by the statement date of its cycle`() {
        val alerts = engine.alerts(CardAlertInput(card(), LocalDate.of(2026, 3, 24), Money(70_000_00)))

        assertThat(alerts.map { it.cycleStartIsoDate }.distinct()).containsExactly("2026-03-05")
    }

    // --- status ---------------------------------------------------------------------------------

    /** Input: a card mid-cycle. Output: asserts the two bases disagree, and each says which it is. */
    @Test
    fun `status reports both utilisations, each labelled`() {
        val status = engine.statusOf(CardStatusInput(card(), TODAY, Money(1_00_000_00)))

        assertThat(status.live.basis).isEqualTo(UtilisationBasis.LIVE)
        assertThat(status.live.ratioBps).isEqualTo(5_000)
        assertThat(status.statement.basis).isEqualTo(UtilisationBasis.STATEMENT)
        assertThat(status.statement.ratioBps).isEqualTo(3_500)
    }

    /**
     * Input: a card with no statement recorded.
     * Output: asserts the statement utilisation is `null`, not `0`. Zero would tell a user with a
     * ₹1,00,000 balance that they are using none of their limit (P-03).
     */
    @Test
    fun `an unrecorded statement is absent, not zero`() {
        val status = engine.statusOf(CardStatusInput(card(statement = null), TODAY, Money(1_00_000_00)))

        assertThat(status.statement.ratioBps).isNull()
        assertThat(status.statement.used).isNull()
        assertThat(status.live.ratioBps).isEqualTo(5_000)
    }

    /** Input: spend since the statement. Output: asserts unbilled is the difference (FR-ACC-002). */
    @Test
    fun `unbilled is what has been spent since the statement`() {
        val status = engine.statusOf(CardStatusInput(card(), TODAY, Money(85_000_00)))

        assertThat(status.unbilled).isEqualTo(Money(15_000_00))
        assertThat(status.available).isEqualTo(Money(1_15_000_00))
    }

    /**
     * Input: a card paid down below its last statement.
     * Output: asserts unbilled floors at zero rather than going negative. Documented as an
     * approximation: without a payment history the app cannot tell a payment from a reversal.
     */
    @Test
    fun `unbilled floors at zero once the statement is paid`() {
        val status = engine.statusOf(CardStatusInput(card(), TODAY, Money(5_000_00)))

        assertThat(status.unbilled).isEqualTo(Money.ZERO)
    }

    /** Input: an over-limit card. Output: asserts available floors at zero, never a credit. */
    @Test
    fun `available floors at zero on an over-limit card`() {
        val status = engine.statusOf(CardStatusInput(card(), TODAY, Money(2_20_000_00)))

        assertThat(status.available).isEqualTo(Money.ZERO)
        assertThat(status.live.ratioBps).isEqualTo(11_000)
    }

    private fun CardEngine.alerts(input: CardAlertInput): List<CardAlert> = (alert(input) as Ok).value

    private fun CardEngine.statusOf(input: CardStatusInput): CardStatus = (status(input) as Ok).value
}

package com.aicfo.domain.engines.card

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.CreditCard
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import java.time.LocalDate

/**
 * What a credit card's numbers mean (issue 6.1; FR-ACC-002, §5.7, §17.1).
 *
 * Why:  three questions that look like one. *Where am I in the billing cycle?* is calendar
 *       arithmetic with one nasty edge (a card that bills on the 31st, in February). *How much of
 *       the limit am I using?* is a ratio with two defensible numerators, and the app shows both.
 *       *Should the user be interrupted?* is the only one that can wake a person up, and it is
 *       governed by rulebook rows rather than by this code.
 *
 *       Splitting them into three operations rather than one `analyse()` means the screen can ask
 *       for a figure without the alert machinery running, and the alert worker can ask for alerts
 *       without formatting anything.
 * What: one interface, three operations, and the result types they answer with.
 * Result: dates, ratios and alerts that are reproducible from their inputs alone (P-08).
 * Changelog: 2026-08-17 — Created for issue 6.1.
 *
 * **No clock, ever** (TIM-001). `today` is an argument. Calendar *arithmetic* on that argument is
 * fine and has precedent — `DefaultRecurringEngine` advances a cadence the same way — but reading
 * the wall clock in `:domain:*` fails the build (`CfoWallClockInDomain`). It is also what makes a
 * whole billing cycle testable: the golden file simply walks `today` forward a day at a time.
 *
 * **No `Double` anywhere** (MNY-001/MNY-002): amounts are `Money` paise and every ratio is integer
 * basis points.
 */
interface CardEngine {
    /**
     * Places today inside the card's billing cycle.
     * Why:    every other answer needs this first, and it is the only part with a genuine edge case
     *         — a card whose statement day does not exist in the current month.
     * Result: `Ok(BillingCycle)`. There is no failure mode: [CreditCard] has already refused a day
     *         outside 1..31, so any date and any valid card resolve.
     * Input:  [input] — the card's two days and the date to place.
     * Output: `Result<BillingCycle, AppError>`.
     */
    fun cycle(input: CardCycleInput): Result<BillingCycle, AppError>

    /**
     * Reports the card's position: both utilisations, what is unbilled, and where the cycle is.
     * Why:    the screen needs one value, not four calls. Both utilisation bases are returned
     *         together because P-02 requires each to be shown labelled — a single figure called
     *         "utilisation" would be one of two different true numbers with no way to tell which.
     * Result: `Ok(CardStatus)`.
     * Input:  [input] — the card, today, and the live outstanding balance.
     * Output: `Result<CardStatus, AppError>`.
     */
    fun status(input: CardStatusInput): Result<CardStatus, AppError>

    /**
     * Decides what, if anything, the user should be told about this card today.
     * Why:    a **list**, unlike `BudgetEngine.alert`'s single nullable, because a card can be both
     *         due tomorrow and over its utilisation line at the same moment. They are different
     *         messages on different notification channels (§17.1 classes one as Critical and the
     *         other as discipline), so collapsing them would drop one.
     * Result: `Ok(emptyList())` is the ordinary answer and never an error — most cards on most days
     *         have nothing to say.
     * Input:  [input] — the card, today, and the live outstanding balance.
     * Output: `Result<List<CardAlert>, AppError>`.
     */
    fun alert(input: CardAlertInput): Result<List<CardAlert>, AppError>
}

/**
 * Where a date falls in a card's monthly billing cycle (issue 6.1; FR-ACC-002).
 *
 * Why:  a card's dates are two integers and a month, and turning them into "when is my money due"
 *       is the part every reader gets wrong once. Holding the answer as a value means the screen,
 *       the reminder and the golden file all read the same four dates.
 * Result: the cycle containing [statementDate], and the payment date that closes it.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 *
 * Input:  [statementDate] — the most recent statement on or before the date asked about;
 *         [dueDate] — when that statement must be paid, always after [statementDate];
 *         [nextStatementDate] — when the current cycle closes;
 *         [daysUntilDue] — whole days from the date asked about to [dueDate]; negative once the due
 *         date has passed, which is a real state and not an error.
 * Output: an immutable value.
 */
data class BillingCycle(
    val statementDate: LocalDate,
    val dueDate: LocalDate,
    val nextStatementDate: LocalDate,
    val daysUntilDue: Int,
) {
    init {
        require(dueDate > statementDate) {
            "A statement is due after it is cut, not before: statement $statementDate, due $dueDate"
        }
        require(nextStatementDate > statementDate) {
            "The next statement follows this one: $statementDate then $nextStatementDate"
        }
    }
}

/**
 * Which balance a utilisation figure divided (issue 6.1; P-02).
 *
 * Why:  the app shows two utilisations and they disagree by design, so every one of them carries
 *       the basis it used. An unlabelled ratio would be a number the user cannot check, which is
 *       exactly what "show the work" forbids.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
enum class UtilisationBasis {
    /**
     * Everything currently outstanding, statement plus unbilled, from the ledger.
     *
     * What the user feels: it moves with every swipe. Shown on screen, never alerted on — a figure
     * that changes hourly would either nag or be claimed once and then be wrong.
     */
    LIVE,

    /**
     * The last statement amount alone.
     *
     * What a credit bureau records, and what `RULE-CC-UTIL`'s rationale is about. Stable for a whole
     * cycle, which is what makes "alert once per cycle" a coherent promise.
     */
    STATEMENT,
}

/**
 * One utilisation figure and the basis behind it (issue 6.1; FR-ACC-002, MNY-002).
 *
 * Result: [ratioBps] — used ÷ limit in basis points, `null` when [basis] has no number yet (a card
 *         with no statement recorded has no statement utilisation, and rendering that as 0% would
 *         claim the user owes nothing).
 * Input:  [basis]; [used] — the numerator, `null` when unknown; [ratioBps].
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
data class Utilisation(
    val basis: UtilisationBasis,
    val used: Money?,
    val ratioBps: Int?,
)

/**
 * Everything a card screen shows (issue 6.1; FR-ACC-002).
 *
 * Input:  [accountId]; [creditLimit]; [live] and [statement] — the two utilisations;
 *         [unbilled] — spend since the last statement was cut, derived not stored;
 *         [available] — limit less the live outstanding, floored at zero;
 *         [cycle]; [minimumDue]; [provenance] — AI-ARC-003.
 * Output: an immutable value.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
data class CardStatus(
    val accountId: String,
    val creditLimit: Money,
    val live: Utilisation,
    val statement: Utilisation,
    val unbilled: Money,
    val available: Money,
    val cycle: BillingCycle,
    val minimumDue: Money?,
    val provenance: EngineProvenance,
) {
    init {
        require(provenance.evidence.isNotEmpty()) {
            "A card figure that cites no rule is a black-box verdict, which violates P-02"
        }
    }
}

/** Which of a card's two facts an alert is about (issue 6.1; §17.1). */
enum class CardAlertKind {
    /**
     * A payment is near or here. **Critical money event** (§17.1): exempt from NTF-001's daily
     * notification budget and allowed past quiet hours.
     */
    DUE_SOON,

    /** The statement utilisation is at or past `RULE-CC-UTIL`'s line. Discipline, not critical. */
    UTILISATION,
}

/**
 * One thing worth telling the user about a card (issue 6.1; §17.1, AI-ARC-003).
 *
 * Input:  [kind]; [accountId]; [cycleStartIsoDate] — the statement date of the cycle this alert
 *         belongs to, ISO `yyyy-MM-dd` (TIM-002); it is the claim key, which is what makes
 *         "once per cycle" enforceable by a UNIQUE index rather than by a flag;
 *         [amount] — what is owed for [CardAlertKind.DUE_SOON], the statement balance for
 *         [CardAlertKind.UTILISATION]; [creditLimit] — the sanctioned limit, carried so the message
 *         can name it without reconstructing it; [minimumDue]; [daysUntilDue]; [ratioBps];
 *         [provenance].
 * Output: an immutable value.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 *            2026-08-18 — Added [creditLimit]. Found by running the app: the notifier had been
 *            recovering the limit as `amount x 10 000 / ratioBps`, and because `ratioBps` is
 *            truncated to a whole basis point, a ₹2,00,000 limit reached the phone as
 *            "₹2,00,034.82". The guardrail passed it — it was handed the same reconstruction —
 *            which is precisely why P-03 says the engine emits the figure and the words never
 *            re-derive one.
 */
data class CardAlert(
    val kind: CardAlertKind,
    val accountId: String,
    val cycleStartIsoDate: String,
    val amount: Money,
    val creditLimit: Money,
    val minimumDue: Money?,
    val daysUntilDue: Int,
    val ratioBps: Int?,
    val provenance: EngineProvenance,
) {
    init {
        require(provenance.evidence.isNotEmpty()) {
            "An alert that cites no rule cannot answer 'why am I seeing this?' (P-02)"
        }
    }

    /**
     * The utilisation as a whole percent, for a sentence.
     * Why:    bps is the engine's unit (MNY-002) and "3500%" is not a thing to show a person. The
     *         conversion lives here rather than in the notifier so the guardrail can be handed the
     *         same integer the text contains — two roundings, done in two places, is how a message
     *         says 35% while its allow-list holds 34.
     * Result: 0..; `0` when there is no ratio, which no message using it should reach.
     * Input:  none. Output: [Int].
     */
    val usedPercent: Int get() = (ratioBps ?: 0) / BPS_PER_PERCENT
}

/**
 * Input to [CardEngine.cycle].
 * Input:  [today] — the date to place, supplied by the caller's injected `Clock` (TIM-001);
 *         [statementDay], [dueDay] — 1..31; [nowUtcMillis] — stamped onto provenance only;
 *         [rules] — the thresholds, injected so a test can move one.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
data class CardCycleInput(
    val today: LocalDate,
    val statementDay: Int,
    val dueDay: Int,
    val nowUtcMillis: Long = 0L,
    val rules: CardRules = CardRules(),
)

/**
 * Input to [CardEngine.status].
 * Input:  [card] — the terms and the last statement; [today]; [outstanding] — what is owed right
 *         now, as a **positive magnitude**: the ledger stores a card balance negative because it is
 *         a liability, and the repository flips it once so no ratio in this engine has to reason
 *         about a sign; [nowUtcMillis]; [rules].
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
data class CardStatusInput(
    val card: CreditCard,
    val today: LocalDate,
    val outstanding: Money,
    val nowUtcMillis: Long = 0L,
    val rules: CardRules = CardRules(),
) {
    init {
        require(outstanding >= Money.ZERO) {
            "outstanding is a magnitude — the repository negates the stored liability before " +
                "calling, so a negative here means that flip was missed (was ${outstanding.minor} paise)"
        }
    }
}

/**
 * Input to [CardEngine.alert]. Same shape as [CardStatusInput]; named separately so the two
 * operations can diverge without a shared type forcing one to carry the other's fields.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
data class CardAlertInput(
    val card: CreditCard,
    val today: LocalDate,
    val outstanding: Money,
    val nowUtcMillis: Long = 0L,
    val rules: CardRules = CardRules(),
) {
    init {
        require(outstanding >= Money.ZERO) {
            "outstanding is a magnitude (was ${outstanding.minor} paise)"
        }
    }
}

/**
 * Builds the engine for the DI graph (ARC-003).
 * Why:    ARC-003 keeps engine implementations `internal`, so `:app`'s Hilt module cannot name one.
 *         The same seam `BudgetEngineFactory` and `NetWorthEngineFactory` already use.
 * Result: a [CardEngine]. Input: none. Output: the engine.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
object CardEngineFactory {
    /** Result: the production engine. Input: none. Output: [CardEngine]. */
    fun create(): CardEngine = DefaultCardEngine()
}

package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.LoanEntity
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Loan
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.loan.AmortisationRow
import com.aicfo.domain.engines.loan.AmortisationSchedule
import com.aicfo.domain.engines.loan.LoanEngine
import com.aicfo.domain.engines.loan.LoanInstalmentInput
import com.aicfo.domain.engines.loan.LoanTermsInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The only class that reads or writes a loan's terms (issue 6.2; FR-ACC-003, ARC-005).
 *
 * Why:  the loan engine is pure — it takes five numbers and a date. Something has to turn stored
 *       rows into those arguments and turn the answer back into something a screen can hold, and
 *       this is it. Two translations happen here and nowhere else:
 *
 *       **The clock read.** `clock.today()` is called once per read, here, to decide *which*
 *       instalment is the next one due (TIM-001). The engine has no clock and the build fails if it
 *       grows one — but "which of these 240 rows is next" is a question only today can answer, so
 *       the answer is computed at this boundary and handed down as an instalment number.
 *
 *       **The type check.** A `loan` row against a savings account would give it an EMI and an
 *       amortisation schedule computed from a principal it has not got. That is a data-integrity
 *       rule, not a UI concern, so it lives here — the same argument `CreditCardRepository.save`
 *       makes for cards.
 * What: observe every loan's next instalment, read and write one loan's terms, and build a full
 *       schedule on demand.
 * Result: `LoanEmi`, `AmortisationRow` and `AmortisationSchedule` values the UI can use without
 *       knowing Room exists.
 * Changelog: 2026-08-19 — Created for issue 6.2.
 *
 * **Nothing here caches a schedule.** It is derived on every read from the five stored columns
 * (ADR-0026); a cached one is a copy that can disagree with the terms that produced it.
 */
interface LoanRepository {
    /**
     * Watches the next instalment due on every loan the active profile has.
     * Why:    the accounts list shows one line under each loan — "next EMI ₹26,034.70: ₹4,784.70
     *         principal, ₹21,250.00 interest" — and that is the whole reason the engine has a
     *         single-instalment operation. Building 240 rows per loan to render one line is the kind
     *         of waste that only shows up on somebody else's phone.
     * Result: a map keyed by account id, re-emitted whenever a loan is edited. A `LOAN` account with
     *         no `loan` row yet is **absent** from the map rather than present with zeros — the
     *         ordinary state of a loan the user has not filled in, and the screen prompts rather
     *         than claiming a ₹0 EMI. A loan whose last instalment has passed is also absent: it is
     *         repaid, and there is no next EMI to name.
     * Input:  none. Output: `Flow<Map<String, AmortisationRow>>`.
     */
    fun observeNextInstalments(): Flow<Map<String, AmortisationRow>>

    /**
     * Reads one loan's stored terms.
     * Result: `Ok(Loan)`, or `Ok(null)` when the account has no loan detail yet — not an error, so
     *         the editor can open on an empty form.
     * Input:  [accountId]. Output: `Result<Loan?, AppError>`.
     */
    suspend fun find(accountId: String): Result<Loan?, AppError>

    /**
     * Writes a loan's terms.
     * Why:    an upsert rather than create/update, because a loan's terms are current facts about an
     *         account rather than events. A restructured loan overwrites; there is no history here
     *         the schedule could use.
     * Result: `Ok(Unit)`; `Err(NotFound)` when the account does not exist; `Err(Validation)` when it
     *         is not a `LOAN` account, or when the terms cannot amortise — the engine's own refusal,
     *         surfaced at save time so the user learns about it while the form is still open rather
     *         than from an empty schedule later.
     * Input:  [loan] — the terms; its `accountId` must name an existing `LOAN` account.
     * Output: `Result<Unit, AppError>`.
     */
    suspend fun save(loan: Loan): Result<Unit, AppError>

    /**
     * Builds one loan's full amortisation schedule.
     * Why:    derived rather than stored (ADR-0026), so this is where the derivation happens.
     * Result: `Ok(AmortisationSchedule)`; `Err(NotFound)` when the account has no loan detail;
     *         the engine's `Err` when the terms cannot amortise.
     * Input:  [accountId]. Output: `Result<AmortisationSchedule, AppError>`.
     */
    suspend fun schedule(accountId: String): Result<AmortisationSchedule, AppError>
}

/**
 * The Room-backed [LoanRepository].
 * Why:    takes the whole [database] because a save spans `account` and `loan`; and takes the
 *         [engine] rather than constructing one so the figure and the code that produced it stay
 *         assembled in the DI graph (ARC-003, P-03).
 * Result: the implementation injected into the accounts screen.
 * Changelog: 2026-08-19 — Created for issue 6.2.
 *
 * Input:  [database]; [engine]; [clock] — the single clock read on this path (TIM-001);
 *         [dispatchers]; [activeProfileId] — so the demo profile gets its own loans. **No
 *         `IdGenerator`**: a loan is keyed by its account, so there is no id to mint.
 * Output: a working repository.
 */
internal class RoomLoanRepository(
    private val database: CfoDatabase,
    private val engine: LoanEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : LoanRepository {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeNextInstalments(): Flow<Map<String, AmortisationRow>> =
        activeProfileId
            .flatMapLatest { profileId ->
                database.loanDao().observeForProfile(profileId).map { loans ->
                    val today = clock.today()
                    val now = clock.nowUtcMillis()
                    loans.mapNotNull { entity ->
                        val loan = entity.toLoan()
                        val number = nextInstalmentNumber(loan, today) ?: return@mapNotNull null
                        val row = engine.instalment(LoanInstalmentInput(loan, number, now))
                        (row as? Ok)?.value?.let { entity.accountId to it }
                    }.toMap()
                }
            }.flowOn(dispatchers.io)

    override suspend fun find(accountId: String): Result<Loan?, AppError> =
        withContext(dispatchers.io) {
            Ok(database.loanDao().find(accountId)?.toLoan())
        }

    override suspend fun save(loan: Loan): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            val profileId = activeProfileId.first()
            val account = database.accountDao().findWithBalance(loan.accountId, clock.today().toString())
            val cannotAmortise = engine.emi(LoanTermsInput(loan, clock.nowUtcMillis())) as? Err
            when {
                account == null -> Err(AppError.NotFound)
                AccountType.fromStored(account.account.type) != AccountType.LOAN ->
                    Err(AppError.Validation("account.notALoan"))
                // Checked before the write, not after: terms that produce no schedule are terms the
                // user has to fix, and a row saved now would show an empty schedule with nothing on
                // screen explaining why.
                cannotAmortise != null -> cannotAmortise
                else -> {
                    val now = clock.nowUtcMillis()
                    val existing = database.loanDao().find(loan.accountId)
                    database.loanDao().upsert(
                        LoanEntity(
                            accountId = loan.accountId,
                            profileId = profileId,
                            principalMinor = loan.principal.minor,
                            annualRateBps = loan.annualRateBps,
                            tenureMonths = loan.tenureMonths,
                            firstEmiIsoDate = loan.firstEmiIsoDate,
                            emiOverrideMinor = loan.emiOverride?.minor,
                            // Preserved across an edit, so "when did this loan start being tracked?"
                            // stays answerable — as every other row keeps its created stamp.
                            createdAtUtcMillis = existing?.createdAtUtcMillis ?: now,
                            updatedAtUtcMillis = now,
                        ),
                    )
                    Ok(Unit)
                }
            }
        }

    override suspend fun schedule(accountId: String): Result<AmortisationSchedule, AppError> =
        withContext(dispatchers.io) {
            val entity = database.loanDao().find(accountId)
            if (entity == null) {
                Err(AppError.NotFound)
            } else {
                engine.schedule(LoanTermsInput(entity.toLoan(), clock.nowUtcMillis()))
            }
        }

    /**
     * Works out which instalment is the next one due.
     * Why:    the one question in this file that needs today's date, and the reason it is asked here
     *         rather than in the engine (TIM-001). The answer is simply the first instalment whose
     *         due date has not already passed.
     *
     *         **Dates compared, never day counts subtracted.** A loan whose first EMI fell on the
     *         31st has instalments on the 28th, 30th and 31st depending on the month; arithmetic on
     *         elapsed days would drift against that within a year. Walking the same
     *         `plusMonths`-from-the-origin the engine uses is what keeps the two in step.
     * Result: the 1-based instalment number, or `null` when every instalment has passed — the loan
     *         is repaid and there is no next EMI to show.
     * Input:  [loan] — validated terms; [today] — in the profile zone, from the injected clock.
     * Output: [Int]?.
     */
    private fun nextInstalmentNumber(
        loan: Loan,
        today: LocalDate,
    ): Int? {
        val first = LocalDate.parse(loan.firstEmiIsoDate)
        // `!isBefore`, not `isAfter`: an instalment due today is the next one due, not a past one.
        return (1..loan.tenureMonths).firstOrNull { !first.plusMonths((it - 1).toLong()).isBefore(today) }
    }
}

/**
 * Maps a stored row to the domain model.
 * Why:    one conversion, so the nullable-means-absent rule for the EMI override is written once.
 *         Room rows are `internal` to `:core:database`; nothing above this line sees one (ARC-005).
 * Result: a [Loan]. Input: the receiver. Output: [Loan].
 * Changelog: 2026-08-19 — Created for issue 6.2.
 */
private fun LoanEntity.toLoan(): Loan =
    Loan(
        accountId = accountId,
        principal = Money(principalMinor),
        annualRateBps = annualRateBps,
        tenureMonths = tenureMonths,
        firstEmiIsoDate = firstEmiIsoDate,
        emiOverride = emiOverrideMinor?.let(::Money),
    )

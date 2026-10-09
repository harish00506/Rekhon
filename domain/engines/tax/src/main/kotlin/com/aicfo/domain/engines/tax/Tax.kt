package com.aicfo.domain.engines.tax

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * AI-TAX — both regimes, the break-even between them, and what this engine will not estimate
 * (issue 13.4; SRS §38.1, §38.2, TAX-001, TAX-002).
 *
 * Why:  the question §38 exists to answer is one most salaried Indians get wrong by guessing: **which
 *       regime, and by how much?** It is not answerable from a rule of thumb, because it depends on
 *       the deductions a household actually uses rather than the ones it could. So the engine
 *       computes both regimes from real figures and reports the margin in rupees — and, because the
 *       interesting answer is usually "it depends", also the **break-even**: the level of deductions
 *       at which the old regime starts winning.
 * What: one engine. Given salary, the deductions actually used and any realised gains, it returns
 *       each regime's slab-by-slab working, the winner and the margin, the break-even, the capital
 *       gains tax by asset class, the harvesting alerts §38's TAX-001 asks for, and an explicit list
 *       of what it did **not** model.
 * Result: an **estimate**, labelled as one, stamped with the FY rules version (TAX-002). It never
 *       files, never names an instrument to sell, and never quietly reports a low figure where it
 *       knows the real one is higher (P-03, P-07).
 * Changelog: 2026-10-09 — Created for issue 13.4.
 *
 * Pure (P-08): `todayIsoDate` and `nowUtcMillis` are inputs. No clock, no I/O, no Android (ARC-002).
 */
interface TaxEngine {
    /**
     * Estimates a year's tax under both regimes.
     * Result: `Ok(TaxEstimate)`; `Err(AppError.Validation)` naming the field for an input no estimate
     *         can be made from — a negative income, a deduction larger than income, or a gain whose
     *         asset class the knowledge base has no rule for.
     * Input:  [input] — the year's income, deductions and realised gains.
     * Output: `Result<TaxEstimate, AppError>`.
     */
    fun estimate(input: TaxInput): Result<TaxEstimate, AppError>
}

/**
 * Builds the engine (ARC-003 — the implementation stays `internal`).
 * Result: a [TaxEngine]. Input: none. Output: the engine.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
object TaxEngineFactory {
    /** Result: §38's estimator. Input: none. Output: [TaxEngine]. */
    fun create(): TaxEngine = SlabTaxEngine()
}

/**
 * Whether the tax surface is on (issue 13.4; ADR-0072).
 *
 * Why:  Epic 13 is design-for work. The engine is pure and inert; what the flag holds back is
 *       showing somebody a tax figure. That is worth holding back for a reason beyond readiness:
 *       **a number labelled "tax" is read as authoritative**, and this one deliberately omits
 *       surcharge and property gains. The label and the limitations have to be designed into the
 *       screen before the number appears on one, or the omissions become invisible.
 * What: one constant.
 * Result: v1 behaves exactly as it did.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
object TaxMode {
    /** False in v1. ADR-0072 lists what must be true before it changes. */
    const val IS_ENABLED: Boolean = false
}

/** Which of §38.1's two regimes a figure belongs to. */
enum class Regime {
    /** Deductions allowed, higher slabs. */
    OLD,

    /** Few deductions, lower slabs, bigger standard deduction. */
    NEW,
}

/**
 * The asset classes §38.2 gives a rule for.
 *
 * Why:  a closed set, because the knowledge base has a row per class and a class it has no row for
 *       cannot be taxed — the engine refuses rather than guessing a rate (P-03).
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
enum class AssetClass {
    /** Listed equity and equity mutual funds: 20% short, 12.5% long above the annual exemption. */
    EQUITY,

    /** Debt funds bought on or after 1 Apr 2023 — slab rate always, no long-term benefit. */
    DEBT_POST_2023,

    /** Pre-Apr-2023 debt funds and gold: 12.5% long-term, without indexation. */
    DEBT_PRE_2023_OR_GOLD,

    /** Sovereign gold bonds held to maturity — capital gains fully exempt. */
    SGB_HELD_TO_MATURITY,
}

/**
 * A year's salary, as §38.1 needs it.
 * Input:  [grossAnnual] — paise (MNY-001); [employerNpsContribution] — 80CCD(2), deductible in
 *   **both** regimes, which is why it is separate from [Deductions].
 * Output: an immutable value.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
data class SalaryIncome(
    val grossAnnual: Money,
    val employerNpsContribution: Money = Money.ZERO,
)

/**
 * The deductions the household has **actually used** — not the ones it could.
 *
 * Why:  §38.1's break-even engine is explicit about this: "compute both regimes from the user's
 *       actual tagged deductions". A comparison run on the caps rather than on real usage flatters
 *       the old regime for everybody, and would tell a household with no 80C investments to pick
 *       the regime that assumes ₹1.5 lakh of them.
 * Input:  all amounts in paise (MNY-001), each capped by the knowledge base at computation time.
 * Output: an immutable value.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
data class Deductions(
    val section80C: Money = Money.ZERO,
    val section80CcdOneB: Money = Money.ZERO,
    val section80D: Money = Money.ZERO,
    val homeLoanInterest: Money = Money.ZERO,
    val hraExempt: Money = Money.ZERO,
)

/**
 * A gain already realised this financial year.
 * Input:  [assetClass]; [gain] — paise, **signed**: a loss is negative, which is what makes
 *   harvesting arithmetic possible; [heldMonths] — whole months held, deciding short vs long.
 * Output: an immutable value.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
data class RealisedGain(
    val assetClass: AssetClass,
    val gain: Money,
    val heldMonths: Int,
)

/**
 * A position not yet sold, for the harvesting alerts (TAX-001).
 * Input:  [assetClass]; [unrealisedGain] — signed paise; [heldMonths]; [label] — what the user
 *   calls it, carried so a screen can group by it. **The engine never names it in an alert.**
 * Output: an immutable value.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
data class OpenPosition(
    val assetClass: AssetClass,
    val unrealisedGain: Money,
    val heldMonths: Int,
    val label: String = "",
)

/**
 * Everything the engine is given.
 * Input:  [salary]; [deductions]; [todayIsoDate] — ISO `yyyy-MM-dd` (TIM-002), which decides how
 *   close the financial year's end is; [nowUtcMillis] — provenance only; [realisedGains];
 *   [openPositions].
 * Output: an immutable value.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
data class TaxInput(
    val salary: SalaryIncome,
    val todayIsoDate: String,
    val nowUtcMillis: Long,
    val deductions: Deductions = Deductions(),
    val realisedGains: List<RealisedGain> = emptyList(),
    val openPositions: List<OpenPosition> = emptyList(),
)

/**
 * What the engine estimated.
 * Input:  [old] and [new] — each regime's full working; [winner]; [margin] — what the winner saves,
 *   never negative; [breakEvenDeductions] — how much **more** deduction the old regime would need
 *   to start winning, on top of what is already claimed, or `null` when it already wins (under
 *   FY2025-26's rates that threshold is high: about ₹7.25L of deductions at ₹18L of income);
 *   [capitalGains]; [alerts]; [limitations] — what was
 *   **not** modelled, which is part of the answer rather than a footnote; [fyRulesVersion] —
 *   TAX-002 requires it on every result; [provenance].
 * Output: an immutable value.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
data class TaxEstimate(
    val old: RegimeComputation,
    val new: RegimeComputation,
    val winner: Regime,
    val margin: Money,
    val breakEvenDeductions: Money?,
    val capitalGains: CapitalGainsSummary,
    val alerts: List<TaxAlert>,
    val limitations: List<TaxLimitation>,
    val fyRulesVersion: String,
    val provenance: EngineProvenance,
)

/**
 * One regime's working, slab by slab.
 *
 * Why:  AC2 asks the comparison to **show the math** (P-02). A single "you owe ₹X" is exactly the
 *       black-box verdict P-02 forbids, and tax is the subject where a reader most wants to check
 *       the arithmetic — so the bands are carried, each with what was taxed in it and at what rate.
 * Input:  [regime]; [grossIncome]; [deductionsAllowed] — what this regime actually permitted of
 *   what was claimed; [taxableIncome]; [bands]; [taxBeforeRebate]; [rebate] — §87A; [cess] — 4%
 *   health and education; [totalTax].
 * Output: an immutable value.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
data class RegimeComputation(
    val regime: Regime,
    val grossIncome: Money,
    val deductionsAllowed: Money,
    val taxableIncome: Money,
    val bands: List<SlabBand>,
    val taxBeforeRebate: Money,
    val rebate: Money,
    val cess: Money,
    val totalTax: Money,
)

/**
 * One slab band, and what this taxpayer paid in it.
 * Input:  [fromInclusive] and [toExclusive] — paise, `null` on the top band's upper edge;
 *   [rateBps] — basis points (MNY-002); [taxedHere] — how much income fell in the band;
 *   [taxHere] — the tax that produced.
 * Output: an immutable value.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
data class SlabBand(
    val fromInclusive: Money,
    val toExclusive: Money?,
    val rateBps: Int,
    val taxedHere: Money,
    val taxHere: Money,
)

/**
 * The capital-gains half of the estimate (§38.2).
 * Input:  [shortTermTax]; [longTermTax]; [exemptionUsed] — of the equity annual exemption;
 *   [exemptionRemaining] — what TAX-001a's alert is about; [slabTaxedGains] — gains taxed at the
 *   slab rate rather than a capital-gains rate, which land on top of salary; [exemptGains].
 * Output: an immutable value.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
data class CapitalGainsSummary(
    val shortTermTax: Money,
    val longTermTax: Money,
    val exemptionUsed: Money,
    val exemptionRemaining: Money,
    val slabTaxedGains: Money,
    val exemptGains: Money,
)

/**
 * Something worth telling the user, from TAX-001.
 *
 * Why:  **generic by construction.** TAX-001 says the alerts never say "sell fund X", and that is
 *       enforced by this type having nowhere to put an instrument: a kind, an amount and a number
 *       of days. The engine is handed labels on [OpenPosition] and never copies one here.
 * Input:  [kind]; [amount] — the rupees at stake, or `null` where there are none; [daysAway] — to
 *   the financial year's end or to a holding turning long-term; [citation].
 * Output: an immutable value.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
data class TaxAlert(
    val kind: TaxAlertKind,
    val amount: Money?,
    val daysAway: Long,
    val citation: String,
)

/** The alerts TAX-001 asks for. */
enum class TaxAlertKind {
    /** Part of the annual LTCG exemption is unused and the year is nearly over (TAX-001a). */
    UNUSED_LTCG_EXEMPTION,

    /** There are losses that could be realised before 31 March (TAX-001b). */
    HARVESTABLE_LOSSES,

    /** A holding is within the countdown of turning long-term (TAX-001c). */
    SHORT_TERM_TURNING_LONG,
}

/**
 * Something the estimate did **not** account for.
 *
 * Why:  this is the type that keeps the engine honest. An estimate that silently omits surcharge is
 *       not an estimate, it is a wrong number — so the omission travels **with** the figure instead
 *       of living in a comment somebody may not read. P-03 is about never inventing a number; this
 *       is its other half, never hiding that one is incomplete.
 * Input:  [kind]; [note] — a key the screen turns into words (§21.6), never a sentence from an
 *   engine.
 * Output: an immutable value.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
data class TaxLimitation(
    val kind: LimitationKind,
)

/** What an estimate left out. */
enum class LimitationKind {
    /** Income is above the surcharge threshold, which the knowledge base does not model. */
    SURCHARGE_NOT_MODELLED,

    /** A property gain was supplied; §38.2 sends those to a professional (TAX-002). */
    PROPERTY_GAINS_NOT_MODELLED,

    /** HRA was claimed; the engine takes the exempt figure as given rather than deriving it. */
    HRA_TAKEN_AS_GIVEN,
}

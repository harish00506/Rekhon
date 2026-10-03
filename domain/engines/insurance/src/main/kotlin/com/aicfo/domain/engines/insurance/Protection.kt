package com.aicfo.domain.engines.insurance

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * AI-INS — how much cover a household needs, how much it has, and which policies are not cover at
 * all (issue 13.3; SRS §39.1, §39.3).
 *
 * Why:  §14's financial health score has carried a Protection pillar since issue 9.4, and it has
 *       been scored off **self-declared booleans** — "do you have health insurance? yes/no". §39.1
 *       exists to replace those with a number: a household with a ₹3L health policy and a ₹2Cr
 *       liability answers "yes" to both questions and is not protected at all. The gap is the
 *       headline, and until something computes it the pillar is measuring whether somebody ticked
 *       a box.
 * What: one engine. Given the household's age, income, liabilities and policies, it returns the
 *       term cover needed and on which of §39.1's two arms, the health floor, the gap against each,
 *       the policies whose premium says they are investments rather than protection, and the
 *       arithmetic behind that claim.
 * Result: rupee figures a household can act on, each citing the rulebook row that produced it
 *       (P-02). **Advisory only, by construction** (P-07): there is no output that says surrender,
 *       buy, or switch — the engine publishes gaps and a comparison, and the decision stays with
 *       the person whose money it is.
 * Changelog: 2026-10-03 — Created for issue 13.3.
 *
 * Pure (P-08): `todayIsoDate` and `nowUtcMillis` are inputs, so the same household assesses
 * identically for ever. No clock, no I/O, no Android (ARC-002).
 */
interface ProtectionEngine {
    /**
     * Assesses one household's protection.
     * Why:    one call, because the three answers share inputs and a reader compares them — a term
     *         gap means something different beside a large health gap than beside none.
     * Result: `Ok(ProtectionAssessment)`; `Err(AppError.Validation)` naming the field for an input
     *         no assessment can be made from — an age outside the rulebook's bands, a negative
     *         income, or a policy with cover but no premium.
     * Input:  [input] — the household and its policies.
     * Output: `Result<ProtectionAssessment, AppError>`.
     */
    fun assess(input: ProtectionInput): Result<ProtectionAssessment, AppError>
}

/**
 * Builds the engine (ARC-003 — the implementation stays `internal`).
 * Result: a [ProtectionEngine]. Input: none. Output: the engine.
 * Changelog: 2026-10-03 — Created for issue 13.3.
 */
object ProtectionEngineFactory {
    /** Result: §39.1's protection assessor. Input: none. Output: [ProtectionEngine]. */
    fun create(): ProtectionEngine = RuleProtectionEngine()
}

/**
 * Whether the protection surface is on (issue 13.3; ADR-0071).
 *
 * Why:  Epic 13 is design-for work and AC2 says "feature-flagged". The engine is pure and inert, so
 *       what this holds back is the decision to *show* a protection gap — and that decision is not
 *       only about readiness. A screen that tells someone they are ₹1.4 crore under-insured is the
 *       most alarming sentence this app can produce, and it must not appear before somebody has
 *       designed how it is said (§39.1 is a calculation; the wording is not specified anywhere).
 * What: one constant.
 * Result: v1 behaves exactly as it did.
 * Changelog: 2026-10-03 — Created for issue 13.3.
 */
object ProtectionMode {
    /** False in v1. ADR-0071 lists what must be true before it changes. */
    const val IS_ENABLED: Boolean = false
}

/**
 * What kind of thing a policy is, as far as the engine can tell.
 *
 * Why:  the user says which; the engine does not infer it. A kind the app guessed wrong would put a
 *       genuine term plan on a "this is not insurance" list, which is the one false positive here
 *       that would cost somebody trust in everything else the app says.
 * Changelog: 2026-10-03 — Created for issue 13.3.
 */
enum class PolicyKind {
    /** Pure life cover: pays out on death, no maturity value. */
    TERM,

    /** Health indemnity or a family floater. */
    HEALTH,

    /**
     * Anything else the household pays a premium for — endowment, ULIP, money-back, whole life.
     * It is this kind that `RULE-TERM-VS-ENDOW` examines, never [TERM] or [HEALTH].
     */
    OTHER,
}

/**
 * One policy the household holds.
 * Input:  [id]; [label] — the insurer or nickname, for the screen; [kind]; [cover] — the sum
 *   insured in paise (MNY-001); [annualPremium] — paise; [renewalIsoDate] — ISO `yyyy-MM-dd`
 *   (TIM-002), or `null` when the household has not recorded it.
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.3.
 */
data class Policy(
    val id: String,
    val label: String,
    val kind: PolicyKind,
    val cover: Money,
    val annualPremium: Money,
    val renewalIsoDate: String? = null,
)

/**
 * The household, as §39.1 needs it.
 *
 * Why:  every field here is one §39.1 or §39.3 names, and nothing more. [isSingleIncome] in
 *       particular is not a nicety: §39.3 says a single-income household's term multiple goes to the
 *       top of the 10–15× band, because the whole household's income rests on one life.
 * Input:  [ageYears] — of the earner the cover is on; [annualIncome] — paise (MNY-001);
 *   [outstandingLiabilities] — paise, what somebody would inherit; [hasDependents] — whether term
 *   cover is assessed at all; [isSingleIncome]; [isMetro] — selects the health floor.
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.3.
 */
data class Household(
    val ageYears: Int,
    val annualIncome: Money,
    val outstandingLiabilities: Money = Money.ZERO,
    val hasDependents: Boolean = true,
    val isSingleIncome: Boolean = false,
    val isMetro: Boolean = false,
)

/**
 * Everything the engine is given.
 * Input:  [household]; [policies]; [todayIsoDate]; [nowUtcMillis] — provenance only.
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.3.
 */
data class ProtectionInput(
    val household: Household,
    val todayIsoDate: String,
    val nowUtcMillis: Long,
    val policies: List<Policy> = emptyList(),
)

/**
 * What the engine decided.
 * Input:  [term] — or `null` when there are no dependents, which is an answer and not a zero;
 *   [health]; [investmentLinked] — policies `RULE-TERM-VS-ENDOW` flagged, dearest per lakh first;
 *   [provenance].
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.3.
 */
data class ProtectionAssessment(
    val term: CoverGap?,
    val health: CoverGap,
    val investmentLinked: List<InvestmentLinkedPolicy>,
    val provenance: EngineProvenance,
)

/**
 * How much cover is needed, how much exists, and the difference.
 * Input:  [needed]; [existing] — the sum of the household's policies of that kind; [gap] — floored
 *   at zero, because being over-covered is not a negative gap and showing one as a credit would
 *   invite netting it against something; [basis] — which rule arm produced [needed] (P-02);
 *   [citation] — the rulebook row and version.
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.3.
 */
data class CoverGap(
    val needed: Money,
    val existing: Money,
    val gap: Money,
    val basis: CoverBasis,
    val citation: String,
) {
    init {
        require(gap >= Money.ZERO) { "a cover gap is floored at zero, was $gap" }
    }
}

/**
 * Which arm of §39.1 decided the figure.
 *
 * Why:  §39.1 takes the **higher** of two very different ideas, and which one won is the most
 *       useful thing the screen can say. "Fifteen times your income plus the home loan" and "the
 *       IRDAI multiple for your age" lead to different conversations, and a bare rupee figure leads
 *       to neither (P-02).
 * Changelog: 2026-10-03 — Created for issue 13.3.
 */
enum class CoverBasis {
    /** `income_multiple × annual income + outstanding liabilities` was the larger. */
    INCOME_MULTIPLE_PLUS_LIABILITIES,

    /** The IRDAI human-life-value multiple for the age band was the larger. */
    HUMAN_LIFE_VALUE,

    /** The health floor, which has one arm: the metro or non-metro figure. */
    HEALTH_FLOOR,
}

/**
 * A policy whose premium says it is an investment, and the arithmetic behind saying so.
 *
 * Why:  §39.1's INS-002 asks for the buy-term-invest-the-rest comparison to be **shown as math**,
 *       with the decision left to the user. So this carries the workings rather than a verdict:
 *       what the policy costs per lakh of cover, what the same cover costs as term, and what the
 *       difference would come to invested over the rulebook's horizon — beside what the policy
 *       itself would return over the same period.
 * Result: everything a reader needs to disagree with the app.
 * Input:  [policyId]; [label]; [premiumPerLakhPaise] — the discriminator; [termEquivalentPremium] —
 *   what this cover would cost as term; [annualDifference] — what is left over; [investedValue] —
 *   the difference invested at the rulebook's equity rate for its horizon; [policyValueLow] and
 *   [policyValueHigh] — the same premium at the rulebook's endowment band; [horizonYears];
 *   [citation].
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.3.
 */
data class InvestmentLinkedPolicy(
    val policyId: String,
    val label: String,
    val premiumPerLakhPaise: Long,
    val termEquivalentPremium: Money,
    val annualDifference: Money,
    val investedValue: Money,
    val policyValueLow: Money,
    val policyValueHigh: Money,
    val horizonYears: Int,
    val citation: String,
)

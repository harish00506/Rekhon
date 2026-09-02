package com.aicfo.feature.emergencyfund

import androidx.annotation.StringRes
import com.aicfo.domain.engines.emergencyfund.EmergencyStatus
import com.aicfo.domain.engines.emergencyfund.EssentialsBasis

/**
 * Turns the domain's verdicts into words (issue 7.2; §21.6).
 *
 * Why:  the engine returns an [EmergencyStatus] and an [EssentialsBasis], never a sentence — the
 *       domain decides what is true and a feature module decides how to say it, which is what lets
 *       the app be translated without touching `:domain:*`. Keeping the mapping in one `when` also
 *       means adding a status cannot compile until somebody has decided what it says.
 *
 *       **§10.1's coach behaviour is this file.** "Urgent framing", "build a plan", "celebrate",
 *       "suggest deploying the excess" are wording decisions about a band the engine already
 *       decided, so they belong here and not in the engine.
 * What: two exhaustive mappings to string resources.
 * Result: one place to argue about tone.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 */
internal object EmergencyFundLabels {
    /**
     * Result: the headline for this verdict. Input: [status]. Output: a string resource id.
     *
     * Exhaustive with no `else`, so a new [EmergencyStatus] fails the build here rather than
     * rendering as a blank line above somebody's runway.
     */
    @StringRes
    fun status(status: EmergencyStatus): Int =
        when (status) {
            EmergencyStatus.SURPLUS -> R.string.emf_status_surplus
            EmergencyStatus.FUNDED -> R.string.emf_status_funded
            EmergencyStatus.BUILDING -> R.string.emf_status_building
            EmergencyStatus.UNKNOWN -> R.string.emf_status_unknown
            EmergencyStatus.URGENT -> R.string.emf_status_urgent
        }

    /**
     * Result: §10.1's coach line for this verdict. Input: [status]. Output: a string resource id.
     *
     * **Separate from [status] because they answer different questions**: one names where the user
     * is, the other says what §10.1 suggests doing about it. Every one of these is phrased as a
     * suggestion — the app advises and the user decides (P-07), and nothing on this screen acts on
     * any of them.
     */
    @StringRes
    fun coach(status: EmergencyStatus): Int =
        when (status) {
            EmergencyStatus.SURPLUS -> R.string.emf_coach_surplus
            EmergencyStatus.FUNDED -> R.string.emf_coach_funded
            EmergencyStatus.BUILDING -> R.string.emf_coach_building
            EmergencyStatus.UNKNOWN -> R.string.emf_coach_unknown
            EmergencyStatus.URGENT -> R.string.emf_coach_urgent
        }

    /**
     * Result: the resource for one entry in the essentials evidence, or **null** when it needs none.
     *
     * Why:    the essentials figure is §8.3's `NEED` total, not a sum over named categories, so the
     *         engine's evidence carries the *nature*. Rendered raw it read **"Counted as essential:
     *         NEED"** on the device — a domain token the user has never seen anywhere in the app,
     *         found by running it rather than by any assertion about a figure.
     *
     *         Null for anything else, so an actual category name — which a later issue may supply,
     *         and which is already human — passes through untranslated rather than needing a
     *         resource per category the user invented.
     * Input:  [name] — one entry from `EmergencyFundPlan.essentialCategoryNames`.
     * Output: a string resource id, or null to use [name] as it stands.
     */
    @StringRes
    fun essentialCategory(name: String): Int? =
        when (name) {
            NEED_NATURE -> R.string.emf_essential_nature_need
            else -> null
        }

    /** The `CategoryNature.NEED` token the repository puts in the evidence list. */
    private const val NEED_NATURE = "NEED"

    /**
     * Result: how the essentials figure was arrived at. Input: [basis]. Output: a string resource id.
     *
     * §10.1 requires every number to link to its evidence, and this is the first link: a target
     * built from six months of observed spending deserves more confidence than one built from a
     * figure typed once at onboarding, and the user is entitled to know which they are looking at.
     */
    @StringRes
    fun essentialsBasis(basis: EssentialsBasis): Int =
        when (basis) {
            EssentialsBasis.OBSERVED_MEDIAN -> R.string.emf_basis_observed
            EssentialsBasis.DECLARED_ENVELOPE -> R.string.emf_basis_declared
            EssentialsBasis.NONE -> R.string.emf_basis_none
        }
}

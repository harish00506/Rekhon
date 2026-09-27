package com.aicfo.feature.market

import com.aicfo.data.repository.OpportunityView

/**
 * What §30's Opportunity screen shows (issue 10.7; ARC-004).
 *
 * Why:  one immutable data class per screen as a `StateFlow`, so the screen is a pure function of
 *       it and a test can assert the whole sequence including the empty and loading states.
 * What: the instruments with their assessments, and whether the first read has arrived.
 * Result: the screen renders this and nothing else.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 *
 * Input:  [views] — one per held instrument; [isLoaded] — false until the first emission, so an
 *         empty list is not mistaken for "nothing held"; [errorCode].
 * Output: an immutable value.
 */
data class OpportunityUiState(
    val views: List<OpportunityView> = emptyList(),
    val isLoaded: Boolean = false,
    val errorCode: String? = null,
)

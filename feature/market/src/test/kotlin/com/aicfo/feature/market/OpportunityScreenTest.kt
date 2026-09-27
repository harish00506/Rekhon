package com.aicfo.feature.market

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.RuleCitation
import com.aicfo.data.repository.OpportunityView
import com.aicfo.domain.engines.marketsignal.AssessmentOutcome
import com.aicfo.domain.engines.marketsignal.CapacityGate
import com.aicfo.domain.engines.marketsignal.HitRate
import com.aicfo.domain.engines.marketsignal.Instrument
import com.aicfo.domain.engines.marketsignal.MarketKnowledge
import com.aicfo.domain.engines.marketsignal.OpportunityAssessment
import com.aicfo.domain.engines.marketsignal.OpportunityBand
import com.aicfo.domain.engines.marketsignal.SignalContribution
import com.aicfo.domain.engines.marketsignal.Staleness
import com.aicfo.domain.engines.marketsignal.TranchePlan
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What §30's screen must actually put in front of a person (issue 10.7; P-02, P-07, §21.6).
 *
 * Why:  the figures are the engine's and are tested there. What only this layer can get wrong is
 *       the reading — and on this screen a misreading costs money. A verdict shown without the
 *       record behind it is a tip; a signal that could not be measured shown as a zero is a lie of
 *       omission; a blocked suggestion with no reason is a mystery; and a screen that does not say
 *       it buys nothing is one a user may assume has (P-07).
 * What: each verdict's wording, the score line, the hit rate and its absence, an unmeasured signal,
 *       the blocked suggestion and its named gate, the staleness line and the standing promise.
 * Result: a screen a user can argue with.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
@RunWith(RobolectricTestRunner::class)
class OpportunityScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a strong day is shown with the score it came from`() {
        render(OpportunityUiState(views = listOf(view()), isLoaded = true))

        compose.onNodeWithText("Today scores as a strong day to deploy.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("72 points out of a possible 85.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the verdict is shown with its measured record`() {
        // §30.3 is the whole differentiator: a verdict without its hit rate is a tip.
        render(OpportunityUiState(views = listOf(view()), isLoaded = true))

        compose
            .onNodeWithText(
                "In this household's own history, this verdict was followed by a higher price 64% of the time.",
            ).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Measured over 44 comparable days.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `no measured record says so, rather than quietly omitting it`() {
        render(OpportunityUiState(views = listOf(view(hitRate = null)), isLoaded = true))

        compose
            .onNodeWithText("Not enough comparable days yet to say how this verdict has done.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a signal that could not be measured says so, and not zero`() {
        render(OpportunityUiState(views = listOf(view()), isLoaded = true))

        compose
            .onNodeWithText("How cheap the market is — not measured, so it counts for nothing either way")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a measured signal shows the number behind its points`() {
        render(OpportunityUiState(views = listOf(view()), isLoaded = true))

        compose.onNodeWithText("How far below its 52-week high (-13%) — 15 of 20").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a blocked suggestion names what blocked it`() {
        render(OpportunityUiState(views = listOf(view()), isLoaded = true))

        compose.onNodeWithText("No deployment is suggested right now.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your emergency fund is not at its target yet.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a young history is said plainly, with no score at all`() {
        render(
            OpportunityUiState(
                views = listOf(view(outcome = AssessmentOutcome.NOT_ENOUGH_HISTORY, band = null)),
                isLoaded = true,
            ),
        )

        compose.onNodeWithText("Not enough price history yet to score this one.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an old price is said plainly`() {
        render(
            OpportunityUiState(
                views = listOf(view(outcome = AssessmentOutcome.TOO_STALE, band = null)),
                isLoaded = true,
            ),
        )

        compose
            .onNodeWithText("The last price here is too old to judge today by.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the screen says it never buys anything`() {
        render(OpportunityUiState(views = listOf(view()), isLoaded = true))

        compose
            .onNodeWithText("This screen never buys anything. It scores, it shows its working, and you decide.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `an empty list explains itself rather than looking broken`() {
        render(OpportunityUiState(views = emptyList(), isLoaded = true))

        compose
            .onNodeWithText(
                "Nothing to score yet. Add an investment with a price key, and a history builds up " +
                    "as prices are fetched.",
            ).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `every signal in the library has words`() {
        // A signal added to the knowledge base with no wording would reach the user as a blank line.
        MarketKnowledge.BUNDLED.signals.forEach { spec ->
            assertTrue("${spec.id} has no wording", compose.activity.getString(signalLabel(spec.id)).isNotBlank())
        }
    }

    private fun view(
        outcome: AssessmentOutcome = AssessmentOutcome.SCORED,
        band: OpportunityBand? = OpportunityBand.STRONG_BUY_DAY,
        hitRate: HitRate? = HitRate(samples = 44, hits = 28, ratePct = 64, horizonDays = 90),
    ) = OpportunityView(
        assessment =
            OpportunityAssessment(
                instrument = Instrument("NSE:NIFTYBEES", "Nifty BeES"),
                outcome = outcome,
                score = 72,
                possibleScore = 85,
                band = band,
                signals =
                    listOf(
                        SignalContribution(MarketKnowledge.VALUATION, 0, 25, evaluated = false),
                        SignalContribution(MarketKnowledge.DRAWDOWN, 15, 20, true, measuredBps = -1_300),
                    ),
                hitRate = hitRate,
                tranches =
                    TranchePlan(
                        suggested = 0,
                        gates =
                            listOf(
                                CapacityGate("RULE-IDLE-CASH", passed = true),
                                CapacityGate("RULE-RUNWAY-M", passed = false),
                                CapacityGate("AI-FCT", passed = true),
                            ),
                    ),
                staleness = Staleness("2026-09-27", 0, isStale = false),
                provenance =
                    EngineProvenance(
                        engineId = "AI-MKT",
                        engineVersion = "1.0",
                        computedAtUtcMillis = NOW,
                        evidence = listOf(RuleCitation("MKT-SIGNALS", "1.1")),
                    ),
            ),
        holdingLabel = "Nifty BeES",
    )

    private fun render(uiState: OpportunityUiState) {
        compose.setContent { OpportunityContent(uiState = uiState, onDone = {}) }
    }

    private companion object {
        const val NOW = 1_790_000_000_000L
    }
}

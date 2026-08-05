package com.aicfo.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.aicfo.core.designsystem.chart.CfoProportionBar
import com.aicfo.core.designsystem.chart.CfoProportionSegment
import com.aicfo.core.designsystem.chart.CfoSparkline
import com.aicfo.core.designsystem.component.CfoAmountText
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoDemoBanner
import com.aicfo.core.designsystem.component.CfoListRow
import com.aicfo.core.designsystem.component.CfoPinField
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Money
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot tests for the design system — light, dark and 200% font (§21.5, ACC-*).
 *
 * Why:  this project has no emulator, so these renders are the **only** way anyone sees what this
 *       UI actually looks like. They also turn two acceptance criteria that are usually claimed
 *       rather than checked into artefacts: dark mode really is a different render, and the
 *       200%-font case really does show whether a layout survives a user who needs large text.
 *       Paparazzi runs on the JVM, which is what makes any of this possible here.
 * What: every component and both charts, in three configurations each.
 * Result: a visual diff fails the build when a token or layout changes unintentionally.
 * Changelog: 2026-07-25 — Created for issue 1.8 (closes governance audit G-02).
 *            2026-07-26 — Issue 2.2: CfoPinField joins the gallery.
 *            2026-07-28 — Issue 2.4: CfoDemoBanner joins the gallery.
 *
 * Record baselines with `./gradlew :core:designsystem:recordPaparazziDebug`; verify with
 * `verifyPaparazziDebug`. Baselines are committed — a screenshot test with no committed baseline
 * checks nothing.
 */
class DesignSystemScreenshotTest {
    @get:Rule
    val paparazzi: Paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5,
            // Pinned: the renderer's platform version decides the pixels, so leaving it floating
            // would make baselines change under an unrelated SDK update.
            theme = "android:Theme.Material.Light.NoActionBar",
        )

    /** Input: the component gallery in light mode. Output: records/verifies the light baseline. */
    @Test
    fun gallery_light() {
        paparazzi.snapshot { Gallery(darkTheme = false) }
    }

    /** Input: the same gallery in dark mode. Output: proves dark mode is genuinely themed. */
    @Test
    fun gallery_dark() {
        paparazzi.snapshot { Gallery(darkTheme = true) }
    }

    /**
     * Input:  the gallery at a 2x font scale.
     * Output: the accessibility case the DoD asks for — if a label clips or a row collapses at
     *         200% font, it is visible here rather than on a user's phone.
     */
    @Test
    fun gallery_largeFont() {
        // Only the font scale changes; the theme is passed to the composable, so uiMode is left alone.
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = LARGE_FONT_SCALE))
        paparazzi.snapshot { Gallery(darkTheme = false) }
    }

    /**
     * Every component in one frame.
     * Why:    one render per configuration rather than per component keeps the baseline count (and
     *         the review burden of a diff) proportionate to what actually changed. Split into two
     *         halves purely to stay under the 40-line function limit (§21.6).
     * Result: the gallery composable used by all three tests.
     * Input:  [darkTheme]. Output: the composition.
     */
    @Composable
    private fun Gallery(darkTheme: Boolean) {
        CfoTheme(darkTheme = darkTheme) {
            Surface {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(CfoDimens.spaceMd),
                    verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
                ) {
                    MoneySection()
                    ChartSection()
                    // Issue 2.2: rendered with digits already typed, because what matters visually
                    // is that they are **masked** — a baseline showing "135790" would be the bug.
                    CfoPinField(value = "1357", onValueChange = {}, label = "PIN")
                    CfoButton(text = "Add transaction", onClick = {})
                    CfoSecondaryButton(text = "Skip", onClick = {})
                    // Issue 2.4: the demo label. In the gallery because its whole job is to be
                    // unmistakable — whether it still reads that way in dark mode and at 200% font
                    // is a question only a render can answer.
                    CfoDemoBanner(
                        message = "Demo data — not your money",
                        actionText = "Exit demo",
                        onExit = {},
                    )
                }
            }
        }
    }

    /**
     * The money half: a summary card and two list rows.
     * Result: the composition. Input: none. Output: the rendered section.
     */
    @Composable
    private fun MoneySection() {
        CfoCard {
            Text("Net worth")
            // showSign = false: a net-worth figure is a balance, not a movement, so "+" is noise.
            CfoAmountText(
                amount = Money(1_23_456_78L),
                contentDescription = "Net worth ₹1,23,456.78",
                showSign = false,
            )
        }
        CfoListRow(
            title = "Groceries",
            supporting = "Big Bazaar · 2 Jan",
            trailing = { CfoAmountText(Money(-2_450_00L), contentDescription = "Spent ₹2,450.00") },
        )
        CfoListRow(
            title = "Salary",
            supporting = "Credited · 1 Jan",
            trailing = { CfoAmountText(Money(85_000_00L), contentDescription = "Received ₹85,000.00") },
        )
    }

    /**
     * The chart half: a spending split and a balance trend.
     * Result: the composition. Input: none. Output: the rendered section.
     */
    @Composable
    private fun ChartSection() {
        CfoProportionBar(
            segments =
                listOf(
                    CfoProportionSegment(40L, CfoTheme.extendedColors.negative),
                    CfoProportionSegment(35L, CfoTheme.extendedColors.warning),
                    CfoProportionSegment(25L, CfoTheme.extendedColors.positive),
                ),
            contentDescription = "Spending split: needs 40%, wants 35%, savings 25%",
        )
        CfoSparkline(
            values = listOf(10L, 30L, 20L, 55L, 40L, 70L),
            contentDescription = "Balance trend over six months, rising",
        )
    }

    private companion object {
        /** The DoD's accessibility case: text at twice the default size. */
        const val LARGE_FONT_SCALE = 2.0f
    }
}

package com.aicfo.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicfo.core.model.Money
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [CfoWidgetContent] — that the widget actually emits what it decided to (issue 5.5).
 *
 * Why:  `WidgetTextTest` proves the *decision* is right; this proves the decision reaches the
 *       screen. They are different failures: a correct `amountText` still shows nothing if the
 *       composable drops the second figure, resolves the wrong string resource, or throws.
 *
 *       **Why not Paparazzi.** Every other UI in this repo is screenshot-tested, and this one
 *       cannot be. Glance does not produce a Compose or Android view tree — it emits `RemoteViews`
 *       for the launcher's `AppWidgetHost` to inflate in another process, which LayoutLib has
 *       nothing to render. `runGlanceAppWidgetUnitTest` composes the Glance tree on the JVM and
 *       lets a test assert over the emitted nodes instead. That covers content and the blur; the
 *       pixels in light and dark are verified on a device and logged in the tracker, not claimed
 *       by a baseline that would check nothing. See ADR-0024.
 * What: the loaded state, the blurred state, and the state before any refresh has run.
 * Result: both figures and both labels are on the widget, and the blurred render carries neither
 *       formatted amount.
 * Changelog: 2026-08-17 — Created for issue 5.5.
 */
@RunWith(AndroidJUnit4::class)
class CfoWidgetContentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Input: both figures computed, blur off.
     * Output: asserts both labels and both formatted amounts are emitted — the ordinary case, and
     * the one that would silently regress if a figure were dropped from the layout.
     */
    @Test
    fun `shows both figures with their labels`() =
        runGlanceAppWidgetUnitTest(COMPOSE_TIMEOUT) {
            setAppWidgetSize(WIDGET_SIZE)
            provideComposable {
                CfoWidgetContent(
                    snapshot = WidgetSnapshot(safeToSpend = Money(12_345_67), netWorth = Money(5_00_000_00)),
                    context = context,
                )
            }

            onNode(hasText(context.getString(R.string.widget_safe_to_spend_label))).assertExists()
            onNode(hasText(context.getString(R.string.widget_net_worth_label))).assertExists()
            onNode(hasText("₹12,345.67")).assertExists()
            onNode(hasText("₹5,00,000.00")).assertExists()
        }

    /**
     * Input: the same figures, blur on.
     * Output: asserts the masks are shown and **neither formatted amount exists anywhere in the
     * emitted tree** — the assertion this widget's privacy rests on (P-01, ADR-0022). The labels
     * stay: hiding those would tell a shoulder-reader nothing extra and would make the widget
     * unreadable as anything at all.
     */
    @Test
    fun `shows no amount when the blur is on`() =
        runGlanceAppWidgetUnitTest(COMPOSE_TIMEOUT) {
            setAppWidgetSize(WIDGET_SIZE)
            provideComposable {
                CfoWidgetContent(
                    snapshot =
                        WidgetSnapshot(
                            safeToSpend = Money(12_345_67),
                            netWorth = Money(5_00_000_00),
                            blurred = true,
                        ),
                    context = context,
                )
            }

            onNode(hasText("₹12,345.67")).assertDoesNotExist()
            onNode(hasText("₹5,00,000.00")).assertDoesNotExist()
            onNode(hasText(context.getString(R.string.widget_safe_to_spend_label))).assertExists()
        }

    /**
     * Input: the state of a freshly placed widget — nothing written yet.
     * Output: asserts **both** figures show the pending label, and that `₹0.00` appears nowhere. A
     * zero here would be the app stating a figure no engine produced, on the surface a user trusts
     * most casually (P-03). Counting two rather than asserting one exists is deliberate: it catches
     * the half-failure where one figure falls back correctly and the other renders a zero.
     */
    @Test
    fun `shows the pending label before anything has been computed`() =
        runGlanceAppWidgetUnitTest(COMPOSE_TIMEOUT) {
            setAppWidgetSize(WIDGET_SIZE)
            provideComposable { CfoWidgetContent(snapshot = WidgetSnapshot(), context = context) }

            onAllNodes(hasText(context.getString(R.string.widget_pending))).assertCountEquals(2)
            onNode(hasText("₹0.00")).assertDoesNotExist()
        }

    private companion object {
        /** A 3×1 cell, the size declared in `cfo_widget_info.xml`. */
        val WIDGET_SIZE = DpSize(180.dp, 70.dp)

        /**
         * How long a positive assertion may wait for the composition.
         *
         * The harness defaults to a second, which is generous once the JVM is warm and not nearly
         * enough when this class happens to run first: Robolectric, the Compose runtime and the
         * Glance node tree all load on the first `assertExists`, and the whole budget goes on class
         * loading before a single node is emitted. It surfaced as two tests passing on
         * `testDebugUnitTest` and timing out on `testReleaseUnitTest` — nothing to do with the
         * variant, only with which ran cold. These assertions are about **content**; making them
         * also a speed test would mean a flake that looks like a privacy regression.
         */
        val COMPOSE_TIMEOUT = 30.seconds
    }
}

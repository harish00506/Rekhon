package com.aicfo.app

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicfo.core.model.Money
import com.aicfo.widget.CfoWidget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Places a real widget on a real device and reads back what it draws (issue 5.5; P-01/P-03/P-04).
 *
 * Why:  the JVM tests in `:widget` compose a Glance tree and assert over its nodes; that is not the
 *       same thing as the launcher inflating `RemoteViews`. The step between — Glance turning the
 *       composition into remote views that another process can inflate — is where a widget silently
 *       renders nothing, and it has no JVM equivalent because there is no `AppWidgetHost` there.
 *       This is also why the widget has no Paparazzi baselines (ADR-0024): this test, and a person
 *       looking at the home screen, are what stand in for them.
 *
 *       **It exercises the real state store**, not a hand-made snapshot. `writeFigures` writes to
 *       the same Glance preference file `WidgetRefreshWorker` writes to in production, and `compose`
 *       reads it back through the same state definition, so the chain under test is
 *       write → preferences → compose → RemoteViews → inflate.
 * What: the loaded render, and the blurred render swept for digits.
 * Result: the assertion the feature rests on — no amount reaches a home screen while the blur is on
 *       — is checked against inflated views, not against an abstraction of them.
 *
 * **Airplane mode changes nothing here** (P-04): binding a widget, reading preferences and
 * inflating remote views are all local. The suite is run in airplane mode and logged that way.
 *
 * Changelog: 2026-08-17 — Created for issue 5.5.
 *
 * Requires the shell grant `adb shell appwidget grantbind --package com.aicfo.personalcfo --user 0`,
 * which lets the app bind its own provider without a launcher. Without it the bind is refused and
 * these tests **skip with that reason named** rather than passing on an unplaced widget — a green
 * tick for a widget that was never on screen is exactly the kind of gate this repo has been bitten
 * by before.
 */
@RunWith(AndroidJUnit4::class)
class WidgetDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val host = AppWidgetHost(context, HOST_ID)
    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    /** Allocates and binds one widget, or skips the class. Input: none. Output: none. */
    @Before
    fun placeWidget() {
        widgetId = host.allocateAppWidgetId()
        val bound =
            AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(
                widgetId,
                ComponentName(context, "com.aicfo.widget.CfoWidgetReceiver"),
            )
        assumeTrue("no bind permission — run `adb shell appwidget grantbind --package ${context.packageName}`", bound)
    }

    /** Releases the widget id so a rerun starts clean. Input: none. Output: none. */
    @After
    fun removeWidget() {
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(widgetId)
    }

    /**
     * Input: two figures written through the production writer.
     * Output: asserts both reach the inflated view — the end-to-end the JVM cannot reach.
     */
    @Test
    fun rendersTheCachedFigures() =
        runBlocking {
            CfoWidget.writeFigures(context, safeToSpend = Money(12_345_67), netWorth = Money(5_00_000_00))

            val text = renderedText()

            assertTrue("Safe-to-Spend missing from $text", text.any { it.contains("₹12,345.67") })
            assertTrue("net worth missing from $text", text.any { it.contains("₹5,00,000.00") })
        }

    /**
     * Input: the same figures, then the blur turned on the way the watcher turns it on.
     * Output: asserts the masks are drawn **and** that no ASCII digit appears anywhere in the
     * inflated view — the privacy criterion carried over from issue 5.3, checked on the surface it
     * was carried here for.
     *
     * The mask assertion comes first on purpose. A digit sweep alone passes trivially against a
     * widget showing nothing at all, which is exactly what happened the first time this test ran
     * (`compose()` was reading an unwritten state) — a green tick for a blur that had no amounts to
     * hide. Requiring both masks means the sweep is over a widget that genuinely had figures.
     */
    @Test
    fun hidesEveryDigitWhenBlurred() =
        runBlocking {
            CfoWidget.writeFigures(context, safeToSpend = Money(12_345_67), netWorth = Money(5_00_000_00))

            // No database is touched by this call, which is why it also works on a locked device.
            CfoWidget.writeBlurred(context, blurred = true)
            val text = renderedText()

            assertEquals("both figures must be masked, not absent: $text", 2, text.count { it.contains("•") })
            text.forEach { line ->
                assertFalse("a digit survived the blur: $line", line.any { it in '0'..'9' })
            }
        }

    /**
     * Composes the placed widget and inflates the result, exactly as a launcher would.
     * Why:    `compose` is the shipped path — the same call `GlanceAppWidgetReceiver` makes — so
     *         inflating its output is the closest a test gets to looking at the home screen.
     *
     *         **The `GlanceId` is not optional here, and that is the bug this test found.**
     *         `compose(context)` with no id composes against *empty* state, so the first version of
     *         this file rendered two "Not yet worked out" labels no matter what had been written —
     *         and the blur test passed on it, because a widget with no amounts has no digits to
     *         leak. Passing the id of the widget actually bound in [placeWidget] is what makes this
     *         read the same preference file `WidgetRefreshWorker` writes in production.
     * Result: every string in the inflated view hierarchy.
     * Input:  none (reads the placed widget's real state). Output: the texts.
     */
    @OptIn(ExperimentalGlanceApi::class)
    private suspend fun renderedText(): List<String> {
        val glanceId = GlanceAppWidgetManager(context).getGlanceIds(CfoWidget::class.java).first()
        val remoteViews = CfoWidget().compose(context, id = glanceId)
        val root = remoteViews.apply(context, FrameLayout(context))
        return buildList { collectText(root, this) }
    }

    /**
     * Walks an inflated hierarchy collecting every visible string.
     * Why:    the blur assertion has to be over *everything* drawn, not over the two nodes a test
     *         thought to look at — a digit that leaked through a label would pass a targeted check.
     * Result: the accumulated texts. Input: [view]; [into] — the accumulator. Output: none.
     */
    private fun collectText(
        view: View,
        into: MutableList<String>,
    ) {
        if (view is TextView) view.text?.toString()?.takeIf { it.isNotBlank() }?.let(into::add)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectText(view.getChildAt(index), into)
        }
    }

    private companion object {
        /** Any id not used by a real launcher; the host is created and torn down by this test. */
        const val HOST_ID = 0x5F5F
    }
}

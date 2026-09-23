package com.aicfo.app.notification

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.model.DateFormatter
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.domain.engines.guardrail.GuardrailEngineFactory
import com.aicfo.domain.engines.insight.Insight
import com.aicfo.domain.engines.insight.InsightType
import com.aicfo.domain.engines.notification.NotificationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate

/**
 * The insight notifications: which insights may become one, and what reaches the phone (issue 9.6;
 * §17.1, AI-ARC-004, NTF-004).
 *
 * Why:  three claims. **The mapping is §17.1's defaults** — a crunch is Critical, a goal falling
 *       behind is a goal event, and nothing else is posted per insight. **The key is stable where the
 *       period is not** — a crunch keyed by the day it was computed would notify every morning on the
 *       channel that ignores the caps. **What is posted is the engine's figures or nothing**, and with
 *       the blur on, no digit at all.
 * What: `kindFor` over every type; `keyFor`; the posted text, channel and lock-screen visibility;
 *       the blurred text; permission denied.
 * Result: a regression in any of them names itself.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
@RunWith(RobolectricTestRunner::class)
class InsightNotifierTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val notifier = AndroidInsightNotifier(application, GuardrailEngineFactory.create())
    private val manager = application.getSystemService(NotificationManager::class.java)

    /** Input: none. Output: the permission granted and the channels registered, as at start-up. */
    @Before
    fun setUp() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        CfoNotifications.createChannels(application)
    }

    @Test
    fun `only a crunch and a goal falling behind are notified per insight`() {
        val kinds = InsightType.entries.associateWith(InsightNotifications::kindFor)

        assertEquals(
            mapOf(
                InsightType.CRUNCH_DAY to NotificationKind.CRITICAL_MONEY,
                InsightType.BUDGET_OVERSPENT to null,
                InsightType.EMERGENCY_FUND_SHORT to null,
                InsightType.GOAL_BEHIND to NotificationKind.GOAL_EVENT,
                InsightType.SEASONAL_MONTH to null,
                InsightType.HEALTH_LEVER to null,
            ),
            kinds,
        )
    }

    @Test
    fun `a crunch is keyed by its first crunch day, not by the day it was computed`() {
        val today = crunch(period = "2026-09-20")
        val tomorrow = crunch(period = "2026-09-21")

        assertEquals(InsightNotifications.keyFor(today), InsightNotifications.keyFor(tomorrow))
        assertEquals("insight:CRUNCH_DAY|2026-09-28", InsightNotifications.keyFor(today))
        // An earlier crunch is news, and says so under a new key.
        assertFalse(
            InsightNotifications.keyFor(today) ==
                InsightNotifications.keyFor(crunch(date = LocalDate.parse("2026-09-25"))),
        )
    }

    @Test
    fun `a goal is keyed by its fingerprint`() {
        assertEquals("insight:GOAL_BEHIND|goal:car|2027-03-31", InsightNotifications.keyFor(goal()))
    }

    @Test
    fun `a crunch is posted on the critical channel with the engine's figures, and private on the lock screen`() {
        assertTrue(notifier.notify(crunch(), NotificationKind.CRITICAL_MONEY, blurAmounts = false))

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(CfoNotifications.CRITICAL_MONEY_CHANNEL_ID, posted.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        val title = posted.extras.getString(NotificationCompat.EXTRA_TITLE)
        val body = posted.extras.getString(NotificationCompat.EXTRA_TEXT)
        assertTrue(title!!.contains(DateFormatter.day("2026-09-28")))
        assertTrue(body!!.contains(MoneyFormatter.format(Money(1_200_00L))))
        assertTrue(body.contains(MoneyFormatter.format(Money(5_000_00L))))
        assertTrue("three days, pluralised", body.startsWith("3 days"))
    }

    @Test
    fun `a goal falling behind is posted on the goals channel`() {
        assertTrue(notifier.notify(goal(), NotificationKind.GOAL_EVENT, blurAmounts = false))

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(CfoNotifications.GOAL_EVENTS_CHANNEL_ID, posted.channelId)
        assertEquals("Car 2 is falling behind", posted.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertTrue(
            posted.extras.getString(NotificationCompat.EXTRA_TEXT)!!.contains(MoneyFormatter.format(Money(2_500_00L))),
        )
    }

    @Test
    fun `with the blur on, no digit reaches the phone, dates included`() {
        assertTrue(notifier.notify(crunch(), NotificationKind.CRITICAL_MONEY, blurAmounts = true))
        assertTrue(notifier.notify(goal(label = "Car"), NotificationKind.GOAL_EVENT, blurAmounts = true))

        shadowOf(manager).allNotifications.forEach { posted ->
            val text = "${posted.extras.getString(
                NotificationCompat.EXTRA_TITLE,
            )} ${posted.extras.getString(NotificationCompat.EXTRA_TEXT)}"
            assertFalse("a digit leaked: $text", text.any(Char::isDigit))
        }
    }

    @Test
    fun `an insight this notifier does not word is not posted`() {
        val seasonal = crunch().copy(type = InsightType.SEASONAL_MONTH)

        assertFalse(notifier.notify(seasonal, NotificationKind.AI_INSIGHT, blurAmounts = false))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `a figure the engine did not produce is not posted at all`() {
        // The wiring, not the gate: AI-GRD is proven in its own module. What this asserts is that a
        // refusal reaches the phone as silence rather than as a notification with a warning
        // (AI-ARC-004, P-03). The amount here is real; the *evidence* no longer contains it.
        val lying = crunch().copy(amount = null, secondary = null)

        assertFalse(notifier.notify(lying, NotificationKind.CRITICAL_MONEY, blurAmounts = false))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `without the permission nothing is posted`() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(notifier.notify(crunch(), NotificationKind.CRITICAL_MONEY, blurAmounts = false))
        assertNull(shadowOf(manager).allNotifications.firstOrNull())
    }

    private fun crunch(
        period: String = "2026-09-20",
        date: LocalDate = LocalDate.parse("2026-09-28"),
    ) = Insight(
        type = InsightType.CRUNCH_DAY,
        subject = null,
        subjectLabel = null,
        period = period,
        amount = Money(1_200_00L),
        secondary = Money(5_000_00L),
        date = date,
        quantity = 3,
        sourceEngineId = "AI-FCT",
        sourceEngineVersion = "1.0",
    )

    private fun goal(label: String = "Car 2") =
        Insight(
            type = InsightType.GOAL_BEHIND,
            subject = "goal:car",
            subjectLabel = label,
            period = "2027-03-31",
            amount = Money(2_500_00L),
            sourceEngineId = "AI-GOAL",
            sourceEngineVersion = "1.0",
        )
}

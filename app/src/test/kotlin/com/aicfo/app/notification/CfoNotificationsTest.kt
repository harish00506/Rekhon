package com.aicfo.app.notification

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.aicfo.domain.engines.notification.NotificationKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * One Android channel per §17.1 row (issue 9.6; NTF-006).
 *
 * Why:  the policy engine names a channel by id and Android posts into whatever channel that id
 *       names. A kind with no registered channel posts into nothing on API 26+, silently — so the two
 *       lists have to be the same list, and only a test can hold them there.
 * What: the ids match `NotificationKind`; all are registered; the importance of each.
 * Result: a new kind without a channel, or a renamed id, fails here.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
@RunWith(RobolectricTestRunner::class)
class CfoNotificationsTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `every taxonomy row has exactly one channel`() {
        assertEquals(
            NotificationKind.entries.map { it.channelId }.toSet(),
            CfoNotifications.channels.map { it.id }.toSet(),
        )
        assertEquals(NotificationKind.entries.size, CfoNotifications.channels.size)
    }

    @Test
    fun `every channel is registered at start-up, with its importance`() {
        CfoNotifications.createChannels(application)

        val manager = application.getSystemService(NotificationManager::class.java)
        val importance = manager.notificationChannels.associate { it.id to it.importance }
        assertEquals(NotificationKind.entries.size, importance.size)
        assertEquals(
            "the one channel that may interrupt",
            NotificationManager.IMPORTANCE_HIGH,
            importance["critical-money-events"],
        )
        assertEquals(
            "§17.1: off by default, and never a sound",
            NotificationManager.IMPORTANCE_LOW,
            importance["habit-capture"],
        )
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, importance["goal-events"])
    }

    @Test
    fun `registering twice is harmless`() {
        CfoNotifications.createChannels(application)
        CfoNotifications.createChannels(application)

        assertEquals(
            NotificationKind.entries.size,
            application.getSystemService(NotificationManager::class.java).notificationChannels.size,
        )
    }
}

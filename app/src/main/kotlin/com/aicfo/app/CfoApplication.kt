package com.aicfo.app

import android.app.Application
import com.aicfo.app.di.ProfileZoneProvider
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point and Hilt dependency-injection root.
 *
 * Why:  §21.2 / ARC-003 — the app is a single-Activity Compose host whose object graph is built by
 *       Hilt; `@HiltAndroidApp` bootstraps that graph once at process start.
 * What: the custom [Application], plus the one piece of startup work the app must do: begin
 *       tracking the profile time zone.
 * Result: constructor injection everywhere, and a `Clock` that resolves in the user's zone.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 *            2026-07-25 — Issue 1.10: starts [ProfileZoneProvider].
 *
 * Starting the collector here rather than in the provider's constructor keeps object construction
 * free of side effects — Hilt may build the graph earlier than the app is ready, and a test must be
 * able to create the provider without also launching a coroutine.
 */
@HiltAndroidApp
class CfoApplication : Application() {
    @Inject
    lateinit var profileZoneProvider: ProfileZoneProvider

    /** Input: none. Output: none (starts the profile-zone collector). */
    override fun onCreate() {
        super.onCreate()
        profileZoneProvider.start()
    }
}

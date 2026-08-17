package com.aicfo.app.widget

import android.content.Context
import com.aicfo.core.common.getOrNull
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.widget.CfoWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hides the home-screen widget the moment the user asks for the blur (issue 5.5; 5.3, P-01).
 *
 * Why:  issue 5.3 built a blur that reaches every amount **in the app**. The widget is outside it,
 *       and outside it in the worst way: it renders on the launcher, with no app lock in front of
 *       it, and it stays on screen after the phone is handed to someone. 5.3 left that acceptance
 *       criterion unticked because `:widget` did not exist; this is where it lands.
 *
 *       **Separate from `WidgetRefreshWorker`, and that separation is the whole point.** The
 *       obvious design is to let the refresh worker carry the flag — it reads it anyway. But that
 *       worker cannot run while the app is locked (`provideDatabase` throws, SEC-002), and the
 *       moment a user most wants amounts gone is exactly a moment they may have just locked the
 *       phone. Writing the flag on its own touches nothing but Glance's preference store, so it
 *       always succeeds. The figures already in the cache stay put and are simply masked.
 *
 *       **A watcher rather than a call in the toggle's handler**, for the reason
 *       `SmsConsentWatcher` gives: today one screen writes this flag, tomorrow the settings screen
 *       is the second, and the one that forgot would be the one leaving a balance on the home
 *       screen. Observing the state catches every writer, including ones not built yet.
 * What: watches `SettingsStore`'s `privacyBlurEnabled` and pushes each change into the widget.
 * Result: toggling the blur masks or unmasks the widget within a frame, locked or unlocked.
 * Changelog: 2026-08-17 — Created for issue 5.5.
 *
 * **Every emission is written, not only transitions** — unlike `SmsConsentWatcher`, which purges
 * only on a granted → revoked edge because purging is destructive. Writing a boolean is not: the
 * first emission after a cold start is precisely when the widget's stored flag may be out of step
 * with settings (the process was dead when the user last changed it), so skipping it would be the
 * bug. `distinctUntilChanged` keeps a re-emission of the same value from redrawing for nothing.
 *
 * Input:  [settings] — the flag's source of truth, shared with the app's own blur; [context] — the
 *         application context Glance writes through; [scope] — the injected application scope the
 *         collector lives on (never `GlobalScope`, ARC-006).
 * Output: a watcher `CfoApplication` starts once.
 */
@Singleton
open class WidgetBlurWatcher
    @Inject
    constructor(
        private val settings: SettingsStore,
        @ApplicationContext private val context: Context,
        private val scope: CoroutineScope,
    ) {
        /**
         * Starts watching.
         * Why:    called from `CfoApplication.onCreate`, the seam `ProfileZoneProvider.start()` and
         *         `SmsConsentWatcher.start()` already use, so the app keeps one list of things that
         *         begin at launch.
         * Result: a collector on [scope] that outlives every screen. An unreadable settings store
         *         reads as **not** blurred, matching `MainViewModel`: the blur is a display
         *         preference, and the security boundary is the app lock, which fails closed
         *         separately (ADR-0022).
         * Input:  none. Output: none.
         * Changelog: 2026-08-17 — Created for issue 5.5.
         */
        fun start() {
            scope.launch {
                settings.observe()
                    .map { it.getOrNull()?.privacyBlurEnabled == true }
                    .distinctUntilChanged()
                    .collectLatest { blurred -> publish(blurred) }
            }
        }

        /**
         * Pushes the flag into the widget's state (issue 5.5).
         *
         * Why:    `open`, and the only reason is testability — a deliberate, documented one. Writing
         *         Glance state needs a placed widget and an `AppWidgetHost`, neither of which exists
         *         on the JVM, so a test calling [start] could assert nothing at all about what
         *         reached the widget. Overriding one method lets `WidgetBlurWatcherTest` prove the
         *         flag *arrives* — that a cold start publishes, that a toggle publishes, and that an
         *         unreadable store publishes `false` rather than masking the widget by accident.
         *
         *         The alternative was a one-method interface with a Hilt `@Binds`, which is thirty
         *         lines of ceremony around this one call. Nothing overrides this in production.
         * Result: every placed widget masks or unmasks. No database is touched, so this succeeds
         *         while the app is locked — the property the whole watcher exists for.
         * Input:  [blurred] — the flag. Output: none.
         * Changelog: 2026-08-17 — Created for issue 5.5.
         */
        internal open suspend fun publish(blurred: Boolean) {
            CfoWidget.writeBlurred(context, blurred)
        }
    }

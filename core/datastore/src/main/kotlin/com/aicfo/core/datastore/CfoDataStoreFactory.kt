package com.aicfo.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Builds the settings store and the two views over it (issue 1.9).
 *
 * Why:  settings and consents share one file so a single `updateData` keeps them consistent, but
 *       callers should see two narrow interfaces rather than one grab-bag — a screen that toggles
 *       the theme has no business being able to grant a consent.
 * What: creates the [DataStore] and wraps it in [SettingsStore] and [ConsentStore].
 * Result: the only Android-aware code in this module, and the only part a JVM test cannot cover.
 * Changelog: 2026-07-25 — Created for issue 1.9.
 *
 * **No corruption handler, deliberately.** The obvious wiring —
 * `ReplaceFileCorruptionHandler { defaultValue }` — would silently reset a corrupt file to
 * defaults, which for a consent ledger means silently discarding every permission decision the
 * user made. Corruption instead surfaces as `Err(Storage)` and the app decides what to tell them.
 *
 * Input:  [context] — any context; the application context is used. [clock] — timestamps
 *         (TIM-001). [dispatchers] — where I/O runs. [scope] — the DataStore's own coroutine
 *         scope, injected rather than created so it is cancelled with its owner (ARC-006).
 * Output: [CfoDataStores] holding both interfaces.
 */
object CfoDataStoreFactory {
    /** The settings file inside app-private storage. */
    private const val FILE_NAME = "cfo_settings.pb"

    /**
     * Creates the stores.
     * Result: both interfaces over one atomic file.
     * Input:  see the class doc. Output: [CfoDataStores].
     */
    fun create(
        context: Context,
        clock: Clock,
        dispatchers: DispatcherProvider,
        scope: CoroutineScope,
    ): CfoDataStores {
        val dataStore =
            CfoSettingsStorage.create(
                path = File(context.applicationContext.filesDir, FILE_NAME).absolutePath,
                scope = scope,
            )
        return CfoDataStores(
            settings = DataStoreSettingsStore(dataStore, clock, dispatchers),
            consents = DataStoreConsentStore(dataStore, clock, dispatchers),
            appLock = DataStoreAppLockStore(dataStore, clock, dispatchers),
        )
    }
}

/**
 * The interfaces over the shared settings file.
 * Why:    returning them from one factory makes it obvious they are backed by the same atomic
 *         store, while keeping their surfaces separate — a screen that changes the theme has no
 *         business granting a consent or resetting a lockout counter.
 * Result: what the DI graph (issue 1.10) binds.
 * Input:  [settings], [consents], [appLock]. Output: a holder.
 * Changelog: 2026-07-25 — Created for issue 1.9.
 *            2026-07-26 — Issue 2.2: added [appLock].
 */
data class CfoDataStores(
    val settings: SettingsStore,
    val consents: ConsentStore,
    val appLock: AppLockStore,
)

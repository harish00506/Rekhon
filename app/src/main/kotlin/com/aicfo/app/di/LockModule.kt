package com.aicfo.app.di

import android.content.Context
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.crypto.KeystoreMacFactory
import com.aicfo.core.crypto.PinVerifier
import com.aicfo.core.crypto.SessionLock
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.datastore.AppLockStore
import com.aicfo.core.datastore.CfoDataStores
import com.aicfo.data.repository.AuditLogRepository
import com.aicfo.data.repository.RepositoryFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The app lock's slice of the object graph (issue 2.2; SEC-002, ARC-003).
 *
 * Why:  kept apart from `CoreModule` rather than added to it. Partly because that object had grown
 *       past detekt's function limit, but mostly because these four bindings are the security
 *       perimeter: the session gate, the PIN check, the stored lock state, and the one repository
 *       allowed to touch the database before an unlock. Having them in one short file is what makes
 *       "who can read data while locked?" a question answerable by reading one screen of code.
 * What: `SessionLock`, `PinVerifier`, `AppLockStore` and `AuditLogRepository`.
 * Result: the lock ViewModel gets everything by constructor injection (ARC-003, no service locators).
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
@Module
@InstallIn(SingletonComponent::class)
object LockModule {
    /**
     * The session gate (SEC-002).
     * Why:    app-scoped so the lock ViewModel, the database provider and any later background job
     *         all read the same flag. Starts closed — fail-secure is the default, not a code path.
     * Result: the app's single [SessionLock]. Input: none. Output: [SessionLock].
     */
    @Provides
    @Singleton
    fun provideSessionLock(): SessionLock = SessionLock()

    /**
     * The PIN check (SEC-002, SEC-003).
     * Why:    ARC-003 keeps `TinkPinVerifier` internal, so the factory in `:core:crypto` is what
     *         assembles it over the Keystore-backed MAC — the same seam `CfoDatabaseFactory` uses.
     * Result: the production [PinVerifier]. Input: [context]. Output: [PinVerifier].
     */
    @Provides
    @Singleton
    fun providePinVerifier(
        @ApplicationContext context: Context,
    ): PinVerifier = KeystoreMacFactory.createVerifier(context)

    /**
     * The persisted lock state and failure counter.
     * Result: the [AppLockStore] over the shared settings file. Input: [stores]. Output: the store.
     */
    @Provides
    @Singleton
    fun provideAppLockStore(stores: CfoDataStores): AppLockStore = stores.appLock

    /**
     * The security event log (§21.6).
     * Why:    takes [AuditDatabase] — the **ungated** binding — because it must be able to record a
     *         refused unlock, and a refused unlock happens while the app is locked. This is the only
     *         injection point in the app allowed to do that, and it is why the qualifier exists.
     * Result: the [AuditLogRepository].
     * Input:  [database] — ungated; [clock] — TIM-001; [dispatchers]. Output: the repository.
     */
    @Provides
    @Singleton
    fun provideAuditLogRepository(
        @AuditDatabase database: CfoDatabase,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ): AuditLogRepository = RepositoryFactory.auditLog(database, clock, dispatchers)
}

package com.aicfo.data.repository

import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.database.CfoDatabase

/**
 * Assembles the repositories for the DI graph (issue 2.2; ARC-003, ARC-005).
 *
 * Why:  ARC-003 keeps repository implementations `internal`, so `:app`'s Hilt module cannot
 *       construct one directly — something inside this module has to. This is the same seam
 *       `CfoDatabaseFactory` and `CfoDataStoreFactory` already use, kept deliberately identical so
 *       there is one pattern in the codebase rather than three.
 * What: one factory function per repository, taking only what the implementation needs.
 * Result: `:app` binds interfaces without ever naming an implementation.
 * Changelog: 2026-07-26 — Created for issue 2.2, with the module's first repository.
 *
 * As `:data:repository` fills out (2.5 accounts, 3.x transactions), each new repository adds a
 * function here rather than becoming public.
 */
object RepositoryFactory {
    /**
     * Builds the security event log (§21.6).
     * Result: an [AuditLogRepository] over the encrypted database.
     * Input:  [database] — the open, encrypted database; [clock] — TIM-001; [dispatchers].
     * Output: [AuditLogRepository].
     */
    fun auditLog(
        database: CfoDatabase,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ): AuditLogRepository = RoomAuditLogRepository(database.auditLogDao(), clock, dispatchers)

    /**
     * Builds the quick-setup store (issue 2.3, FR-ONB-002).
     * Why:    takes the whole [database] rather than DAOs, because its atomicity guarantee rests on
     *         `withTransaction`, which is a method on the database.
     * Result: a [QuickSetupRepository] over the encrypted database.
     * Input:  [database] — the open, encrypted database; [clock] — TIM-001; [dispatchers].
     * Output: [QuickSetupRepository].
     */
    fun quickSetup(
        database: CfoDatabase,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ): QuickSetupRepository = RoomQuickSetupRepository(database, clock, dispatchers)
}

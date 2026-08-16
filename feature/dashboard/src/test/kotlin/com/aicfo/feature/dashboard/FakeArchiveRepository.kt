package com.aicfo.feature.dashboard

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.data.repository.ArchiveRepository
import com.aicfo.data.repository.ImportSummary

/**
 * An [ArchiveRepository] the dashboard tests drive directly (issue 5.4).
 *
 * Why:  the ViewModel's job here is not serialisation — `ArchiveRepositoryTest` proves the round
 *       trip against real SQLite. What this file's tests are about is the **order of operations**,
 *       and specifically that picking a file does not import it. That is a claim about which calls
 *       happen and when, which a recorder makes visible and a real repository hides.
 * What: records every call, and lets a test choose what each returns.
 * Result: "the confirmation is not decorative" becomes an assertion rather than a hope.
 * Changelog: 2026-08-16 — Created for issue 5.4.
 *
 * Hand-written rather than mocked, matching the fakes beside it: a mock would answer "was it
 * called?" in a language nobody reads, and this reads as the sentence the test is making.
 */
internal class FakeArchiveRepository : ArchiveRepository {
    /** How many times the destructive half actually ran. The number the confirmation guards. */
    var importCount: Int = 0
        private set

    /** The JSON the last import was handed, so a test can prove the right file was applied. */
    var importedJson: String? = null
        private set

    /** What [export] answers. */
    var exportResult: Result<String, AppError> = Ok(ARCHIVE_JSON)

    /** What [import] answers once it is allowed to run. */
    var importResult: Result<ImportSummary, AppError> = Ok(ImportSummary(rowsImported = 42, exportedAtUtcMillis = 1L))

    override suspend fun export(): Result<String, AppError> = exportResult

    override suspend fun import(json: String): Result<ImportSummary, AppError> {
        importCount++
        importedJson = json
        return importResult
    }

    /** Result: a failing export. Input: none. Output: none. */
    fun failExport() {
        exportResult = Err(AppError.Storage("disk"))
    }

    /** Result: a refused import. Input: [field] — the archive code. Output: none. */
    fun failImport(field: String) {
        importResult = Err(AppError.Validation(field))
    }

    companion object {
        /** Stands in for an archive; its contents are `ArchiveRepositoryTest`'s concern, not this file's. */
        const val ARCHIVE_JSON = """{"archiveVersion":1}"""
    }
}

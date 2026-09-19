package com.aicfo.data.repository

import android.database.Cursor
import com.aicfo.core.database.CfoDatabase

/**
 * What one profile holds in a database, table by table, read straight from SQLite (issue 8.3; DRL-001).
 *
 * Why:  the restore drill compares a database before a backup with one rebuilt from it. Comparing
 *       two **archives** would be circular — a table the archive forgets is missing from both sides
 *       and the drill would pass. So this reads the schema itself: every table SQLite has, found in
 *       `sqlite_master`, not a list someone has to remember to extend. A table added next year is in
 *       the drill the day its migration lands.
 * What: [tables] — every profile-scoped table's rows, each row rendered column by column and the
 *       rows sorted, so two databases compare equal exactly when they hold the same rows;
 *       [unscoped] — tables with no way to tell whose rows they are, which the drill must know about.
 * Result: a value two databases can be compared by.
 * Changelog: 2026-09-19 — Created for issue 8.3.
 *
 * Input:  [tables]; [unscoped]. Output: an immutable value.
 */
data class ProfileSnapshot(
    val tables: Map<String, List<String>>,
    val unscoped: Set<String>,
) {
    /** Tables that hold no row for the profile — the drill requires this to be empty. */
    val emptyTables: Set<String> get() = tables.filterValues { it.isEmpty() }.keys

    /** Rows per table, for a failure message a person can read. */
    val rowCounts: Map<String, Int> get() = tables.mapValues { it.value.size }

    companion object {
        /**
         * Tables that are deliberately not in a backup, and why.
         *
         * `audit_log` has no `profile_id` and is never exported (ADR-0023) — and a drill writes to it
         * on both sides (`BACKUP_CREATED`, `BACKUP_RESTORED`), so it could never be equal anyway.
         * The rest are SQLite's and Room's own bookkeeping.
         */
        val EXCLUDED = setOf("audit_log", "room_master_table", "android_metadata", "sqlite_sequence")

        /**
         * Reads [profileId]'s rows from every table in [database].
         * Why:    raw SQL over `openHelper` rather than the DAOs, so the read cannot share a blind spot
         *         with the archive, which goes through the DAOs.
         * Result: the snapshot. `profile` is scoped by `id`; every other table by `profile_id`;
         *         anything with neither, and not in [EXCLUDED], lands in [unscoped].
         * Input:  [database]; [profileId]. Output: [ProfileSnapshot].
         */
        fun of(
            database: CfoDatabase,
            profileId: String,
        ): ProfileSnapshot {
            val db = database.openHelper.readableDatabase
            val names =
                db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }.filterNot { it in EXCLUDED || it.startsWith("sqlite_") }

            val tables = sortedMapOf<String, List<String>>()
            val unscoped = sortedSetOf<String>()
            names.forEach { table ->
                val columns =
                    db.query("PRAGMA table_info(`$table`)").use { cursor ->
                        val name = cursor.getColumnIndexOrThrow("name")
                        buildList { while (cursor.moveToNext()) add(cursor.getString(name)) }
                    }
                val scope =
                    when {
                        "profile_id" in columns -> "profile_id"
                        table == "profile" -> "id"
                        else -> null
                    }
                if (scope == null) {
                    unscoped += table
                } else {
                    tables[table] =
                        db.query("SELECT * FROM `$table` WHERE `$scope` = ?", arrayOf(profileId)).use(::rows)
                }
            }
            return ProfileSnapshot(tables, unscoped)
        }

        /**
         * Renders every row as `column=value|…`, sorted.
         * Why:    sorted so row order — which SQLite does not promise — cannot make equal tables
         *         differ; typed so `1` the integer and `"1"` the text cannot compare equal.
         * Result: one string per row. Input: [cursor]. Output: `List<String>`.
         */
        private fun rows(cursor: Cursor): List<String> =
            buildList {
                while (cursor.moveToNext()) {
                    val cells = (0 until cursor.columnCount).map { "${cursor.getColumnName(it)}=${value(cursor, it)}" }
                    add(cells.joinToString("|"))
                }
            }.sorted()

        /** Result: a typed rendering of one cell. Input: [cursor]; [index]. Output: [String]. */
        private fun value(
            cursor: Cursor,
            index: Int,
        ): String =
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> "null"
                Cursor.FIELD_TYPE_INTEGER -> "i:${cursor.getLong(index)}"
                Cursor.FIELD_TYPE_FLOAT -> "f:${cursor.getString(index)}"
                Cursor.FIELD_TYPE_BLOB -> "b:${cursor.getBlob(index).joinToString("") { "%02x".format(it) }}"
                else -> "s:${cursor.getString(index)}"
            }
    }
}

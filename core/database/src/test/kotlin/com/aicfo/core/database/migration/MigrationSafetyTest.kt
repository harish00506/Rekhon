package com.aicfo.core.database.migration

import androidx.room.migration.bundle.DatabaseBundle
import androidx.room.migration.bundle.EntityBundle
import androidx.room.migration.bundle.FieldBundle
import androidx.room.migration.bundle.PrimaryKeyBundle
import androidx.room.migration.bundle.SchemaBundle
import com.aicfo.core.database.CfoDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The migration guard: DB-003 checked against the real exported schemas, on the JVM (§21.5).
 *
 * Why:  DB-003 forbids destructive migrations, and this app has no server copy — a dropped column
 *       is a user's financial history gone for good. Room's `MigrationTestHelper` proves data
 *       survives, but it needs a device, and this project has none. The structural half of the
 *       rule does not: the exported schema JSON is data, so "no table or column may disappear or
 *       change type" can be proved everywhere the unit tests run. That is the difference between
 *       a rule enforced today and one enforced whenever someone plugs in a phone.
 * What: fixtures exist and are contiguous, the newest matches the declared version, no consecutive
 *       pair contains a destructive change, and the schema-level money/time invariants hold.
 * Result: a version bump that would lose data fails the build.
 * Changelog: 2026-07-25 — Created for issue 1.7.
 *
 * **Adding a database version:** bump `CfoDatabase.VERSION`, write the `Migration`, build the
 * module so KSP exports `schemas/<version>.json`, **commit that file**, and add a round-trip case
 * to `MigrationRoundTripTest`. These tests fail if the fixture is missing, so the step cannot be
 * skipped silently.
 */
class MigrationSafetyTest {
    // --- the fixtures themselves ----------------------------------------------------------

    /**
     * Input:  the exported `schemas/` directory.
     * Output: asserts a fixture exists for the current version. Without this, every other check
     *         here would pass vacuously on an empty directory — the failure mode this project has
     *         already been bitten by once (the audit's no-op coverage gate).
     */
    @Test
    fun `an exported schema exists for the current version`() {
        val versions = SchemaFixtures.exportedVersions()
        assertTrue(
            "No exported schemas found in ${SchemaFixtures.schemaDirectory().absolutePath}. " +
                "Build :core:database so KSP writes them, and commit the result.",
            versions.isNotEmpty(),
        )
        assertTrue(
            "Missing schema fixture for the declared version ${CfoDatabase.VERSION}; found $versions",
            CfoDatabase.VERSION in versions,
        )
    }

    /**
     * Input:  the exported versions.
     * Output: asserts they run 1..VERSION with no gaps. A gap means a migration nothing can test.
     */
    @Test
    fun `exported schema versions are contiguous from one`() {
        val versions = SchemaFixtures.exportedVersions()
        assertEquals(
            "Schema fixtures must cover every version with no gaps",
            (1..CfoDatabase.VERSION).toList(),
            versions,
        )
    }

    /**
     * Input:  each fixture.
     * Output: asserts the version inside the JSON matches its file name — a mismatch means a
     *         hand-edited schema file, which the workflow forbids.
     */
    @Test
    fun `each fixture declares the version its filename claims`() {
        SchemaFixtures.exportedVersions().forEach { version ->
            assertEquals(
                "schemas/$version.json declares a different version",
                version,
                SchemaFixtures.load(version).database.version,
            )
        }
    }

    // --- DB-003 --------------------------------------------------------------------------------

    /**
     * Input:  every consecutive pair of exported schemas.
     * Output: asserts no pair contains a destructive change. At version 1 there is no pair, which
     *         is why the detector's own teeth are proved separately below rather than assumed.
     */
    @Test
    fun `no migration between exported versions is destructive`() {
        val versions = SchemaFixtures.exportedVersions()
        versions.zipWithNext().forEach { (older, newer) ->
            val changes = findDestructiveChanges(SchemaFixtures.load(older), SchemaFixtures.load(newer))
            assertTrue(
                "DB-003: migrating $older -> $newer would destroy data:\n" +
                    changes.joinToString("\n") { "  - $it" },
                changes.isEmpty(),
            )
        }
    }

    /**
     * Input:  the real v1 schema paired with synthetic successors that each break DB-003.
     * Output: asserts the detector reports every one. Without this the guard above would pass on
     *         a project with a single version while checking nothing — precisely the vacuous gate
     *         the governance audit found in the coverage rule, and the reason this project now
     *         proves a gate can fail before trusting it.
     */
    @Test
    fun `the detector catches every kind of destructive change`() {
        val v1 = SchemaFixtures.load(1)

        val tableDropped = v1.withoutTable("account")
        assertTrue(
            "dropping a table must be reported",
            findDestructiveChanges(v1, tableDropped).any { it.detail.contains("table was removed") },
        )

        val columnDropped = v1.withoutColumn("account", "current_balance_minor")
        assertTrue(
            "dropping a column must be reported",
            findDestructiveChanges(v1, columnDropped).any { it.detail.contains("was removed") },
        )

        val retyped = v1.withColumnAffinity("account", "current_balance_minor", "TEXT")
        assertTrue(
            "changing a column's type must be reported",
            findDestructiveChanges(v1, retyped).any { it.detail.contains("changed type") },
        )

        val tightened = v1.withColumnNotNull("transactions", "category_id")
        assertTrue(
            "making a nullable column NOT NULL must be reported",
            findDestructiveChanges(v1, tightened).any { it.detail.contains("NOT NULL") },
        )
    }

    /**
     * Input:  v1 paired with itself plus an added nullable column and an added table.
     * Output: asserts additive changes are **not** flagged. A guard that blocks safe migrations
     *         would be turned off within a week, which protects nothing.
     */
    @Test
    fun `additive changes are allowed`() {
        val v1 = SchemaFixtures.load(1)
        assertTrue("an unchanged schema is not destructive", findDestructiveChanges(v1, v1).isEmpty())

        val withNewColumn = v1.withAddedNullableColumn("account", "nickname", "TEXT")
        assertTrue(
            "adding a nullable column is safe",
            findDestructiveChanges(v1, withNewColumn).isEmpty(),
        )

        val withNewTable = v1.withAddedTable("budget")
        assertTrue("adding a table is safe", findDestructiveChanges(v1, withNewTable).isEmpty())
    }

    // --- schema-level money and time invariants ---------------------------------------------------

    /**
     * Input:  every column in the current schema.
     * Output: asserts the naming/type invariants hold in the **database**, not just in Kotlin:
     *         `*_minor` and `*_utc_millis` are INTEGER, `*_iso_date` is TEXT. The lint rules from
     *         issue 1.5 guard the Kotlin side; this guards the column that actually stores the
     *         value, which is where a `REAL` amount would do its damage (MNY-001, TIM-001/002).
     */
    @Test
    fun `money and time columns have the right storage types`() {
        SchemaFixtures.load(CfoDatabase.VERSION).database.entitiesByTableName().forEach { (table, entity) ->
            entity.fields.forEach { field ->
                val column = field.columnName
                when {
                    column.endsWith("_minor") ->
                        assertEquals(
                            "MNY-001: $table.$column must be INTEGER paise, never a floating-point amount",
                            "INTEGER",
                            field.affinity,
                        )

                    column.endsWith("_utc_millis") ->
                        assertEquals(
                            "TIM-001: $table.$column must be INTEGER epoch millis",
                            "INTEGER",
                            field.affinity,
                        )

                    column.endsWith("_iso_date") ->
                        assertEquals(
                            "TIM-002: $table.$column must be a TEXT ISO date, never a timestamp",
                            "TEXT",
                            field.affinity,
                        )
                }
            }
        }
    }

    /**
     * Input:  every table in the current schema.
     * Output: asserts the two invariants every table in this app carries — a soft-delete column,
     *         and profile scoping on everything except the profile table itself. Catching this in
     *         the schema means a new entity cannot quietly skip them.
     */
    @Test
    fun `every table supports soft delete and profile scoping`() {
        SchemaFixtures.load(CfoDatabase.VERSION).database.entitiesByTableName()
            .filterKeys { it !in INVARIANT_EXEMPT_TABLES }
            .forEach { (table, entity) ->
                val columns = entity.fieldsByColumnName().keys
                assertTrue(
                    "$table must have deleted_at_utc_millis for soft delete (DB-003 recoverability)",
                    "deleted_at_utc_millis" in columns,
                )
                if (table != "profile") {
                    assertTrue("$table must carry profile_id so no query can span profiles", "profile_id" in columns)
                }
            }
    }

    /**
     * Input:  the exemption list itself.
     * Output: asserts it is exactly the one table that has been argued for. The exemption above is
     *         a hole in an invariant that protects user data, so it must not be possible to widen
     *         it by adding a string — this test makes that a deliberate, reviewed edit.
     */
    @Test
    fun `only audit_log is exempt from the per-row invariants`() {
        assertEquals(setOf("audit_log"), INVARIANT_EXEMPT_TABLES.keys)
    }

    /**
     * Input:  the `audit_log` table's columns.
     * Output: asserts the table is exactly the four columns issue 2.2 defined, and no more.
     *
     * §21.6 bans PII and amounts from logs and sends security events here instead — which only
     * holds because there is nowhere in this table to put them. `event` and `method` store constant
     * names from closed enums (`AuditEventTest` pins those). A column added later called `detail`,
     * `note` or `user` would quietly turn a PII-free log into a PII store, and no other test would
     * notice. So the column set is pinned rather than the intent being described in a comment.
     */
    @Test
    fun `audit_log has no column that could hold PII or an amount`() {
        val columns =
            SchemaFixtures.load(CfoDatabase.VERSION)
                .database
                .entitiesByTableName()
                .getValue("audit_log")
                .fieldsByColumnName()
                .keys
        assertEquals(setOf("id", "event", "occurred_at_utc_millis", "method"), columns)
    }
}

/**
 * Tables deliberately outside the soft-delete and profile-scoping invariants, with the reason.
 *
 * Why:    those two rules exist to protect *user data* — a deleted transaction must be recoverable,
 *         and no query may leak across profiles. `audit_log` is neither: it holds security events
 *         about the app itself, written before any profile is even selected (the lock gates the app
 *         first), and an append-only log a caller can soft-delete is not evidence of anything.
 * Result: the invariant keeps biting for every table it was written for.
 * Changelog: 2026-07-26 — Created for issue 2.2, when `audit_log` became the first exemption.
 */
private val INVARIANT_EXEMPT_TABLES =
    mapOf(
        "audit_log" to
            "append-only security log (issue 2.2, §21.6): no profile exists at unlock time, and " +
            "a security log that can be soft-deleted proves nothing. Rows leave only with " +
            "erase-all (SEC-006).",
    )

// --- synthetic-schema builders, used only to prove the detector bites --------------------------

/** Result: a copy of this schema with [table] removed. Input: [table]. Output: [SchemaBundle]. */
private fun SchemaBundle.withoutTable(table: String): SchemaBundle =
    replacingEntities(database.entitiesByTableName().filterKeys { it != table }.values.toList())

/** Result: a copy with one column dropped. Input: [table], [column]. Output: [SchemaBundle]. */
private fun SchemaBundle.withoutColumn(
    table: String,
    column: String,
): SchemaBundle = mapEntity(table) { it.replacingFields(it.fields.filterNot { field -> field.columnName == column }) }

/** Result: a copy with one column's affinity changed. Input: [table], [column], [affinity]. Output: [SchemaBundle]. */
private fun SchemaBundle.withColumnAffinity(
    table: String,
    column: String,
    affinity: String,
): SchemaBundle =
    mapEntity(table) { entity ->
        entity.replacingFields(
            entity.fields.map { field ->
                if (field.columnName == column) field.copyWith(affinity = affinity) else field
            },
        )
    }

/** Result: a copy with one nullable column made NOT NULL. Input: [table], [column]. Output: [SchemaBundle]. */
private fun SchemaBundle.withColumnNotNull(
    table: String,
    column: String,
): SchemaBundle =
    mapEntity(table) { entity ->
        entity.replacingFields(
            entity.fields.map { field ->
                if (field.columnName == column) field.copyWith(nonNull = true) else field
            },
        )
    }

/** Result: a copy with an extra nullable column. Input: [table], [column], [affinity]. Output: [SchemaBundle]. */
private fun SchemaBundle.withAddedNullableColumn(
    table: String,
    column: String,
    affinity: String,
): SchemaBundle =
    mapEntity(table) { entity ->
        entity.replacingFields(entity.fields + FieldBundle(column, column, affinity, false, null))
    }

/** Result: a copy with an extra table. Input: [table]. Output: [SchemaBundle]. */
private fun SchemaBundle.withAddedTable(table: String): SchemaBundle {
    val id = FieldBundle("id", "id", "TEXT", true, null)
    val added =
        EntityBundle(
            table,
            "CREATE TABLE IF NOT EXISTS `$table` (`id` TEXT NOT NULL, PRIMARY KEY(`id`))",
            listOf(id),
            PrimaryKeyBundle(false, listOf("id")),
            emptyList(),
            emptyList(),
        )
    return replacingEntities(database.entitiesByTableName().values + added)
}

private fun SchemaBundle.mapEntity(
    table: String,
    transform: (EntityBundle) -> EntityBundle,
): SchemaBundle =
    replacingEntities(
        database.entitiesByTableName().map { (name, entity) ->
            if (name == table) transform(entity) else entity
        },
    )

private fun SchemaBundle.replacingEntities(entities: List<EntityBundle>): SchemaBundle =
    SchemaBundle(
        formatVersion,
        DatabaseBundle(
            database.version,
            database.identityHash,
            entities,
            database.views,
            // setupQueries is private on DatabaseBundle and irrelevant to the structural
            // comparison; these synthetic bundles exist only to feed findDestructiveChanges.
            emptyList(),
        ),
    )

private fun EntityBundle.replacingFields(fields: List<FieldBundle>): EntityBundle =
    EntityBundle(tableName, createSql, fields, primaryKey, indices, foreignKeys)

private fun FieldBundle.copyWith(
    affinity: String = this.affinity,
    nonNull: Boolean = isNonNull,
): FieldBundle = FieldBundle(fieldPath, columnName, affinity, nonNull, defaultValue)

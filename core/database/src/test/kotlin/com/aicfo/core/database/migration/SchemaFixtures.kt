package com.aicfo.core.database.migration

import androidx.room.migration.bundle.DatabaseBundle
import androidx.room.migration.bundle.EntityBundle
import androidx.room.migration.bundle.FieldBundle
import androidx.room.migration.bundle.SchemaBundle
import com.aicfo.core.database.CfoDatabase
import java.io.File

/**
 * Reads the exported Room schemas so migrations can be checked on the JVM (DB-003, §21.5).
 *
 * Why:  Room's own `MigrationTestHelper` needs a device, and this project has none — which would
 *       leave DB-003 ("destructive migrations are forbidden") enforced by nobody. But the exported
 *       schema JSON is just data, and `androidx.room:room-migration` parses it in plain Kotlin. So
 *       the *structural* half of the rule — no table or column may disappear or change type
 *       between versions — can be proved in a unit test that runs everywhere, today. The
 *       row-level half (data actually survives the migration) still needs a device; see
 *       `MigrationRoundTripTest`.
 * What: locates the `schemas/` directory the KSP `room.schemaLocation` argument writes to, and
 *       deserialises a version's bundle.
 * Result: test infrastructure for the guard in [MigrationSafetyTest].
 * Changelog: 2026-07-25 — Created for issue 1.7.
 *
 * Test-source-set only, so none of this ships in an APK.
 */
internal object SchemaFixtures {
    /** Matches `room.schemaLocation` in this module's build script. */
    private val SCHEMA_DIRECTORY =
        File("schemas/${CfoDatabase::class.java.canonicalName}")

    /**
     * Every schema version that has an exported fixture, ascending.
     * Why:    the guard walks consecutive pairs, and a missing file is itself a failure — a
     *         version bumped without committing its schema is how migration testing quietly dies.
     * Result: the sorted version numbers found on disk.
     * Input:  none. Output: `List<Int>`.
     */
    fun exportedVersions(): List<Int> =
        (SCHEMA_DIRECTORY.listFiles().orEmpty())
            .mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
            .sorted()

    /**
     * Loads one exported schema.
     * Result: the parsed [SchemaBundle].
     * Input:  [version] — the schema version. Output: [SchemaBundle].
     * Throws: [IllegalStateException] if the fixture is absent — a test-only failure, and exactly
     *         the signal we want when someone bumps the version without exporting.
     */
    fun load(version: Int): SchemaBundle {
        val file = File(SCHEMA_DIRECTORY, "$version.json")
        check(file.exists()) {
            "No exported schema for version $version at ${file.absolutePath}. " +
                "Bumping CfoDatabase.VERSION requires committing the generated schema JSON " +
                "(DB-003) — build the module and commit core/database/schemas/."
        }
        // SchemaBundle.deserialize, not a hand-rolled Json: the exported JSON stores entities
        // polymorphically with no class discriminator, so only Room's own configured serializer
        // reads it. Rolling our own silently failed on every fixture.
        return file.inputStream().use(SchemaBundle.Companion::deserialize)
    }

    /** The directory itself, so a test can report a useful message when nothing is exported. */
    fun schemaDirectory(): File = SCHEMA_DIRECTORY
}

/**
 * A structural difference between two schema versions that would lose data.
 * Why:    DB-003 forbids destructive migrations, and "destructive" needs a definition a machine
 *         can apply. These four are the ones that lose user data outright.
 * Result: a finding reported by [findDestructiveChanges], empty when the change is additive.
 * Input:  [table], [detail] — where and what. Output: a description for the failure message.
 * Changelog: 2026-07-25 — Created for issue 1.7.
 */
internal data class DestructiveChange(
    val table: String,
    val detail: String,
) {
    /** Result: a one-line description for a test failure message. */
    override fun toString(): String = "$table: $detail"
}

/**
 * Compares two schema versions and reports anything that would destroy data (DB-003).
 *
 * Why:    the rule is easy to state and easy to break by accident — renaming a column in an entity
 *         is a one-word edit that silently drops the old column and everything in it. This turns
 *         the rule into a check that runs on every build.
 * What:   flags a dropped table, a dropped column, a column whose SQL affinity changed, and a
 *         column that became `NOT NULL` (which fails on existing rows holding null). Additive
 *         changes — new tables, new nullable columns — are allowed and produce no findings.
 * Result: an empty list for a safe migration; one entry per destructive change otherwise.
 * Input:  [from] — the older schema; [to] — the newer one.
 * Output: `List<DestructiveChange>`.
 * Changelog: 2026-07-25 — Created for issue 1.7.
 *
 * Deliberately structural: it cannot see whether a migration *copies* data into a replacement
 * column, so a genuine rename-with-copy will be flagged. That is the right bias — the fix is to
 * document the migration and assert the data survives in `MigrationRoundTripTest`, not to weaken
 * the check.
 */
internal fun findDestructiveChanges(
    from: SchemaBundle,
    to: SchemaBundle,
): List<DestructiveChange> {
    val newTables = to.database.entitiesByTableName()
    return from.database.entitiesByTableName().flatMap { (tableName, oldEntity) ->
        val newEntity =
            newTables[tableName]
                ?: return@flatMap listOf(DestructiveChange(tableName, "table was removed"))
        val newFields = newEntity.fieldsByColumnName()
        oldEntity.fieldsByColumnName().mapNotNull { (columnName, oldField) ->
            val newField = newFields[columnName]
            when {
                newField == null ->
                    DestructiveChange(tableName, "column '$columnName' was removed")

                newField.affinity != oldField.affinity ->
                    DestructiveChange(
                        tableName,
                        "column '$columnName' changed type ${oldField.affinity} -> ${newField.affinity}",
                    )

                newField.isNonNull && !oldField.isNonNull ->
                    DestructiveChange(
                        tableName,
                        "column '$columnName' became NOT NULL; existing rows may hold null",
                    )

                else -> null
            }
        }
    }
}

/** Result: the schema's tables keyed by table name. Input: none. Output: `Map<String, EntityBundle>`. */
internal fun DatabaseBundle.entitiesByTableName(): Map<String, EntityBundle> =
    entities.filterIsInstance<EntityBundle>().associateBy { it.tableName }

/** Result: the table's columns keyed by column name. Input: none. Output: `Map<String, FieldBundle>`. */
internal fun EntityBundle.fieldsByColumnName(): Map<String, FieldBundle> = fields.associateBy { it.columnName }

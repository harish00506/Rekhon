package com.aicfo.data.repository

import com.aicfo.core.database.entity.TransactionEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the archive's on-disk field names (issue 5.4; §5.10, ADR-0023).
 *
 * Why:  the archive serialises the Room entities directly, which makes a dropped column impossible
 *       and a **property rename** load-bearing: kotlinx keys on Kotlin property names, so renaming
 *       `amountMinor` would silently make every archive already on a user's phone unreadable by the
 *       next build. That is a bigger deal than it sounds — these files are the user's only copy of
 *       their data, kept precisely for the day the app or the phone fails.
 *
 *       So the format is a contract, and this is where it is written down. A rename is not
 *       forbidden; it just has to be a deliberate act that turns the build red and bumps
 *       `CfoArchive.VERSION`, rather than an IDE refactor nobody notices.
 * What: the envelope's own keys, and the field names of the row a user is most likely to care about.
 * Result: renaming a serialised property fails here, naming the field that moved.
 * Changelog: 2026-08-16 — Created for issue 5.4.
 *
 * **Deliberately not every field of every table.** That would be 156 assertions restating the
 * entities, which nobody would maintain and which `ArchiveRepositoryTest`'s round trip already
 * covers for *loss*. This covers the money, the dates and the identity — the fields whose meaning a
 * future reader of an old file has to be able to rely on.
 */
class ArchiveFormatTest {
    private val json = Json { encodeDefaults = true }

    /**
     * Input:  an empty archive.
     * Output: asserts the envelope's four keys are exactly what a reader of an old file expects.
     */
    @Test
    fun `the envelope names its version, its schema and when it was taken`() {
        val encoded = json.encodeToString(CfoArchive(archiveVersion = 1, schemaVersion = 15, exportedAtUtcMillis = 7L))

        listOf("archiveVersion", "schemaVersion", "exportedAtUtcMillis").forEach { key ->
            assertTrue("the archive envelope no longer carries '$key'", encoded.contains("\"$key\""))
        }
    }

    /**
     * Input:  an empty archive.
     * Output: asserts every table's key is present, so a restore can tell "no rows" from "no such
     *         table" — which is why the encoder sets `encodeDefaults`.
     */
    @Test
    fun `every table appears even when it is empty`() {
        val encoded = json.encodeToString(CfoArchive(archiveVersion = 1, schemaVersion = 15, exportedAtUtcMillis = 0L))

        TABLES.forEach { table ->
            assertTrue("the archive no longer carries a '$table' list", encoded.contains("\"$table\""))
        }
    }

    /**
     * Input:  one transaction.
     * Output: asserts the field names a user's own file depends on.
     *
     * Why:    `amountMinor` in particular. MNY-001 says money is `Long` paise end to end, and the
     *         archive is where that leaves the app — a reader six months from now has to know that
     *         `-120000` is ₹1,200 and not ₹120,000. Renaming this field, or ever writing rupees into
     *         it, breaks every archive in existence.
     */
    @Test
    fun `a transaction keeps the field names its archive is read by`() {
        val encoded = json.encodeToString(TransactionEntity.serializer(), transaction())

        listOf(
            "id", "profileId", "accountId", "amountMinor", "currencyCode",
            "occurredAtUtcMillis", "bookedOnIsoDate", "type", "deletedAtUtcMillis",
        ).forEach { field ->
            assertTrue("a transaction no longer serialises '$field'", encoded.contains("\"$field\""))
        }
    }

    /**
     * Input:  an amount in paise.
     * Output: asserts it is written as the integer it is stored as — not rescaled, not a decimal.
     *
     * Why:    MNY-001's whole point, at the one boundary where the app hands money to something
     *         else. A `Double` here would reintroduce the drift `Money` exists to prevent, in a file
     *         that outlives the install.
     */
    @Test
    fun `money is written as whole paise`() {
        val encoded = json.encodeToString(TransactionEntity.serializer(), transaction())

        assertTrue(
            "money must serialise as integer paise (MNY-001), was: $encoded",
            encoded.contains("\"amountMinor\":-120000"),
        )
    }

    /**
     * Input:  an archive written by this build.
     * Output: asserts it decodes back to itself — the format is symmetric, which is the property
     *         every other assertion here assumes.
     */
    @Test
    fun `an archive decodes back to what was encoded`() {
        val original =
            CfoArchive(
                archiveVersion = 1,
                schemaVersion = 15,
                exportedAtUtcMillis = 99L,
                transactions = listOf(transaction()),
            )

        assertEquals(original, json.decodeFromString<CfoArchive>(json.encodeToString(original)))
    }

    private fun transaction() =
        TransactionEntity(
            id = "txn:1",
            profileId = "local",
            accountId = "account:1",
            amountMinor = -120_000L,
            currencyCode = "INR",
            occurredAtUtcMillis = 1_786_000_000_000L,
            bookedOnIsoDate = "2026-08-15",
            categoryId = "category:groceries",
            merchant = "Big Bazaar",
            note = null,
            source = "manual",
            type = "expense",
            createdAtUtcMillis = 1_786_000_000_000L,
            updatedAtUtcMillis = 1_786_000_000_000L,
        )

    private companion object {
        /** Every list the envelope carries. A table dropped from here is data dropped from backups. */
        val TABLES =
            listOf(
                "profiles", "accounts", "categories", "transactions", "transactionSplits",
                "tags", "transactionTags", "budgets", "budgetAlerts", "budgetReviews",
                "recurringRules", "netWorthSnapshots", "attachments", "smsDrafts",
            )
    }
}

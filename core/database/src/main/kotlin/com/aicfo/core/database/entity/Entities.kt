package com.aicfo.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * The base schema: profile, account, transaction, category (SRS §20; MNY-001, TIM-001/002, DB-003).
 *
 * Why:  these four are what issue 1.6's acceptance criteria name, and they are the tables whose
 *       shape is already settled by the SRS. The design spec lists nine more (budget, goal,
 *       investment_lot, insight, consent, audit_log …), but each is defined by its own feature
 *       issue — and under DB-003 a wrong column guessed today costs a migration tomorrow, while a
 *       table added later is a trivial non-destructive one. So the expensive mistake is building
 *       ahead, not building narrow.
 * What: Room entities carrying the three invariants that apply to every table in this app —
 *       money as `Long` paise, timestamps as UTC epoch millis with date-only fields as ISO
 *       strings, and soft delete plus per-profile scoping on every row.
 * Result: an encrypted schema later features extend rather than rewrite.
 * Changelog: 2026-07-25 — Created for issue 1.6 (profile, account, transaction, category).
 *
 * **Invariants, enforced by review and by the `:lint` detectors from issue 1.5:**
 * - **MNY-001** — every amount column is `Long` minor units (paise). A `Double` here fails lint.
 * - **TIM-001/002** — instants are `…UtcMillis: Long`; a date the user picked is an ISO
 *   `LocalDate` string, never a midnight timestamp, because midnight depends on a time zone that
 *   can change under a stored value.
 * - **Soft delete** — `deletedAtUtcMillis` is set instead of deleting the row, so an accidental
 *   delete is recoverable and a sync can still see the tombstone. Every query filters it.
 * - **Per-profile scoping** — every row carries `profileId`; no query may span profiles.
 */

/**
 * A person using the app. One profile per household member.
 * Why:    every other row is scoped to a profile, and the profile owns the time zone all calendar
 *         logic resolves in (TIM-001) — which is why `Clock` reads its zone from settings rather
 *         than the device.
 * Result: the root record everything else hangs off.
 * Input:  see the constructor. Output: a Room row in `profile`.
 * Changelog: 2026-07-25 — Created for issue 1.6.
 */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    /** IANA zone id, e.g. `Asia/Kolkata`. The profile time zone TIM-001 refers to. */
    @ColumnInfo(name = "time_zone_id")
    val timeZoneId: String,
    /** ISO-4217, e.g. `INR`. A `Money` is single-currency; this says which. */
    @ColumnInfo(name = "currency_code")
    val currencyCode: String,
    @ColumnInfo(name = "created_at_utc_millis")
    val createdAtUtcMillis: Long,
    @ColumnInfo(name = "updated_at_utc_millis")
    val updatedAtUtcMillis: Long,
    @ColumnInfo(name = "deleted_at_utc_millis")
    val deletedAtUtcMillis: Long? = null,
)

/**
 * A place money sits: bank, cash, wallet, card, loan or investment.
 * Why:    balances are the input to net worth and Safe-to-Spend, so the amount columns are the
 *         first place MNY-001 has to hold.
 * Result: a Room row in `account`, scoped to one profile.
 * Input:  see the constructor. Output: a Room row.
 * Changelog: 2026-07-25 — Created for issue 1.6.
 */
@Entity(
    tableName = "account",
    indices = [Index("profile_id"), Index("profile_id", "deleted_at_utc_millis")],
)
data class AccountEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    @ColumnInfo(name = "name")
    val name: String,
    /** `bank` | `cash` | `wallet` | `card` | `loan` | `investment`. A string, so a new type is not a migration. */
    @ColumnInfo(name = "type")
    val type: String,
    /** MNY-001: paise, never a floating-point rupee value. */
    @ColumnInfo(name = "opening_balance_minor")
    val openingBalanceMinor: Long,
    @ColumnInfo(name = "current_balance_minor")
    val currentBalanceMinor: Long,
    @ColumnInfo(name = "currency_code")
    val currencyCode: String,
    @ColumnInfo(name = "created_at_utc_millis")
    val createdAtUtcMillis: Long,
    @ColumnInfo(name = "updated_at_utc_millis")
    val updatedAtUtcMillis: Long,
    @ColumnInfo(name = "deleted_at_utc_millis")
    val deletedAtUtcMillis: Long? = null,
)

/**
 * A single movement of money.
 * Why:    the highest-volume table and the one every engine reads. It carries **both** an instant
 *         and a date on purpose: `occurred_at_utc_millis` orders events exactly, while
 *         `booked_on_iso_date` is the day the user considers it to have happened in their own zone
 *         (TIM-002). Deriving the second from the first at read time would re-open the 23:30 IST
 *         bug `Clock` exists to close.
 * Result: a Room row in `transactions`.
 * Input:  see the constructor. Output: a Room row.
 * Changelog: 2026-07-25 — Created for issue 1.6.
 *
 * Table named `transactions`: `transaction` is a reserved SQL keyword.
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index("profile_id", "booked_on_iso_date"),
        Index("account_id"),
        Index("category_id"),
    ],
)
data class TransactionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    @ColumnInfo(name = "account_id")
    val accountId: String,
    /** MNY-001: paise. Negative is an outflow, positive an inflow. */
    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,
    @ColumnInfo(name = "currency_code")
    val currencyCode: String,
    /** TIM-001: the exact instant, UTC epoch millis. */
    @ColumnInfo(name = "occurred_at_utc_millis")
    val occurredAtUtcMillis: Long,
    /** TIM-002: the profile-zone day this belongs to, ISO `yyyy-MM-dd`. Never a midnight timestamp. */
    @ColumnInfo(name = "booked_on_iso_date")
    val bookedOnIsoDate: String,
    @ColumnInfo(name = "category_id")
    val categoryId: String? = null,
    @ColumnInfo(name = "merchant")
    val merchant: String? = null,
    @ColumnInfo(name = "note")
    val note: String? = null,
    /** `manual` | `ocr` | `sms` | `import`. Provenance, so the UI can show where a row came from (P-02). */
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "created_at_utc_millis")
    val createdAtUtcMillis: Long,
    @ColumnInfo(name = "updated_at_utc_millis")
    val updatedAtUtcMillis: Long,
    @ColumnInfo(name = "deleted_at_utc_millis")
    val deletedAtUtcMillis: Long? = null,
)

/**
 * A spending category, optionally nested one level under a parent.
 * Why:    categorisation drives budgets and every insight, and `nature` is what separates a need
 *         from a want from an investment — the distinction the advice layer is built on.
 * Result: a Room row in `category`.
 * Input:  see the constructor. Output: a Room row.
 * Changelog: 2026-07-25 — Created for issue 1.6.
 */
@Entity(
    tableName = "category",
    indices = [Index("profile_id"), Index("parent_id")],
)
data class CategoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "parent_id")
    val parentId: String? = null,
    /** `need` | `want` | `invest` | `asset` | `liability`. Set by issue 4.3's classifier. */
    @ColumnInfo(name = "nature")
    val nature: String,
    /** True for the seeded defaults, so a user-created category can be told apart from ours. */
    @ColumnInfo(name = "is_system")
    val isSystem: Boolean,
    @ColumnInfo(name = "created_at_utc_millis")
    val createdAtUtcMillis: Long,
    @ColumnInfo(name = "updated_at_utc_millis")
    val updatedAtUtcMillis: Long,
    @ColumnInfo(name = "deleted_at_utc_millis")
    val deletedAtUtcMillis: Long? = null,
)

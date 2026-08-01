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
 * A place money sits — one of the eleven types FR-ACC-001 names.
 * Why:    balances are the input to net worth and Safe-to-Spend, so the amount columns are the
 *         first place MNY-001 has to hold.
 * Result: a Room row in `account`, scoped to one profile.
 * Input:  see the constructor. Output: a Room row.
 * Changelog: 2026-07-25 — Created for issue 1.6.
 *            2026-07-28 — Issue 2.5: `institution` and `archived_at_utc_millis` added at schema
 *            version 4; the type list moved to `AccountType`, which is now its only definition.
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
    /**
     * An `AccountType.storedValue` (`:core:model`) — the §20.2 vocabulary, e.g. `credit_card`.
     *
     * A plain string with no CHECK constraint, so **a new type is not a migration**; the enum is
     * what keeps a typo from becoming a row every query silently misses. Issue 1.6's doc comment
     * listed six types including `wallet`, which is in no SRS list; issue 2.5 replaced that guess
     * with the enum and dropped `wallet`.
     */
    @ColumnInfo(name = "type")
    val type: String,
    /** MNY-001: paise, never a floating-point rupee value. */
    @ColumnInfo(name = "opening_balance_minor")
    val openingBalanceMinor: Long,
    /**
     * MNY-001: paise. **A cache, not the truth (DB-001).**
     *
     * The current balance "is derivable from opening balance + transactions" and is "never mutated
     * ad hoc", so every read derives it — see `AccountDao.observeWithBalances`. This column stays
     * for the nightly integrity job DB-001 describes, which compares the two and raises an
     * adjustment prompt when they disagree (FR-ACC-006, issue 2.7). Recorded in ADR-0007.
     */
    @ColumnInfo(name = "current_balance_minor")
    val currentBalanceMinor: Long,
    @ColumnInfo(name = "currency_code")
    val currencyCode: String,
    /** The bank, issuer or custodian. Free text and optional — cash has none (issue 2.5). */
    @ColumnInfo(name = "institution")
    val institution: String? = null,
    /**
     * FR-ACC-007: when the user retired this account, or null while it is active.
     *
     * **Distinct from [deletedAtUtcMillis] on purpose.** A deleted account is a mistake being
     * undone; an archived one is a real account the user closed, and FR-ACC-007 requires its
     * history to survive while it drops out of active totals. Conflating them would lose a closed
     * card's transactions from every past month.
     */
    @ColumnInfo(name = "archived_at_utc_millis")
    val archivedAtUtcMillis: Long? = null,
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

/**
 * A planned amount for one kind of spending in one month (issue 2.3; FR-BUD-001, FR-ONB-002).
 *
 * Why:    FR-BUD-001 allows a budget "per category or category-group", and at first run there are
 *         no categories at all — issue 4.1 builds the editor. So the row supports both: a budget
 *         either names a [categoryId] or, when it does not, carries a [nature] and is an envelope
 *         for a whole group. Quick setup writes the second kind; issue 4.4's editor will write the
 *         first, into these same columns rather than a new table.
 * Result: a Room row in `budget`, added at schema version 3 by issue 2.3.
 * Input:  see the constructor. Output: a Room row.
 * Changelog: 2026-07-27 — Created for issue 2.3 (FR-ONB-002, FR-BUD-001).
 *
 * **Both foreign keys are deliberately nullable**, and both are filled in by a later issue: 4.1
 * attaches categories, and neither exists at onboarding. Under DB-003 a nullable column added now
 * is free, while a NOT NULL one guessed now would need a migration to relax.
 *
 * **[source], [ruleId] and [ruleVersion] are the P-02 trail.** A budget the app proposed must be
 * able to say which rule proposed it and at what version, or the user's drill-down shows a number
 * with no derivation (AI-ARC-006). A budget the user typed carries `source = manual` and no rule.
 */
@Entity(
    tableName = "budget",
    indices = [
        Index("profile_id", "period_start_iso_date"),
        Index("category_id"),
    ],
)
data class BudgetEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    /** Set when this budget is for one category (issue 4.4); null for a nature-level envelope. */
    @ColumnInfo(name = "category_id")
    val categoryId: String? = null,
    /** `need` | `want` | `invest`. Set when [categoryId] is null — the group this envelope covers. */
    @ColumnInfo(name = "nature")
    val nature: String? = null,
    /** TIM-002: the first day of the budget month, ISO `yyyy-MM-dd`. Never a midnight timestamp. */
    @ColumnInfo(name = "period_start_iso_date")
    val periodStartIsoDate: String,
    /** MNY-001: paise. */
    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,
    /** FR-BUD-001's optional rollover of an unused amount. Off by default. */
    @ColumnInfo(name = "rollover_enabled")
    val rolloverEnabled: Boolean = false,
    /** `quick_setup` | `manual` | `suggested`. Where the figure came from (P-02). */
    @ColumnInfo(name = "source")
    val source: String,
    /** The rulebook row that produced this amount, or null when the user typed it (AI-ARC-006). */
    @ColumnInfo(name = "rule_id")
    val ruleId: String? = null,
    @ColumnInfo(name = "rule_version")
    val ruleVersion: String? = null,
    @ColumnInfo(name = "created_at_utc_millis")
    val createdAtUtcMillis: Long,
    @ColumnInfo(name = "updated_at_utc_millis")
    val updatedAtUtcMillis: Long,
    @ColumnInfo(name = "deleted_at_utc_millis")
    val deletedAtUtcMillis: Long? = null,
)

/**
 * A money movement the user expects to repeat (issue 2.3; FR-ONB-002, FR-TXN-006).
 *
 * Why:    quick setup captures a salary and a rent that recur every month, and FR-TXN-006 describes
 *         the same shape arriving the other way — proposed by the detector in issue 3.7 and
 *         confirmed by the user. Both are the same row, which is why [isConfirmed] exists rather
 *         than the two living in separate tables: a rule the user has not confirmed must not start
 *         creating transactions.
 * Result: a Room row in `recurring_rule`, added at schema version 3 by issue 2.3.
 * Input:  see the constructor. Output: a Room row.
 * Changelog: 2026-07-27 — Created for issue 2.3 (FR-ONB-002, FR-TXN-006).
 *
 * **[amountMinor] is signed exactly as `transactions` is** — positive is an inflow, negative an
 * outflow — so a rule that fires produces a transaction without anything re-deciding the sign.
 *
 * **[seedKind] rather than a stored English label.** §21.6 puts every user-visible string in
 * `strings.xml`; a row holding "Rent or EMI" could never be translated. Quick setup writes a code
 * the UI resolves to a string resource, and [name] stays null and reserved for issue 3.7's rules,
 * which are named after a real merchant and so *are* data rather than copy.
 */
@Entity(
    tableName = "recurring_rule",
    indices = [
        Index("profile_id", "next_due_iso_date"),
        Index("account_id"),
    ],
)
data class RecurringRuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    /** Which account it moves through. Null until issue 2.5 gives the user an account to attach. */
    @ColumnInfo(name = "account_id")
    val accountId: String? = null,
    /** Attached by issue 4.1/4.3 once categories exist. */
    @ColumnInfo(name = "category_id")
    val categoryId: String? = null,
    /** A user- or merchant-supplied name (issue 3.7). Null for quick-setup rows — see [seedKind]. */
    @ColumnInfo(name = "name")
    val name: String? = null,
    /** `income` | `rent_emi` | `savings` for a quick-setup row; null otherwise. A code, not copy. */
    @ColumnInfo(name = "seed_kind")
    val seedKind: String? = null,
    /** MNY-001: paise, signed. Negative is an outflow, positive an inflow — as in `transactions`. */
    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,
    /** `monthly`. A string, so a new cadence is not a migration. */
    @ColumnInfo(name = "cadence")
    val cadence: String,
    /** TIM-002: the next occurrence, ISO `yyyy-MM-dd`. */
    @ColumnInfo(name = "next_due_iso_date")
    val nextDueIsoDate: String,
    /** `quick_setup` | `detected` | `manual`. Provenance, so the UI can show where it came from. */
    @ColumnInfo(name = "source")
    val source: String,
    /** FR-TXN-006: false until the user confirms. An unconfirmed rule creates nothing. */
    @ColumnInfo(name = "is_confirmed")
    val isConfirmed: Boolean = false,
    @ColumnInfo(name = "created_at_utc_millis")
    val createdAtUtcMillis: Long,
    @ColumnInfo(name = "updated_at_utc_millis")
    val updatedAtUtcMillis: Long,
    @ColumnInfo(name = "deleted_at_utc_millis")
    val deletedAtUtcMillis: Long? = null,
)

/**
 * One security event. Never anything about the person it happened to.
 *
 * Why:    §21.6 bans PII and amounts from logs and routes security events here instead. That only
 *         works if the table has nowhere to put PII, which is why there is no free-text column:
 *         [event] and [method] hold `AuditEvent`/`AuditMethod` constant names, both closed sets.
 *         A reviewer can confirm the rule by reading the four columns rather than by auditing every
 *         call site.
 * Result: a Room row in `audit_log`, added at schema version 2 by issue 2.2.
 * Input:  see the constructor. Output: a Room row.
 * Changelog: 2026-07-26 — Created for issue 2.2 (SEC-002, §21.6).
 *
 * **Three deliberate absences.**
 * - **No `profile_id`.** Unlike every other table this is not per-profile: the app lock gates the
 *   whole app before any profile is selected, so at unlock time there is no profile to scope to.
 * - **No soft delete.** A security log a caller can quietly retire is not a security log. Rows go
 *   only when the whole database does (erase-all, SEC-006).
 * - **No `updated_at`.** An event happened at an instant; it is never edited.
 */
@Entity(
    tableName = "audit_log",
    indices = [Index("occurred_at_utc_millis")],
)
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    /** An `AuditEvent` constant name. A code from a closed set, never a sentence. */
    @ColumnInfo(name = "event")
    val event: String,
    /** TIM-001: the exact instant, UTC epoch millis, from the injected `Clock`. */
    @ColumnInfo(name = "occurred_at_utc_millis")
    val occurredAtUtcMillis: Long,
    /** An `AuditMethod` constant name, or null where the event had no factor. */
    @ColumnInfo(name = "method")
    val method: String? = null,
)

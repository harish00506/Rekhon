package com.aicfo.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

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
 *
 * **Why every profile-scoped entity carries `@Serializable` (issue 5.4).** §5.10's export is a
 * lossless dump of these tables, so the archive's shape *is* the schema — and the obvious
 * alternative, a parallel set of hand-written DTOs with mappers both ways, is less safe rather than
 * more. Fifteen tables and 156 columns of mapper have exactly one failure mode: somebody adds a
 * column, forgets the DTO, and every future export silently drops that data with no test able to
 * notice. Serialising the entities makes that unrepresentable — a new column is in the archive the
 * moment it is in the table.
 *
 * The cost is that a **Kotlin property rename changes the archive format**, because kotlinx
 * serialises by property name. `ArchiveFormatTest` pins every field name for exactly that reason:
 * the format is a contract with files already on users' phones, and it should take a red build to
 * change it. `AuditLogEntity` is deliberately *not* serialisable — see [AuditLogEntity].
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
@Serializable
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
@Serializable
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
     * Whether this account counts towards net worth (issue 2.6; §20.2, FR-ACC-005).
     *
     * **Distinct from [archivedAtUtcMillis], and both from [deletedAtUtcMillis].** Archiving retires
     * an account the user has closed; this excludes one that is still open and still transacting but
     * is not theirs to count — a company card, or an account held for someone else. An account can
     * be active, used daily, and legitimately outside the total.
     *
     * Defaults to counting: the migration fills every existing row with `1`, and an account the user
     * has said nothing about is one they expect to see in their net worth.
     */
    @ColumnInfo(name = "include_in_networth", defaultValue = "1")
    val includeInNetWorth: Boolean = true,
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
 *            2026-08-02 — Issue 3.2: `type` and `transfer_id`, so a transfer is one logical record
 *            across two rows (FR-TXN-003) and so transfers can be excluded from spend totals.
 *            2026-08-03 — Issue 3.4: `posted_at_utc_millis`, the record that a future-dated row's
 *            date has arrived (FR-TXN-010).
 *
 * Table named `transactions`: `transaction` is a reserved SQL keyword.
 */
@Serializable
@Entity(
    tableName = "transactions",
    indices = [
        Index("profile_id", "booked_on_iso_date"),
        Index("account_id"),
        Index("category_id"),
        // Issue 3.2: every read of a transfer starts from this column — collapsing the pair for the
        // list, and finding the sibling leg to delete alongside it.
        Index("transfer_id"),
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
    /**
     * `manual` | `ocr` | `sms` | `import` | `reconciliation`. Provenance, so the UI can show where a
     * row came from (P-02).
     *
     * `reconciliation` (issue 2.7, FR-ACC-006) marks an adjustment the app posted to close the gap
     * between the derived balance and a statement the user typed. **On that row the source is the
     * rule that fired** — nothing else about it says why it exists, and `note` is deliberately left
     * null rather than holding an un-localised sentence repeating the amount in the column beside it.
     */
    @ColumnInfo(name = "source")
    val source: String,
    /**
     * `expense` | `income` | `transfer_out` | `transfer_in` | `adjustment` (issue 3.2; §20.2).
     *
     * **Direction is stored twice — here and as [amountMinor]'s sign — and they must never
     * disagree.** The guard is that nothing outside `:data:repository` ever supplies this: it is
     * derived at one mapping site from the amount and the write path, and `TransactionType.matches`
     * states the rule once. SQLite cannot carry §20.2's `CHECK` constraint here because `ALTER TABLE
     * … ADD COLUMN` cannot add one, so the invariant lives in a test instead of in the schema.
     *
     * **`defaultValue` is not decoration.** The 5 → 6 migration must add a `NOT NULL` column, which
     * SQLite only permits with a `DEFAULT`; if this annotation omitted it, Room's schema validation
     * would fail against the committed `6.json` at open time on every upgraded install. The
     * migration then rewrites the placeholder into the right value per row.
     */
    @ColumnInfo(name = "type", defaultValue = "'expense'")
    val type: String,
    /**
     * Links the two legs of a transfer (issue 3.2; FR-TXN-003). Null on every other row.
     *
     * Two rows share one id: the outflow from the source account and the inflow to the destination.
     * That is what makes a transfer "a single logical record affecting two accounts atomically" —
     * the pair is found, rendered and deleted by this column, never by matching amounts and dates.
     */
    @ColumnInfo(name = "transfer_id")
    val transferId: String? = null,
    /**
     * When this row's booked day arrived and the app recorded it as posted (issue 3.4; FR-TXN-010).
     * Null while the row is still future-dated.
     *
     * **This column does not gate a single balance, and that is deliberate.** Every amount query —
     * `AccountDao.observeWithBalances`, the net-worth as-of read — bounds on
     * `booked_on_iso_date <= :asOfIsoDate` and continues to, so a scheduled row is out of the
     * figures because of its *date*. If posting were gated on this column instead, a
     * `ScheduledTransactionWorker` deferred by Doze, a powered-off device or a locked session
     * (SEC-002) would leave real money missing from the user's balance until the job next ran. The
     * date cannot be deferred; a job can.
     *
     * What it is for: the worker's **idempotence key** — the `WHERE … IS NULL` clause is what makes
     * a second run in the same day affect zero rows — and a durable record that the rollover
     * happened, which issue 3.7's recurring-auto series and any later "posted today" surface read.
     *
     * Nullable, so `ALTER TABLE … ADD COLUMN` needs no `DEFAULT` and the entity needs no
     * `@ColumnInfo(defaultValue = …)` — the trap [type] documents applies only to `NOT NULL`. The
     * 7 → 8 migration still has to **backfill** it, or every row written before issue 3.4 reads as
     * an unposted one.
     */
    @ColumnInfo(name = "posted_at_utc_millis")
    val postedAtUtcMillis: Long? = null,
    /**
     * The user's nature override, or `null` (issue 4.3; §8.3, `CategoryNature.storedValue`).
     *
     * **`null` does not mean "unknown".** §8.3 makes nature "auto-assigned, user-correctable,
     * learned", and only the middle word needs a column: the automatic nature is derived on read by
     * `:domain:engines:nature` from the account, the category and the user's past corrections. So
     * `null` means "whatever §8.3.1's decision order currently says", and a value here means "the
     * user disagreed, and this is what they said instead".
     *
     * Storing the *resolved* nature would put a derived value on disk, where a rulebook edit leaves
     * it stale and needs a recompute job — the shape that already bit the net-worth series in issue
     * 3.10. It would also make this column's meaning ambiguous: there would be no way to tell a
     * value the engine wrote from one the user chose, and the learned tier (§8.3.1 step 4) reads
     * exactly that distinction.
     */
    @ColumnInfo(name = "nature")
    val nature: String? = null,
    @ColumnInfo(name = "created_at_utc_millis")
    val createdAtUtcMillis: Long,
    @ColumnInfo(name = "updated_at_utc_millis")
    val updatedAtUtcMillis: Long,
    @ColumnInfo(name = "deleted_at_utc_millis")
    val deletedAtUtcMillis: Long? = null,
)

/**
 * One line of a split transaction (issue 3.3; FR-TXN-004, §20.1).
 *
 * Why:    a ₹1,000 supermarket trip is groceries *and* household *and* a bottle of wine, and
 *         FR-TXN-004 requires "N lines with independent categories" whose amounts "sum exactly to
 *         the parent amount". One `category_id` on the parent cannot express that, so the lines get
 *         their own rows.
 *
 *         **A line does not move money.** The parent transaction holds the amount and is what every
 *         balance sums (DB-001, ADR-0007); these rows only say how that one amount is *attributed*.
 *         That is the whole reason this is a child table rather than more rows in `transactions`,
 *         where the parent and its lines would both land in the balance and double-count it —
 *         see `docs/adr/0009-splits-as-a-child-table.md`.
 * Result: a Room row in `transaction_splits`.
 * Input:  see the constructor. Output: a Room row.
 * Changelog: 2026-08-02 — Created for issue 3.3 (FR-TXN-004).
 *
 * **`profile_id` is denormalised onto the line**, duplicating the parent's. It is not redundant
 * bookkeeping: `DemoDao`'s wipe and its residue count are both profile-scoped single-table queries
 * (ADR-0006), and a table they cannot reach by `profile_id` alone would leave residue behind when a
 * demo is exited. `MigrationSafetyTest` enforces the same rule on every table but `audit_log`.
 */
@Serializable
@Entity(
    tableName = "transaction_splits",
    indices = [
        // Every read starts from the parent: "what are this transaction's lines?".
        Index("transaction_id"),
        // The demo wipe and the residue count both scope by profile.
        Index("profile_id"),
        Index("category_id"),
    ],
)
data class TransactionSplitEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    @ColumnInfo(name = "transaction_id")
    val transactionId: String,
    /**
     * MNY-001: paise, signed **the same way as the parent**.
     *
     * A line of an expense is negative like its parent, so `SUM(lines) = parent.amount_minor` is a
     * plain comparison of two signed figures rather than a rule about magnitudes — which is exactly
     * what FR-TXN-004's "sum exactly to the parent amount" asks to be checkable.
     */
    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,
    /**
     * The category this line is attributed to. Null until issue 4.1 gives a real profile any
     * categories at all — the same state `transactions.category_id` is in.
     */
    @ColumnInfo(name = "category_id")
    val categoryId: String? = null,
    @ColumnInfo(name = "note")
    val note: String? = null,
    @ColumnInfo(name = "created_at_utc_millis")
    val createdAtUtcMillis: Long,
    @ColumnInfo(name = "updated_at_utc_millis")
    val updatedAtUtcMillis: Long,
    @ColumnInfo(name = "deleted_at_utc_millis")
    val deletedAtUtcMillis: Long? = null,
)

/**
 * A free-text label the user can attach to any number of transactions (issue 3.6; §20.1).
 *
 * Why:    FR-TXN-007 requires the list to be searchable by tag and FR-TXN-008 requires bulk retag,
 *         and neither is expressible with a column on `transactions` — a transaction has "tags
 *         (many)" (SRS §5.3) and a tag belongs to many transactions. §20.1 names the two tables this
 *         and [TransactionTagEntity] implement.
 *
 *         **A tag is not a category, and the difference is load-bearing.** A category answers "what
 *         kind of spending was this?", is exactly one per transaction, and drives budgets and the
 *         need/want/invest nature (issue 4.3). A tag answers "what was this *for*?" — `goa-trip`,
 *         `reimbursable`, `business` — is unlimited per transaction, and drives nothing but search.
 *         Modelling them as one thing would put `goa-trip` in a budget envelope.
 * Result: a Room row in `tags`.
 * Input:  see the constructor. Output: a Room row.
 * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-007, FR-TXN-008).
 *
 * **The unique index is on `(profile_id, name)`, not on `name`.** Two profiles — a real one and the
 * demo — may each have a `travel` tag, and they are different rows; a global unique index would make
 * entering the demo fail on the second profile's first collision.
 */
@Serializable
@Entity(
    tableName = "tags",
    indices = [Index(value = ["profile_id", "name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    /**
     * The label as the user typed it, trimmed.
     *
     * **Case is preserved but uniqueness is not case-sensitive** — the repository lower-cases before
     * looking a tag up, so `Travel` and `travel` resolve to one row rather than becoming two chips
     * the user cannot tell apart. Storing what they typed is what keeps the chip reading `Travel`.
     */
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "created_at_utc_millis")
    val createdAtUtcMillis: Long,
    @ColumnInfo(name = "updated_at_utc_millis")
    val updatedAtUtcMillis: Long,
    @ColumnInfo(name = "deleted_at_utc_millis")
    val deletedAtUtcMillis: Long? = null,
)

/**
 * Attaches one [TagEntity] to one [TransactionEntity] (issue 3.6; §20.1).
 *
 * Why:    the many-to-many join. A row here is the *only* statement that a transaction carries a
 *         tag — there is no denormalised copy on `transactions`, so retagging touches this table
 *         alone and no second representation can drift out of step with it.
 * Result: a Room row in `transaction_tags`.
 * Input:  see the constructor. Output: a Room row.
 * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-007, FR-TXN-008).
 *
 * **`profile_id` is denormalised onto the join**, for exactly the reason [TransactionSplitEntity]
 * carries one: `DemoDao`'s wipe and its residue count are profile-scoped single-table deletes
 * (ADR-0006), and a table they cannot reach by `profile_id` alone leaves residue behind when the
 * demo is exited. `MigrationSafetyTest` enforces it on every table but `audit_log`.
 *
 * **Soft-deleted rather than removed**, like everything else here (DB-002): untagging is undoable,
 * and a hard `DELETE` in a bulk retag would be the one irreversible step in an otherwise reversible
 * feature.
 */
@Serializable
@Entity(
    tableName = "transaction_tags",
    indices = [
        // "which tags does this transaction have?" — the detail sheet and the list projection.
        Index("transaction_id"),
        // "which transactions carry this tag?" — the tag filter's `EXISTS` clause.
        Index("tag_id"),
        // The demo wipe and the residue count both scope by profile.
        Index("profile_id"),
    ],
)
data class TransactionTagEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    @ColumnInfo(name = "transaction_id")
    val transactionId: String,
    @ColumnInfo(name = "tag_id")
    val tagId: String,
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
@Serializable
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
@Serializable
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
 * A record that the user has already been told about one budget crossing one band (issue 4.5;
 * FR-BUD-004).
 *
 * Why:    "alert at 80% and 100%" is only half a requirement — the other half is *not* alerting
 *         again on the next transaction, and the one after that. A user notified every time they
 *         spend a rupee past 80% learns to dismiss the channel, which costs them the 100% message
 *         that mattered. Something therefore has to remember what has been said.
 *
 *         **The unique index is that memory, not a flag on `budget`.** `UNIQUE(profile_id,
 *         budget_id, month_start, band)` makes re-notification structurally impossible rather than
 *         conditionally avoided: the insert of a second WARN for the same month fails, whatever the
 *         calling code believes. A boolean column would have needed a read-then-write that two
 *         concurrent workers could interleave, and would have had no room for the *second* band —
 *         crossing 80% and later 100% must produce two messages, and does, because the band is part
 *         of the key.
 *
 *         It doubles as the §29 audit row: it records which rule fired, at which version, and when
 *         the user was told (AI-ARC-006, P-02).
 * What:   one row per (budget, month, band) the app has notified.
 * Result: a Room row in `budget_alert`, added at schema version 14 by issue 4.5.
 * Input:  see the constructor. Output: a Room row.
 * Changelog: 2026-08-13 — Created for issue 4.5 (FR-BUD-004).
 *
 * **No amounts are stored.** The figures the notification quoted are derivable from the budget and
 * the transactions at any time, and a copy here would be a second version of the truth that could
 * disagree with the screen. What cannot be re-derived — that a person was interrupted — is what the
 * row holds.
 */
@Serializable
@Entity(
    tableName = "budget_alert",
    indices = [
        Index("profile_id", "budget_id", "month_start_iso_date", "band", unique = true),
        Index("profile_id", "month_start_iso_date"),
    ],
)
data class BudgetAlertEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    /** The `budget` row this alert is about. */
    @ColumnInfo(name = "budget_id")
    val budgetId: String,
    /** Denormalised from the budget so the banner can group without a join. Null for an envelope. */
    @ColumnInfo(name = "category_id")
    val categoryId: String? = null,
    /** TIM-002: the first day of the budget month, ISO `yyyy-MM-dd`. Part of the unique key. */
    @ColumnInfo(name = "month_start_iso_date")
    val monthStartIsoDate: String,
    /** `WARN` | `EXCEEDED` — `BudgetAlertBand.name`. Part of the unique key, so both can fire. */
    @ColumnInfo(name = "band")
    val band: String,
    /** The rulebook row that set this band, and its version (AI-ARC-006, P-02). */
    @ColumnInfo(name = "rule_id")
    val ruleId: String,
    @ColumnInfo(name = "rule_version")
    val ruleVersion: String,
    /** TIM-001: when the user was actually told, UTC epoch millis. */
    @ColumnInfo(name = "notified_at_utc_millis")
    val notifiedAtUtcMillis: Long,
)

/**
 * The record that a closed month's budget review has been shown to the user (issue 4.6; §5.5).
 *
 * Why:    `RULE-BUD-REVIEW.review_once_per_month` is documentation, not enforcement — the same
 *         split `BudgetAlertEntity` draws. Without a persisted claim, reopening the budgets screen
 *         after dismissing last month's review would compute the identical `BudgetReview` again
 *         and show the card straight back, since the engine is pure and the closed month's data
 *         does not change. **Keyed by (profile, month) alone, not per category** — this is a
 *         review-and-move-on card, not a persistent per-finding status like the alert bands, so
 *         one dismissal closes the whole month (ADR-0020).
 * What:   one row per (profile, reviewed month) once the user has dismissed or acted on it.
 * Result: a Room row in `budget_review`, added at schema version 15 by issue 4.6.
 * Input:  see the constructor. Output: a Room row.
 * Changelog: 2026-08-15 — Created for issue 4.6 (FR-BUD-*, §5.5).
 *
 * **No per-category detail is stored**, for the reason `BudgetAlertEntity` gives for amounts: the
 * totals are derivable from the budget and the transactions at any time, and a copy here could
 * disagree with the screen. The totals are still stamped, purely as an audit trail (AI-ARC-006,
 * P-02) of what the reviewed month looked like at the moment it was dismissed.
 */
@Serializable
@Entity(
    tableName = "budget_review",
    indices = [
        Index("profile_id", "month_start_iso_date", unique = true),
    ],
)
data class BudgetReviewEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    /** TIM-002: the first day of the **reviewed** (closed) month, ISO `yyyy-MM-dd`. */
    @ColumnInfo(name = "month_start_iso_date")
    val monthStartIsoDate: String,
    /** The rulebook row that produced this review, and its version (AI-ARC-006, P-02). */
    @ColumnInfo(name = "rule_id")
    val ruleId: String,
    @ColumnInfo(name = "rule_version")
    val ruleVersion: String,
    /** Audit-only snapshot of the reviewed month's totals (MNY-001), not re-read by the app. */
    @ColumnInfo(name = "total_budgeted_minor")
    val totalBudgetedMinor: Long,
    @ColumnInfo(name = "total_actual_minor")
    val totalActualMinor: Long,
    /** TIM-001: when the user dismissed or acted on the review, UTC epoch millis. */
    @ColumnInfo(name = "reviewed_at_utc_millis")
    val reviewedAtUtcMillis: Long,
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
@Serializable
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
    /**
     * FR-TXN-006: when the user said "not recurring", UTC epoch millis (TIM-001); null otherwise.
     *
     * **Deliberately not [deletedAtUtcMillis].** A tombstone means "this rule is gone"; a dismissal
     * means "the detector was right that this merchant repeats, and the user still does not want a
     * rule for it". Only the second must stop the detector proposing the same series next week, and
     * a soft-deleted row cannot say that without also lying to every read that filters tombstones.
     */
    @ColumnInfo(name = "dismissed_at_utc_millis")
    val dismissedAtUtcMillis: Long? = null,
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
 *
 * **A fourth absence, added by issue 5.4: no `@Serializable`, so it is not in the export.** The
 * first absence forces it — with no `profile_id` there is no way to scope this table to the profile
 * being exported, and 5.4's import *replaces* what it restores, so including it would mean either
 * erasing security events or duplicating them. The design spec's data-model bullet lists
 * `audit_log` among the portable tables; the schema it describes cannot support that, and the
 * schema is right. See ADR-0023. A security log is also a record of what happened on *this device*,
 * not the user's financial data to carry to another one.
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

/**
 * One day's net worth, computed and frozen (issue 2.6; FR-ACC-005, AI-ARC-006).
 *
 * Why:    FR-ACC-005 requires net worth to be "snapshotted daily", and the reason is that a *trend*
 *         must not move under the user. Recomputing history from today's accounts would silently
 *         rewrite it every time an account is archived, an opening balance corrected, or a
 *         transaction back-dated — the chart would show a past that never happened. A stored row is
 *         what makes issue 6.6's history exact rather than merely plausible.
 * Result: a Room row in `net_worth_snapshot`, added at schema version 5 by issue 2.6.
 * Input:  see the constructor. Output: a Room row.
 * Changelog: 2026-08-01 — Created for issue 2.6 (FR-ACC-005).
 *
 * **[id] is derived, never generated** — `<profile>:networth:<as-of-date>` — which is what makes the
 * daily job idempotent under REPLACE: running twice on the same day updates one row rather than
 * leaving two figures for one date. The same reasoning as `budgetId()` in `:data:repository`.
 *
 * **[engineId] and [engineVersion] are stored on every row (AI-ARC-006).** A snapshot outlives the
 * code that produced it, and "why did it say ₹2,92,000 in August?" has an answer only if the row
 * remembers which formula ran. Without them a future engine change would make the whole series
 * unexplainable.
 *
 * **All three figures are stored, not just [netWorthMinor].** Recomputing the split from the total
 * is impossible, and P-02 requires the working — a history point the user cannot break down into
 * assets and liabilities is a number with no derivation.
 */
@Serializable
@Entity(
    tableName = "net_worth_snapshot",
    indices = [Index("profile_id", "as_of_iso_date")],
)
data class NetWorthSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    /** TIM-002: the profile-zone day this figure describes, ISO `yyyy-MM-dd`. Never a timestamp. */
    @ColumnInfo(name = "as_of_iso_date")
    val asOfIsoDate: String,
    /** MNY-001: paise. Signed — an overdrawn asset account legitimately reduces this. */
    @ColumnInfo(name = "assets_minor")
    val assetsMinor: Long,
    /** MNY-001: paise, a positive magnitude — what the user owes, as the engine reports it. */
    @ColumnInfo(name = "liabilities_minor")
    val liabilitiesMinor: Long,
    /** MNY-001: paise. `assets_minor - liabilities_minor`, stored so history cannot drift. */
    @ColumnInfo(name = "net_worth_minor")
    val netWorthMinor: Long,
    /** The engine that produced this row (AI-ARC-003). */
    @ColumnInfo(name = "engine_id")
    val engineId: String,
    /** That engine's version at the moment it ran (AI-ARC-006). */
    @ColumnInfo(name = "engine_version")
    val engineVersion: String,
    /** TIM-001: when it was computed, which is not the same as the day it describes. */
    @ColumnInfo(name = "computed_at_utc_millis")
    val computedAtUtcMillis: Long,
    @ColumnInfo(name = "created_at_utc_millis")
    val createdAtUtcMillis: Long,
    @ColumnInfo(name = "updated_at_utc_millis")
    val updatedAtUtcMillis: Long,
    @ColumnInfo(name = "deleted_at_utc_millis")
    val deletedAtUtcMillis: Long? = null,
)

/**
 * A file attached to a transaction — for now, a scanned receipt (issue 3.8; FR-OCR-005, §20.1).
 *
 * Why:    FR-OCR-005 is a MUST with two halves: *"The original image MUST be stored encrypted and
 *         linked as an attachment; user can delete image while keeping the transaction."* The second
 *         half is what decides this table's shape. **There is no BLOB column here, deliberately.**
 *         Putting the image bytes in the row would mean the receipt lives inside the database file,
 *         and "delete the image, keep the transaction" would become a rewrite of the row rather than
 *         the deletion of a file — with the old bytes still recoverable from SQLite's freelist until
 *         a VACUUM nobody scheduled. [fileName] names a separately encrypted blob in app-private
 *         storage instead, so erasing the image is `File.delete()` and is complete.
 * Result: a Room row in `attachments`, added at schema version 11 by issue 3.8.
 * Input:  see the constructor. Output: a Room row.
 * Changelog: 2026-08-06 — Created for issue 3.8 (FR-OCR-005).
 *
 * **No foreign key on [transactionId], matching every other table here.** §20 asks for foreign keys,
 * and this schema has consistently used soft delete plus scoped queries instead: with `deleted_at`
 * tombstones a real `ON DELETE CASCADE` would fire on a *hard* delete that never happens, while the
 * tombstoned parent it should have followed stays behind. The link is enforced by the repository,
 * which is the only class allowed to write either side (ARC-005).
 */
@Serializable
@Entity(
    tableName = "attachments",
    indices = [Index("transaction_id"), Index("profile_id")],
)
data class AttachmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    /** The transaction this belongs to. One transaction may hold several attachments. */
    @ColumnInfo(name = "transaction_id")
    val transactionId: String,
    /**
     * `receipt` today. A code from a closed set, never copy — the UI maps it to a word, so a
     * translation never reaches the database (§21.6).
     */
    @ColumnInfo(name = "kind")
    val kind: String,
    /**
     * The ciphertext blob's file name inside app-private storage — **not a path**.
     *
     * A stored absolute path would break the moment Android moved the app's data directory, which it
     * does on restore-to-a-new-device. The directory is resolved at read time by the image store.
     */
    @ColumnInfo(name = "file_name")
    val fileName: String,
    /** e.g. `image/jpeg`. What the *plaintext* was, so a future export knows what it is handing over. */
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    /** The plaintext size in bytes, so a settings screen can say what receipts are costing. */
    @ColumnInfo(name = "byte_size")
    val byteSize: Long,
    @ColumnInfo(name = "created_at_utc_millis")
    val createdAtUtcMillis: Long,
    @ColumnInfo(name = "updated_at_utc_millis")
    val updatedAtUtcMillis: Long,
    /**
     * FR-OCR-005's "delete image while keeping the transaction": this is stamped **and** the blob is
     * erased. The tombstone stays so a later sync can see the attachment was removed rather than
     * never having existed.
     */
    @ColumnInfo(name = "deleted_at_utc_millis")
    val deletedAtUtcMillis: Long? = null,
)

/**
 * What the parser concluded one bank alert says, awaiting the user's decision (issue 3.9; §18, §23).
 *
 * Why:    the message itself stays in the inbox that already owns it. **There is no body column
 *         here, and that is the acceptance criterion made structural** — 3.9 requires that "no raw
 *         SMS [is] stored beyond what is needed", and the cheapest way to guarantee that is for the
 *         schema to have nowhere to put one. The row holds a conclusion and the [smsId] it was
 *         drawn from; anyone wanting the original text can read it where it lives, under the
 *         permission the user granted for exactly that.
 *
 *         It is a table rather than a list recomputed on each screen open for one reason: **a
 *         dismissal has to stick**. Re-deriving the drafts from the inbox would re-propose every
 *         alert the user has already said no to, for ever, which is the fastest way to make a
 *         review screen worth ignoring.
 * Result: a Room row in `sms_draft`, added at schema version 12 by issue 3.9.
 * Input:  see the constructor. Output: a Room row.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * **No foreign key on [transactionId], matching every other table here** — soft-delete tombstones
 * make `ON DELETE CASCADE` fire on a hard delete that never happens. The link is enforced by
 * `SmsRepository`, the only class allowed to write either side (ARC-005).
 */
@Serializable
@Entity(
    tableName = "sms_draft",
    indices = [
        // UNIQUE, and it is the guard that stops one alert becoming two drafts when a scan overlaps
        // — a crash between reading a batch and advancing the cursor re-reads it, and without this
        // the user would find the same purchase offered twice. Scoped by profile because the demo
        // lives under a second profile id (ADR-0006).
        Index(value = ["profile_id", "sms_id"], unique = true),
        // The review screen's only query: this profile's pending drafts.
        Index(value = ["profile_id", "status"]),
    ],
)
data class SmsDraftEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    /**
     * `Telephony.Sms._ID` of the message this was read from.
     *
     * Kept so the same alert is never proposed twice, and so a draft can be traced back to its
     * source while that message still exists. **Not a copy of the message** — an id whose meaning
     * lives entirely in a provider this app can only read with the user's permission.
     */
    @ColumnInfo(name = "sms_id")
    val smsId: Long,
    /** The DLT header the alert came from, e.g. `VM-HDFCBK`. Shown so the user can recognise it. */
    @ColumnInfo(name = "sender")
    val sender: String,
    /** MNY-001: paise, as a positive magnitude. The sign comes from [direction]. */
    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,
    /** `debit` or `credit`. A code from a closed set, never copy (§21.6). */
    @ColumnInfo(name = "direction")
    val direction: String,
    /** TIM-002: ISO `yyyy-MM-dd`, the day the message arrived in the profile zone. */
    @ColumnInfo(name = "booked_on")
    val bookedOn: String,
    /** The payee or payer as the alert printed it. Null when it named none — best-effort. */
    @ColumnInfo(name = "counterparty")
    val counterparty: String? = null,
    /** The masked account or card digits the alert quoted, e.g. `4521`. Null when it quoted none. */
    @ColumnInfo(name = "account_tail")
    val accountTail: String? = null,
    /** MNY-002: basis points. Below the rulebook's floor the review screen flags the draft. */
    @ColumnInfo(name = "confidence_bps")
    val confidenceBps: Int,
    /**
     * The parser version and the rulebook version that produced this reading (AI-ARC-006).
     *
     * Stored on the row rather than assumed from the current build, because a draft can sit
     * unreviewed across an app update — and "why did it say ₹1,250?" has an answer only if the row
     * remembers which code and which thresholds ran.
     */
    @ColumnInfo(name = "engine_version")
    val engineVersion: String,
    @ColumnInfo(name = "rule_version")
    val ruleVersion: String,
    /** `pending`, `accepted` or `dismissed`. A code from a closed set, never copy. */
    @ColumnInfo(name = "status")
    val status: String,
    /** The transaction this draft became, once accepted. Null while pending or dismissed. */
    @ColumnInfo(name = "transaction_id")
    val transactionId: String? = null,
    @ColumnInfo(name = "created_at_utc_millis")
    val createdAtUtcMillis: Long,
    @ColumnInfo(name = "updated_at_utc_millis")
    val updatedAtUtcMillis: Long,
)

/**
 * What a credit card is, beyond an `account` row with a balance (issue 6.1; FR-ACC-002, §5.7).
 *
 * Why:    `AccountEntity` has carried eleven types and one balance since issue 1.6, and
 *         `AccountType`'s own doc comment has been promising this table since 2.5: *"A credit
 *         card's limit and statement day (FR-ACC-002) … live in their own tables."* Putting the six
 *         fields on `account` instead would add six always-null columns to the ten types that can
 *         never use them — and would do it again for every loan field 6.2 adds, and every holding
 *         field 6.3 adds. One table per type-specific shape keeps `account` the thing every type
 *         genuinely shares.
 *
 *         **Keyed by `account_id`, not by an id of its own.** A card *is* an account here; a second
 *         identity would allow two card rows for one account, which nothing in the app could then
 *         choose between.
 * What:   the card's terms and the last statement cut against them.
 * Result: a Room row in `credit_card`, added at schema version 16 by issue 6.1.
 * Input:  see the constructor. Output: a Room row.
 * Changelog: 2026-08-17 — Created for issue 6.1 (FR-ACC-002).
 *
 * **"Current unbilled" is absent although FR-ACC-002 names it.** It is
 * `outstanding − last_statement`, and the ledger already knows the first term — storing the
 * difference would be a second version of a number the app can always re-derive, which is the
 * argument [BudgetAlertEntity] makes for storing no amounts at all. The card engine derives it.
 *
 * **The days are days-of-month, not dates**, because that is what FR-ACC-002 says and what a card
 * does: it bills on the 5th every month. Storing dates would mean rewriting this row twelve times a
 * year and still having no answer for next February. The clamp to a short month is the engine's.
 */
@Serializable
@Entity(
    tableName = "credit_card",
    indices = [
        Index("profile_id"),
        Index("profile_id", "deleted_at_utc_millis"),
    ],
)
data class CreditCardEntity(
    /** The `account` row this describes. One card per account, which is why it is the key. */
    @PrimaryKey
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    /** MNY-001: paise. Positive — utilisation divides by it, and the model refuses zero. */
    @ColumnInfo(name = "credit_limit_minor")
    val creditLimitMinor: Long,
    /** 1..31, clamped to the month's length when resolved into a date. */
    @ColumnInfo(name = "statement_day")
    val statementDay: Int,
    /** 1..31. May precede [statementDay] — the payment then falls in the following month. */
    @ColumnInfo(name = "due_day")
    val dueDay: Int,
    /** MNY-001: paise on the most recent statement. Null until one is recorded — not zero (P-03). */
    @ColumnInfo(name = "last_statement_minor")
    val lastStatementMinor: Long? = null,
    /** TIM-002: when that statement was cut, ISO `yyyy-MM-dd`. */
    @ColumnInfo(name = "last_statement_iso_date")
    val lastStatementIsoDate: String? = null,
    /** MNY-001: paise. The minimum payment on that statement, when the user has entered it. */
    @ColumnInfo(name = "minimum_due_minor")
    val minimumDueMinor: Long? = null,
    /** MNY-002: the purchase APR in integer basis points. Never a `Double`. */
    @ColumnInfo(name = "apr_bps")
    val aprBps: Int? = null,
    @ColumnInfo(name = "created_at_utc_millis")
    val createdAtUtcMillis: Long,
    @ColumnInfo(name = "updated_at_utc_millis")
    val updatedAtUtcMillis: Long,
    @ColumnInfo(name = "deleted_at_utc_millis")
    val deletedAtUtcMillis: Long? = null,
)

/**
 * A record that the user has already been told one thing about one card in one billing cycle
 * (issue 6.1; FR-ACC-002, §17.1).
 *
 * Why:    the same problem [BudgetAlertEntity] solves, with a worse failure mode. A payment
 *         reminder that re-fires every day for three days is not three reminders, it is a channel
 *         the user mutes — and this is the channel §17.1 classes as a **Critical money event**,
 *         the one that must still work in eleven months when it actually matters.
 *
 *         **The unique index is the memory**, not a flag: `UNIQUE(profile_id, account_id,
 *         cycle_start_iso_date, kind)` makes a second identical notification structurally
 *         impossible rather than conditionally avoided.
 *
 *         **`kind` is part of the key** for the reason `band` is part of the budget one: a card can
 *         be both due and over its utilisation line in the same cycle, and those are two different
 *         messages on two different Android channels (NTF-006). Collapsing them would drop one.
 *
 *         **The cycle start is the statement date, not the month**, which is the whole reason this
 *         key is not `month_start_iso_date`. A card billing on the 25th has a cycle straddling two
 *         calendar months; keyed by month, the reminder would fire twice for one statement.
 * What:   one row per (card, cycle, kind) the app has notified.
 * Result: a Room row in `card_alert`, added at schema version 16 by issue 6.1.
 * Input:  see the constructor. Output: a Room row.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 *
 * **No amounts and no tombstone.** The figures are re-derivable from the card and the ledger, so a
 * copy here could disagree with the screen; and a claim that a person was interrupted is not
 * something a soft delete could honestly undo. That makes this the fifth table exempt from the
 * per-row invariants `MigrationSafetyTest` enforces, and the exemption is argued there by name.
 */
@Serializable
@Entity(
    tableName = "card_alert",
    indices = [
        Index("profile_id", "account_id", "cycle_start_iso_date", "kind", unique = true),
        Index("profile_id", "cycle_start_iso_date"),
    ],
)
data class CardAlertEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    /** The `credit_card` / `account` row this alert is about. Part of the unique key. */
    @ColumnInfo(name = "account_id")
    val accountId: String,
    /** TIM-002: the statement date opening the cycle, ISO `yyyy-MM-dd`. Part of the unique key. */
    @ColumnInfo(name = "cycle_start_iso_date")
    val cycleStartIsoDate: String,
    /** `DUE_SOON` | `UTILISATION` — `CardAlertKind.name`. Part of the key, so both can fire. */
    @ColumnInfo(name = "kind")
    val kind: String,
    /** The rulebook row that fired this alert, and its version (AI-ARC-006, P-02). */
    @ColumnInfo(name = "rule_id")
    val ruleId: String,
    @ColumnInfo(name = "rule_version")
    val ruleVersion: String,
    /** TIM-001: when the user was actually told, UTC epoch millis. */
    @ColumnInfo(name = "notified_at_utc_millis")
    val notifiedAtUtcMillis: Long,
)

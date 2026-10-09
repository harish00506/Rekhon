package com.aicfo.domain.usecase

/**
 * Whether the business/freelancer book is on (issue 13.5; SRS §27, §33, ADR-0073).
 *
 * Why:  §3.3's persona — Suresh, the GST-registered freelance designer — needs his business money
 *       kept apart from his household's, and the app has carried that intention since v1 as a
 *       **forward-compatibility decision** rather than a feature. Issue 13.5 is where the design is
 *       written down and the promise is audited.
 *
 *       The flag is off, and unlike the other Epic 13 flags that is not only about readiness.
 *       Business mode is **a second profile**, and ADR-0073 §3 records the precondition it shares
 *       with household mode: the fourteen id-keyed DAO queries are safe today only because one
 *       profile exists. Turning this on without scoping them is the cross-leak ADR-0069 exists to
 *       prevent — and here the leak would be between a person's business books and their personal
 *       ones, which is the kind that attracts a tax authority rather than an apology.
 * What: one constant. There is deliberately no engine, no entity and no screen behind it — AC2 for
 *       this issue is "ADR only in v1", and the design is the deliverable.
 * Result: v1 behaves exactly as it did.
 * Changelog: 2026-10-09 — Created for issue 13.5.
 *
 * Kept beside [HouseholdMode] because they are the same mechanism: both are "a second profile with
 * its own books", and whichever ships first pays for the id-keyed queries the other also needs.
 */
object BusinessMode {
    /** False in v1. ADR-0073 lists what must be true before it changes. */
    const val IS_ENABLED: Boolean = false

    /**
     * The tag name a transaction is marked with to put it in the business book.
     *
     * Why:  §33's forward-compatibility table says "tags support business/personal from v1", and
     *       **that one is true** — `tags` and `transaction_tags` have existed since schema 1. So
     *       the interim design is not a new mechanism: a user can already separate the two books by
     *       tagging, and business mode's job later is to make that separation structural rather
     *       than a convention the user maintains by hand.
     *
     *       Named here rather than left as a string in a screen so that the day the structural
     *       version arrives, the migration has one thing to look for (ADR-0073 §2).
     */
    const val INTERIM_TAG: String = "business"
}

package com.aicfo.app.navigation

import kotlinx.serialization.Serializable

/**
 * Every destination in the app (ARC-001).
 *
 * Why:  §21.2 forbids feature modules depending on each other — the dashboard must be able to send
 *       the user to the transaction list without importing `:feature:transactions`. Routes
 *       therefore live in `:app`, the one module that already sees them all. Declaring them as
 *       serializable types rather than strings means a destination cannot be reached with a
 *       mistyped path, and arguments are checked by the compiler instead of parsed at runtime.
 * What: the closed set of destinations, one object or data class each.
 * Result: adding a screen is a compile error until it is registered, which is the point.
 * Changelog: 2026-07-25 — Created for issue 1.10.
 *
 * A route with arguments becomes a `data class` with typed properties — never a string template.
 */
sealed interface CfoRoute {
    /**
     * First-run onboarding (issue 2.1). The start destination until it has been completed, after
     * which it is popped off the back stack so Back cannot return to it.
     */
    @Serializable
    data object Onboarding : CfoRoute

    /** The home screen: Safe-to-Spend and net worth. The start destination (issue 5.1). */
    @Serializable
    data object Dashboard : CfoRoute

    /** The transaction list (issue 3.1's recent window; issue 3.6 adds search, filters and paging). */
    @Serializable
    data object Transactions : CfoRoute

    /**
     * Capture one transaction (issue 3.1; FR-TXN-002).
     *
     * A destination rather than a bottom sheet inside a screen, though §5 calls add-transaction a
     * "modal flow": the FAB that opens it is global, so a sheet would have to be hoisted above the
     * whole graph and would then survive navigation it should not. A destination gets the back stack,
     * Back, and process-death restoration for free — and it costs the same one tap.
     *
     * No arguments: everything the screen needs it reads from the active profile.
     */
    @Serializable
    data object AddTransaction : CfoRoute

    /**
     * Scan a receipt and confirm what it says (issue 3.8; FR-OCR-001..006).
     *
     * **No arguments, and that is the design rather than a simplification.** The obvious shape would
     * have been `ReceiptReview(imageUri: String)` — capture on the previous screen, pass the URI.
     * But a content URI's read grant belongs to the activity that received it and does not survive
     * process death, so a route carrying one would work until the day Android killed the app behind
     * the camera. The screen launches the picker itself, so there is no URI to carry.
     */
    @Serializable
    data object ReceiptReview : CfoRoute

    /** The accounts list (issue 2.5; FR-ACC-001). */
    @Serializable
    data object Accounts : CfoRoute

    /**
     * Create or edit one account (issue 2.5).
     *
     * **The first route in this app to carry an argument**, and the shape this file's header
     * describes: a `data class` with a typed property, not a string template. [accountId] is `null`
     * when creating, which is what lets one destination serve both — and being nullable is checked
     * by the compiler here rather than parsed out of a path at runtime.
     */
    @Serializable
    data class AccountEditor(val accountId: String? = null) : CfoRoute
}

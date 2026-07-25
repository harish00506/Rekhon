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
    /** The home screen: Safe-to-Spend and net worth. The start destination (issue 5.1). */
    @Serializable
    data object Dashboard : CfoRoute

    /** The transaction list (issue 3.6). Present here to prove cross-feature navigation works. */
    @Serializable
    data object Transactions : CfoRoute
}

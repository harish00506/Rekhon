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

    /**
     * Review what the app read from bank alerts (issue 3.9; §18, §23, P-01).
     *
     * **No arguments, and the screen requests the OS permission itself.** A route that carried
     * "already granted" would be a claim made at navigation time about something Android can change
     * a moment later — from Settings, while this screen is open. The screen observes the two
     * permissions instead, so revoking either one empties it rather than leaving a stale list.
     */
    @Serializable
    data object SmsDrafts : CfoRoute

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

    /**
     * What one investment account holds (issue 6.3; §11).
     *
     * A typed property rather than a string template, like every route here (ARC-001). [accountId]
     * is required, unlike [AccountEditor]'s: there is no "holdings in general" screen — a holding
     * only means anything inside the account that holds it.
     */
    @Serializable
    data class Holdings(val accountId: String) : CfoRoute

    /**
     * How the whole portfolio is spread across asset classes (issue 6.4; FR-INV-002).
     *
     * A `data object` and not a `data class`, unlike [Holdings]: allocation is a question about
     * every investable account at once, so there is no id to scope it by. Reached from the accounts
     * list rather than from an account, for the same reason.
     */
    @Serializable
    data object Allocation : CfoRoute

    /**
     * Income, consents and the app lock (FR-SET-001).
     *
     * **The route FR-SET-001 always specified and the app never had.** Five strings across three
     * feature modules used to tell the user to change something "in Settings" while no such
     * destination existed; worse, the SMS consent could be granted at onboarding and never revoked,
     * which P-01 forbids. Reached from the dashboard.
     */
    @Serializable
    data object Settings : CfoRoute

    /**
     * The category taxonomy editor (issue 4.1; FR-SET-001).
     *
     * **No arguments, and it is reached from the transaction list rather than from Settings.**
     * FR-SET-001 files this editor under Settings, but no settings screen exists — the requirement's
     * other items are separate issues (11.3 consents, 11.4 erase-all) and the shell itself has none.
     * Hanging the editor off the list where categories are actually used gets it in front of the
     * user now; moving the entry point when the shell lands is a one-line change here, and the
     * destination itself does not move.
     */
    @Serializable
    data object Categories : CfoRoute

    /**
     * The per-category budget screen (issue 4.4; FR-BUD-001/002/003).
     *
     * **No arguments, and no month in the route.** The obvious shape would have been
     * `Budgets(month: String)` — but a month in a route is a claim made at navigation time about
     * *now*, and the app is expected to survive midnight with the screen open. The screen resolves
     * the current month from the injected `Clock` on every emission instead, so a month boundary
     * moves the figures rather than leaving a stale route arguing with them.
     *
     * Reached from the dashboard, beside the 50/30/20 bar: the bar is the plan at the nature level
     * and this is the plan at the category level, so a user who wants to change what the bar shows
     * is one tap from the place to do it.
     */
    @Serializable
    data object Budgets : CfoRoute

    /**
     * What the user is saving for, and what it takes each month (issue 7.1; §15, AI-GOAL).
     *
     * **No arguments**, for the reason [Budgets] has none: a goals list is a question about every
     * goal at once, and the day it is reckoned from comes from the injected `Clock` on every
     * emission rather than from a route argument fixed at navigation time. The app is expected to
     * survive midnight with the screen open, and a required monthly that changes at a month boundary
     * should change on screen rather than argue with a stale route.
     *
     * Reached from the dashboard beside the budgets button: a budget is the plan for this month and
     * a goal is the plan for the years after it, so the two belong within a tap of each other.
     */
    @Serializable
    data object Goals : CfoRoute

    /**
     * How long the user could live on what they have liquid (issue 7.2; §10.1, AI-EMF).
     *
     * **No arguments**, for the reason [Goals] has none: there is exactly one emergency fund
     * per profile, and the day it is reckoned from comes from the injected `Clock` on every
     * emission rather than from the back stack.
     *
     * Sits beside [Goals] in the graph because §10.1's coach suggests pausing goals when the
     * runway is thin — the two screens are the two halves of one decision.
     */
    @Serializable
    data object EmergencyFund : CfoRoute
}

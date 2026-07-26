package com.aicfo.feature.onboarding

import java.time.ZoneId

/**
 * The choices offered on the profile step (issue 2.1).
 *
 * Why:  `ZoneId.getAvailableZoneIds()` returns roughly 600 entries. Offering all of them is not a
 *       choice, it is a search problem, and onboarding has a three-minute budget (FR-ONB-005). A
 *       short list covering India plus the places Indians most commonly earn from answers it for
 *       nearly everyone, and the device's own zone is always added on top — so someone in Oslo
 *       still sees their own zone first and never has to hunt.
 * What: the currency and time-zone options, as plain ids.
 * Result: a profile step that is two taps rather than a scroll through the world.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 *
 * These are UI conveniences, not financial parameters — no threshold or rate lives here, so §6's
 * "thresholds belong in `ai/rules/`" rule does not apply. Settings (and issue 13.1's household mode)
 * can offer the full list later; this is only the first-run shortcut.
 */
internal object OnboardingOptions {
    /** v1 is India-only (P-06) — `MoneyFormatter` renders ₹ and nothing else yet. */
    val currencies: List<String> = listOf(DEFAULT_CURRENCY_CODE)

    /** India first, then the zones an NRI user is most likely to be in. */
    private val COMMON_ZONE_IDS =
        listOf(
            "Asia/Kolkata",
            "Asia/Dubai",
            "Asia/Singapore",
            "Europe/London",
            "America/New_York",
            "America/Los_Angeles",
        )

    /**
     * The time zones to offer.
     *
     * Why:    the device's own zone goes first, so the most likely answer is also the shortest
     *         reach — and a user outside the common list is not stranded. De-duplication is by
     *         **rules, not by id**, which matters more than it sounds: Android emulators and many
     *         real Indian devices still report the legacy alias `Asia/Calcutta`, so a plain
     *         `distinct()` leaves the list showing `Asia/Calcutta` and `Asia/Kolkata` as two
     *         separate options that mean exactly the same thing — in this app's primary market, on
     *         the one screen where the answer has to be unambiguous. Found on a device, not in a
     *         test.
     * Result: the device zone followed by the common zones, one entry per distinct set of rules.
     * Input:  [deviceZoneId] — from the injected `Clock`, never read here directly (TIM-001). An
     *         id this JVM cannot parse is dropped rather than throwing, the same defensive stance
     *         `ProfileZoneProvider` takes.
     * Output: the ordered list of IANA ids.
     */
    fun zoneIds(deviceZoneId: String): List<String> =
        (listOf(deviceZoneId) + COMMON_ZONE_IDS)
            .mapNotNull { id -> runCatching { ZoneId.of(id) }.getOrNull() }
            .distinctBy { it.rules }
            .map { it.id }
}

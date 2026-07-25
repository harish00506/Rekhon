package com.aicfo.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/**
 * Publishes the project's custom lint checks to the Android Lint runtime.
 *
 * Why:  lint discovers checks through a registry named in the jar manifest
 *       (`Lint-Registry-v2`, set in `lint/build.gradle.kts`). Without this class the detectors
 *       compile and do nothing — which is precisely the failure mode the governance audit found
 *       elsewhere in this repo, so it is worth being explicit: the proof these rules work is a
 *       seeded violation turning the build red, not the existence of the files.
 * What: the five issues that make MNY-001, ARC-006, TIM-001, the strings rule and P-01
 *       build-blocking, all at severity ERROR.
 * Result: `lintChecks(project(":lint"))` in the convention plugins gives every module these rules.
 * Changelog: 2026-07-25 — Created for issue 1.5 / task 1.1.5.
 */
class CfoIssueRegistry : IssueRegistry() {
    /** The checks this registry contributes. */
    override val issues: List<Issue> =
        listOf(
            MoneyDoubleDetector.ISSUE,
            GlobalScopeDetector.ISSUE,
            DomainClockDetector.ISSUE,
            HardcodedUiStringDetector.ISSUE,
            PiiLoggingDetector.ISSUE,
        )

    /** The lint API these detectors were compiled against (catalog `lintVersion`, tracks AGP). */
    override val api: Int = CURRENT_API

    /** Oldest lint API accepted; keeps a stale IDE from loading checks it cannot run. */
    override val minApi: Int = CURRENT_API

    /** Shown with each finding so a reader knows the rule is ours, not Google's. */
    override val vendor: Vendor =
        Vendor(
            vendorName = "AI Personal CFO",
            identifier = "com.aicfo.lint",
            feedbackUrl = "https://github.com/harishgccil/AI_personal_cfo/issues",
        )
}

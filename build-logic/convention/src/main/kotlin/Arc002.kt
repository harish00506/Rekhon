/**
 * ARC-002 decision logic — kept pure so it is deterministic and unit-testable (P-08).
 *
 * Why:  §21.2 — :core:model and :domain:* must stay JVM-only; the *decision* of what
 *       counts as a violation is separated from the Gradle wiring so it can be tested
 *       without spinning up a build.
 * What: the forbidden Android plugin ids and the message builder.
 * Result: [enforceNoAndroidPlugins] wires this into afterEvaluate; tests assert it here.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
internal object Arc002 {
    /** Android plugin ids that must never appear on a pure-Kotlin module. */
    val FORBIDDEN_PLUGIN_IDS: List<String> =
        listOf("com.android.application", "com.android.library", "com.android.base")

    /**
     * Decides whether a module violates ARC-002 and builds the failure message.
     * Input:  [modulePath] — the module's Gradle path; [appliedPluginIds] — ids applied.
     * Output: the ARC-002 message if a forbidden plugin is present, else null.
     */
    fun violationMessage(modulePath: String, appliedPluginIds: Collection<String>): String? {
        val offending = FORBIDDEN_PLUGIN_IDS.firstOrNull { it in appliedPluginIds } ?: return null
        return "ARC-002 violation: pure-Kotlin module '$modulePath' applied the Android plugin " +
            "'$offending'. :core:model and :domain:* must stay JVM-only (SRS §21.2)."
    }
}

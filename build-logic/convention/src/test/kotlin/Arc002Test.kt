import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure ARC-002 decision logic ([Arc002]).
 *
 * Why:  P-08 — the invariant's decision must be deterministic and testable without a
 *       Gradle build. Complements the TestKit test ([Arc002GuardTest]) that proves the
 *       wiring fires end-to-end.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
class Arc002Test {
    /** A pure-Kotlin module with only JVM plugins is not a violation. */
    @Test
    fun cleanModuleHasNoViolation() {
        val message =
            Arc002.violationMessage(
                ":core:model",
                listOf("java-library", "org.jetbrains.kotlin.jvm"),
            )
        assertNull(message)
    }

    /** Applying com.android.library to a pure-Kotlin module is a violation. */
    @Test
    fun androidLibraryOnPureKotlinModuleViolates() {
        val message = Arc002.violationMessage(":core:model", listOf("com.android.library"))
        assertTrue(message != null && message.contains("ARC-002"))
        assertTrue(message!!.contains(":core:model"))
        assertTrue(message.contains("com.android.library"))
    }

    /** com.android.application is also forbidden (e.g. on a :domain:* module). */
    @Test
    fun androidApplicationAlsoViolates() {
        val message = Arc002.violationMessage(":domain:usecase", listOf("com.android.application"))
        assertTrue(message != null && message.contains("ARC-002"))
    }

    /** The forbidden set covers the three Android plugin ids that pull in the SDK. */
    @Test
    fun forbiddenListCoversTheAndroidOffenders() {
        assertTrue(
            Arc002.FORBIDDEN_PLUGIN_IDS.containsAll(
                listOf("com.android.application", "com.android.library", "com.android.base"),
            ),
        )
    }
}

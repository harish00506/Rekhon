package com.aicfo.spike.kmp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC1's confirmation: are `:core:model` and `:domain:*` actually KMP-portable? (issue 13.7.)
 *
 * Why:  the acceptance criterion asks to "confirm `:core:model` + `:domain:*` stay Android-free so
 *       they are KMP-portable (ARC-002)". Those are **two different claims joined by a 'so'**, and
 *       the join is where this fails.
 *
 *       They are Android-free: `enforceNoAndroidPlugins()` has failed the build on an Android
 *       plugin in a pure-Kotlin module since issue 1.1, and it still does. But Android-free is
 *       necessary and **not sufficient**. A module can be perfectly Android-free and still import
 *       `java.time`, `java.math.BigDecimal` or `java.util.UUID` — none of which exist in
 *       Kotlin/Native, which is what an iOS port runs on. The spike's negative control proves it:
 *       the same `import java.math.BigDecimal` compiles for JVM and fails for `linuxX64` with
 *       "Unresolved reference 'java'".
 *
 *       §33's forward-compatibility table says "ARC-002 keeps all engines pure Kotlin; only UI and
 *       platform services need porting". The second half is wrong, and this measures by how much.
 * What: counts the files in the supposedly-portable layer that import `java.*`, pins the number,
 *       and pins which modules are already clean.
 * Result: the audit cannot rot, and progress toward portability is visible as the number falls.
 * Changelog: 2026-10-10 — Created for issue 13.7.
 *
 * The directories this reads are declared as task inputs in `spike/kmp/build.gradle.kts` — without
 * that, Gradle would call this UP-TO-DATE on exactly the edits it exists to notice (issues 7.2,
 * 11.5, 11.7, 13.1, 13.5).
 */
class PortabilityAuditTest {
    private val portableLayer: List<File> by lazy {
        listOf("core/model", "domain")
            .map { File(repoRoot(), it) }
            .flatMap { root -> root.walkTopDown().filter { it.isMainKotlinSource() }.toList() }
    }

    /**
     * Output: asserts the audit is reading something. A measurement over zero files would report
     *         perfect portability and mean nothing — the vacuity this repo has been bitten by
     *         before (issues 1.5, 12.1).
     */
    @Test
    fun the_audit_is_reading_the_portable_layer() {
        assertTrue(portableLayer.size > 100, "only found ${portableLayer.size} main sources — is the path wrong?")
    }

    /**
     * Output: asserts the count of JVM-bound files, pinned.
     *
     * **This fails when the number changes in either direction**, and both are informative: upward
     * means somebody added a `java.*` import to the layer that is supposed to be portable, and
     * downward means a port is making progress and the figure in ADR-0075 should be updated.
     */
    @Test
    fun the_number_of_JVM_bound_files_is_pinned() {
        val bound = portableLayer.filter { it.readText().contains("\nimport java.") }

        assertEquals(
            EXPECTED_JVM_BOUND,
            bound.size,
            "the portable layer's JVM-bound file count moved. Up means a java.* import was added " +
                "to a module that must one day run on Kotlin/Native; down means a port is " +
                "progressing. Either way, update ADR-0075's figures. Files: " +
                bound.map { it.name }.sorted(),
        )
    }

    /**
     * Output: asserts `Money` is still the blocker.
     *
     * This is the fact that makes the whole audit matter: every one of the thirty engines depends
     * on `:core:model`, and `Money` uses `BigDecimal`. **So no engine is portable until Money is**,
     * and porting a leaf engine first would prove nothing. When this test fails, the single most
     * important obstacle to an iOS port is gone.
     */
    @Test
    fun Money_is_still_the_blocker_every_engine_sits_behind() {
        val money = File(repoRoot(), "core/model/src/main/kotlin/com/aicfo/core/model/Money.kt")

        assertTrue(money.isFile, "Money.kt moved — the audit needs updating")
        assertTrue(
            money.readText().contains("import java.math.BigDecimal"),
            "Money no longer uses BigDecimal — the blocker ADR-0075 names is gone. Re-measure, and " +
                "check whether the spike's PortableMoney can now be retired",
        )
    }

    /**
     * Output: asserts the modules that are already clean are still clean, by name.
     *
     * These are where a port would start, so losing one is a regression worth a failure rather
     * than a slow drift. Named individually because "six are clean" would stay true if one became
     * dirty and another was cleaned.
     */
    @Test
    fun the_already_clean_engines_are_still_clean() {
        val clean =
            CLEAN_MODULES.filter { module ->
                File(repoRoot(), module)
                    .walkTopDown()
                    .filter { it.isMainKotlinSource() }
                    .none { it.readText().contains("\nimport java.") }
            }

        assertEquals(CLEAN_MODULES, clean, "a module that was free of java.* imports no longer is")
    }

    /**
     * Output: asserts ARC-002's existing guard is still in place — the half of AC1 that **does**
     *         hold. The audit above says Android-free is not enough; it does not say it is not
     *         needed.
     */
    @Test
    fun the_ARC_002_guard_that_does_hold_is_still_there() {
        val extensions = File(repoRoot(), "build-logic/convention/src/main/kotlin/ProjectExtensions.kt")

        assertTrue(extensions.readText().contains("enforceNoAndroidPlugins"), "ARC-002's guard is gone")
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun File.isMainKotlinSource(): Boolean =
        isFile && extension == "kt" && path.contains("/src/main/") && !path.contains("/build/")

    private fun repoRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Could not find the repository root from ${File("").absolutePath}")
    }

    private companion object {
        /**
         * Measured on 2026-10-10 at 0.13.5: 40 of 139 main sources in `:core:model` + `:domain:*`
         * import `java.*`, chiefly `java.time.LocalDate` (31) and `java.math.BigDecimal` (14).
         */
        const val EXPECTED_JVM_BOUND = 40

        /** The modules with no `java.*` import at all — where a port would begin. */
        val CLEAN_MODULES =
            listOf(
                "domain/engines/budget",
                "domain/engines/classification",
                "domain/engines/nature",
                "domain/engines/networth",
                "domain/engines/safetospend",
                "domain/engines/simulator",
                "domain/usecase",
            )
    }
}

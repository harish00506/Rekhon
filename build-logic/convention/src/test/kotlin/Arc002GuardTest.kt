import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TestKit functional test (T2) proving the ARC-002 guard fires end-to-end.
 *
 * Why:  AC3 — "when an Android plugin is added to a pure-Kotlin module, the build fails
 *       with a clear ARC-002 message." This runs a throwaway Gradle build that applies
 *       com.android.library and then cfo.kotlin.library, and asserts the failure text.
 * What: generates a temp project that includes the real build-logic, runs `help`, and
 *       expects configuration to fail citing ARC-002.
 * Result: regression protection for the module-boundary invariant.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
class Arc002GuardTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * Input:  none (paths are derived from the test working dir = build-logic/convention).
     * Output: asserts the build fails and the output contains "ARC-002".
     */
    @Test
    fun applyingAndroidPluginToKotlinLibraryFailsWithArc002() {
        val workingDir = File(System.getProperty("user.dir"))
        val buildLogicDir = workingDir.parentFile
        val repoRoot = buildLogicDir.parentFile
        val agpVersion =
            File(repoRoot, "gradle/libs.versions.toml")
                .readLines()
                .first { it.trimStart().startsWith("agp ") }
                .substringAfter('"')
                .substringBefore('"')

        val projectDir = tempFolder.root
        File(projectDir, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                includeBuild("${buildLogicDir.invariantSeparatorsPath}")
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "arc002-test"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.library") version "$agpVersion"
                id("cfo.kotlin.library")
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("help")
                .buildAndFail()

        assertTrue(
            "Expected an ARC-002 failure, got:\n${result.output}",
            result.output.contains("ARC-002"),
        )
    }
}

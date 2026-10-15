package com.aicfo.ml.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds the installer script to the app's manifest (ADR-0079; ADR-0017's pattern).
 *
 * Why:  two places now state which file is the model: [LlmModelManifest.PUBLISHED], which the app
 *       checks against, and `scripts/install_llm_model.py`, which puts the file there. If they
 *       disagree the failure is maximally annoying — the script downloads and verifies half a
 *       gigabyte happily, pushes it, and the app then refuses it — so the disagreement is made a
 *       build failure instead.
 * What: the file name, the length and the digest, read out of the script's own constants.
 * Result: upgrading the model means editing both, or the build goes red.
 * Changelog: 2026-10-10 — Created for ADR-0079.
 *
 * The script is read with small regular expressions rather than executed, for the reason the
 * other drift tests give: running Python from a Kotlin unit test would be the more expensive
 * mistake.
 */
class ModelManifestDriftTest {
    private val script: String by lazy { scriptFile().readText() }
    private val manifest = LlmModelManifest.PUBLISHED

    @Test
    fun `the installer is where this test thinks it is`() {
        assertTrue("the install script looks empty or truncated", script.length > 2_000)
        assertTrue("no manifest block in the script", "FILE_NAME" in script)
    }

    @Test
    fun `the script installs the file the app accepts`() {
        assertEquals(manifest.fileName, quoted("FILE_NAME"))
    }

    @Test
    fun `the script checks the length the app checks`() {
        assertEquals(manifest.sizeBytes.toString(), number("SIZE_BYTES"))
    }

    @Test
    fun `the script checks the digest the app checks`() {
        assertEquals(manifest.sha256, quoted("SHA_256"))
    }

    @Test
    fun `the script names the licence the app claims`() {
        assertEquals(manifest.licence, quoted("LICENCE"))
    }

    @Test
    fun `the script points at the repository the manifest names`() {
        assertTrue("the script must download from the manifest's source", quoted("REPO") in manifest.source)
    }

    private fun quoted(name: String): String =
        Regex("^$name = \"([^\"]+)\"", RegexOption.MULTILINE).find(script)?.groupValues?.get(1)
            ?: error("$name is not a quoted constant in ${SCRIPT_PATH}")

    private fun number(name: String): String =
        Regex("^$name = ([0-9_]+)", RegexOption.MULTILINE).find(script)?.groupValues?.get(1)?.replace("_", "")
            ?: error("$name is not a numeric constant in ${SCRIPT_PATH}")

    private fun scriptFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, SCRIPT_PATH)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Could not find $SCRIPT_PATH walking up from ${File("").absolutePath}")
    }

    private companion object {
        const val SCRIPT_PATH = "scripts/install_llm_model.py"
    }
}

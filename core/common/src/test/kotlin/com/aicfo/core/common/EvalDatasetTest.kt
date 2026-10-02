package com.aicfo.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a frozen dataset's declared revision (issue 12.2; §21.5).
 *
 * Why:  AC1 asks for datasets that are frozen **and versioned**, and by issue 12.2 not one of the
 *       four declared a revision anywhere. That makes every accuracy number unattributable: a run
 *       reporting 94% tells you nothing if the set it ran against might have been edited since the
 *       last number was recorded. Worse, it makes a quiet edit — relabelling an awkward case rather
 *       than fixing the engine — invisible.
 * What: the version line's parsing, and the refusal to proceed without one.
 * Result: every accuracy figure in a CI log names the dataset revision that produced it.
 * Changelog: 2026-10-02 — Created for issue 12.2.
 */
class EvalDatasetTest {
    @Test
    fun `the declared version is read from the header`() {
        val text = "Some prose about the set.\n# dataset-version: 2.1\n\n=== a case\n# label=x\n"

        assertEquals("2.1", EvalDataset.versionOf(text, "/eval/x.txt"))
    }

    @Test
    fun `the version may sit anywhere in the header, before the first record`() {
        val text = "line one\nline two\n#   dataset-version:  3.0  \n=== a case\n# label=x\n"

        assertEquals("3.0", EvalDataset.versionOf(text, "/eval/x.txt"))
    }

    @Test
    fun `a version declared after the first record is not accepted`() {
        // It would be data, not metadata — and a reader scanning the header would not find it.
        val text = "prose\n=== a case\n# dataset-version: 9.9\n# label=x\n"

        val error = assertThrows(IllegalStateException::class.java) { EvalDataset.versionOf(text, "/eval/x.txt") }

        assertTrue(error.message!!.contains("dataset-version"))
    }

    @Test
    fun `a dataset with no declared version is an error`() {
        // The whole point. Silence here would let a dataset be edited with no record of the change.
        val error =
            assertThrows(IllegalStateException::class.java) {
                EvalDataset.versionOf("prose only\n=== a case\n# label=x\n", "/eval/x.txt")
            }

        assertTrue(error.message!!.contains("/eval/x.txt"))
        assertTrue(error.message!!.contains("dataset-version"))
    }

    @Test
    fun `a blank version is an error, not an empty string`() {
        val error =
            assertThrows(IllegalStateException::class.java) {
                EvalDataset.versionOf("# dataset-version:\n=== c\n# label=x\n", "/eval/x.txt")
            }

        assertTrue(error.message!!.contains("blank"))
    }

    @Test
    fun `the first declaration wins, and a second is an error`() {
        // Two versions in one header means somebody edited without noticing the existing line; taking
        // either silently would record the wrong revision against every future result.
        val text = "# dataset-version: 1.0\n# dataset-version: 2.0\n=== c\n# label=x\n"

        val error = assertThrows(IllegalStateException::class.java) { EvalDataset.versionOf(text, "/eval/x.txt") }

        assertTrue(error.message!!.contains("more than once"))
    }
}

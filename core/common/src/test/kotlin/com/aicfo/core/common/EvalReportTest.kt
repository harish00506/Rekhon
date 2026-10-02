package com.aicfo.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared AI-evaluation report (issue 12.2; §21.5).
 *
 * Why:  §21.5 sets accuracy floors — categorisation ≥ 92%, receipts ≥ 95%, SMS ≥ 95% — and by issue
 *       12.2 all four runners asserted them and **none reported the number**. A gate that only says
 *       "passed" hides the thing worth watching: a floor of 92% sitting at 92.1% is one bad case from
 *       red, and nobody can see it coming. Three releases later somebody loosens the floor instead of
 *       fixing the regression, because by then there is no record of what it used to be.
 *
 *       So a run prints what it measured, against what it required, on what dataset revision.
 * What: the percentage arithmetic, the formatted line, and the guards against reporting nonsense.
 * Result: CI logs carry the accuracy trend, and a failure message already contains it.
 * Changelog: 2026-10-02 — Created for issue 12.2.
 */
class EvalReportTest {
    @Test
    fun `a passing report names the dataset, the version, the score and the floor`() {
        val report = EvalReport(dataset = "categorisation", version = "1.0", floorPercent = 92)

        val line = report.line(metric = "accuracy", correct = 72, total = 76)

        assertTrue(line, line.contains("categorisation"))
        assertTrue(line, line.contains("v1.0"))
        assertTrue(line, line.contains("accuracy"))
        assertTrue("the measured share must be visible: $line", line.contains("94"))
        assertTrue("and so must the floor: $line", line.contains("92"))
        assertTrue("and the raw counts, so the share can be checked: $line", line.contains("72/76"))
    }

    @Test
    fun `the share is truncated, never rounded up to the floor`() {
        // 91.9% must not print as 92% next to a 92% floor. Rounding up at the boundary would make a
        // failing run read as if it had passed, in the one line a reader trusts.
        val report = EvalReport(dataset = "d", version = "1", floorPercent = 92)

        assertTrue(report.line("accuracy", correct = 919, total = 1000).contains("91.9"))
        assertEquals(91, report.percent(correct = 919, total = 1000))
    }

    @Test
    fun `a report below its floor says so in words`() {
        // The failure message is the deliverable: it must be obvious from the line alone, without the
        // reader having to compare two numbers.
        val report = EvalReport(dataset = "d", version = "1", floorPercent = 95)

        val line = report.line("accuracy", correct = 9, total = 10)

        assertTrue(line, line.contains("BELOW"))
        assertTrue(report.meetsFloor(correct = 9, total = 10).not())
    }

    @Test
    fun `a report at exactly its floor passes`() {
        // The floor is inclusive — "at least 92%" — and a boundary that silently excluded would make
        // every threshold one point stricter than the SRS says.
        val report = EvalReport(dataset = "d", version = "1", floorPercent = 92)

        assertTrue(report.meetsFloor(correct = 92, total = 100))
        assertTrue(report.line("accuracy", 92, 100).contains("ok"))
    }

    @Test
    fun `an empty dataset cannot be reported on`() {
        // The vacuity guard. 0/0 is not 100%: it is a dataset that was never loaded, and reporting it
        // as a pass is the exact failure this project has found five times elsewhere.
        val report = EvalReport(dataset = "d", version = "1", floorPercent = 92)

        val error = assertThrows(IllegalArgumentException::class.java) { report.line("accuracy", 0, 0) }

        assertTrue(error.message!!.contains("empty"))
        assertThrows(IllegalArgumentException::class.java) { report.meetsFloor(0, 0) }
    }

    @Test
    fun `more correct than total is rejected`() {
        // A counting bug in the runner, not a result. Reporting 120% would look like good news.
        val report = EvalReport(dataset = "d", version = "1", floorPercent = 92)

        val error = assertThrows(IllegalArgumentException::class.java) { report.line("accuracy", 11, 10) }

        assertTrue(error.message!!.contains("more correct"))
    }

    @Test
    fun `a blank dataset version is rejected`() {
        // AC1 asks for datasets that are frozen *and versioned*. A report that could not say which
        // revision produced a number would make the whole exercise unattributable.
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                EvalReport(dataset = "d", version = "  ", floorPercent = 92)
            }

        assertTrue(error.message!!.contains("version"))
    }

    @Test
    fun `a floor outside 0 to 100 is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { EvalReport("d", "1", floorPercent = 101) }
        assertThrows(IllegalArgumentException::class.java) { EvalReport("d", "1", floorPercent = -1) }
    }
}

package com.aicfo.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The snapshot-update workflow (issue 12.1; §21.5, P-08).
 *
 * Why:  a golden file has to be regenerable, or the first large but correct change makes updating
 *       thirty records by hand so unpleasant that somebody loosens the assertion instead. But the
 *       obvious implementation — a flag that rewrites the fixture in place — is the most dangerous
 *       thing that could be added to this repository: if it were ever on in CI, **every golden test
 *       in the project would pass for ever**, rewriting its own expectations to match whatever the
 *       code now does. That is the vacuous-gate failure this project has found five times, with a
 *       switch attached.
 *
 *       So the update never writes to `src/test/resources`. It writes a candidate under `build/` and
 *       **fails the test** with the command to move it. A human performs the overwrite, and the diff
 *       goes through review like any other change.
 * What: the candidate is written, the test fails, the message says what to do, and the real fixture
 *       is never touched.
 * Result: regenerating is one command, and no configuration can make a golden test self-approve.
 * Changelog: 2026-10-02 — Created for issue 12.1.
 */
class GoldenSnapshotTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `proposing an update writes the candidate under build and fails`() {
        val destination = temporaryFolder.newFolder("candidates")

        val error =
            assertThrows(AssertionError::class.java) {
                GoldenSnapshot.propose(
                    name = "card.txt",
                    content = "=== a record\n# label=x\n",
                    candidateDirectory = destination,
                    fixturePath = "domain/engines/card/src/test/resources/golden/card.txt",
                )
            }

        assertEquals("=== a record\n# label=x\n", File(destination, "card.txt").readText())
        assertTrue("the message must name the candidate", error.message!!.contains("card.txt"))
        assertTrue("and the destination fixture", error.message!!.contains("domain/engines/card"))
        assertTrue("and tell the reader to review the diff", error.message!!.contains("review"))
    }

    @Test
    fun `proposing an update never writes to the fixture itself`() {
        // The assertion this whole class exists for. A helper that overwrote the fixture would make
        // every golden test in the repository self-approving.
        val fixture = temporaryFolder.newFile("card.txt")
        fixture.writeText("ORIGINAL")

        assertThrows(AssertionError::class.java) {
            GoldenSnapshot.propose(
                name = "card.txt",
                content = "REGENERATED",
                candidateDirectory = temporaryFolder.newFolder("out"),
                fixturePath = fixture.path,
            )
        }

        assertEquals("ORIGINAL", fixture.readText())
    }

    @Test
    fun `the candidate directory is created when it does not exist`() {
        val destination = File(temporaryFolder.root, "nested/does/not/exist")

        assertThrows(AssertionError::class.java) {
            GoldenSnapshot.propose("x.txt", "body", destination, "somewhere/x.txt")
        }

        assertTrue(File(destination, "x.txt").isFile)
    }

    @Test
    fun `proposing an empty snapshot is refused`() {
        // An empty candidate would, if copied, turn the fixture into one with no records — which
        // `GoldenFixture` rejects, but only on the next run. Refusing here says why immediately.
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                GoldenSnapshot.propose("x.txt", "   \n", temporaryFolder.newFolder("o"), "somewhere/x.txt")
            }

        assertTrue(error.message!!.contains("empty"))
    }
}

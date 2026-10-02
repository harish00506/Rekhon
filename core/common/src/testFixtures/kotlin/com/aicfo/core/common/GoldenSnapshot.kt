package com.aicfo.core.common

import java.io.File

/**
 * Regenerating a golden file, without letting a test approve its own expectations (issue 12.1).
 *
 * Why:  a golden file has to be regenerable. Without that, the first large-but-correct change makes
 *       updating thirty records by hand unpleasant enough that somebody loosens the assertion
 *       instead — and a loosened golden test is worse than none, because it still looks like a gate.
 *
 *       The obvious implementation is a flag that rewrites the fixture in place. **That would be the
 *       most dangerous thing in this repository.** If it were ever set in CI — an env var, a stray
 *       `gradle.properties`, a copied command line — every golden test in the project would pass for
 *       ever, quietly rewriting its expectations to match whatever the code had started doing. This
 *       repository has found five vacuous gates already; none of them came with a switch.
 *
 *       So nothing here writes to `src/test/resources`. The candidate goes under `build/`, the test
 *       **fails**, and a human runs one `cp`. The overwrite is a reviewed diff like any other change.
 * What: write the candidate, then fail with the exact command and why it is not automatic.
 * Result: regenerating is one command, and no configuration can make a golden test self-approve.
 * Changelog: 2026-10-02 — Created for issue 12.1.
 *
 * Usage, in an engine's golden test:
 * ```
 * val rendered = records().joinToString("\n") { render(engine.evaluate(it)) }
 * if (rendered != expectedText) {
 *     GoldenSnapshot.propose("card.txt", rendered, File(buildDir, "golden-update"), FIXTURE_PATH)
 * }
 * ```
 * The workflow is documented in `docs/testing/engine-test-harness.md`.
 */
object GoldenSnapshot {
    /**
     * Writes a regenerated fixture as a candidate and fails the test.
     * Why:    see the class note — writing the fixture itself would let a golden test approve its
     *         own output. This always throws, and that is the contract, not a failure mode.
     * Result: never returns. The candidate is on disk; the fixture is untouched.
     * Input:  [name] — the fixture's file name; [content] — the regenerated text, which must not be
     *         blank; [candidateDirectory] — somewhere under `build/`, created if absent;
     *         [fixturePath] — where the reviewer should copy it, named in the message.
     * Output: nothing; throws [AssertionError] always, or [IllegalArgumentException] for blank
     *         content.
     * Changelog: 2026-10-02 — Created for issue 12.1.
     */
    fun propose(
        name: String,
        content: String,
        candidateDirectory: File,
        fixturePath: String,
    ): Nothing {
        require(content.isNotBlank()) {
            "refusing to propose an empty snapshot for $name: copied over the fixture it would " +
                "leave a golden file with no records, which asserts nothing"
        }
        candidateDirectory.mkdirs()
        val candidate = File(candidateDirectory, name)
        candidate.writeText(content)
        throw AssertionError(
            """
            The golden fixture $name is out of date. A candidate has been written to:
              ${candidate.absolutePath}

            If — and only if — the new output is correct, copy it over the fixture and review the diff:
              cp ${candidate.absolutePath} $fixturePath

            This is deliberately not automatic. A test that rewrote its own expectations would pass
            for ever while asserting nothing, and a flag that did it could be left on in CI by
            accident. The diff is the review (§21.5).
            """.trimIndent(),
        )
    }
}

package com.aicfo.core.common

/**
 * The declared revision of a frozen evaluation dataset (issue 12.2; §21.5).
 *
 * Why:  §21.5 sets accuracy floors on frozen, labelled datasets, and by issue 12.2 all four of this
 *       project's sets — categorisation, receipts, SMS, forecast ledgers — were frozen and **not
 *       versioned**. That makes every accuracy number unattributable: "94% on the categorisation set"
 *       means nothing if the set may have been edited since the last figure was recorded.
 *
 *       It also hides the one edit that matters. The cheapest way to fix a failing accuracy gate is
 *       not to improve the engine but to relabel the awkward case, and without a revision marker that
 *       change leaves no trace in any report. A version does not prevent it — review does — but it
 *       makes the diff and the number tell the same story.
 * What: reads a `# dataset-version: <v>` line from the header, and refuses a set without one.
 * Result: every accuracy figure in a CI log names the dataset revision that produced it.
 * Changelog: 2026-10-02 — Created for issue 12.2.
 *
 * The marker lives in the **header** — before the first `===` record — so it is metadata rather than
 * data, and a reader scanning the top of the file finds it. A declaration after the first record is
 * refused for that reason, not as pedantry.
 */
object EvalDataset {
    /** The header line that declares the revision, as a reader writes it. */
    private const val MARKER = "# dataset-version:"

    /**
     * Matches the marker however much whitespace a hand-written header uses after the `#`.
     *
     * Deliberately tolerant: these files are edited by people, and refusing `#  dataset-version: 2.1`
     * over one extra space would be pedantry that teaches everyone to distrust the parser. The
     * *placement* rule — header only — is the one that carries meaning and stays strict.
     */
    private val MARKER_PATTERN = Regex("""^#\s*dataset-version\s*:""")

    /** Where the header stops and the records begin — [GoldenFixture]'s own marker. */
    private const val RECORD_MARKER = "==="

    /**
     * The revision a dataset declares.
     * Why:    an error rather than a default, for the same reason [GoldenFixture] errors on a missing
     *         resource: a silently-unversioned dataset is one whose results cannot be compared with
     *         yesterday's, and defaulting to "unknown" would make that permanent.
     * Result: the declared version, trimmed.
     * Input:  [text] — the dataset's full contents; [path] — only for error messages.
     * Output: [String].
     * Changelog: 2026-10-02 — Created for issue 12.2.
     *
     * Throws [IllegalStateException] when the marker is absent, blank, declared twice, or appears
     * only after the first record.
     */
    fun versionOf(
        text: String,
        path: String,
    ): String {
        val header = text.lineSequence().takeWhile { !it.trimStart().startsWith(RECORD_MARKER) }.toList()
        val declarations = header.filter { MARKER_PATTERN.containsMatchIn(it.trimStart()) }
        check(declarations.isNotEmpty()) {
            "evaluation dataset $path declares no `$MARKER <version>` line in its header. A frozen " +
                "set without a revision makes every accuracy figure unattributable — a number from " +
                "today cannot be compared with one from last month if the set may have changed. " +
                "Add the line above the first `$RECORD_MARKER` record, and bump it whenever a case " +
                "is added, removed or relabelled."
        }
        check(declarations.size == 1) {
            "evaluation dataset $path declares `$MARKER` more than once (${declarations.size} times). " +
                "Taking either silently would record the wrong revision against every future result."
        }
        val version = MARKER_PATTERN.replace(declarations.single().trimStart(), "").trim()
        check(version.isNotBlank()) { "evaluation dataset $path declares a blank `$MARKER`" }
        return version
    }

    /**
     * Loads a dataset's revision from the classpath.
     * Result: the declared version. Input: [owner] — any instance from the module holding the
     *         resource; [path] — an absolute classpath path. Output: [String].
     * Changelog: 2026-10-02 — Created for issue 12.2.
     */
    fun versionFrom(
        owner: Any,
        path: String,
    ): String {
        val stream =
            checkNotNull(owner.javaClass.getResourceAsStream(path)) {
                "evaluation dataset $path is missing from the test classpath"
            }
        return versionOf(stream.bufferedReader().use { it.readText() }, path)
    }
}

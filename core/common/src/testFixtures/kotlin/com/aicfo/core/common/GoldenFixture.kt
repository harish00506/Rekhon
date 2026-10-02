package com.aicfo.core.common

/**
 * The shared reader for engine golden files (issue 12.1; §21.5, P-08).
 *
 * Why:  §21.5 asks every engine for golden-file tests, and by issue 12.1 twenty-four of them had
 *       one — each with its own hand-written reader, because `:domain:*` has no serialisation
 *       dependency by design (ARC-002) and so cannot simply parse JSON. Twenty-four readers mean
 *       twenty-four chances to get wrong the one thing a test cannot check about itself: **that the
 *       fixture was actually read.** A typo'd resource path, an emptied file, or a `records()` that
 *       silently returns nothing turns a frozen gate into a loop over zero cases that passes in
 *       silence. That is the single most common way a test stops being a test, and this repository
 *       has found it five times in other guises.
 *
 *       So this reader refuses to be vacuous: a missing resource, an empty fixture and an
 *       unparseable line are each an error, not an empty list.
 * What: splits a fixture into `===`-delimited records of `# key=value` lines, with typed accessors.
 * Result: an engine inherits the guards instead of re-deriving them, and its test says what it
 *       asserts rather than how it reads a file.
 * Changelog: 2026-10-02 — Created for issue 12.1.
 *
 * **The format is text, not JSON, and deliberately so.** `:domain:*` is pure Kotlin with no
 * serialisation library (ARC-002), and a golden file is read by people far more often than by code —
 * so the format leaves room for the prose that explains what the numbers mean. Everything before the
 * first `===` is ignored for exactly that reason.
 *
 * Example:
 * ```
 * Amounts are PAISE (MNY-001). Ratios are basis points (MNY-002).
 *
 * === the statement day itself
 * # today=2026-03-01
 * # outstanding=5000000
 * # expect_utilisation_bps=2500
 * ```
 */
object GoldenFixture {
    /** Begins a record. Chosen because no `# key=value` line can start with it. */
    private const val RECORD_MARKER = "==="

    /** Prefixes an assertion line inside a record. */
    private const val FIELD_PREFIX = "# "

    /**
     * Loads a fixture from the calling test's own classpath.
     * Why:    `owner` rather than a `Class<*>` parameter so a test reads
     *         `GoldenFixture.load(this, "/golden/card.txt")` — the shortest call that still resolves
     *         against the right module's resources.
     * Result: one [GoldenRecord] per block, in file order, never empty.
     * Input:  [owner] — any instance from the module whose resources hold the fixture; [path] — an
     *         absolute classpath path, e.g. `/golden/card.txt`.
     * Output: the records.
     * Changelog: 2026-10-02 — Created for issue 12.1.
     *
     * Throws [IllegalStateException] when the resource is absent — which is the point. Returning an
     * empty list there would make every caller's golden test pass while asserting nothing.
     */
    fun load(
        owner: Any,
        path: String,
    ): List<GoldenRecord> {
        val stream =
            checkNotNull(owner.javaClass.getResourceAsStream(path)) {
                "golden fixture $path is missing from the test classpath — this gate would " +
                    "otherwise pass vacuously, asserting nothing at all"
            }
        return parse(stream.bufferedReader().use { it.readText() }, path)
    }

    /**
     * Parses fixture text that is already in hand.
     * Why:    separated from [load] so the parser is testable without a resource, and so a caller
     *         that generates a fixture (or reads one from somewhere else) is not forced through the
     *         classpath.
     * Result: the records. Input: [text] — the fixture's contents; [path] — only for error messages.
     * Output: the records, never empty.
     * Changelog: 2026-10-02 — Created for issue 12.1.
     *
     * Throws [IllegalStateException] on a fixture with no records, or on a line inside a record that
     * is not `# key=value`. A malformed line is **not** skipped: the fixture is hand-written, so a
     * line meant to be an assertion that cannot be read is a dropped expectation.
     */
    fun parse(
        text: String,
        path: String,
    ): List<GoldenRecord> {
        // A marker is a LINE that starts with `===`, not the substring anywhere. Splitting on the
        // substring was the first implementation and this harness's own sample fixture broke it: the
        // prose explaining the format mentions `===` inline, and two paragraphs became records. A
        // golden file is prose plus data, so the marker has to be anchored to the line.
        val records = mutableListOf<GoldenRecord>()
        var heading: String? = null
        val body = mutableListOf<String>()
        text.lineSequence().forEach { line ->
            if (line.trimStart().startsWith(RECORD_MARKER)) {
                heading?.let { records += recordFrom(it, body.toList(), path) }
                heading = line.trimStart().removePrefix(RECORD_MARKER).trim()
                body.clear()
            } else if (heading != null) {
                body += line
            }
        }
        heading?.let { records += recordFrom(it, body.toList(), path) }
        check(records.isNotEmpty()) {
            "golden fixture $path contains no records — every block starts with a line beginning " +
                "`$RECORD_MARKER`, and a fixture with none asserts nothing"
        }
        return records
    }

    /**
     * Turns one block's lines into a record.
     * Result: the record, with its heading and fields. Input: [heading] — the text after the marker;
     *         [lines] — the block's remaining lines; [path] — for errors. Output: a [GoldenRecord].
     * Changelog: 2026-10-02 — Created for issue 12.1.
     */
    private fun recordFrom(
        heading: String,
        lines: List<String>,
        path: String,
    ): GoldenRecord {
        val fields = mutableMapOf<String, String>()
        lines.map { it.trim() }.forEach { line ->
            if (line.isEmpty() || !line.startsWith(FIELD_PREFIX)) return@forEach
            val body = line.removePrefix(FIELD_PREFIX)
            check('=' in body) {
                "golden fixture $path, record \"$heading\": the line `$line` is not `# key=value`. " +
                    "A line that was meant to be an assertion and cannot be read is a dropped " +
                    "expectation, so this is an error rather than a skip."
            }
            fields[body.substringBefore('=').trim()] = body.substringAfter('=').trim()
        }
        return GoldenRecord(heading = heading, fields = fields.toMap())
    }
}

/**
 * One case from a golden file (issue 12.1).
 *
 * Why:  the typed accessors are the other half of what every engine re-implemented, and two of them
 *       encode a decision worth stating once: an absent key reads as `null`, never as `0`, because
 *       zero is a legitimate amount and conflating the two would let a dropped field pass as a
 *       deliberate zero — precisely the diff a golden file exists to show.
 * What: the heading, the parsed fields, and accessors that fail with the record's name.
 * Result: an engine's golden test reads as assertions rather than as string handling.
 * Changelog: 2026-10-02 — Created for issue 12.1.
 *
 * Input:  [heading] — the text after `===`, used to label failures; [fields] — the parsed pairs.
 * Output: an immutable value.
 */
data class GoldenRecord(
    val heading: String,
    val fields: Map<String, String>,
) {
    /** The keys this record carries, for a test that wants to assert the fixture's own shape. */
    val keys: Set<String> get() = fields.keys

    /**
     * A value that must be present.
     * Result: the raw string. Input: [key]. Output: [String].
     * Why:    the error names both the key and the record, because a bare "key not found" in a
     *         sixty-record fixture sends the reader hunting.
     * Changelog: 2026-10-02 — Created for issue 12.1.
     */
    fun required(key: String): String =
        checkNotNull(fields[key]) {
            "golden record \"${label()}\" has no `$key`. Present keys: ${fields.keys.sorted()}"
        }

    /**
     * A value that may be absent.
     * Result: the raw string, or `null`. Input: [key]. Output: `String?`.
     * Changelog: 2026-10-02 — Created for issue 12.1.
     */
    fun optional(key: String): String? = fields[key]

    /** Result: the value as paise or basis points (MNY-001/002). Input: [key]. Output: [Long]. */
    fun long(key: String): Long =
        required(key).toLongOrNull()
            ?: error("golden record \"${label()}\": `$key` is `${required(key)}`, which is not a Long")

    /** Result: the value as a [Long], or `null` when absent. Input: [key]. Output: `Long?`. */
    fun longOrNull(key: String): Long? = optional(key)?.toLongOrNull()

    /** Result: the value as an [Int] — a day of month, a count. Input: [key]. Output: [Int]. */
    fun int(key: String): Int =
        required(key).toIntOrNull()
            ?: error("golden record \"${label()}\": `$key` is `${required(key)}`, which is not an Int")

    /** Result: the value as an [Int], or `null` when absent. Input: [key]. Output: `Int?`. */
    fun intOrNull(key: String): Int? = optional(key)?.toIntOrNull()

    /**
     * Result: the value as a [Boolean]. Input: [key]. Output: [Boolean].
     * Why:    strict rather than `toBoolean()`, which maps every typo to `false` — so a fixture
     *         saying `ture` would silently assert the opposite of what it meant.
     */
    fun boolean(key: String): Boolean =
        when (val raw = required(key)) {
            "true" -> true
            "false" -> false
            else -> error("golden record \"${label()}\": `$key` is `$raw`, expected `true` or `false`")
        }

    /**
     * Result: a comma-separated value as a trimmed list; empty when the key is absent.
     * Why:    empty rather than an error, because "this record cites no rules" is a normal case and
     *         an empty list says it exactly. A *required* list uses `required(key)` and splits.
     * Input:  [key]; [separator] — `,` by default. Output: a list of non-blank entries.
     * Changelog: 2026-10-02 — Created for issue 12.1.
     */
    fun list(
        key: String,
        separator: Char = ',',
    ): List<String> =
        optional(key)
            .orEmpty()
            .split(separator)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * Result: the best name for this record in a failure message. Input: none. Output: [String].
     * Why:    prefers an explicit `label` field over the heading, since several existing fixtures
     *         already carry one and a test that adopts the harness should not have to rename it.
     */
    fun label(): String = fields["label"] ?: heading
}

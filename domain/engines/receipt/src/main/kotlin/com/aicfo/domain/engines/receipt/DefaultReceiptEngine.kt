package com.aicfo.domain.engines.receipt

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.core.model.RecognizedBlock
import java.time.LocalDate
import kotlin.math.abs

/**
 * The production [ReceiptEngine] — SRS §18.1's pipeline, literally (issue 3.8; FR-OCR-003, MNY-001).
 *
 * Why:  the whole parser turns on one judgement — **an amount is only a candidate if it is written
 *       like money**. The tempting shortcut is to take every number on the receipt and pick the
 *       largest, which is cheap and quietly disastrous: a bill printed `GST 18%` above `Bill No
 *       20260406` would then be read as a ₹20,260,406 purchase. Requiring a currency marker or two
 *       decimal places is what makes "the biggest number" mean "the biggest amount", and it is the
 *       difference between a parser and a random number generator with a review screen.
 * What: candidate amounts → scored against §18.1's keywords → total; Indian date formats → date;
 *       top-region text → merchant; GST lines → tax. Each with its own confidence.
 * Result: what the review screen pre-fills. Nothing is written and nothing is final (P-07).
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * `internal` per ARC-003 — constructed only by [ReceiptEngineFactory].
 *
 * **There is not a `Double` in this file** (MNY-001). Every amount comes out of
 * [MoneyFormatter.parse], which refuses anything not exactly representable in paise rather than
 * rounding it, and every confidence is integer basis points (MNY-002).
 *
 * **No merchant knowledge base**, though §18.1 mentions matching one. `merchant_id` and the alias
 * table belong to issue 4.1; guessing a canonical name here would put a payee on the user's row that
 * they never saw on their receipt, which is worse than leaving the field as printed.
 */
internal class DefaultReceiptEngine : ReceiptEngine {
    override fun extract(input: ReceiptInput): Result<ReceiptFields, AppError> =
        runCatchingToResult {
            // Parsed first, and outside every field: a caller that supplied a malformed today is a
            // programming error, and it should fail as one rather than silently disabling the date
            // rule while the other three fields quietly succeed.
            val today = LocalDate.parse(input.todayIsoDate)
            val lines = logicalLines(input.text.blocks, input.rules.sameRowBps)
            val total = findTotal(lines, input.rules)
            ReceiptFields(
                total = total,
                date = findDate(lines, today),
                merchant = findMerchant(input.text.blocks, input.rules),
                tax = findTax(lines, input.rules),
                provenance =
                    EngineProvenance(
                        engineId = ENGINE_ID,
                        engineVersion = ENGINE_VERSION,
                        computedAtUtcMillis = input.nowUtcMillis,
                        evidence = listOf(ReceiptRules.FIELD_EXTRACT),
                        inputWindow = input.todayIsoDate,
                        // The receipt's overall confidence *is* the total's: §18.1 sets one numeric
                        // target and it is total-amount accuracy. A parse that read the shop name
                        // and missed the amount has not read the receipt.
                        confidenceBps = total?.confidenceBps ?: 0,
                    ),
            )
        }

    /**
     * Picks the payable total (§18.1: "max plausible candidate near keywords").
     *
     * Why:    three tiers, tried in order, and the order is the argument. **A currency-formatted
     *         amount on a keyword line** is the receipt telling us the answer — `GRAND TOTAL
     *         1,240.00` — and the largest such amount wins even when the line also prints a
     *         subtotal. Failing that, **a bare integer on a keyword line**, because plenty of tills
     *         print `TOTAL 1240` with no paise and the keyword is itself the proof that the number
     *         is money; this tier is second rather than merged into the first so a receipt that
     *         prints both is never decided by a count of items. Last, **the largest amount
     *         anywhere**, which is right surprisingly often and wrong often enough that its
     *         confidence sits **below the rulebook's flag floor on purpose**, so FR-OCR-004's review
     *         screen marks it for the user rather than presenting a guess as a reading.
     * Result: the total as a positive magnitude, or `null` when the receipt held no amount at all.
     *         Positive because the expense/income sign is the capture screen's to apply — the same
     *         split `TransactionDraft` already makes for a typed transaction.
     * Input:  [lines] — the trimmed non-empty lines; [rules] — the rulebook thresholds.
     * Output: `ExtractedMoney?`.
     * Changelog: 2026-08-06 — Created for issue 3.8.
     */
    @Suppress("ReturnCount") // One early return per tier — see the doc; folding them into a single
    // expression would hide which tier produced the figure the user is being shown.
    private fun findTotal(
        lines: List<String>,
        rules: ReceiptRules,
    ): ExtractedMoney? {
        val keyed = lines.filter { line -> rules.totalKeywords.any { it in line.lowercase() } }
        val marked = keyed.flatMap { it.amounts() }
        if (marked.isNotEmpty()) {
            // One figure beside a keyword is a clean read; several mean the receipt printed a
            // subtotal, a tax line and a total that all matched, so the pick is less certain.
            val confidence = if (marked.distinct().size == 1) KEYWORD_CONFIDENCE else CROWDED_CONFIDENCE
            return ExtractedMoney(marked.max(), confidence)
        }
        val bare = keyed.flatMap { it.bareAmounts() }.maxOrNull()
        if (bare != null) return ExtractedMoney(bare, BARE_KEYWORD_CONFIDENCE)
        val anywhere = lines.flatMap { it.amounts() }.maxOrNull() ?: return null
        return ExtractedMoney(anywhere, FALLBACK_CONFIDENCE)
    }

    /**
     * Finds the receipt date, Indian formats first (§18.1).
     *
     * Why:    **`dd/mm` before `mm/dd`, deliberately.** `03/04/2026` is 3 April in every shop in
     *         India and 4 March in the convention most OCR training data comes from, and this app is
     *         India-first (§1). Getting it backwards would silently file a purchase in the wrong
     *         month, which the user would never notice and which would then move a budget.
     *
     *         **A date after today is rejected rather than accepted with low confidence.** A receipt
     *         cannot be from the future, so a future reading is not an uncertain date — it is the
     *         wrong text, usually a bill number or an expiry. Returning it "with low confidence"
     *         would pre-fill a field FR-TXN-010 treats as a *scheduled* transaction.
     * Result: the date as ISO `yyyy-MM-dd` (TIM-002), or `null` when no line held a plausible one.
     * Input:  [lines] — the trimmed lines; [today] — the profile-zone day, passed in (TIM-001).
     * Output: `ExtractedText?`.
     * Changelog: 2026-08-06 — Created for issue 3.8.
     */
    private fun findDate(
        lines: List<String>,
        today: LocalDate,
    ): ExtractedText? =
        lines.firstNotNullOfOrNull { line ->
            NUMERIC_DATE.find(line)?.let { match -> numericDate(match, today) }
        }

    /**
     * Reads one `dd/mm/yy(yy)` match, or refuses it.
     * Why:    split out so [findDate] stays a scan and every reason to refuse a match is in one
     *         place: an impossible calendar date (`31/02`), a date in the future, or a two-digit year
     *         that resolves past today. `LocalDate.of` throws on the first, which is caught here
     *         rather than escaping to [extract]'s `runCatchingToResult` — a bill number that happens
     *         to look like `45/13/99` must not fail the whole parse.
     * Result: the date and its confidence, or `null` to keep scanning the next line.
     * Input:  [match] — a [NUMERIC_DATE] match; [today]. Output: `ExtractedText?`.
     * Changelog: 2026-08-06 — Created for issue 3.8.
     */
    private fun numericDate(
        match: MatchResult,
        today: LocalDate,
    ): ExtractedText? {
        val (dayText, monthText, yearText) = match.destructured
        val year = fullYear(yearText.toInt(), today)
        // The two refusals read as one expression: an impossible calendar date and a date in the
        // future are both "keep scanning the next line", not "fail the parse".
        val parsed =
            runCatching { LocalDate.of(year, monthText.toInt(), dayText.toInt()) }
                .getOrNull()
                ?.takeUnless { it.isAfter(today) }
                ?: return null
        // A leading component over 12 can only be a day, so the dd/mm reading is not an assumption.
        val unambiguous = dayText.toInt() > MONTHS_IN_YEAR
        val confidence =
            DATE_BASE_CONFIDENCE +
                (if (yearText.length == FULL_YEAR_DIGITS) FULL_YEAR_BONUS else 0) +
                (if (unambiguous) UNAMBIGUOUS_DAY_BONUS else 0)
        return ExtractedText(parsed.toString(), confidence.coerceAtMost(BPS_FULL))
    }

    /**
     * Takes the merchant from the top of the image (§18.1: "merchant = top-region text").
     *
     * Why:    a receipt is laid out the same way everywhere: the shop's name is printed largest, at
     *         the top, above the address. That is the whole heuristic, and it is why
     *         [RecognizedBlock.topFraction] exists at all. The band comes from the rulebook rather
     *         than being a constant here, because "how far down is the top" is exactly the kind of
     *         number CLAUDE.md §6 says belongs in `ai/`.
     *
     *         **A line that is itself an amount or a date is skipped.** Some receipts print the bill
     *         number or the till time above the name, and a merchant field reading `04/06/2026` is
     *         worse than an empty one — the user would have to notice it was wrong before correcting
     *         it, where an empty field asks.
     * Result: the shop name as printed, or `null` when the top region held nothing usable.
     * Input:  [blocks] — every recognised block; [rules] — the top-region band.
     * Output: `ExtractedText?`.
     * Changelog: 2026-08-06 — Created for issue 3.8.
     */
    private fun findMerchant(
        blocks: List<RecognizedBlock>,
        rules: ReceiptRules,
    ): ExtractedText? {
        val block =
            blocks
                .filter { it.topFraction <= rules.merchantTopRegionBps }
                .minByOrNull { it.topFraction } ?: return null
        val name =
            block.text.lines()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && it.amounts().isEmpty() && NUMERIC_DATE.find(it) == null }
                ?: return null
        // Falls linearly from certain at the very top to half-certain at the edge of the band: a
        // name printed lower is likelier to be the address line under it. `+1` so a band of zero
        // — a rulebook edit meaning "the very top line only" — is not a division by zero.
        val depth = block.topFraction * (BPS_FULL / 2) / (rules.merchantTopRegionBps + 1)
        return ExtractedText(name, (BPS_FULL - depth).coerceIn(0, BPS_FULL))
    }

    /**
     * Reads the GST component, best-effort (§18.1; FR-OCR-003 calls tax best-effort).
     *
     * Why:    Indian receipts print tax in one of two shapes and they must not be added together.
     *         An intra-state bill splits it — `CGST 9% 40.50` and `SGST 9% 40.50` — while an
     *         inter-state one prints a single `IGST`/`GST` line. Summing the split pair is the
     *         correct total tax; summing *everything* on a receipt that also prints a `GST` summary
     *         line would count the same rupees twice and hand the user a tax figure larger than the
     *         bill's own.
     * Result: the tax as a positive magnitude, or `null` when no tax line carried an amount.
     * Input:  [lines] — the trimmed lines; [rules] — the tax keywords. Output: `ExtractedMoney?`.
     * Changelog: 2026-08-06 — Created for issue 3.8.
     */
    private fun findTax(
        lines: List<String>,
        rules: ReceiptRules,
    ): ExtractedMoney? {
        // mapNotNull, so a `CGST` line that carried no readable figure falls through to the single
        // branch rather than being counted as a zero-rupee levy.
        val split =
            lines.filter { line -> SPLIT_TAX_KEYWORDS.any { it in line.lowercase() } }
                .mapNotNull { it.amounts().maxOrNull() }
        if (split.isNotEmpty()) return ExtractedMoney(split.reduce(Money::plus), SPLIT_TAX_CONFIDENCE)
        val single =
            lines.filter { line -> rules.taxKeywords.any { it in line.lowercase() } }
                .flatMap { it.amounts() }
                .maxOrNull()
        return single?.let { ExtractedMoney(it, SINGLE_TAX_CONFIDENCE) }
    }

    private companion object {
        /** Stable identifier stored with every extraction (AI-ARC-003). */
        const val ENGINE_ID = "receipt-parser"

        /**
         * Bump whenever the heuristics change (AI-ARC-006).
         *
         * Stored with every extraction, so a transaction saved from a receipt today can still be
         * explained after the parser is rewritten — which is the whole reason the field exists.
         */
        const val ENGINE_VERSION = "1.0"

        /** One keyword line, one amount on it: the receipt said what the total is. */
        const val KEYWORD_CONFIDENCE = 9_500

        /** Several amounts sat beside a keyword — subtotal, tax and total all matched. */
        const val CROWDED_CONFIDENCE = 7_500

        /** `TOTAL 1240` — no paise printed, so the keyword is the only thing making it money. */
        const val BARE_KEYWORD_CONFIDENCE = 7_000

        /**
         * No keyword line carried an amount, so this is the largest amount anywhere.
         *
         * **Deliberately below `ReceiptRules.lowConfidenceBps` (6 000)**, so FR-OCR-004's review
         * screen flags it. A guess presented as a reading is the failure mode this whole issue is
         * arranged to avoid.
         */
        const val FALLBACK_CONFIDENCE = 4_000

        /** CGST + SGST, added: the split shape, and the one this can be sure it summed correctly. */
        const val SPLIT_TAX_CONFIDENCE = 8_000

        /** A single GST line — right whenever the receipt did not also print a split. */
        const val SINGLE_TAX_CONFIDENCE = 7_000

        /** A date that parsed at all, before the format bonuses. */
        const val DATE_BASE_CONFIDENCE = 7_000

        /** A four-digit year needed no century guessed for it. */
        const val FULL_YEAR_BONUS = 2_000

        /** A leading component over 12 can only be a day, so dd/mm was not an assumption. */
        const val UNAMBIGUOUS_DAY_BONUS = 1_000

        /** Above this, a date's first component cannot be a month. */
        const val MONTHS_IN_YEAR = 12

        /** `2026` rather than `26`. */
        const val FULL_YEAR_DIGITS = 4

        /** Tax keywords whose amounts are *added*; see [findTax]. */
        val SPLIT_TAX_KEYWORDS = listOf("cgst", "sgst")
    }
}

/**
 * `dd/mm/yy` and `dd-mm-yyyy` and `dd.mm.yyyy`, the formats printed in India (§18.1).
 *
 * Why:  the separators are the three that appear on real receipts, and the year is 2 **or** 4 digits
 *       because both are printed. The surrounding `(?<![0-9])` / `(?![0-9])` guards are load-bearing:
 *       without them a bill number like `1234/5678/90` would match from its middle and produce a
 *       date the receipt never contained.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
private val NUMERIC_DATE = Regex("""(?<![0-9])([0-9]{1,2})[/.\-]([0-9]{1,2})[/.\-]([0-9]{2}|[0-9]{4})(?![0-9])""")

/**
 * An amount with a currency marker: `₹1,240.00`, `Rs 1240`, `INR 1,240.50`.
 * Why:  the marker is proof the number is money, so no decimal places are required — `Rs 1240` is an
 *       amount and `1240` on its own is not.
 */
private val MARKED_AMOUNT =
    Regex("""(?<![a-z])(?:₹|rs\.?|inr)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)

/**
 * A bare amount written with paise: `1,240.00` — or `365.8`, which is the same thing.
 *
 * Why:  a decimal point is the other way a receipt says "this is money". **One decimal digit is
 *       accepted as well as two**, and that is not laxity: ML Kit routinely returns `365.8` for a
 *       printed `365.80` when the last glyph is clipped or merged, and `MoneyFormatter.parse` pads
 *       it to ₹365.80 exactly — a single decimal digit is *tens* of paise, not a rounding guess.
 *       Found on the device run against a real recognition, not reasoned about in advance.
 *
 *       The guards on both sides carry the whole cost of that relaxation. `(?![0-9%.,/\-])` keeps
 *       `18%` out and — the case one digit newly exposes — keeps `04.08.2026` from reading as
 *       ₹4.08, because the match would be followed by another separator. The leading guard stops a
 *       match starting in the middle of a longer number or a date.
 */
private val DECIMAL_AMOUNT = Regex("""(?<![0-9.,/\-])([0-9][0-9,]*\.[0-9]{1,2})(?![0-9%.,/\-])""")

/** See [String.bareAmounts] — a whole number, used only where a total keyword already vouched for it. */
private val BARE_AMOUNT = Regex("""(?<![0-9.,])([0-9][0-9,]*)(?![0-9.,%])""")

/**
 * Turns recognised blocks into the lines the heuristics read (issue 3.8; §18.1).
 *
 * Why:    **a printed receipt is a table, and ML Kit returns its cells, not its rows.** The device
 *         run against a real photograph is what showed this: a bill reading `GRAND TOTAL   365.80`
 *         came back as two separate blocks — `GRAND TOTAL` and `365.8` — sitting at the same height.
 *         Every line-based heuristic here then saw a keyword with no amount beside it and fell
 *         through to "the largest number anywhere", which on that receipt was an item price. The
 *         same split hid the GST line.
 *
 *         So a **single-line block is joined with the other single-line blocks on its row**, where a
 *         row is `|Δ topFraction| ≤ same_row_bps` from the rulebook. That is what §18.1's "near
 *         keywords" means on a two-column layout, and it is the reason `RecognizedBlock` carries a
 *         position at all.
 *
 *         **A multi-line block is left exactly as it is.** Its own lines already carry the receipt's
 *         structure, and joining them by height would collapse a whole block — every line of which
 *         shares one `topFraction` — into a single line, so the label of one row would end up beside
 *         the amount of another.
 * Result: the lines to scan, trimmed and non-empty.
 * Input:  [blocks] — as recognised; [sameRowBps] — the row band. Output: `List<String>`.
 * Changelog: 2026-08-06 — Created for issue 3.8, after the emulator run read an item price as the
 *            total on a two-column receipt.
 */
private fun logicalLines(
    blocks: List<RecognizedBlock>,
    sameRowBps: Int,
): List<String> {
    val positioned = mutableListOf<Pair<Int, String>>()
    blocks.filter { it.text.lines().size > 1 }
        .forEach { block -> block.text.lines().forEach { positioned += block.topFraction to it } }
    // Grouped, not "every block plus its neighbours": pairing each cell with its row separately
    // would emit the same row once per cell, and `findTax` — which *adds* the CGST and SGST lines —
    // would then double the levy.
    groupIntoRows(blocks.filter { it.text.lines().size == 1 }, sameRowBps)
        .forEach { row -> positioned += row.first().topFraction to row.joinToString(" ") { it.text } }
    return positioned
        // Sorted by height, so the lines arrive in reading order however the recogniser ordered its
        // blocks — which matters because `findDate` takes the *first* plausible date it sees.
        // Kotlin's sort is stable, so a multi-line block's own lines keep their order.
        .sortedBy { it.first }
        .map { it.second.trim() }
        .filter { it.isNotEmpty() }
}

/**
 * Groups single-line blocks into printed rows (issue 3.8).
 * Why:    a greedy sweep down the page rather than a clustering algorithm: receipt rows are far
 *         apart relative to the band, so "is this cell within `sameRowBps` of the row I am building?"
 *         is the whole decision. Measured against the row's **first** member rather than its last, so
 *         a column of tightly spaced cells cannot chain into one enormous row.
 * Result: the rows, top first, each with at least one cell.
 * Input:  [cells] — single-line blocks; [sameRowBps] — the band. Output: `List<List<RecognizedBlock>>`.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
private fun groupIntoRows(
    cells: List<RecognizedBlock>,
    sameRowBps: Int,
): List<List<RecognizedBlock>> {
    val rows = mutableListOf<MutableList<RecognizedBlock>>()
    cells.sortedBy { it.topFraction }.forEach { cell ->
        val open = rows.lastOrNull()
        if (open != null && abs(cell.topFraction - open.first().topFraction) <= sameRowBps) {
            open += cell
        } else {
            rows += mutableListOf(cell)
        }
    }
    return rows
}

/**
 * Every currency-formatted amount on one line (§18.1: "candidate totals = currency-formatted
 * numbers").
 *
 * Why:    **this is the parser's single most important line.** §18.1 says candidates are
 *         currency-formatted numbers, and honouring that literally — a currency marker, or exactly
 *         two decimal places — is what stops a bill number, a phone number, a GSTIN or a percentage
 *         from being read as an amount. Parsing goes through [MoneyFormatter.parse], which already
 *         refuses anything not exactly representable in paise and never rounds (MNY-001), so this
 *         function contributes no arithmetic of its own.
 * Result: the amounts found, in the order they appear; empty for a line with no money on it.
 * Input:  the receiver — one line of recognised text. Output: `List<Money>`.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
private fun String.amounts(): List<Money> =
    (MARKED_AMOUNT.findAll(this) + DECIMAL_AMOUNT.findAll(this))
        .map { it.groupValues[1] }
        .mapNotNull(MoneyFormatter::parse)
        .filter { it > Money.ZERO }
        .toList()

/**
 * A bare whole number written like rupees: `1240`, `1,240`.
 *
 * Why:  used **only on a line that already matched a total keyword**, which is what makes it safe —
 *       on any other line this would match a phone number, a bill number or a quantity. The trailing
 *       `(?![0-9.,%])` keeps it from claiming the integer part of `1240.50` (that is
 *       [DECIMAL_AMOUNT]'s) or the `18` of `18%`.
 * Result: the whole-rupee amounts on the line. Input: the receiver. Output: `List<Money>`.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
private fun String.bareAmounts(): List<Money> =
    BARE_AMOUNT.findAll(this)
        .map { it.groupValues[1] }
        .mapNotNull(MoneyFormatter::parse)
        .filter { it > Money.ZERO }
        .toList()

/**
 * Resolves a printed year to a full one.
 * Why:    `26` on a receipt means 2026, not 26 AD — but "add 2000" is only right until it is not, so
 *         the century is taken from the day the scan happened (TIM-001's injected clock, passed down
 *         as `today`) rather than hardcoded. A two-digit year that lands in the future is pulled back
 *         a century, which is what makes `31/12/26` scanned in January 2027 read as 2026 rather than
 *         as a date the [numericDate] guard would then throw away.
 * Result: the four-digit year. Input: [printed] — as printed; [today]. Output: [Int].
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
private fun fullYear(
    printed: Int,
    today: LocalDate,
): Int {
    if (printed >= YEARS_IN_CENTURY) return printed
    val century = today.year / YEARS_IN_CENTURY * YEARS_IN_CENTURY
    val candidate = century + printed
    return if (candidate > today.year) candidate - YEARS_IN_CENTURY else candidate
}

/** Below this a printed year is two digits and needs a century. */
private const val YEARS_IN_CENTURY = 100

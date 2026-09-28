package com.aicfo.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What must be true of every translation the app ships (issue 10.8; §21.6, NFR-011, SRS §3.5).
 *
 * Why:  three things go wrong with translations, and only the first is visible by reading the app.
 *       **A missing key** falls back to English, so a Hindi user meets an English sentence in the
 *       middle of their own screen — survivable, and invisible to anyone testing in English. **A
 *       dropped format argument** is not survivable: `getString(R.string.x, a, b)` against a
 *       translation that kept only `%1$s` throws `MissingFormatArgumentException` on the user's
 *       device and nowhere else. **A missing plural category** silently renders the wrong sentence
 *       for the wrong quantity. None of the three is caught by a compiler, and the last two are not
 *       caught by Android lint either — so they are caught here, over the files that actually ship.
 * What: the file set, key coverage both ways, format-argument parity, plural categories, and
 *       translations that are still English.
 * Result: a new screen cannot ship with `values/` alone, and a translated sentence cannot crash.
 * Changelog: 2026-09-28 — Created for issue 10.8.
 */
class TranslationCoverageTest {
    @Test
    fun `every module with user-visible strings ships a file for every locale`() {
        val missing =
            StringCatalogue.modules.flatMap { module ->
                StringCatalogue.SHIPPED_LOCALES
                    .filterNot { StringCatalogue.file(module, it).isFile }
                    .map { "${StringCatalogue.label(module)} has no values-$it/strings.xml" }
            }

        assertEquals("a module shipping English only reaches a Hindi user in English", emptyList<String>(), missing)
    }

    @Test
    fun `every English key is translated into every locale`() {
        val gaps =
            eachLocale { module, locale, english, translated ->
                (english.keys - translated.keys).map { "${StringCatalogue.label(module)} · $locale · $it" }
            }

        assertEquals("untranslated keys fall back to English mid-screen", emptyList<String>(), gaps)
    }

    @Test
    fun `no translation carries a key the English file does not`() {
        // A leftover key is a string nobody can see and nobody will maintain — and, more often, the
        // sign that a key was renamed in English and the rename never reached the translations.
        val strays =
            eachLocale { module, locale, english, translated ->
                (translated.keys - english.keys).map { "${StringCatalogue.label(module)} · $locale · $it" }
            }

        assertEquals(emptyList<String>(), strays)
    }

    @Test
    fun `every translation takes exactly the format arguments its English original does`() {
        // The check that stops a crash. Positional arguments may be **reordered** — that is what
        // they are for, and Hindi, Kannada and Tamil all put the verb last — so what must match is
        // the set of arguments used, never their order.
        val mismatches =
            eachLocale { module, locale, english, translated ->
                english.keys.intersect(translated.keys).mapNotNull { key ->
                    val expected = formatArguments(english.getValue(key).texts)
                    val actual = formatArguments(translated.getValue(key).texts)
                    if (expected == actual) {
                        null
                    } else {
                        "${StringCatalogue.label(module)} · $locale · $key: expected $expected, found $actual"
                    }
                }
            }

        assertEquals("a dropped argument throws on the user's device, not here", emptyList<String>(), mismatches)
    }

    @Test
    fun `every plural declares the categories its language needs, and no others`() {
        val wrong =
            eachLocale { module, locale, english, translated ->
                english.keys.intersect(translated.keys).mapNotNull { key ->
                    val entry = translated.getValue(key) as? StringCatalogue.Entry.Plural ?: return@mapNotNull null
                    val required = REQUIRED_PLURAL_CATEGORIES.getValue(locale)
                    if (entry.byQuantity.keys == required) {
                        null
                    } else {
                        "${StringCatalogue.label(module)} · $locale · $key: ${entry.byQuantity.keys} != $required"
                    }
                }
            }

        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `a plurals in English stays a plurals in every language, and a string stays a string`() {
        val mixed =
            eachLocale { module, locale, english, translated ->
                english.keys.intersect(translated.keys).mapNotNull { key ->
                    val same = english.getValue(key)::class == translated.getValue(key)::class
                    if (same) null else "${StringCatalogue.label(module)} · $locale · $key changed shape"
                }
            }

        assertEquals(
            "a <string> where the code calls getQuantityString is a runtime failure",
            emptyList<String>(),
            mixed,
        )
    }

    @Test
    fun `no sentence is left in English, unless it has nothing to translate`() {
        // The check that catches a file copied and not finished, and the one that needs an honest
        // definition of "nothing to translate": see [hasNothingToTranslate].
        val untranslated =
            eachLocale { module, locale, english, translated ->
                english.keys.intersect(translated.keys).mapNotNull { key ->
                    val source = english.getValue(key).texts.joinToString("\u0000")
                    val target = translated.getValue(key).texts.joinToString("\u0000")
                    val exempt = key in NAMES || hasNothingToTranslate(source)
                    if (exempt || source != target) {
                        null
                    } else {
                        "${StringCatalogue.label(module)} · $locale · $key is still English"
                    }
                }
            }

        assertEquals(emptyList<String>(), untranslated)
    }

    @Test
    fun `every shipped locale is declared in locales_config, and nothing else is`() {
        // Without this file Android 13+ offers no per-app language, and a locale left out of it is
        // one the system will never switch to however complete its strings are.
        val declared =
            LOCALE_TAG
                .findAll(StringCatalogue.root.resolve(LOCALES_CONFIG).takeIf { it.isFile }?.readText().orEmpty())
                .map { it.groupValues[1] }
                .toList()

        assertEquals(listOf(BASE_LOCALE) + StringCatalogue.SHIPPED_LOCALES, declared)
    }

    @Test
    fun `the catalogue is reading the whole app, not an empty directory`() {
        // Every check above passes vacuously against an empty list. This one fails if the discovery
        // itself breaks — the way a drift test is worthless when its file is not an input.
        assertTrue("found only ${StringCatalogue.modules.size} modules", StringCatalogue.modules.size >= KNOWN_MODULES)
        assertTrue(
            "the dashboard's strings were not found",
            StringCatalogue.modules.any { StringCatalogue.label(it) == ":feature:dashboard" },
        )
    }

    // --- helpers -----------------------------------------------------------------------------------

    /**
     * Runs one check over every module × locale.
     * Why:    each test below differs only in what it compares; the walk is the same and is written
     *         once so a new check cannot accidentally cover fewer modules.
     * Result: every complaint from every module, so one run names all of them rather than the first.
     * Input:  [check] — module, locale, the English entries, the translated entries.
     * Output: `List<String>` — the complaints.
     */
    private fun eachLocale(
        check: (File, String, Map<String, StringCatalogue.Entry>, Map<String, StringCatalogue.Entry>) -> List<String>,
    ): List<String> =
        StringCatalogue.modules.flatMap { module ->
            val english = StringCatalogue.parse(StringCatalogue.file(module, null))
            StringCatalogue.SHIPPED_LOCALES.flatMap { locale ->
                check(module, locale, english, StringCatalogue.parse(StringCatalogue.file(module, locale)))
            }
        }

    /**
     * Whether a value is the same string in every language by construction.
     * Why:    three kinds of value are identical in Hindi, Kannada and Tamil not because someone
     *         forgot to translate them, but because there is nothing there to translate: a pure
     *         format string (`%1$s%%`), an acronym the languages borrow as it stands (`PIN`), and
     *         a rule id quoted as evidence (`RULE-PAY-FIRST v1.0`). Written as a rule rather than
     *         a list of keys, so the next screen that cites a rule does not have to amend a list —
     *         and it stays strict, because a real sentence always has a lowercase letter in it.
     * What:   removes the format specifiers and any version token, then asks whether any lowercase
     *         letter is left.
     * Result: `true` when the value carries no prose. Input: [source]. Output: [Boolean].
     */
    private fun hasNothingToTranslate(source: String): Boolean =
        source
            // The version marker first: in `%1$s v%2$s` the `v` is only recognisable while the
            // specifier it introduces is still there.
            .replace(VERSION, "")
            .replace(SPECIFIER, "")
            .none { it.isLetter() && it.isLowerCase() }

    /**
     * The format arguments a set of sentences consumes.
     * Why:    what must match between a sentence and its translation is *which* arguments are read
     *         and how each is converted — never the order they appear in.
     * What:   `%1$s` becomes `1:s`; a bare `%d` is numbered by the order it appears, which is how
     *         `String.format` reads it; `%%` is a literal percent sign and consumes nothing.
     * Result: a set that is equal across a faithful translation and unequal across a broken one.
     * Input:  [texts] — every sentence of one entry. Output: `Set<String>`.
     */
    private fun formatArguments(texts: Collection<String>): Set<String> =
        texts
            .flatMap { text ->
                var implicit = 0
                SPECIFIER.findAll(text).mapNotNull { match ->
                    val conversion = match.groupValues[2]
                    if (conversion == "%") {
                        null
                    } else {
                        val position = match.groupValues[1].removeSuffix("$").ifEmpty { (++implicit).toString() }
                        "$position:$conversion"
                    }
                }.toList()
            }.toSet()

    private companion object {
        /**
         * `%%`, `%s`, `%d`, `%1$s`, `%2$d`, and the flagged forms this app uses for money and
         * rates — `%2$02d`. The flags matter here only so that the conversion is still found.
         */
        val SPECIFIER = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?([sdf%])""")
        val LOCALE_TAG = Regex("""<locale +android:name="([^"]+)" *\/>""")

        /** `v1.0`, and the bare `v` in `%1$s v%2$s` — a version marker quoted beside a rule id. */
        val VERSION = Regex("""\bv(?=[\d%])\d*(\.\d+)*""")
        const val LOCALES_CONFIG = "app/src/main/res/xml/locales_config.xml"
        const val BASE_LOCALE = "en"

        /**
         * The plural categories each shipped language actually uses (CLDR).
         * Why:    written down rather than taken from the English file, because that is the whole
         *         point — a language whose rules differ from English's must not inherit English's
         *         categories. Hindi, Kannada and Tamil each select `one` and `other` and nothing
         *         else, so today the sets match; a fourth language may not, and this map is where
         *         that is stated.
         */
        val REQUIRED_PLURAL_CATEGORIES =
            mapOf(
                "hi" to setOf("one", "other"),
                "kn" to setOf("one", "other"),
                "ta" to setOf("one", "other"),
            )

        /** Names, not sentences: the same in every language. */
        val NAMES =
            setOf(
                // The app's own name, in the launcher and in the widget picker.
                "app_name",
                "widget_title",
                // A date mask, not prose: the field it labels is parsed as ISO, so translating
                // the letters would tell the user to type something the app would reject.
                "receipt_date_hint",
                // Language names are written in their own script in every language's file: that is
                // how someone finds their language in a list they cannot otherwise read.
                "settings_language_english",
                "settings_language_hindi",
                "settings_language_kannada",
                "settings_language_tamil",
            )

        /** The modules that owned strings when this was written — a floor, never a ceiling. */
        const val KNOWN_MODULES = 17
    }
}

package com.aicfo.app.i18n

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every `strings.xml` in the repository, read as data (issue 10.8; §21.6, NFR-011).
 *
 * Why:  a translation gap is invisible until someone running the app in Hindi hits an English
 *       sentence, and a *format* gap — `%2$s` dropped from a translated sentence — is invisible
 *       until it throws on their device. Both are checked here rather than by reading files,
 *       because the check has to cover **every module that exists now or later**: a new feature
 *       shipping with no `values-hi` is exactly the failure this catches, and it can only catch it
 *       by discovering modules instead of being told about them.
 * What: finds the repository root, lists the modules that own user-visible strings, and parses each
 *       one's base and translated files into comparable entries.
 * Result: the tests below argue about maps, not about XML.
 * Changelog: 2026-09-28 — Created for issue 10.8.
 */
internal object StringCatalogue {
    /** The locales the app ships, in the order the picker lists them (SRS §3.5: Hindi/Kannada/Tamil). */
    val SHIPPED_LOCALES = listOf("hi", "kn", "ta")

    /**
     * The repository root, found by walking up from the module the test runs in.
     * Why:    Gradle runs a unit test with the *module* directory as the working directory, and
     *         hardcoding `../` would break the moment this test moved module. `settings.gradle.kts`
     *         is the one file that exists exactly once, at the root.
     * Result: the root directory. Input: none. Output: [File].
     */
    val root: File by lazy {
        val here = checkNotNull(System.getProperty("user.dir")) { "no working directory" }
        generateSequence(File(here).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("could not find the repository root from $here")
    }

    /**
     * Every module that owns user-visible strings.
     * Why:    discovered rather than listed, so a module added next year is covered without anyone
     *         remembering to add it here.
     * What:   any directory holding `src/main/res/values/strings.xml`, ignoring build outputs.
     * Result: the module directories, sorted, so a failure message reads the same way twice.
     * Input:  none. Output: `List<File>` — module directories relative to nothing, absolute paths.
     */
    val modules: List<File> by lazy {
        root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" }
            .filter { it.isFile && it.name == "strings.xml" && it.parentFile?.name == "values" }
            // <module>/src/main/res/values/strings.xml — five levels up is the module.
            .mapNotNull { it.parentFile?.parentFile?.parentFile?.parentFile?.parentFile }
            .filter { it.resolve("src/main/res/values/strings.xml").isFile }
            .distinct()
            .sortedBy { it.path }
            .toList()
    }

    /**
     * Result: the module's path as a person would name it, e.g. `:feature:market`.
     * Input: [module]. Output: [String].
     */
    fun label(module: File): String = ":" + module.relativeTo(root).path.replace(File.separatorChar, ':')

    /** Result: where a locale's file would live. Input: [module]; [locale] — `null` for English. Output: [File]. */
    fun file(
        module: File,
        locale: String?,
    ): File = module.resolve("src/main/res/values${locale?.let { "-$it" } ?: ""}/strings.xml")

    /**
     * Parses one `strings.xml`.
     * Why:    a regex over XML would disagree with the resource compiler on exactly the entries
     *         worth checking — the escaped ones. `DocumentBuilder` is what aapt effectively does.
     * Result: key → entry, in file order; an absent file reads as no entries rather than throwing,
     *         because "this locale has no file at all" is a failure a *test* should report, not a
     *         crash inside the reader.
     * Input:  [file]. Output: `Map<String, Entry>`.
     */
    fun parse(file: File): Map<String, Entry> {
        if (!file.isFile) return emptyMap()
        val document =
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(file)
        val entries = LinkedHashMap<String, Entry>()
        val resources = document.documentElement
        val children = resources.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index) as? Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> entries[name] = Entry.Plain(node.textContent)
                "plurals" -> entries[name] = Entry.Plural(quantities(node))
            }
        }
        return entries
    }

    /** Result: a plurals element's `quantity` → text. Input: [node]. Output: `Map<String, String>`. */
    private fun quantities(node: Element): Map<String, String> {
        val items = node.getElementsByTagName("item")
        return (0 until items.length)
            .map { items.item(it) as Element }
            .associate { it.getAttribute("quantity") to it.textContent }
    }

    /**
     * One translatable resource.
     * Why:    a `<string>` and a `<plurals>` fail differently — a plural can be complete and still
     *         be missing the category its language needs — so they are different shapes here rather
     *         than one map of strings.
     * Changelog: 2026-09-28 — Created for issue 10.8.
     */
    sealed interface Entry {
        /** Every piece of text the entry would put on screen. */
        val texts: Collection<String>

        /** A single sentence. Input: [text]. Output: the entry. */
        data class Plain(
            val text: String,
        ) : Entry {
            override val texts: Collection<String> get() = listOf(text)
        }

        /** A quantity-dependent sentence. Input: [byQuantity] — CLDR category → text. Output: the entry. */
        data class Plural(
            val byQuantity: Map<String, String>,
        ) : Entry {
            override val texts: Collection<String> get() = byQuantity.values
        }
    }
}

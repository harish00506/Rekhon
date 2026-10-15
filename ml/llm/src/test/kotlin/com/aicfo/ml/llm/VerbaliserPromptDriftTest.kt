package com.aicfo.ml.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Holds the shipped prompt to `ai/chat/system-prompt.md` (CLAUDE.md §6, ADR-0017, ADR-0079).
 *
 * Why:  §6 says the AI's behaviour is data, not code — so the instruction that governs the
 *       on-device model lives in `ai/chat/system-prompt.md` where it can be changed without
 *       touching Kotlin. An Android library cannot read a repository file at runtime, so it reads a
 *       copy in its assets, and a copy is only safe while something fails the build when the two
 *       disagree. That is this test. Without it, editing the prompt where the project says to edit
 *       it would change nothing about what the app sends — the quietest kind of broken.
 * What: the asset equals the text between the VERBALISER PROMPT markers, and the prompt still says
 *       the two things it exists to say.
 * Result: the file and the asset cannot drift.
 * Changelog: 2026-10-10 — Created for ADR-0079.
 */
@RunWith(RobolectricTestRunner::class)
class VerbaliserPromptDriftTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val asset: String by lazy { VerbaliserInstruction(context).text() }
    private val source: String by lazy { promptFile().readText() }

    @Test
    fun `the prompt file is where this test thinks it is`() {
        assertTrue("the system prompt looks empty or truncated", source.length > 2_000)
        assertTrue("no verbaliser section in the file", BEGIN in source)
        assertTrue("no end marker in the file", END in source)
    }

    @Test
    fun `the asset is the marked section, character for character`() {
        assertEquals(
            "ml/llm/src/main/assets/chat/verbaliser-prompt.txt must restate ai/chat/system-prompt.md's " +
                "VERBALISER PROMPT section — re-copy it rather than editing the asset",
            marked(),
            asset,
        )
    }

    @Test
    fun `the prompt still forbids the two things that matter`() {
        // The prompt is data and may be reworded freely — but a wording that drops either of these
        // is a different prompt, and the sanitiser would be left enforcing a rule the model was
        // never told (P-03).
        assertTrue("the prompt must forbid changing a value", "Never change" in asset || "never change" in asset)
        assertTrue("the prompt must forbid an unsupplied number", "not in the list" in asset)
    }

    @Test
    fun `the prompt is short enough for the model's context window`() {
        // The published build carries a 1,280-token KV cache. At the usual rough four characters
        // per token, 2,000 characters is about 500 tokens, which leaves ~780 for the figures and
        // the reply — and the figures are at most four short lines. ADR-0080's worked examples
        // took it from 700 to 1,500 characters, so the ceiling is stated rather than implied: the
        // next example added should have to argue with this number.
        assertTrue(
            "the verbaliser prompt is too long for the shipped model: ${asset.length} chars",
            asset.length < 2_000,
        )
    }

    @Test
    fun `the prompt still carries worked examples, which is what tuned it`() {
        // ADR-0080: the examples are the part that measurably moved the fidelity rate, and they are
        // the app's own template sentences. A wording change may replace them; deleting them is a
        // different prompt and should fail here first.
        assertTrue("the prompt must show at least one worked example", "Sentence: You spent" in asset)
        assertTrue("the examples must cover more than one intent", asset.split("Intent:").size - 1 >= 3)
    }

    private fun marked(): String = source.substringAfter(BEGIN).substringBefore(END).trim()

    private fun promptFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, PROMPT_PATH)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Could not find $PROMPT_PATH walking up from ${File("").absolutePath}")
    }

    private companion object {
        const val PROMPT_PATH = "ai/chat/system-prompt.md"
        const val BEGIN = "<!-- ================== BEGIN VERBALISER PROMPT ================== -->"
        const val END = "<!-- =================== END VERBALISER PROMPT =================== -->"
    }
}

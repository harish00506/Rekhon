package com.aicfo.ml.llm

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.domain.engines.chat.ChatIntent
import com.aicfo.domain.engines.chat.LlmEngine
import com.aicfo.domain.engines.chat.VerbalisationDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which model answers, and what happens when the better one cannot (ADR-0079; §19, P-02, P-04).
 *
 * Why:  adding a second model must not make the app worse on the phones that do not have it — which
 *       is most phones. So the rules are tested rather than assumed: no weights means the app
 *       behaves exactly as it did before, a neural failure costs the user nothing, a run of
 *       failures stops being retried, and the reply always says which model actually wrote it,
 *       because a provenance line that names a model that did not answer is a lie (P-02).
 * What: the five paths — absent, neural, fallback, non-English, and the failure run.
 * Result: a sentence every time.
 * Changelog: 2026-10-10 — Created for ADR-0079.
 */
class SelectingLlmEngineTest {
    private val template = FakeEngine("template", Ok("The template answered."))

    @Test
    fun `with no weights installed the app answers exactly as it did before`() {
        val engine = SelectingLlmEngine(template, { null }, { "en" })

        assertEquals("The template answered.", engine.verbalise(draft()).expectOk())
        assertEquals("template", engine.id)
    }

    @Test
    fun `with weights installed the neural model answers, and is named`() {
        val neural = FakeEngine("on-device:test", Ok("The model answered."))
        val engine = SelectingLlmEngine(template, { neural }, { "en" })

        assertEquals("The model answered.", engine.verbalise(draft()).expectOk())
        assertEquals("on-device:test", engine.id)
    }

    @Test
    fun `a neural failure costs the user nothing but a plainer sentence`() {
        val neural = FakeEngine("on-device:test", Err(AppError.Validation("chat.draft.figure")))
        val engine = SelectingLlmEngine(template, { neural }, { "en" })

        assertEquals("The template answered.", engine.verbalise(draft()).expectOk())
        assertEquals("the reply must name the model that wrote it", "template", engine.id)
    }

    @Test
    fun `on a Hindi device the translated template wins over an English-tuned model`() {
        val neural = FakeEngine("on-device:test", Ok("The model answered."))
        val engine = SelectingLlmEngine(template, { neural }, { "hi" })

        assertEquals("The template answered.", engine.verbalise(draft()).expectOk())
        assertEquals("the model must not even be built", 0, neural.calls)
    }

    @Test
    fun `after a run of failures the process stops paying for the model`() {
        val neural = FakeEngine("on-device:test", Err(AppError.Unexpected("TimeoutException")))
        val engine = SelectingLlmEngine(template, { neural }, { "en" })

        repeat(5) { engine.verbalise(draft()) }

        assertEquals("three strikes, then the template answers directly", 3, neural.calls)
    }

    @Test
    fun `a failure recovers — one bad reply does not disable the model for good`() {
        val neural = FakeEngine("on-device:test", Err(AppError.Validation("chat.draft.figure")))
        val engine = SelectingLlmEngine(template, { neural }, { "en" })

        engine.verbalise(draft())
        neural.reply = Ok("The model answered.")
        engine.verbalise(draft())
        neural.reply = Err(AppError.Validation("chat.draft.figure"))

        repeat(3) { engine.verbalise(draft()) }

        assertEquals("the counter resets on a good answer", 5, neural.calls)
    }

    @Test
    fun `a model that cannot even be built is not an error the user sees`() {
        val engine = SelectingLlmEngine(template, { error("no native library") }, { "en" })

        assertEquals("The template answered.", engine.verbalise(draft()).expectOk())
        assertEquals("template", engine.id)
    }

    private fun draft() = VerbalisationDraft(ChatIntent.BALANCE, emptyList())

    private fun Result<String, AppError>.expectOk(): String {
        assertTrue("expected Ok but was $this", this is Ok)
        return (this as Ok).value
    }

    /**
     * An [LlmEngine] that returns what the test set, and counts how often it was asked.
     * Input:  [id]; [reply] — what `verbalise` returns. Output: a fake engine.
     */
    private class FakeEngine(
        override val id: String,
        var reply: Result<String, AppError>,
    ) : LlmEngine {
        var calls: Int = 0

        override fun verbalise(draft: VerbalisationDraft): Result<String, AppError> {
            calls++
            return reply
        }
    }
}

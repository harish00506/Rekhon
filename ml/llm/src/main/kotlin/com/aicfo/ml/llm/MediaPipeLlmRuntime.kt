package com.aicfo.ml.llm

import android.content.Context
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.toAppError
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs the sideloaded weights through MediaPipe's LLM Inference API (ADR-0079; §19, AI-ARC-007,
 * P-01, P-04).
 *
 * Why:  the model has to execute somewhere, and this is the one part of the feature that touches a
 *       native library. It is deliberately the dumbest class in the module: load the file, run the
 *       prompt, hand back the text. No prompt building, no checking, no fallback — those are
 *       testable and therefore live elsewhere ([VerbalisationPrompt], [DraftSanitiser],
 *       [SelectingLlmEngine]).
 * What: a lazily created [LlmInference] over the verified model path, one fresh session per call,
 *       and a timeout so a slow or wedged native call cannot hang the assistant.
 * Result: the model's raw words. **There is no network call here and there cannot be** — the
 *       weights are a local file and MediaPipe reads them from disk, which is what keeps the
 *       assistant working in airplane mode (P-04) and the user's question on the device (P-01).
 * Changelog: 2026-10-10 — Created for ADR-0079.
 *
 * Not unit tested, on purpose, and for the same reason `MlKitReceiptTextRecognizer` is not: the
 * behaviour worth asserting is the native library's. What this class *decides* is covered by
 * [SelectingLlmEngineTest] through a fake [LlmRuntime].
 *
 * Input:  [context] — for MediaPipe's own initialisation; [modelPath] — a path [LlmModelStore] has
 *         already verified. Output: an [LlmRuntime].
 */
internal class MediaPipeLlmRuntime(
    private val context: Context,
    private val modelPath: String,
) : LlmRuntime {
    /**
     * One thread, so two questions can never be inside the native library at once, and so a call
     * that outlives its timeout finishes on its own thread instead of being abandoned mid-inference.
     */
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var inference: LlmInference? = null

    override fun generate(prompt: String): Result<String, AppError> {
        val timeout = if (inference == null) LOAD_AND_REPLY_SECONDS else REPLY_SECONDS
        return runCatching {
            worker.submit(Callable { reply(prompt) }).get(timeout, TimeUnit.SECONDS)
        }.fold(
            onSuccess = { text -> Ok(text) },
            // The cause's class name only — never its message, which native libraries fill with
            // file paths (P-01, AppError.toAppError's own rule).
            onFailure = { failure -> Err(failure.toAppError()) },
        )
    }

    /**
     * Result: the model's reply to one prompt, on the worker thread.
     * Why:    a session per call rather than a long-lived one: the assistant answers one question
     *         at a time and each answer is independent, so carrying a conversation's tokens
     *         forward would only spend the model's small context window on text it must not use.
     *         Temperature and top-k are pinned at their least creative settings — this model's job
     *         is to copy figures into a sentence, and sampling is how it stops doing that (P-08's
     *         spirit; a model is never bit-reproducible, which is why the sanitiser exists).
     * Input:  [prompt]. Output: [String].
     */
    private fun reply(prompt: String): String {
        val engine = inference ?: load().also { inference = it }
        val options =
            LlmInferenceSession.LlmInferenceSessionOptions
                .builder()
                .setTopK(1)
                .setTemperature(0f)
                .build()
        return LlmInferenceSession.createFromOptions(engine, options).use { session ->
            session.addQueryChunk(prompt)
            session.generateResponse()
        }
    }

    /** Result: the loaded model. Input: none. Output: [LlmInference]. Takes seconds. */
    private fun load(): LlmInference {
        val options =
            LlmInference.LlmInferenceOptions
                .builder()
                .setModelPath(modelPath)
                .setMaxTokens(MAX_TOKENS)
                .setMaxTopK(1)
                .build()
        return LlmInference.createFromOptions(context, options)
    }

    override fun close() {
        runCatching { inference?.close() }
        inference = null
        worker.shutdown()
    }

    private companion object {
        /**
         * The published model is built with a 1,280-token KV cache, so asking for more than it was
         * compiled for would fail at load. The prompt is ~200 tokens and the reply is one sentence.
         */
        const val MAX_TOKENS = 1_280

        /** The first call pays for loading the weights off disk. */
        const val LOAD_AND_REPLY_SECONDS = 30L

        /** Every call after it. Longer than this and the template's answer is the better one. */
        const val REPLY_SECONDS = 12L
    }
}

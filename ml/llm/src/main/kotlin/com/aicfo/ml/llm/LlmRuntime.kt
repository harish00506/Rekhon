package com.aicfo.ml.llm

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result

/**
 * The seam between this app and whichever native library runs the weights (ADR-0079; AI-ARC-007).
 *
 * Why:  the runtime is the one part of the neural path that cannot be unit tested — it loads half a
 *       gigabyte of weights through JNI and needs a device. Everything around it *can* be, so it
 *       sits behind this interface: the engine, the prompt and the sanitiser are all tested against
 *       a fake, and the untestable part is one small class with no logic of its own.
 *       It is also the migration seam. MediaPipe's LLM Inference API is in maintenance-only mode
 *       and LiteRT-LM is its successor (ADR-0079 §Consequences); swapping to it replaces one
 *       implementation of this interface and touches nothing else.
 * What: one call — text in, text out — and a close.
 * Result: the model's raw reply, which no caller is allowed to trust: [DraftSanitiser] and then
 *         AI-GRD both run on it.
 * Changelog: 2026-10-10 — Created for ADR-0079.
 */
internal interface LlmRuntime {
    /**
     * Runs one prompt to completion.
     * Why:    synchronous because [com.aicfo.domain.engines.chat.LlmEngine.verbalise] is, and that
     *         call already runs on an IO dispatcher inside the repository. The implementation is
     *         responsible for bounding how long it may take: an unbounded native call would hang
     *         the assistant with no way out.
     * Result: `Ok(text)` — possibly empty, which the sanitiser rejects; `Err` when the model could
     *         not be loaded, the call failed, or it took too long.
     * Input:  [prompt] — the whole of the model's input. Output: `Result<String, AppError>`.
     */
    fun generate(prompt: String): Result<String, AppError>

    /** Releases the weights and the thread. Output: none. */
    fun close()
}

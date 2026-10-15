package com.aicfo.ml.llm

import android.content.Context
import com.aicfo.domain.engines.chat.LlmEngine
import java.util.Locale

/**
 * Builds the app's model for the DI graph (ADR-0079; ARC-003, §19, AI-ARC-007).
 *
 * Why:  `:app`'s Hilt module must not name an `internal` class, which is the seam every engine in
 *       this codebase already uses ([com.aicfo.domain.engines.chat.ChatEngineFactory],
 *       `ReceiptTextRecognizerFactory`). It also means the decision about *which* model the app
 *       uses is made in exactly one place rather than in a DI module.
 * What: the template verbaliser, plus the on-device model when this device has verified weights.
 * Result: an [LlmEngine] that is the deterministic verbaliser on a phone with no model file, and
 *         the neural one on a phone with it — with the same guarantees either way, because the
 *         guardrail does not care which model wrote the words (AI-ARC-004).
 * Changelog: 2026-10-10 — Created for ADR-0079; replaces `:app` constructing TemplateLlmEngine.
 */
object LlmEngineFactory {
    /**
     * Result: the model the app should use on this device.
     * Input:  [context] — the application context: string resources for the template, assets for
     *         the instruction, `filesDir` for the weights. Output: [LlmEngine].
     */
    fun create(context: Context): LlmEngine {
        val instruction = VerbaliserInstruction(context)
        val store = LlmModelStore(context)
        return SelectingLlmEngine(
            template = TemplateLlmEngine(context),
            neural = { neural(context, store, instruction) },
            language = { Locale.getDefault().language },
        )
    }

    /**
     * Result: the neural engine, or `null` when no verified model is installed — which is the
     *         ordinary case and not a fault.
     * Why:    the file check is the opt-in. There is no setting to flip and no download to
     *         consent to, because the app never fetches the weights: the user puts the file there
     *         (see `scripts/fetch_llm_model.sh`), and that act *is* the choice (P-01, P-04).
     * Input:  [context]; [store] — the model manifest and its check; [instruction].
     * Output: [LlmEngine]?
     */
    private fun neural(
        context: Context,
        store: LlmModelStore,
        instruction: VerbaliserInstruction,
    ): LlmEngine? {
        val ready = store.status() as? ModelStatus.Ready ?: return null
        return OnDeviceLlmEngine(
            runtime = MediaPipeLlmRuntime(context, ready.path),
            instruction = instruction::text,
            id = ready.modelId,
        )
    }
}

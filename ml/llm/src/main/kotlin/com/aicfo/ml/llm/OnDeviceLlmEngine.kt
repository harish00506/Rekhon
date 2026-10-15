package com.aicfo.ml.llm

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.domain.engines.chat.LlmEngine
import com.aicfo.domain.engines.chat.VerbalisationDraft

/**
 * The neural verbaliser — §19's on-device model, finally present (ADR-0079; §19, AI-ARC-007, P-03).
 *
 * Why:  §19 asks for an on-device compact instruction model and ADR-0053 had to ship a template
 *       verbaliser instead, because the weights cannot live in a repository. This is the other half
 *       of that decision: when the weights *are* on the device, the sentences stop being ten fixed
 *       forms and start being written for the figures at hand — without the model ever being given
 *       a figure to work out. It is handed the finished values and asked only for words (P-03).
 * What: prompt → runtime → sanitiser. Three steps, none of which computes anything.
 * Result: one sentence whose every digit came from a tool, or an `Err` that sends the caller back
 *         to the template. A model that cannot be trusted with this turn is simply not used for it.
 * Changelog: 2026-10-10 — Created for ADR-0079.
 *
 * Input:  [runtime] — whatever executes the weights; [instruction] — the verbaliser prompt, read
 *         from `ai/chat/system-prompt.md`'s mirrored asset; [id] — the model's provenance id.
 * Output: an [LlmEngine].
 */
internal class OnDeviceLlmEngine(
    private val runtime: LlmRuntime,
    private val instruction: () -> String,
    override val id: String,
) : LlmEngine {
    override fun verbalise(draft: VerbalisationDraft): Result<String, AppError> {
        // Nothing to say and nothing to say it from. Asking a model anyway is precisely how it is
        // invited to invent a figure (P-03), so this turn belongs to the template's honest
        // "I could not find anything" sentence instead.
        val verbalisation = VerbalisationPrompt.of(instruction(), draft) ?: return Err(AppError.Validation(FIELD))
        return when (val generated = runtime.generate(verbalisation.prompt)) {
            is Err -> generated
            is Ok -> DraftSanitiser.sanitise(generated.value, verbalisation)
        }
    }

    private companion object {
        const val FIELD = "chat.draft.figures"
    }
}

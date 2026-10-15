package com.aicfo.ml.llm

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.domain.engines.chat.LlmEngine
import com.aicfo.domain.engines.chat.VerbalisationDraft

/**
 * Which model answers this turn (ADR-0079; §19, AI-ARC-007, P-02, P-04).
 *
 * Why:  the app now has two models and must never be worse off for having the second. So the
 *       neural one is used when it is installed and it is the right tool for the turn, and the
 *       template answers otherwise — including when the neural one fails, is slow, or returns
 *       something the sanitiser refuses. The user always gets a sentence; what varies is which
 *       model wrote it, which is why [id] reports the one that actually did (P-02).
 * What: three conditions — a verified model file, an English-language device, and no run of recent
 *       failures — and a fallback that is not an error path but the ordinary one.
 * Result: an [LlmEngine] that behaves exactly like the template verbaliser on a phone with no
 *         model file, which is most phones (P-04).
 * Changelog: 2026-10-10 — Created for ADR-0079.
 *
 * Input:  [template] — the shipped deterministic verbaliser; [neural] — builds the on-device
 *         engine, or returns `null` when no verified model is installed; [language] — the device's
 *         current language tag.
 * Output: an [LlmEngine].
 */
class SelectingLlmEngine internal constructor(
    private val template: LlmEngine,
    private val neural: () -> LlmEngine?,
    private val language: () -> String,
) : LlmEngine {
    @Volatile
    private var answered: String = template.id

    @Volatile
    private var failures: Int = 0

    private var built: LlmEngine? = null
    private var attempted: Boolean = false

    /** Which model wrote the last reply — the template's id until a neural one has answered. */
    override val id: String get() = answered

    override fun verbalise(draft: VerbalisationDraft): Result<String, AppError> {
        val model = if (usable()) model() else null
        if (model != null) {
            when (val spoken = model.verbalise(draft)) {
                is Ok -> {
                    failures = 0
                    answered = model.id
                    return spoken
                }
                // Not logged: a failure here is routine, and the only things worth saying about it
                // would name the user's question or their figures (the PII-logging ban, §21.6).
                is Err -> failures++
            }
        }
        answered = template.id
        return template.verbalise(draft)
    }

    /**
     * Result: true when the neural path should be tried for this turn.
     * Why:    two guards, each for a failure that would otherwise be invisible. **Language:** the
     *         template's sentences are translated into Hindi, Kannada and Tamil (issue 10.8) and
     *         this model is English-tuned, so on a non-English device the *deterministic* engine is
     *         the better answer rather than the weaker one. **Failures:** a wedged or missing
     *         native library would otherwise cost every question its full timeout before falling
     *         back, so after a short run of failures the process stops asking.
     * Input:  none. Output: [Boolean].
     */
    private fun usable(): Boolean = failures < MAX_FAILURES && language().startsWith(ENGLISH)

    /**
     * Result: the neural engine, built at most once, or `null` when none is installed.
     * Why:    building it loads nothing by itself, but checking the model file hashes half a
     *         gigabyte on first use, so the answer is kept. `null` is cached too — plugging a model
     *         in mid-session is not a case worth re-checking on every question.
     * Input:  none. Output: [LlmEngine]?
     */
    private fun model(): LlmEngine? =
        synchronized(this) {
            if (!attempted) {
                attempted = true
                built = runCatching(neural).getOrNull()
            }
            built
        }

    private companion object {
        const val MAX_FAILURES = 3
        const val ENGLISH = "en"
    }
}

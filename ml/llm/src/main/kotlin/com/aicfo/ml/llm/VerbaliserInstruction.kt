package com.aicfo.ml.llm

import android.content.Context

/**
 * The model's instructions, read from the file the project treats as their home (CLAUDE.md §6,
 * ADR-0079).
 *
 * Why:  §6 is explicit — the AI's behaviour is data, not code. The instruction text that governs
 *       what the on-device model may do therefore lives in `ai/chat/system-prompt.md`, between the
 *       VERBALISER PROMPT markers, where someone can change it without touching Kotlin. This class
 *       reads the copy mirrored into the module's assets, and `VerbaliserPromptDriftTest` fails the
 *       build the moment the copy and the source disagree (ADR-0017's pattern).
 * What: one cached read of `assets/chat/verbaliser-prompt.txt`.
 * Result: the prompt text, or empty when the asset cannot be read — in which case the prompt
 *         builder still produces the figures and the sanitiser still holds the reply to them, so a
 *         missing asset degrades the sentence rather than the guarantee.
 * Changelog: 2026-10-10 — Created for ADR-0079.
 *
 * Input:  [context] — for the asset manager only. Output: a [VerbaliserInstruction].
 */
internal class VerbaliserInstruction(
    private val context: Context,
) {
    private val cached: String by lazy {
        runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }.trim()
        }.getOrDefault("")
    }

    /** Result: the instruction text. Input: none. Output: [String]. Read once per process. */
    fun text(): String = cached

    private companion object {
        const val ASSET = "chat/verbaliser-prompt.txt"
    }
}

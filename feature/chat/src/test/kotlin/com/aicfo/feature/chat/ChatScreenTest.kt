package com.aicfo.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aicfo.core.model.EngineProvenance
import com.aicfo.data.repository.ChatTurn
import com.aicfo.domain.engines.chat.ChatIntent
import com.aicfo.domain.engines.chat.ChatReply
import com.aicfo.domain.engines.chat.RefusalReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What §19's screen must actually put in front of a person (issue 10.5; P-02, P-03, P-07, §21.6).
 *
 * Why:  the pipeline is proven elsewhere. What only this layer can get wrong is what reaches the
 *       eye — and for an assistant that is most of the trust. A blocked reply must not be dressed
 *       as a polite "sorry" that hides what happened; a refusal must still offer a way forward; and
 *       a screen that answers questions about money must say, in words, that it never moves any.
 * What: an answered turn with its evidence, each refusal's own wording, the chips, and the promise.
 * Result: an assistant a user can check.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
@RunWith(RobolectricTestRunner::class)
class ChatScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `an answer is shown with the model and the rules behind it`() {
        render(ChatUiState(turns = listOf(turn(text = "You have ₹9,823.00 available."))))

        compose.onNodeWithText("You have ₹9,823.00 available.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("From model=template, tools=1 · RULE-STS").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a blocked reply says what happened rather than apologising vaguely`() {
        // The user is entitled to know the app caught itself. "Sorry, something went wrong" would
        // be the same words a crash gets, and this is not a crash — it is the guardrail working.
        render(ChatUiState(turns = listOf(turn(refusal = RefusalReason.GUARDRAIL_BLOCKED))))

        compose
            .onNodeWithText(
                "I had an answer, but it contained a figure I could not trace to an engine — so I did not show it.",
            ).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `an out-of-scope refusal offers what the assistant can do`() {
        render(
            ChatUiState(
                turns = listOf(turn(refusal = RefusalReason.OUT_OF_SCOPE)),
                chips = listOf(ChatIntent.HEALTH, ChatIntent.AFFORD),
            ),
        )

        compose
            .onNodeWithText(
                "I can't help with that one — no stock tips, tax filing or legal advice. Here is what I can do.",
            )
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("How am I doing?").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `every refusal has words of its own`() {
        // A reason with no wording would reach the user as a blank card.
        RefusalReason.entries.forEach { reason ->
            val label = compose.activity.getString(refusalLabel(reason))

            assertTrue("$reason has no wording", label.isNotBlank())
        }
    }

    @Test
    fun `every chip asks a question a person would type`() {
        ChatIntent.entries.forEach { intent ->
            val label = compose.activity.getString(chipLabel(intent))

            assertTrue("$intent has no chip wording", label.isNotBlank())
        }
    }

    @Test
    fun `a chip asks its question`() {
        val events = mutableListOf<ChatEvent>()
        render(ChatUiState(chips = listOf(ChatIntent.BALANCE)), onEvent = events::add)

        compose.onNodeWithText("What is my balance?").performScrollTo().performClick()

        assertEquals(listOf(ChatEvent.AskChip(ChatIntent.BALANCE)), events)
    }

    @Test
    fun `an empty conversation explains itself rather than looking broken`() {
        render(ChatUiState())

        compose.onNodeWithText("Nothing asked yet. Try one of these.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the screen says the assistant never moves money`() {
        render(ChatUiState())

        compose
            .onNodeWithText("The assistant reads and explains. It never moves money.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `nothing can be asked while an answer is in flight`() {
        val events = mutableListOf<ChatEvent>()
        render(ChatUiState(typed = "what is my balance?", isAsking = true), onEvent = events::add)

        compose.onNodeWithText("Ask").performScrollTo().performClick()

        assertTrue("a second question was sent while the first was running: $events", events.isEmpty())
    }

    private fun turn(
        text: String = "",
        refusal: RefusalReason? = null,
    ) = ChatTurn(
        id = "chat:1",
        question = "what is my balance?",
        reply =
            ChatReply(
                text = text,
                figures = emptyList(),
                chips = emptyList(),
                citations = if (refusal == null) listOf("RULE-STS") else emptyList(),
                refusal = refusal,
                verified = emptyList(),
                provenance =
                    EngineProvenance(
                        engineId = "AI-CHAT",
                        engineVersion = "1.0",
                        computedAtUtcMillis = NOW,
                        inputWindow = "model=template, tools=1",
                    ),
            ),
        askedAtUtcMillis = NOW,
    )

    private fun render(
        uiState: ChatUiState,
        onEvent: (ChatEvent) -> Unit = {},
    ) {
        compose.setContent { ChatContent(uiState = uiState, onEvent = onEvent, onDone = {}) }
    }

    private companion object {
        const val NOW = 1_790_000_000_000L
    }
}

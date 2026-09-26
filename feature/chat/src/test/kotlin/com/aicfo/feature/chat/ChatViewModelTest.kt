package com.aicfo.feature.chat

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.data.repository.ChatRepository
import com.aicfo.data.repository.ChatTurn
import com.aicfo.domain.engines.chat.ChatIntent
import com.aicfo.domain.engines.chat.ChatReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the assistant screen's state holder must get right (issue 10.5; ARC-004, CHT-003).
 *
 * Why:  it does no arithmetic, so the ways it can be wrong are about *when*: asking twice because a
 *       slow answer left the field full, sending a chip's words that differ from what a person
 *       would type (which would route differently), or leaving the screen looking broken while the
 *       pipeline runs.
 * What: the subscription, asking, the field clearing, the double-ask guard, chips and the clear.
 * Result: one question in, one turn out.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = RecordingChatRepository()

    /** Input: none. Output: the main dispatcher is the test one. */
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    /** Input: none. Output: the main dispatcher is restored. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the conversation arrives`() =
        runTest(dispatcher) {
            repository.turns.value = listOf(turn())

            assertEquals(1, viewModel().uiState.first().turns.size)
        }

    @Test
    fun `a typed question is asked and the field clears`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.onEvent(ChatEvent.Typed("what is my balance?"))

            model.onEvent(ChatEvent.Ask)

            assertEquals(listOf("what is my balance?"), repository.asked)
            assertEquals("a field that does not clear invites the same question twice", "", model.uiState.first().typed)
        }

    @Test
    fun `an empty question is not asked`() =
        runTest(dispatcher) {
            viewModel().onEvent(ChatEvent.Ask)

            assertTrue(repository.asked.isEmpty())
        }

    @Test
    fun `a chip asks the words a person would have typed`() =
        runTest(dispatcher) {
            // The chip and the free-text path must reach the same planner, or a chip could route
            // somewhere typing never could.
            viewModel().onEvent(ChatEvent.AskChip(ChatIntent.HEALTH))

            assertEquals(listOf("how am i doing financially?"), repository.asked)
        }

    @Test
    fun `every chip has words behind it`() =
        runTest(dispatcher) {
            val model = viewModel()

            ChatIntent.entries.forEach { intent ->
                repository.asked.clear()
                model.onEvent(ChatEvent.AskChip(intent))

                assertTrue("$intent asks nothing", repository.asked.single().isNotBlank())
            }
        }

    @Test
    fun `the reply's own chips become the next suggestions`() =
        runTest(dispatcher) {
            repository.reply = turn(chips = listOf(ChatIntent.SPEND, ChatIntent.BUDGET))
            val model = viewModel()

            model.onEvent(ChatEvent.AskChip(ChatIntent.BALANCE))

            assertEquals(listOf(ChatIntent.SPEND, ChatIntent.BUDGET), model.uiState.first().chips)
        }

    @Test
    fun `a reply with no chips of its own falls back to the defaults`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.onEvent(ChatEvent.AskChip(ChatIntent.BALANCE))

            assertEquals(ChatUiState.DEFAULT_CHIPS, model.uiState.first().chips)
        }

    @Test
    fun `clearing is forwarded`() =
        runTest(dispatcher) {
            viewModel().onEvent(ChatEvent.Clear)

            assertTrue(repository.cleared)
        }

    private fun viewModel() = ChatViewModel(repository)

    private fun turn(chips: List<ChatIntent> = emptyList()) =
        ChatTurn(
            id = "chat:1",
            question = "what is my balance?",
            reply =
                ChatReply(
                    text = "You have ₹9,823.00 available.",
                    figures = emptyList(),
                    chips = chips,
                    citations = listOf("RULE-STS"),
                    refusal = null,
                    verified = emptyList(),
                    provenance = EngineProvenance("AI-CHAT", "1.0", NOW),
                ),
            askedAtUtcMillis = NOW,
        )

    /** Records what was asked, and answers with whatever the test set. */
    private inner class RecordingChatRepository : ChatRepository {
        val turns = MutableStateFlow(emptyList<ChatTurn>())
        val asked = mutableListOf<String>()
        var cleared = false
        var reply: ChatTurn = turn()

        override fun observeConversation(): Flow<List<ChatTurn>> = turns

        override suspend fun ask(text: String): Result<ChatTurn, AppError> {
            asked += text
            return Ok(reply)
        }

        override suspend fun clear(): Result<Unit, AppError> {
            cleared = true
            return Ok(Unit)
        }
    }

    private companion object {
        const val NOW = 1_790_000_000_000L
    }
}

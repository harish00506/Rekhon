package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.common.UuidIdGenerator
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.chat.ChatEngineFactory
import com.aicfo.domain.engines.chat.FigureKind
import com.aicfo.domain.engines.chat.LlmEngine
import com.aicfo.domain.engines.chat.RefusalReason
import com.aicfo.domain.engines.chat.ToolCall
import com.aicfo.domain.engines.chat.ToolFigure
import com.aicfo.domain.engines.chat.ToolName
import com.aicfo.domain.engines.chat.ToolResult
import com.aicfo.domain.engines.chat.VerbalisationDraft
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId

/**
 * The chat pipeline end to end (issue 10.5; §19, CHT-001..004, P-01, P-03, AI-ARC-004).
 *
 * Why:  the engine is proven pure in `:domain:engines:chat`. What only this layer can get wrong is
 *       the wiring, and the wiring is where the promises live: that an out-of-scope question reads
 *       **no data at all**, that the model is handed tool results and never the user's ledger, that
 *       a figure the model invents is still blocked once a real database is behind it, and that a
 *       conversation the user clears is actually gone (CHT-004).
 * What: a whole turn, the refusal paths, what the executor was asked for, persistence, the clear,
 *       and the profile scope.
 * Result: an assistant that can only say what an engine already computed.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: ChatRepository

    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock(Instant.parse("2026-09-26T04:30:00Z").toEpochMilli(), ZoneId.of("Asia/Kolkata"))
    private val activeProfileId = MutableStateFlow(PROFILE)
    private val tools = RecordingExecutor()
    private val llm = RecordingLlm()

    /** Input: none. Output: an in-memory database and the pipeline over it. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CfoDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            RepositoryFactory.chat(
                database = database,
                engine = ChatEngineFactory.create(),
                llm = llm,
                tools = tools,
                clock = clock,
                dispatchers = TestDispatchers(dispatcher),
                activeProfileId = activeProfileId,
                idGenerator = UuidIdGenerator(),
            )
    }

    /** Input: none. Output: closed. */
    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a question is planned, the tool is run, and the answer is what the model said`() =
        runTest(dispatcher) {
            val turn = repository.ask("what is my balance?").expectOk()

            assertEquals(listOf(ToolName.GET_BALANCE), tools.calls.map { it.tool })
            assertTrue(turn.reply.answered)
            assertEquals("You have ₹9,823.00 available.", turn.reply.text)
        }

    @Test
    fun `an out-of-scope question reads no data at all`() =
        runTest(dispatcher) {
            // CHT-002, and the promise that matters most: a refusal must not have gone looking.
            val turn = repository.ask("should i buy reliance today?").expectOk()

            assertEquals(RefusalReason.OUT_OF_SCOPE, turn.reply.refusal)
            assertTrue("a refusal ran a tool: ${tools.calls}", tools.calls.isEmpty())
            assertTrue("a refusal woke the model", llm.drafts.isEmpty())
        }

    @Test
    fun `the model is handed the tool results and never the question`() =
        runTest(dispatcher) {
            // §19.4 and P-01: the model's whole input is the intent and what the tools returned.
            repository.ask("what is my balance?").expectOk()

            val draft = llm.drafts.single()
            assertEquals(listOf(ToolName.GET_BALANCE), draft.results.map { it.tool })
            assertTrue(
                "the user's words reached the model",
                draft.results.none { result -> result.figures.any { it.key.contains("balance?") } },
            )
        }

    @Test
    fun `a figure the model invented is still blocked with a real database behind it`() =
        runTest(dispatcher) {
            llm.reply = "You have ₹99,999.00 available."

            val turn = repository.ask("what is my balance?").expectOk()

            assertFalse(turn.reply.answered)
            assertEquals(RefusalReason.GUARDRAIL_BLOCKED, turn.reply.refusal)
        }

    @Test
    fun `a tool that cannot answer produces an honest refusal, not a zero`() =
        runTest(dispatcher) {
            tools.fail = true

            val turn = repository.ask("what is my balance?").expectOk()

            assertEquals(RefusalReason.NO_DATA, turn.reply.refusal)
            assertTrue("a failed tool still woke the model", llm.drafts.isEmpty())
        }

    @Test
    fun `every turn is kept, refusals included`() =
        runTest(dispatcher) {
            repository.ask("what is my balance?").expectOk()
            repository.ask("should i buy reliance?").expectOk()

            val conversation = repository.observeConversation().first()

            assertEquals(2, conversation.size)
            assertEquals("what is my balance?", conversation.first().question)
            assertEquals(RefusalReason.OUT_OF_SCOPE, conversation.last().reply.refusal)
        }

    @Test
    fun `a stored turn keeps the words and not the figures`() =
        runTest(dispatcher) {
            // A figure verified against today's data cannot honestly be re-shown next month
            // without being checked again, so it is not stored to be re-shown.
            repository.ask("what is my balance?").expectOk()

            val stored = repository.observeConversation().first().single()

            assertEquals("You have ₹9,823.00 available.", stored.reply.text)
            assertTrue(stored.reply.figures.isEmpty())
        }

    @Test
    fun `clearing the conversation actually deletes it`() =
        runTest(dispatcher) {
            // CHT-004: a tombstone is not forgetting.
            repository.ask("what is my balance?").expectOk()

            repository.clear().expectOk()

            assertTrue(repository.observeConversation().first().isEmpty())
            assertEquals(0, database.chatDao().observeMessages(PROFILE).first().size)
        }

    @Test
    fun `another profile's conversation is not this profile's`() =
        runTest(dispatcher) {
            repository.ask("what is my balance?").expectOk()
            activeProfileId.value = "demo"

            assertTrue(repository.observeConversation().first().isEmpty())
        }

    @Test
    fun `an empty question is refused by field`() =
        runTest(dispatcher) {
            assertEquals(AppError.Validation("chat.text"), (repository.ask("  ") as Err).error)
        }

    // --- fakes ------------------------------------------------------------------------------------

    /** Records what the pipeline asked for, and answers with one known figure. */
    private class RecordingExecutor : ChatToolExecutor {
        val calls = mutableListOf<ToolCall>()
        var fail = false

        override suspend fun run(call: ToolCall): ToolResult {
            calls += call
            return if (fail) {
                ToolResult(call.tool, failed = true)
            } else {
                ToolResult(
                    tool = call.tool,
                    figures = listOf(ToolFigure("liquid", FigureKind.AMOUNT, amount = Money(9_823_00L))),
                    citations = listOf("RULE-STS"),
                )
            }
        }
    }

    /** Records what the model was handed, and says whatever the test told it to. */
    private class RecordingLlm : LlmEngine {
        val drafts = mutableListOf<VerbalisationDraft>()
        var reply = "You have ₹9,823.00 available."

        override val id: String = "template"

        override fun verbalise(draft: VerbalisationDraft): Result<String, AppError> {
            drafts += draft
            return Ok(reply)
        }
    }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val PROFILE = "local"
    }
}

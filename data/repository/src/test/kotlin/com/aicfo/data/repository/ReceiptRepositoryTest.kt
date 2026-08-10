package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.FakeIdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.common.getOrNull
import com.aicfo.core.crypto.ReceiptImageStoreFactory
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.core.model.RecognizedBlock
import com.aicfo.core.model.RecognizedText
import com.aicfo.core.model.TransactionSource
import com.aicfo.domain.engines.classification.ClassificationEngineFactory
import com.aicfo.domain.engines.receipt.ReceiptEngineFactory
import com.aicfo.ml.ocr.ReceiptTextRecognizer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests for [ReceiptRepository] — the pipeline, the guard and the deletion (issue 3.8; FR-OCR-*).
 *
 * Why:  the parser's arithmetic is proven in its own module and the encryption in `:core:crypto`.
 *       What is proven here is everything neither can see: **that a scan writes nothing**, **that a
 *       saved receipt is tagged `ocr` and linked to a real blob**, and **exactly where FR-OCR-006's
 *       ±1% / ±1 day band stops**. That last one is the load-bearing case: a guard that is one paisa
 *       too generous starts offering to merge purchases that are not the same, and a guard one paisa
 *       too tight silently lets the user record the same coffee twice.
 * What: the scan's read-only-ness, both writes, both edges of the duplicate band, the merge, and the
 *       delete-image-keep-the-transaction rule.
 * Result: the storage half of FR-OCR is proven against a real SQL engine and a real AEAD.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * Unencrypted in-memory Room and Tink's own in-memory keyset, deliberately — the same reasoning as
 * the suites beside it: what is under test is this class's behaviour, not SQLCipher's or the Android
 * Keystore's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReceiptRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: CfoDatabase
    private lateinit var transactions: TransactionRepository
    private lateinit var accounts: AccountRepository
    private lateinit var repository: ReceiptRepository
    private val recognizer = FakeReceiptTextRecognizer()
    private val clock = FakeClock(initialMillis = TODAY_MILLIS, initialZone = ZoneId.of("Asia/Kolkata"))
    private val activeProfileId = MutableStateFlow(PROFILE)

    /** Input: none. Output: a fresh database, blob directory and repository over both. */
    @Before
    fun setUp() {
        AeadConfig.register()
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CfoDatabase::class.java,
            ).allowMainThreadQueries().build()
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val ids = FakeIdGenerator()
        transactions =
            RepositoryFactory.transactions(
                database, clock, ids, dispatchers, activeProfileId, ClassificationEngineFactory.create(),
            )
        accounts = RepositoryFactory.accounts(database, clock, ids, dispatchers, activeProfileId)
        val aead: Aead =
            KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        repository =
            RepositoryFactory.receipts(
                database = database,
                transactions = transactions,
                recognizer = recognizer,
                engine = ReceiptEngineFactory.create(),
                images = ReceiptImageStoreFactory.create(aead, blobDirectory()),
                clock = clock,
                ids = ids,
                dispatchers = dispatchers,
                activeProfileId = activeProfileId,
            )
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- the scan (FR-OCR-002, FR-OCR-003, P-07) -------------------------------------------------

    @Test
    fun `a scan reads the receipt and writes nothing`() =
        runTest {
            recognizer.returns(RecognizedText(listOf(RecognizedBlock("BIG BAZAAR\nTOTAL 365.80\n04/08/2026"))))

            val scan = repository.scan(SOME_BYTES).getOrNull()!!

            assertEquals(Money(36_580L), scan.fields.total?.value)
            assertEquals("2026-08-04", scan.fields.date?.value)
            assertEquals("P-07: a scan proposes; nothing is written", emptyList<Any>(), transactions.liveTransactions())
            assertTrue("no blob may exist before the user has saved", blobDirectory().listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `an unreadable photo yields empty fields rather than an error`() =
        runTest {
            recognizer.returns(RecognizedText(emptyList()))

            val scan = repository.scan(SOME_BYTES)

            assertTrue("§18: a failed read falls back to manual entry, not to an error", scan is Ok)
            assertNull((scan as Ok).value.fields.total)
        }

    @Test
    fun `bytes that are not an image are refused`() =
        runTest {
            recognizer.fails(AppError.Validation("image"))

            assertTrue(repository.scan(SOME_BYTES) is Err)
        }

    // --- saving (FR-OCR-005, FR-TXN-009) ---------------------------------------------------------

    @Test
    fun `a saved receipt is tagged ocr and linked to an encrypted blob`() =
        runTest {
            val account = newAccount()

            val saved = repository.save(draft(account), SOME_BYTES).getOrNull()!!

            assertEquals(TransactionSource.OCR, saved.source)
            val attachment = repository.observeAttachment(saved.id).first()
            assertNotNull("FR-OCR-005: the image must be linked as an attachment", attachment)
            assertTrue(
                "the blob must actually be on disk",
                File(blobDirectory(), attachment!!.fileName).isFile,
            )
        }

    @Test
    fun `a receipt dated before today is saved on its own date, not on today's`() =
        runTest {
            // ADR-0011, and the case the emulator found: every unit test until this one handed the
            // repository a draft dated today, so `stampsFor`'s refusal of the past never fired.
            val account = newAccount()

            val saved = repository.save(draft(account), SOME_BYTES).getOrNull()!!

            assertEquals("FR-OCR-003: the date the user confirmed is the date stored", "2026-08-04", saved.bookedOn)
        }

    @Test
    fun `a receipt the user chose not to keep saves the transaction alone`() =
        runTest {
            val account = newAccount()

            val saved = repository.save(draft(account), imageBytes = null).getOrNull()!!

            assertNull(repository.observeAttachment(saved.id).first())
            assertTrue(blobDirectory().listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `a rejected draft leaves no blob behind`() =
        runTest {
            // No account exists, so `create` refuses — and the blob written first must be cleaned up.
            val outcome =
                repository.save(
                    TransactionDraft(accountId = "a:missing", amount = Money(-36_580L)),
                    SOME_BYTES,
                )

            assertTrue(outcome is Err)
            assertTrue(
                "a failed save must not leave ciphertext nothing points at",
                blobDirectory().listFiles().orEmpty().isEmpty(),
            )
        }

    @Test
    fun `the stored image can be read back`() =
        runTest {
            val account = newAccount()
            val saved = repository.save(draft(account), SOME_BYTES).getOrNull()!!

            val attachment = repository.observeAttachment(saved.id).first()!!

            assertTrue(repository.readImage(attachment) is Ok)
        }

    // --- the duplicate guard (FR-OCR-006) --------------------------------------------------------

    @Test
    fun `a manual row at exactly one percent and one day is offered as a merge`() =
        runTest {
            // 1% of ₹1,000.00 is ₹10.00 exactly, and the date is one day off — both edges at once.
            insertLedgerRow("t:manual", amountMinor = -1_010_00L, bookedOn = "2026-08-05", source = "manual")

            val found = repository.findDuplicates(Money(1_000_00L), LocalDate.parse("2026-08-04")).getOrNull()!!

            assertEquals(listOf("t:manual"), found.map { it.id })
        }

    @Test
    fun `one paisa past the band is not a duplicate`() =
        runTest {
            insertLedgerRow("t:manual", amountMinor = -1_010_01L, bookedOn = "2026-08-04", source = "manual")

            val found = repository.findDuplicates(Money(1_000_00L), LocalDate.parse("2026-08-04")).getOrNull()!!

            assertTrue("the guard must stop somewhere, and this is where", found.isEmpty())
        }

    @Test
    fun `one day past the band is not a duplicate`() =
        runTest {
            insertLedgerRow("t:manual", amountMinor = -1_000_00L, bookedOn = "2026-08-06", source = "manual")

            val found = repository.findDuplicates(Money(1_000_00L), LocalDate.parse("2026-08-04")).getOrNull()!!

            assertTrue(found.isEmpty())
        }

    @Test
    fun `an sms row is a candidate, because that is what the requirement says`() =
        runTest {
            insertLedgerRow("t:sms", amountMinor = -1_000_00L, bookedOn = "2026-08-04", source = "sms")

            val found = repository.findDuplicates(Money(1_000_00L), LocalDate.parse("2026-08-04")).getOrNull()!!

            assertEquals(listOf("t:sms"), found.map { it.id })
        }

    @Test
    fun `another scan is a different receipt, not this one twice`() =
        runTest {
            insertLedgerRow("t:ocr", amountMinor = -1_000_00L, bookedOn = "2026-08-04", source = "ocr")

            val found = repository.findDuplicates(Money(1_000_00L), LocalDate.parse("2026-08-04")).getOrNull()!!

            assertTrue("merging two scans would delete a real purchase", found.isEmpty())
        }

    @Test
    fun `a transfer leg is never a duplicate of a purchase`() =
        runTest {
            insertLedgerRow(
                "t:sweep",
                amountMinor = -1_000_00L,
                bookedOn = "2026-08-04",
                source = "manual",
                transferId = "tfr:1",
            )

            assertTrue(
                repository.findDuplicates(Money(1_000_00L), LocalDate.parse("2026-08-04")).getOrNull()!!.isEmpty(),
            )
        }

    @Test
    fun `another profile's transaction is never a duplicate`() =
        runTest {
            insertLedgerRow(
                "t:demo",
                amountMinor = -1_000_00L,
                bookedOn = "2026-08-04",
                source = "manual",
                profileId = OTHER_PROFILE,
            )

            assertTrue(
                repository.findDuplicates(Money(1_000_00L), LocalDate.parse("2026-08-04")).getOrNull()!!.isEmpty(),
            )
        }

    @Test
    fun `a scan surfaces the duplicate it would create`() =
        runTest {
            insertLedgerRow("t:manual", amountMinor = -365_80L, bookedOn = "2026-08-04", source = "manual")
            recognizer.returns(RecognizedText(listOf(RecognizedBlock("BIG BAZAAR\nTOTAL 365.80\n04/08/2026"))))

            val scan = repository.scan(SOME_BYTES).getOrNull()!!

            assertEquals(listOf("t:manual"), scan.duplicates.map { it.id })
        }

    // --- the merge (FR-OCR-006) ------------------------------------------------------------------

    @Test
    fun `merging attaches the receipt without creating a second transaction`() =
        runTest {
            val account = newAccount()
            // A hand-typed row dated today — which is what FR-OCR-006's guard actually matches
            // against, and what `stampsFor` still requires of the manual path (ADR-0011).
            val existing =
                transactions.create(draft(account).copy(bookedOn = null)).getOrNull()!!
            val before = transactions.liveTransactions().size

            assertTrue(repository.mergeInto(existing.id, SOME_BYTES) is Ok)

            assertEquals(
                "FR-OCR-006: a merge must not create a duplicate",
                before,
                transactions.liveTransactions().size,
            )
            assertNotNull(repository.observeAttachment(existing.id).first())
        }

    // --- deleting the image (FR-OCR-005) ---------------------------------------------------------

    @Test
    fun `deleting the image keeps the transaction and leaves no blob`() =
        runTest {
            val account = newAccount()
            val saved = repository.save(draft(account), SOME_BYTES).getOrNull()!!
            val attachment = repository.observeAttachment(saved.id).first()!!

            assertTrue(repository.deleteImage(attachment.id) is Ok)

            assertEquals(
                "FR-OCR-005: the transaction must survive its receipt",
                listOf(saved.id),
                transactions.liveTransactions().map { it.id },
            )
            assertNull(repository.observeAttachment(saved.id).first())
            assertFalse(File(blobDirectory(), attachment.fileName).exists())
        }

    @Test
    fun `deleting an attachment that does not exist is not found`() =
        runTest {
            assertEquals(AppError.NotFound, (repository.deleteImage("att:missing") as Err).error)
        }

    // --- fixtures --------------------------------------------------------------------------------

    /** Result: the directory blobs go in. Input: none. Output: [File]. */
    private fun blobDirectory(): File = File(folder.root, "receipts")

    /** Result: a live account to spend from. Input: none. Output: its id. */
    private suspend fun newAccount(): String =
        (
            accounts.create(
                AccountDraft(
                    name = "HDFC Savings",
                    type = AccountType.BANK,
                    openingBalance = Money(1_00_000_00L),
                    currencyCode = "INR",
                ),
            ) as Ok
        ).value.id

    /**
     * Result: the draft the review screen would submit — **back-dated**, because a receipt is by
     *         definition already spent (ADR-0011). Input: [accountId]. Output: the draft.
     */
    private fun draft(accountId: String) =
        TransactionDraft(
            accountId = accountId,
            amount = Money(-36_580L),
            merchant = "Big Bazaar",
            bookedOn = LocalDate.parse("2026-08-04"),
        )

    /**
     * Result: one ledger row written straight to the DAO — the duplicate guard reads rows this
     *         repository never wrote, which is the point. Input: the fields the guard looks at.
     * Output: none (suspends).
     */
    @Suppress("LongParameterList") // A row fixture: the count is the table's, not a design choice.
    private suspend fun insertLedgerRow(
        id: String,
        amountMinor: Long,
        bookedOn: String,
        source: String,
        transferId: String? = null,
        profileId: String = PROFILE,
    ) {
        database.transactionDao().upsert(
            TransactionEntity(
                id = id,
                profileId = profileId,
                accountId = "a:bank",
                amountMinor = amountMinor,
                currencyCode = "INR",
                occurredAtUtcMillis = clock.nowUtcMillis(),
                bookedOnIsoDate = bookedOn,
                merchant = "Big Bazaar",
                source = source,
                type = if (transferId == null) "expense" else "transfer_out",
                transferId = transferId,
                createdAtUtcMillis = clock.nowUtcMillis(),
                updatedAtUtcMillis = clock.nowUtcMillis(),
            ),
        )
    }

    private companion object {
        const val PROFILE = "p:test"
        const val OTHER_PROFILE = "p:demo"

        /** 2026-08-06T12:00 in Asia/Kolkata, so the profile day is unambiguous (TIM-001). */
        val TODAY_MILLIS: Long = Instant.parse("2026-08-06T06:30:00Z").toEpochMilli()

        /** Stand-in image bytes. The recogniser is faked, and the store re-encodes whatever it gets. */
        val SOME_BYTES = ByteArray(64) { it.toByte() }
    }
}

/**
 * A [ReceiptTextRecognizer] that returns whatever the test told it to (issue 3.8).
 *
 * Why:    the real one needs a camera, a device and ML Kit's model. Faking it is what lets the
 *         *pipeline* — recognise, parse, guard, write — be proven on the JVM, and it is exactly the
 *         seam the interface was extracted for.
 * Result: a recogniser under the test's control.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
private class FakeReceiptTextRecognizer : ReceiptTextRecognizer {
    private var outcome: Result<RecognizedText, AppError> = Ok(RecognizedText(emptyList()))

    /** Result: the next call returns [text]. Input: [text]. Output: none. */
    fun returns(text: RecognizedText) {
        outcome = Ok(text)
    }

    /** Result: the next call fails. Input: [error]. Output: none. */
    fun fails(error: AppError) {
        outcome = Err(error)
    }

    override suspend fun recognize(bytes: ByteArray): Result<RecognizedText, AppError> = outcome
}

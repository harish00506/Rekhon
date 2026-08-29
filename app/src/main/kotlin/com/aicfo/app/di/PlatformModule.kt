package com.aicfo.app.di

import android.content.Context
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.crypto.ReceiptImageStore
import com.aicfo.core.crypto.ReceiptImageStoreFactory
import com.aicfo.data.sms.SmsInboxReader
import com.aicfo.data.sms.SmsInboxReaderFactory
import com.aicfo.ml.ocr.ReceiptTextRecognizer
import com.aicfo.ml.ocr.ReceiptTextRecognizerFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The device-backed collaborators the engines read from (ARC-002, P-01).
 *
 * Why:  split out of [EngineModule] when issue 4.4's budget engine took that object to detekt's
 *       `TooManyFunctions` ceiling — the same pressure that produced [EngineModule] itself, and the
 *       same kind of seam rather than an arbitrary cut. [EngineModule]'s own documentation already
 *       drew the line these three sat on the wrong side of: everything there is a pure-Kotlin
 *       calculator with no Android imports, and each of these needs a `Context`, an OS permission or
 *       a keystore. Now the file matches its claim, and a reviewer asking "what touches the device?"
 *       has one file to read.
 * What: one `@Provides` per collaborator, each built through its factory because the implementations
 *       are `internal` to their modules (ARC-003). Each feeds exactly one engine next door — the
 *       recogniser feeds the receipt parser, the inbox reader feeds the SMS parser — so the pairing
 *       is legible from either file.
 * Result: features and repositories inject an interface and never name an implementation.
 * Changelog: 2026-08-11 — Created for issue 4.4, out of [EngineModule].
 *
 * **Binding one does not read anything.** Every class here checks its OS permission on the call, and
 * the repository above it checks the in-app consent before calling at all (P-01, ADR-0013), so a
 * graph that can construct these is not a graph that has read a message or opened a photograph.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {
    /**
     * The on-device text recogniser (issue 3.8; FR-OCR-002, P-01, P-04).
     * Why:    the receipt pipeline's Android half — it needs a device; the parser it feeds does not.
     *
     *         **A singleton because the model is loaded once.** Building a client per scan would
     *         reload it on every photograph, which is a visible pause on the screen the user is
     *         already waiting on.
     * Result: a [ReceiptTextRecognizer]. Input: none. Output: the recogniser.
     * Changelog: 2026-08-06 — Created for issue 3.8, in EngineModule.
     *            2026-08-11 — Issue 4.4: moved here.
     */
    @Provides
    @Singleton
    fun provideReceiptTextRecognizer(): ReceiptTextRecognizer = ReceiptTextRecognizerFactory.create()

    /**
     * The phone's inbox (issue 3.9; §18, §23, P-01, ADR-0013).
     * Why:    the SMS pipeline's Android half, the same pairing the receipt one uses. It holds the
     *         permission so `SmsEngine` can stay pure Kotlin and testable without a device.
     * Result: an [SmsInboxReader]. Input: [context] — for its `ContentResolver`; [dispatchers].
     * Output: the reader.
     * Changelog: 2026-08-07 — Created for issue 3.9, in EngineModule.
     *            2026-08-11 — Issue 4.4: moved here.
     */
    @Provides
    @Singleton
    fun provideSmsInboxReader(
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
    ): SmsInboxReader = SmsInboxReaderFactory.create(context, dispatchers)

    /**
     * The encrypted receipt store (issue 3.8; FR-OCR-005, SEC-003).
     * Why:    assembled by its factory over a **keyset of its own**, so a compromise or a rotation of
     *         the attachment key is not one of the database key. The same seam
     *         `KeystoreMacFactory.createVerifier` uses for the PIN.
     * Result: a [ReceiptImageStore]. Input: [context]. Output: the store.
     * Changelog: 2026-08-06 — Created for issue 3.8, in EngineModule.
     *            2026-08-11 — Issue 4.4: moved here.
     */
    @Provides
    @Singleton
    fun provideReceiptImageStore(
        @ApplicationContext context: Context,
    ): ReceiptImageStore = ReceiptImageStoreFactory.create(context)
}

package com.aicfo.app.di

import android.content.Context
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.crypto.ReceiptImageStore
import com.aicfo.core.crypto.ReceiptImageStoreFactory
import com.aicfo.data.sms.SmsInboxReader
import com.aicfo.data.sms.SmsInboxReaderFactory
import com.aicfo.domain.engines.networth.NetWorthEngine
import com.aicfo.domain.engines.networth.NetWorthEngineFactory
import com.aicfo.domain.engines.quicksetup.QuickSetupEngine
import com.aicfo.domain.engines.quicksetup.QuickSetupEngineFactory
import com.aicfo.domain.engines.receipt.ReceiptEngine
import com.aicfo.domain.engines.receipt.ReceiptEngineFactory
import com.aicfo.domain.engines.recurring.RecurringEngine
import com.aicfo.domain.engines.recurring.RecurringEngineFactory
import com.aicfo.domain.engines.sms.SmsEngine
import com.aicfo.domain.engines.sms.SmsEngineFactory
import com.aicfo.ml.ocr.ReceiptTextRecognizer
import com.aicfo.ml.ocr.ReceiptTextRecognizerFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The `:domain:engines:*` bindings (ARC-002, ARC-003, P-03).
 *
 * Why:  split out of [CoreModule] when adding the net-worth engine took that object to detekt's
 *       `TooManyFunctions` ceiling — the same pressure that produced [RepositoryModule] for issue
 *       2.4, and the same kind of seam rather than an arbitrary cut. Everything here is a
 *       **pure-Kotlin calculator**: no Android imports (ARC-002), no state, no dependencies, and by
 *       P-03 the only code in the app allowed to produce a financial figure. A reviewer asking
 *       "what computes the numbers?" now has one file to read.
 * What: one `@Provides` per engine, each built through its factory because the implementations are
 *       `internal` to their modules (ARC-003).
 * Result: features and repositories inject an interface and never name an implementation.
 * Changelog: 2026-08-01 — Created for issue 2.6, with the app's second engine.
 *
 * **Singletons, safely.** Every engine here is stateless and deterministic — fixed input, fixed
 * output (P-08) — so one instance shared across the app has nothing to reset between screens and
 * no way for one caller's use to affect another's.
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    /**
     * The quick-setup engine (issue 2.3; FR-ONB-002).
     * Result: a [QuickSetupEngine]. Input: none. Output: the engine.
     * Changelog: 2026-07-27 — Created for issue 2.3, in CoreModule.
     *            2026-08-01 — Issue 2.6: moved here.
     */
    @Provides
    @Singleton
    fun provideQuickSetupEngine(): QuickSetupEngine = QuickSetupEngineFactory.create()

    /**
     * The net-worth engine (issue 2.6; FR-ACC-005).
     * Why:    the one place `assets − liabilities` is computed. The repository hands it balances and
     *         stores what it returns; the dashboard renders what was stored. Neither adds up a rupee
     *         of its own (P-03).
     * Result: a [NetWorthEngine]. Input: none. Output: the engine.
     * Changelog: 2026-08-01 — Created for issue 2.6.
     */
    @Provides
    @Singleton
    fun provideNetWorthEngine(): NetWorthEngine = NetWorthEngineFactory.create()

    /**
     * The recurring detector (issue 3.7; FR-TXN-006).
     * Why:    the one place the ledger is searched for a repeating pattern. It proposes and the user
     *         decides (P-07); the repository writes only what they confirmed, and computes nothing
     *         of its own (P-03).
     * Result: a [RecurringEngine]. Input: none. Output: the engine.
     * Changelog: 2026-08-05 — Created for issue 3.7.
     */
    @Provides
    @Singleton
    fun provideRecurringEngine(): RecurringEngine = RecurringEngineFactory.create()

    /**
     * The receipt parser (issue 3.8; FR-OCR-003, §18.1).
     * Why:    the one place recognised text becomes an amount, a date and a merchant. It reads text
     *         someone else recognised and computes every figure itself (P-03); the recogniser above
     *         it is Android-only and lives in `:ml:ocr`, which is why the two are separate bindings.
     * Result: a [ReceiptEngine]. Input: none. Output: the engine.
     * Changelog: 2026-08-06 — Created for issue 3.8.
     */
    @Provides
    @Singleton
    fun provideReceiptEngine(): ReceiptEngine = ReceiptEngineFactory.create()

    /**
     * The on-device text recogniser (issue 3.8; FR-OCR-002, P-01, P-04).
     * Why:    bound here beside the parser it feeds, though it is not an engine in the `:domain:*`
     *         sense — it is the only other class in the receipt pipeline whose implementation is
     *         `internal` behind a factory, and keeping the pair together is what makes the split
     *         legible: this one needs a device, the one above it does not.
     *
     *         **A singleton because the model is loaded once.** Building a client per scan would
     *         reload it on every photograph, which is a visible pause on the screen the user is
     *         already waiting on.
     * Result: a [ReceiptTextRecognizer]. Input: none. Output: the recogniser.
     * Changelog: 2026-08-06 — Created for issue 3.8.
     */
    @Provides
    @Singleton
    fun provideReceiptTextRecognizer(): ReceiptTextRecognizer = ReceiptTextRecognizerFactory.create()

    /**
     * The bank-alert parser (issue 3.9; §18, §23, P-03).
     * Why:    the one place a message becomes an amount and a direction. Pure Kotlin, so it holds no
     *         permission and touches no inbox — the reader below it is the Android half, which is
     *         why the two are separate bindings, exactly as the receipt pair above is.
     * Result: an [SmsEngine]. Input: none. Output: the engine.
     * Changelog: 2026-08-07 — Created for issue 3.9.
     */
    @Provides
    @Singleton
    fun provideSmsEngine(): SmsEngine = SmsEngineFactory.create()

    /**
     * The phone's inbox (issue 3.9; §18, §23, P-01, ADR-0013).
     * Why:    bound here beside the parser it feeds, the same pairing the receipt pipeline uses.
     *
     *         **Binding it does not read anything.** The reader checks the OS permission on every
     *         call and `SmsRepository` checks the in-app consent before calling it at all, so a
     *         graph that can construct one is not a graph that can see a message.
     * Result: an [SmsInboxReader]. Input: [context] — for its `ContentResolver`; [dispatchers].
     * Output: the reader.
     * Changelog: 2026-08-07 — Created for issue 3.9.
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
     * Changelog: 2026-08-06 — Created for issue 3.8.
     */
    @Provides
    @Singleton
    fun provideReceiptImageStore(
        @ApplicationContext context: Context,
    ): ReceiptImageStore = ReceiptImageStoreFactory.create(context)
}

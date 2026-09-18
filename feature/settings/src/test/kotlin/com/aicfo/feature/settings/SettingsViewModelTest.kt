package com.aicfo.feature.settings

import app.cash.turbine.test
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.crypto.PinVerifier
import com.aicfo.core.datastore.AppLockSettings
import com.aicfo.core.datastore.AppLockStore
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentState
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.datastore.OnboardingProfile
import com.aicfo.core.datastore.QuickSetupSeeds
import com.aicfo.core.datastore.SettingsSnapshot
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.core.datastore.ThemeSetting
import com.aicfo.data.repository.BackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The settings screen's state, and the two invariants that make it worth having (FR-SET-001).
 *
 * Why:  this module exists to close a P-01 violation — the SMS consent could be granted at
 *       onboarding and never revoked, because nothing could call `ConsentStore.revoke`. So the
 *       assertions that matter here are not "the switch moved" but **"the store was told"**, and
 *       "a failed write never leaves a switch claiming a feature is off while it is still on".
 *       For a privacy control that lie is the entire risk.
 * What: consent grant and revoke reaching the store, every feature being listed, the lock's
 *       PIN-before-flag ordering, and the money plan's validation.
 * Result: the behaviour the golden rule depends on, assertable without a device.
 * Changelog: 2026-08-29 — Created for FR-SET-001.
 *   2026-09-18 — Issue 8.1: the encrypted-backup flow — its consent gate, the passphrase leaving
 *   state the moment it is used, and a revocation dropping a sealed backup nobody has saved yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val consents = FakeConsentStore()
    private val appLock = FakeAppLockStore()
    private val pins = FakePinVerifier()
    private val backups = FakeBackupRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- consents (P-01) -------------------------------------------------------------------------

    @Test
    fun `every consent feature gets a switch, including ones never touched`() =
        runTest {
            viewModel().uiState.test {
                val state = awaitItem()

                assertEquals(
                    "a consent with no switch is a consent that cannot be revoked",
                    ConsentFeature.entries.toSet(),
                    state.consents.keys,
                )
                assertTrue("absence is never consent", state.consents.values.none { it })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `revoking a consent reaches the store — the defect this module exists to close`() =
        runTest {
            consents.granted.value = setOf(ConsentFeature.SMS_PARSING)
            val vm = viewModel()

            vm.onEvent(SettingsEvent.ConsentToggled(ConsentFeature.SMS_PARSING, granted = false))

            assertTrue("revoke must reach the ledger", ConsentFeature.SMS_PARSING in consents.revoked)
            vm.uiState.test {
                assertFalse(awaitItem().consents.getValue(ConsentFeature.SMS_PARSING))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `granting a consent reaches the store`() =
        runTest {
            val vm = viewModel()

            vm.onEvent(SettingsEvent.ConsentToggled(ConsentFeature.MARKET_DATA, granted = true))

            assertTrue(ConsentFeature.MARKET_DATA in consents.grantedCalls)
        }

    @Test
    fun `a failed revocation raises the banner and leaves the switch on`() =
        runTest {
            consents.granted.value = setOf(ConsentFeature.SMS_PARSING)
            consents.failWith = AppError.Storage("disk")
            val vm = viewModel()

            vm.onEvent(SettingsEvent.ConsentToggled(ConsentFeature.SMS_PARSING, granted = false))

            vm.uiState.test {
                val state = awaitItem()
                assertEquals("storage", state.errorCode)
                assertTrue(
                    "a switch that says off while the consent is still on is the one lie this screen must not tell",
                    state.consents.getValue(ConsentFeature.SMS_PARSING),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- the app lock (SEC-002) ------------------------------------------------------------------

    @Test
    fun `enabling the lock writes the PIN before it flips the flag`() =
        runTest {
            val vm = viewModel()
            vm.onEvent(SettingsEvent.PinChanged("2468"))

            vm.onEvent(SettingsEvent.AppLockToggled(enabled = true))

            assertEquals(listOf("setPin", "setEnabled"), pins.order + appLock.order)
            assertEquals("2468", pins.stored)
        }

    @Test
    fun `enabling the lock without a long enough PIN is refused and writes nothing`() =
        runTest {
            val vm = viewModel()
            vm.onEvent(SettingsEvent.PinChanged("12"))

            vm.onEvent(SettingsEvent.AppLockToggled(enabled = true))

            assertTrue("no PIN may be stored", pins.stored == null)
            assertFalse("and the lock must stay off", appLock.enabled.value)
            vm.uiState.test {
                assertEquals("pin", awaitItem().fieldError)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `turning the lock off clears the PIN so a re-enable cannot inherit it`() =
        runTest {
            pins.stored = "1111"
            appLock.enabled.value = true
            val vm = viewModel()

            vm.onEvent(SettingsEvent.AppLockToggled(enabled = false))

            assertFalse(appLock.enabled.value)
            assertTrue("a stale PIN left behind is a lock the user did not choose", pins.stored == null)
        }

    // --- the money plan ---------------------------------------------------------------------------

    @Test
    fun `saving with no income is refused as a field error, not a banner`() =
        runTest {
            val vm = viewModel()

            vm.onEvent(SettingsEvent.SaveMoneyPlan)

            vm.uiState.test {
                val state = awaitItem()
                assertEquals("monthlyIncome", state.fieldError)
                assertEquals("input the user can fix is not a failure", null, state.errorCode)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `typing an income makes the plan saveable`() =
        runTest {
            val vm = viewModel()

            vm.onEvent(SettingsEvent.MonthlyIncomeChanged("95000"))

            vm.uiState.test {
                assertTrue(awaitItem().canSaveMoney)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- the encrypted backup (issue 8.1; SEC-005, P-01) -------------------------------------------

    @Test
    fun `without the backup consent the only thing the section asks for is the consent`() =
        runTest {
            val vm = viewModel()
            vm.fillValidBackupForm()

            vm.uiState.test {
                val state = awaitItem()
                assertEquals(BackupBlocker.CONSENT, state.backupBlocker)
                assertFalse(state.canCreateBackup)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the blocker names the next thing to fix, in order`() =
        runTest {
            consents.granted.value = setOf(ConsentFeature.CLOUD_BACKUP)
            val vm = viewModel()

            vm.onEvent(SettingsEvent.BackupPassphraseChanged("eleven char"))
            assertEquals(BackupBlocker.TOO_SHORT, vm.uiState.value.backupBlocker)

            vm.onEvent(SettingsEvent.BackupPassphraseChanged(PASSPHRASE))
            vm.onEvent(SettingsEvent.BackupConfirmationChanged(PASSPHRASE.dropLast(1)))
            assertEquals(BackupBlocker.MISMATCH, vm.uiState.value.backupBlocker)

            vm.onEvent(SettingsEvent.BackupConfirmationChanged(PASSPHRASE))
            assertEquals(BackupBlocker.NOT_ACKNOWLEDGED, vm.uiState.value.backupBlocker)

            vm.onEvent(SettingsEvent.BackupAcknowledged(checked = true))
            assertEquals(null, vm.uiState.value.backupBlocker)
            assertTrue(vm.uiState.value.canCreateBackup)
        }

    @Test
    fun `a passphrase of exactly twelve characters is long enough`() =
        runTest {
            consents.granted.value = setOf(ConsentFeature.CLOUD_BACKUP)
            val vm = viewModel()

            vm.onEvent(SettingsEvent.BackupPassphraseChanged("twelve chars"))
            vm.onEvent(SettingsEvent.BackupConfirmationChanged("twelve chars"))
            vm.onEvent(SettingsEvent.BackupAcknowledged(checked = true))

            assertTrue(vm.uiState.value.canCreateBackup)
        }

    @Test
    fun `creating a backup hands the passphrase over and clears it from the screen state`() =
        runTest {
            consents.granted.value = setOf(ConsentFeature.CLOUD_BACKUP)
            val vm = viewModel()
            vm.fillValidBackupForm()

            vm.onEvent(SettingsEvent.CreateBackup)

            assertEquals(PASSPHRASE, backups.received.single())
            val state = vm.uiState.value
            assertEquals("", state.backup.passphraseText)
            assertEquals("", state.backup.confirmationText)
            assertFalse("the acknowledgement is asked again for the next backup", state.backup.acknowledged)
            val status = state.backup.status
            assertTrue(status is BackupStatus.ReadyToWrite)
            assertTrue((status as BackupStatus.ReadyToWrite).bytes.contentEquals(FakeBackupRepository.SEALED))
        }

    @Test
    fun `a blocked create never reaches the repository`() =
        runTest {
            val vm = viewModel()
            vm.fillValidBackupForm()

            vm.onEvent(SettingsEvent.CreateBackup)

            assertTrue(backups.received.isEmpty())
            assertEquals(BackupStatus.Idle, vm.uiState.value.backup.status)
        }

    @Test
    fun `a refused backup shows the refusal's code`() =
        runTest {
            consents.granted.value = setOf(ConsentFeature.CLOUD_BACKUP)
            backups.result = Err(AppError.Validation("backup.consent"))
            val vm = viewModel()
            vm.fillValidBackupForm()

            vm.onEvent(SettingsEvent.CreateBackup)

            assertEquals(BackupStatus.Failed("backup.consent"), vm.uiState.value.backup.status)
        }

    @Test
    fun `a crypto failure shows its error code`() =
        runTest {
            consents.granted.value = setOf(ConsentFeature.CLOUD_BACKUP)
            backups.result = Err(AppError.Crypto("backup.seal"))
            val vm = viewModel()
            vm.fillValidBackupForm()

            vm.onEvent(SettingsEvent.CreateBackup)

            assertEquals(BackupStatus.Failed("crypto"), vm.uiState.value.backup.status)
        }

    @Test
    fun `the picker's outcome is reported — written, or not`() =
        runTest {
            val vm = viewModel()

            vm.onEvent(SettingsEvent.BackupWritten(written = true))
            assertEquals(BackupStatus.Written, vm.uiState.value.backup.status)

            vm.onEvent(SettingsEvent.BackupWritten(written = false))
            assertEquals(BackupStatus.Failed("backup.writeFailed"), vm.uiState.value.backup.status)

            vm.onEvent(SettingsEvent.BackupDismissed)
            assertEquals(BackupStatus.Idle, vm.uiState.value.backup.status)
        }

    @Test
    fun `revoking the consent drops a sealed backup that has not been saved yet`() =
        runTest {
            consents.granted.value = setOf(ConsentFeature.CLOUD_BACKUP)
            val vm = viewModel()
            vm.fillValidBackupForm()
            vm.onEvent(SettingsEvent.CreateBackup)
            assertTrue(vm.uiState.value.backup.status is BackupStatus.ReadyToWrite)

            vm.onEvent(SettingsEvent.ConsentToggled(ConsentFeature.CLOUD_BACKUP, granted = false))

            assertEquals(
                "P-01: a revoked consent stops the data path, including one already half-way out",
                BackupStatus.Idle,
                vm.uiState.value.backup.status,
            )
        }

    // --- fakes ------------------------------------------------------------------------------------

    /** Input: none. Output: the backup form filled so only the consent can block it. */
    private fun SettingsViewModel.fillValidBackupForm() {
        onEvent(SettingsEvent.BackupPassphraseChanged(PASSPHRASE))
        onEvent(SettingsEvent.BackupConfirmationChanged(PASSPHRASE))
        onEvent(SettingsEvent.BackupAcknowledged(checked = true))
    }

    /** Result: a ViewModel over the fakes. The money writer is null-object: its own test covers it. */
    private fun viewModel() =
        SettingsViewModel(
            settingsStore = FakeSettingsStore(),
            consentStore = consents,
            appLockStore = appLock,
            pinVerifier = pins,
            moneyPlan = NeverCalledMoneyPlanWriter,
            backupRepository = backups,
        )

    /**
     * Stands in for the money writer, which none of these tests reaches: the validation path
     * short-circuits before it is called, and its own behaviour is covered by
     * [MoneyPlanWriterTest] against the real engine.
     */
    private object NeverCalledMoneyPlanWriter : MoneyPlanWriter {
        override suspend fun save(
            incomeText: String,
            rentText: String,
            savingsText: String,
        ): Result<Unit, AppError> = error("the money writer must not be reached by these tests")
    }

    /** A settings store with nothing stored: the screen must cope with a profile that skipped setup. */
    private class FakeSettingsStore : SettingsStore {
        private val snapshot = MutableStateFlow(SettingsSnapshot())

        override fun observe(): Flow<Result<SettingsSnapshot, AppError>> = snapshot.map { Ok(it) }

        override suspend fun setProfileTimeZone(zoneId: String): Result<Unit, AppError> = Ok(Unit)

        override suspend fun setCurrencyCode(currencyCode: String): Result<Unit, AppError> = Ok(Unit)

        override suspend fun setPrivacyBlurEnabled(enabled: Boolean): Result<Unit, AppError> = Ok(Unit)

        override suspend fun setTheme(theme: ThemeSetting): Result<Unit, AppError> = Ok(Unit)

        override suspend fun setQuickSetupSeeds(seeds: QuickSetupSeeds): Result<Unit, AppError> {
            snapshot.value = snapshot.value.copy(quickSetup = seeds)
            return Ok(Unit)
        }

        override suspend fun completeOnboarding(profile: OnboardingProfile): Result<Unit, AppError> = Ok(Unit)

        override suspend fun setDemoModeActive(active: Boolean): Result<Unit, AppError> = Ok(Unit)

        override suspend fun setSmsScanCursor(smsId: Long): Result<Unit, AppError> = Ok(Unit)
    }

    private class FakeConsentStore : ConsentStore {
        val granted = MutableStateFlow<Set<ConsentFeature>>(emptySet())
        val revoked = mutableSetOf<ConsentFeature>()
        val grantedCalls = mutableSetOf<ConsentFeature>()
        var failWith: AppError? = null

        override fun observe(feature: ConsentFeature): Flow<Result<ConsentState, AppError>> =
            granted.map { Ok(ConsentState(granted = feature in it)) }

        override fun observeAll(): Flow<Result<Map<ConsentFeature, ConsentState>, AppError>> =
            granted.map { set -> Ok(ConsentFeature.entries.associateWith { ConsentState(granted = it in set) }) }

        override suspend fun grant(feature: ConsentFeature): Result<Unit, AppError> {
            failWith?.let { return Err(it) }
            grantedCalls += feature
            granted.value = granted.value + feature
            return Ok(Unit)
        }

        override suspend fun revoke(feature: ConsentFeature): Result<Unit, AppError> {
            failWith?.let { return Err(it) }
            revoked += feature
            granted.value = granted.value - feature
            return Ok(Unit)
        }
    }

    private class FakeAppLockStore : AppLockStore {
        val enabled = MutableStateFlow(false)
        val order = mutableListOf<String>()

        override fun observe(): Flow<Result<AppLockSettings, AppError>> =
            enabled.map { Ok(AppLockSettings(enabled = it)) }

        override suspend fun setEnabled(enabled: Boolean): Result<Unit, AppError> {
            order += "setEnabled"
            this.enabled.value = enabled
            return Ok(Unit)
        }

        override suspend fun setBiometricEnabled(enabled: Boolean): Result<Unit, AppError> = Ok(Unit)

        override suspend fun setAutoLockTimeoutSeconds(seconds: Int): Result<Unit, AppError> = Ok(Unit)

        override suspend fun recordFailedUnlock(): Result<Unit, AppError> = Ok(Unit)

        override suspend fun clearFailedUnlocks(): Result<Unit, AppError> = Ok(Unit)
    }

    private class FakePinVerifier : PinVerifier {
        var stored: String? = null
        val order = mutableListOf<String>()

        override fun isPinSet(): Result<Boolean, AppError> = Ok(stored != null)

        override fun setPin(pin: String): Result<Unit, AppError> {
            order += "setPin"
            stored = pin
            return Ok(Unit)
        }

        override fun verify(pin: String): Result<Boolean, AppError> = Ok(pin == stored)

        override fun clearPin(): Result<Unit, AppError> {
            stored = null
            return Ok(Unit)
        }
    }

    /** Records what it was asked to seal, and answers with [result]. */
    private class FakeBackupRepository : BackupRepository {
        val received = mutableListOf<String>()
        var result: Result<ByteArray, AppError> = Ok(SEALED)

        override suspend fun create(passphrase: CharArray): Result<ByteArray, AppError> {
            received += String(passphrase)
            return result
        }

        companion object {
            val SEALED = byteArrayOf(0x43, 0x46, 0x4F, 0x42)
        }
    }

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"
    }
}

// :data:repository — the ONLY modules that touch DAOs or network (ARC-005). Exposes
// domain models to ViewModels; depends downward on core + domain.
plugins {
    alias(libs.plugins.cfo.android.library)
}

android {
    namespace = "com.aicfo.data.repository"

    testOptions {
        // Robolectric needs the merged Android resources to boot its runtime.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // api, not implementation: AuditEvent/AuditMethod appear on AuditLogRepository's public
    // surface, so callers (the DI graph, the lock ViewModel) must be able to name them.
    api(project(":core:model"))
    api(project(":core:common"))
    implementation(project(":core:database"))
    // Issue 2.4: demo mode's on/off flag is a setting, and settings live in Proto DataStore
    // (§21.3, SharedPreferences banned). data → core is the allowed direction (ARC-001).
    implementation(project(":core:datastore"))
    implementation(project(":domain:usecase"))

    // api: QuickSetupPlan and BudgetEnvelope appear on QuickSetupRepository's public surface, so
    // the ViewModels that call it must be able to name them (issue 2.3).
    api(project(":domain:engines:quicksetup"))
    // Issue 3.8: the receipt pipeline. `api` on all three, because ReceiptFields, RecognizedText
    // and StoredImage all appear on ReceiptRepository's public surface — the ViewModel pre-fills
    // from the first and the DI graph names the others (FR-OCR-002/003/005).
    api(project(":domain:engines:receipt"))
    api(project(":ml:ocr"))
    api(project(":core:crypto"))

    // Issue 3.9: the SMS pipeline. `api` on both, for the reason the receipt line above gives —
    // SmsDraftFields' types and SmsInboxReader appear on SmsRepository's public surface, so the
    // ViewModel and the DI graph must be able to name them (§18, §23, P-01).
    api(project(":domain:engines:sms"))
    api(project(":data:sms"))

    // Issue 2.6: the net-worth engine FR-ACC-005 requires; the repository hands it balances
    // and stores what it returns, computing nothing of its own (P-03).
    api(project(":domain:engines:networth"))
    // Issue 3.7: FR-TXN-006's detector. `api` — RecurringSeries is what observeSuggestions emits,
    // so the transactions ViewModel must be able to name it (the reasoning `:networth` set above).
    api(project(":domain:engines:recurring"))
    // Issue 4.2: SRS §8.1 Stage 1. `api` — CategorySuggestion is what suggestCategory returns, so
    // the add-transaction ViewModel must be able to name it (the reasoning `:networth` set above).
    api(project(":domain:engines:classification"))
    // Issue 4.3: SRS §8.3. `api` — NatureVerdict and NatureBreakdown are what natureOf and
    // observeNatureBreakdown return, so the detail sheet and the dashboard must be able to name them.
    api(project(":domain:engines:nature"))
    // Issue 4.4: SRS §5.5. `api` — BudgetStatus and BudgetSuggestion are what CategoryBudget and
    // CategoryBudgetSuggestion expose, so the budgets ViewModel must be able to name them.
    api(project(":domain:engines:budget"))
    // Issue 5.2: SRS §5.2/§14. `api` — SafeToSpend is what observeSafeToSpend emits, breakdown and
    // all, so the dashboard must be able to name it and every line on it (the reasoning `:networth`
    // set above).
    api(project(":domain:engines:safetospend"))

    // withTransaction — the atomicity guarantee applySeeds is built on (issue 2.3).
    implementation(libs.room.ktx)

    // Issue 3.6: FR-TXN-007's "infinite scroll via paging". `api`, not `implementation` — a
    // repository read returns Flow<PagingData<Transaction>>, so it is on the public surface and the
    // ViewModels that collect it must be able to name the type. PagingData is a container over a
    // :core:model type, not a Room type, so ARC-005 is untouched: no DAO or entity escapes here.
    api(libs.androidx.paging.runtime)
    // Lets TransactionDao's @Query return a PagingSource that Room invalidates on every write.
    implementation(libs.room.paging)
    testImplementation(libs.androidx.paging.testing)

    // Issue 2.2: the repository's SQL and mapping are tested against a real SQLite engine on the
    // JVM. Unencrypted and in-memory on purpose — SQLCipher needs a device, and what is under test
    // here is the query, not the encryption (see the test's class doc).
    testImplementation(libs.robolectric)
    // Brings androidx.test:core transitively, which is where ApplicationProvider lives.
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // FakeClock / TestDispatchers (issue 1.3).
    testImplementation(testFixtures(project(":core:common")))
}

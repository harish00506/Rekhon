// :core:common — pure Kotlin/JVM — injected Clock, DispatcherProvider, Result plumbing.
//
// Why:  issue 1.3 adds the time + concurrency seams (TIM-001, ARC-006). `java-test-fixtures`
//       publishes FakeClock/TestDispatchers so every later module injects the same test doubles
//       instead of writing its own (task 1.1.3 DoD).
// Note: no kotlinx-datetime — minSdk 26 has java.time natively (NFR-008), so the pinned §21.3
//       stack needs no new dependency for this.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
    `java-test-fixtures`
}

dependencies {
    // api: CoroutineDispatcher appears on DispatcherProvider's public surface.
    api(libs.kotlinx.coroutines.core)

    testFixturesApi(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}

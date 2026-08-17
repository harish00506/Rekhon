// :widget — the Glance home-screen widget, rendering CACHED figures offline (issue 5.5, P-04).
//
// Why this is not a `:feature:*` module: it has no ViewModel, no nav graph and no Hilt graph. The
// widget is a pure function of its Glance state — everything that *computes* lives in :app's
// WidgetRefreshWorker, which is what keeps the render path off the encrypted database (SEC-002:
// `provideDatabase` throws while the app is locked, and a home screen is mostly looked at locked).
// So `cfo.android.feature` would drag in navigation-compose and Hilt for nothing.
plugins {
    alias(libs.plugins.cfo.android.library)
    // Glance is Compose-shaped: it needs the Compose compiler plugin. This convention plugin also
    // brings the Compose BOM, which `glance-material3` resolves its Material3 types against.
    alias(libs.plugins.cfo.android.compose)
}

android {
    namespace = "com.aicfo.widget"

    testOptions {
        // Issue 5.5: `runGlanceAppWidgetUnitTest` composes against a real Context, so the widget's
        // own strings.xml has to be on the classpath. Without this every `getString` comes back
        // blank — and a blank label would let "no amount is on the widget" pass against a widget
        // with no text at all. Same reason :feature:dashboard sets it.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Money (Long paise, MNY-001) and MoneyFormatter.format/mask — the only thing this module
    // needs to turn a cached figure into a string. Issue 5.5 moved `mask` here from
    // :core:designsystem precisely so the widget would not have to depend on Material3.
    implementation(project(":core:model"))

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    testImplementation(libs.glance.appwidget.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// :lint — custom Android Lint detectors (issue 1.5, SRS §21.3/§21.4/§21.6).
//
// Why:  CLAUDE.md's loudest rules (no Double on money, no wall clock in domain, no GlobalScope,
//       no hardcoded UI strings, no PII in logs) were review-blocking only — nothing in the build
//       checked them. These detectors make a violation fail `./gradlew lint`.
// What: a plain java-library holding Detectors + an IssueRegistry, consumed by every module via
//       `lintChecks(project(":lint"))` in the convention plugins.
// Note: deliberately NOT on the cfo.kotlin.library convention plugin. This is build tooling that
//       ships in no APK; the ARC-002 guard and the coverage floor exist for product code, and
//       lint's own API surface makes a line-coverage bound meaningless here.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    // §21.6 applies to every module, this one included — the enforcement layer does not get to
    // skip the style gate. Applied directly rather than via configureQuality(), which also brings
    // Kover: a line-coverage bound on lint detectors would measure the wrong thing, since their
    // real proof is the fixture suite plus a seeded violation failing a real build.
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    ignoreFailures = false
    basePath = rootDir.absolutePath
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // compileOnly: the lint runtime supplies these at execution time. Bundling them would put two
    // copies of the lint API on the classpath.
    compileOnly(libs.lint.api)
    compileOnly(libs.lint.checks)

    testImplementation(libs.junit4)
    testImplementation(libs.lint.api)
    testImplementation(libs.lint.tests)
}

tasks.jar {
    manifest {
        // How lint discovers the registry when this jar is added via lintChecks(...).
        attributes("Lint-Registry-v2" to "com.aicfo.lint.CfoIssueRegistry")
    }
}

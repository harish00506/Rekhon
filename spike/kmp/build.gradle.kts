// :spike:kmp — issue 13.7's feasibility spike. NOT shipped: no app module depends on it, and it is
// excluded from the release build. It exists to answer one question with a compiler rather than an
// opinion — can this project's money arithmetic run on Kotlin/Native, which is what an iOS port
// actually requires? See ADR-0075.
plugins {
    // No version and no catalog alias: the Kotlin Multiplatform plugin is already on the build
    // classpath, because `build-logic` depends on `kotlin-gradle-plugin` which bundles it. Asking
    // for it by alias fails with "already on the classpath with an unknown version", so **nothing
    // new is added to the build** — this module applies a plugin the project already carries.
    kotlin("multiplatform")
}

kotlin {
    jvm()
    // Kotlin/Native. `linuxX64` rather than an iOS target on purpose: iOS targets can only be built
    // on macOS, and this machine is Linux — but BOTH are Kotlin/Native and share the same stdlib,
    // so a `java.*` import fails identically on either. The portability question is answered the
    // same way; only the final linking differs. ADR-0075 says so explicitly rather than implying
    // this spike proves an iOS build.
    linuxX64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // JVM only, and only for tests: the equivalence check needs the real Money to compare
        // against, and :core:model is a JVM library. Kotlin/Native never sees this — which is
        // the point, since :core:model is exactly what cannot compile there today.
        jvmTest.dependencies {
            implementation(project(":core:model"))
        }
    }
}

// `PortabilityAuditTest` walks `:core:model` and `:domain:*` at runtime, counting java.* imports.
// Neither directory is an input of this module's test task, so without these lines Gradle would
// call it UP-TO-DATE on exactly the edits it exists to notice — the staleness bug issues 7.2, 11.5,
// 11.7, 13.1 and 13.5 each found in a different guise.
// The concrete `src/main` directories the audit reads, resolved at configuration time.
//
// Not `fileTree("domain")` with an include pattern: Gradle validates an input against every task
// OUTPUT it contains, and `domain/` holds thirty `build/` directories. Naming the source folders
// individually keeps the input to things no task writes, which is also the honest description of
// what the audit actually reads.
val portableLayerSources: List<File> =
    buildList {
        add(rootProject.file("core/model/src/main"))
        add(rootProject.file("build-logic/convention/src/main"))
        rootProject.file("domain").listFiles().orEmpty().forEach { group ->
            val direct = File(group, "src/main")
            if (direct.isDirectory) {
                add(direct)
            } else {
                group.listFiles().orEmpty().map { File(it, "src/main") }.filter { it.isDirectory }.forEach(::add)
            }
        }
    }

tasks.withType<Test>().configureEach {
    inputs.files(portableLayerSources)
        .withPropertyName("portableLayerSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

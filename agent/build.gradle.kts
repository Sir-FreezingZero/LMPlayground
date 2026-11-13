plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

// The autonomous agent core: project store, scheduler, memory, context
// building, watchdog, tools and the step engine. Deliberately plain
// Kotlin/JVM (like :llamacpp) with no Android or third-party dependencies,
// so all of it is unit-testable on a desktop JVM — including crash/restart
// and long-horizon simulations. Android specifics (foreground worker, the
// llama.cpp-backed LlmBackend, the V8 sandbox runner, UI) live in :app.

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

tasks.test {
    maxHeapSize = "1g"
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

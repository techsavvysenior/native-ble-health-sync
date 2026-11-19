import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin, no Android: parsers, reconnect state machine, reading ids and the upload queue.
// Tested on the JVM against the shared vectors in ../spec.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

val specDir = rootDir.resolve("../spec")

tasks.test {
    systemProperty("pulselog.spec", specDir.absolutePath)
    inputs.dir(specDir).withPropertyName("spec")
    testLogging {
        events("passed", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: prediction, correction and adaptive touch areas. No Android
// dependencies, so it can be unit-tested on any machine.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging { showStandardStreams = true; events("passed", "failed") }
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("junit:junit:4.13.2")
}

// Fetches today's real prices and weather and prints the plan. Not part of
// the normal test run: it needs the network and the answers change by the hour.
tasks.register<JavaExec>("liveCheck") {
    group = "verification"
    description = "Haalt echte prijzen en weerdata op en drukt het laadplan af."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("nl.laadmoment.core.LiveCheckKt")
}

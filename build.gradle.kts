plugins {
    kotlin("jvm") version "2.0.21"
}

group = "com.actors"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val coroutinesVersion = "1.9.0"

dependencies {
    // Kotlin coroutines — foundation for actor scheduling and channel-based mailboxes
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.8")
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Test
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.2")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")

    // Lincheck — JetBrains linearizability checker
    // Model checking: explores ALL thread interleavings deterministically
    // Stress testing: runs massive concurrent load with managed thread scheduling
    testImplementation("org.jetbrains.kotlinx:lincheck:2.34")

    // jqwik — Property-Based Testing with stateful testing support
    testImplementation("net.jqwik:jqwik:1.9.2")
    testImplementation("net.jqwik:jqwik-kotlin:1.9.2")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(
        "-Xms512m", "-Xmx1g",
        "-XX:+UseZGC", "-XX:+ZGenerational",
        // Required by Lincheck for bytecode transformation (model checking mode)
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.util=ALL-UNNAMED",
        "--add-exports", "java.base/sun.security.action=ALL-UNNAMED"
    )
}

kotlin {
    jvmToolchain(21)
}

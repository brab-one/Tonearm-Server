plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    application
}

group = "io.github.deadeyebarb"
version = "1.2.0"

kotlin { jvmToolchain(21) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.github.deadeyebarb.tonearm.server.MainKt")
    applicationName = "tonearm-server"
    applicationDefaultJvmArgs = listOf("-Xmx96m", "-Xss512k", "-XX:+UseSerialGC")
}

tasks.jar { manifest { attributes("Implementation-Version" to project.version) } }

tasks.test { useJUnitPlatform() }

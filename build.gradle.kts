plugins {
    // This tells Gradle to use the Kotlin compiler for JVM
    kotlin("jvm") version "2.1.0"
    // Allows creating a runnable application
    id("application")
}

group = "io.raptor"
version = "1.0-SNAPSHOT"

repositories {
    // Where to download libraries (like Kotlin stdlib)
    mavenCentral()
}

dependencies {
    // Kotlin Standard Library
    implementation(kotlin("stdlib"))

    // Test framework
    testImplementation(kotlin("test-junit5"))
}

application {
    // The main class to run
    mainClass.set("io.raptor.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(11) // Ensure we use Java 11
}
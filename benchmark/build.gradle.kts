plugins {
    kotlin("jvm")
    id("me.champeau.jmh") version "0.7.3"
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

// Include raptor-kt main sources directly (pure JVM, no Android APIs)
sourceSets {
    main {
        kotlin.srcDir("../src/main/kotlin")
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
    // JSON parsing for report generator
    jmhImplementation("org.json:json:20231013")
}

jmh {
    fork = 3
    warmupIterations = 10
    iterations = 20
    resultFormat = "JSON"
    resultsFile = project.file("build/reports/jmh/results.json")
    jvmArgs = listOf("-Xmx1g", "-Xms512m")
    jvmArgsAppend = listOf("-Draptor.dataRoot=${rootProject.projectDir}")
    profilers = listOf("gc")
    includes = listOf("io\\.raptor\\.benchmark\\..*")
}

tasks.test {
    systemProperty("raptor.dataRoot", rootProject.projectDir.absolutePath)
    jvmArgs("-Xmx512m")
}

tasks.register<JavaExec>("generateReport") {
    group = "benchmark"
    description = "Generate BENCHMARK_RESULTS.md from JMH JSON output"
    classpath = sourceSets["jmh"].runtimeClasspath
    mainClass.set("io.raptor.benchmark.ReportGenerator")
    args = listOf(
        project.file("build/reports/jmh/results.json").absolutePath,
        rootProject.file("BENCHMARK_RESULTS.md").absolutePath
    )
    workingDir = rootProject.projectDir
}

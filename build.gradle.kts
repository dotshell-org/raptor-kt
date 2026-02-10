buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
    }
}

plugins {
    id("com.android.library") version "8.2.0"
    kotlin("android") version "2.1.0"
    id("maven-publish")
    id("signing")
    id("com.gradleup.nmcp") version "0.0.8"
}

group = "eu.dotshell"
version = "1.1.0"

android {
    namespace = "eu.dotshell.raptor"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

val demoRuntime = configurations.create("demoRuntime") {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    implementation(kotlin("stdlib"))
    
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    demoRuntime(kotlin("stdlib"))
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "eu.dotshell"
            artifactId = "raptor-kt"
            version = "1.3.0"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("Raptor-KT")
                description.set("RAPTOR algorithm implementation in Kotlin for Android")
                url.set("https://github.com/yourusername/raptor-kt")
                
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                
                developers {
                    developer {
                        id.set("tristan")
                        name.set("Tristan")
                        email.set("contact@dotshell.eu")
                    }
                }
                
                scm {
                    connection.set("scm:git:git://github.com/yourusername/raptor-kt.git")
                    developerConnection.set("scm:git:ssh://github.com:yourusername/raptor-kt.git")
                    url.set("https://github.com/yourusername/raptor-kt")
                }
            }
        }
    }
}

nmcp {
    publishAllProjectsProbablyBreakingProjectIsolation {
        username = findProperty("ossrhUsername") as String? ?: System.getenv("OSSRH_USERNAME")
        password = findProperty("ossrhPassword") as String? ?: System.getenv("OSSRH_PASSWORD")
        publicationType = "AUTOMATIC"
    }
}

signing {
    val signingKeyId = findProperty("signing.keyId") as String? ?: System.getenv("SIGNING_KEY_ID")
    val signingPassword = findProperty("signing.password") as String? ?: System.getenv("SIGNING_PASSWORD")
    val signingKeyRingFile = findProperty("signing.secretKeyRingFile") as String? ?: System.getenv("SIGNING_KEY_RING_FILE")
    
    if (signingKeyId != null && signingPassword != null && signingKeyRingFile != null) {
        // Use GPG signing with local keyring
        extra["signing.keyId"] = signingKeyId
        extra["signing.password"] = signingPassword
        extra["signing.secretKeyRingFile"] = signingKeyRingFile
        sign(publishing.publications["release"])
    }
}

tasks.register<JavaExec>("runRouteFilterDemo") {
    group = "verification"
    description = "Runs a route filter demo that prints results to the terminal."
    val debugUnitTestClasses = layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest")
    val debugMainClasses = layout.buildDirectory.dir("tmp/kotlin-classes/debug")
    classpath = files(debugUnitTestClasses, debugMainClasses) + demoRuntime
    mainClass.set("io.raptor.RouteFilterDemo")
    dependsOn("compileDebugKotlin", "compileDebugUnitTestKotlin")
}

tasks.register<JavaExec>("runBenchmark") {
    group = "verification"
    description = "Runs the RAPTOR performance benchmark."
    val debugUnitTestClasses = layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest")
    val debugMainClasses = layout.buildDirectory.dir("tmp/kotlin-classes/debug")
    classpath = files(debugUnitTestClasses, debugMainClasses) + demoRuntime
    mainClass.set("io.raptor.Benchmark")
    dependsOn("compileDebugKotlin", "compileDebugUnitTestKotlin")
}

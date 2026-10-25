import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val canonicalExamples = rootProject.layout.projectDirectory.dir("../api/examples")
val generateCanonicalTestFixtures by tasks.registering {
    val inputsTree = fileTree(canonicalExamples) { include("*.json") }
    val outputDir = layout.buildDirectory.dir("generated/canonicalTestFixtures")
    inputs.files(inputsTree)
    outputs.dir(outputDir)
    doLast {
        fun kotlinString(value: String): String = buildString {
            append('"')
            value.forEach { character ->
                append(when (character) {
                    '\\' -> "\\\\"
                    '"' -> "\\\""
                    '$' -> "\\$"
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> character.toString()
                })
            }
            append('"')
        }
        val entries = inputsTree.files.sortedBy { it.name }.joinToString(",\n") {
            "        ${kotlinString(it.name)} to ${kotlinString(it.readText(Charsets.UTF_8))}"
        }
        val target = outputDir.get().file("dev/hackatlas/mobile/data/CanonicalFixtures.kt").asFile
        target.parentFile.mkdirs()
        target.writeText("""
            package dev.hackatlas.mobile.data
            internal object CanonicalFixtures {
                val files: Map<String, String> = mapOf(
            $entries
                )
            }
        """.trimIndent())
    }
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "HackAtlasShared"
            isStatic = true
            binaryOption("bundleId", "dev.hackatlas.mobile.shared")
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.compose.ui.backhandler)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest {
            kotlin.srcDir(generateCanonicalTestFixtures.map { layout.buildDirectory.dir("generated/canonicalTestFixtures").get() })
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
    }
}

android {
    namespace = "dev.hackatlas.mobile.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "dev.hackatlas.mobile.resources"
    generateResClass = always
}

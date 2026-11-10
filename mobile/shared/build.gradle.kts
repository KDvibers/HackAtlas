import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

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
            in '\uD800'..'\uDFFF' -> "\\u${character.code.toString(16).padStart(4, '0')}"
            else -> character.toString()
        })
    }
    append('"')
}

val canonicalExamples = rootProject.layout.projectDirectory.dir("../api/examples")
val generateCanonicalTestFixtures by tasks.registering {
    val inputsTree = fileTree(canonicalExamples) { include("*.json") }
    val outputDir = layout.buildDirectory.dir("generated/canonicalTestFixtures")
    inputs.files(inputsTree)
    outputs.dir(outputDir)
    doLast {
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

val n2Examples = rootProject.layout.projectDirectory.dir("test-fixtures/n2")
val n2FixtureSources = layout.buildDirectory.dir("generated/n2TestFixtures")
val generateN2TestFixtures by tasks.registering {
    val captures = listOf("all-responses.json", "delete-password-same-session.json").map(n2Examples::file)
    inputs.files(captures)
    outputs.dir(n2FixtureSources)
    doLast {
        val entries = captures.joinToString(",\n") { capture ->
            val file = capture.asFile
            // The full archive response file exceeds the JVM constant-pool string limit.
            val chunks = file.readText(Charsets.UTF_8).chunked(8_192).joinToString(",\n", transform = ::kotlinString)
            "        ${kotlinString(file.name)} to listOf(\n$chunks\n).joinToString(\"\")"
        }
        val target = n2FixtureSources.get().file("dev/hackatlas/mobile/data/auth/N2FixtureFiles.kt").asFile
        target.parentFile.mkdirs()
        target.writeText("""
            package dev.hackatlas.mobile.data.auth
            internal object N2FixtureFiles {
                val files: Map<String, String> = mapOf(
            $entries
                )
            }
        """.trimIndent(), Charsets.UTF_8)
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
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.threetenbp)
        }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
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
            kotlin.srcDir(generateN2TestFixtures.map { n2FixtureSources.get() })
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
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "dev.hackatlas.mobile.data.auth.SecureStoreInstrumentation"
    }
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

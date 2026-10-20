import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipFile
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

abstract class GenerateDebugFixtureAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtureDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun generate() {
        fileSystem.sync {
            from(fixtureDirectory) {
                include("*.json")
                exclude("manifest.json")
                into("composeResources/dev.hackatlas.mobile.resources/files/demo")
            }
            into(outputDirectory)
        }
    }
}

// Variant API wires this generated directory to both asset packaging and lint.
// No main/release source set can receive canonical demo JSON.
val generateDebugFixtures = tasks.register<GenerateDebugFixtureAssets>("generateDebugFixtures") {
    fixtureDirectory.set(rootProject.layout.projectDirectory.dir("../api/examples"))
}

android {
    namespace = "dev.hackatlas.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.hackatlas.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-demo" }
        release {
            isMinifyEnabled = false
            isDebuggable = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateDebugFixtures,
            GenerateDebugFixtureAssets::outputDirectory,
        )
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
}

tasks.register("verifyReleaseNoDemoFixtures") {
    group = "verification"
    description = "Checks that the unsigned release APK contains no canonical demo JSON."
    dependsOn("assembleRelease")
    doLast {
        val apks = layout.buildDirectory.dir("outputs/apk/release").get().asFile
            .listFiles().orEmpty().filter { it.extension == "apk" }
        check(apks.isNotEmpty()) { "No release APK was produced." }
        apks.forEach { apk ->
            ZipFile(apk).use { zip ->
                val leaked = zip.entries().asSequence()
                    .filter { "/files/demo/" in it.name }.map { it.name }.toList()
                check(leaked.isEmpty()) { "Demo fixtures leaked into ${apk.name}: $leaked" }
            }
        }
    }
}

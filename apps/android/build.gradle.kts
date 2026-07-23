import java.util.Properties
import java.io.FileInputStream
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.legacy.kapt)
    jacoco
}

val localBuildProperties = Properties().apply {
    val file = project.rootProject.file("local.properties")
    if (file.exists()) load(FileInputStream(file))
}
val configuredBackendUrl = localBuildProperties.getProperty("AUTO_ACCOUNTING_BACKEND_URL")
    ?: System.getenv("AUTO_ACCOUNTING_BACKEND_URL")
val debugBackendUrl = configuredBackendUrl?.takeIf { it.isNotBlank() }
    ?: "http://10.0.2.2:8080"
val releaseBackendUrl = configuredBackendUrl
    ?.trim()
    ?.takeIf { it.startsWith("https://", ignoreCase = true) }
    .orEmpty()
val wechatAppId = (localBuildProperties.getProperty("AUTO_ACCOUNTING_WECHAT_APP_ID")
    ?: System.getenv("AUTO_ACCOUNTING_WECHAT_APP_ID"))
    ?.trim()
    .orEmpty()
val composeCompilerReportsEnabled =
    providers.gradleProperty("composeCompilerReports").orNull == "true"

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.autoaccounting"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.autoaccounting"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "AUTO_ACCOUNTING_WECHAT_APP_ID",
            wechatAppId.asBuildConfigString()
        )
    }

    signingConfigs {
        val keystoreFile = file("release.jks")
        val storePassword = localBuildProperties.getProperty("RELEASE_STORE_PASSWORD")
            ?: System.getenv("RELEASE_STORE_PASSWORD")
        val keyAlias = localBuildProperties.getProperty("RELEASE_KEY_ALIAS")
            ?: System.getenv("RELEASE_KEY_ALIAS")
        val keyPassword = localBuildProperties.getProperty("RELEASE_KEY_PASSWORD")
            ?: System.getenv("RELEASE_KEY_PASSWORD")
        if (
            keystoreFile.exists() &&
            !storePassword.isNullOrBlank() &&
            !keyAlias.isNullOrBlank() &&
            !keyPassword.isNullOrBlank()
        ) {
            create("release") {
                storeFile = keystoreFile
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField(
                "String",
                "AUTO_ACCOUNTING_BACKEND_URL",
                debugBackendUrl.asBuildConfigString()
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField(
                "String",
                "AUTO_ACCOUNTING_BACKEND_URL",
                releaseBackendUrl.asBuildConfigString()
            )
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    finalizeDsl { extension ->
        listOf("benchmarkRelease", "nonMinifiedRelease").forEach { buildTypeName ->
            extension.buildTypes.named(buildTypeName).configure {
                applicationIdSuffix = ".benchmark"
                versionNameSuffix = "-benchmark"
                signingConfig = extension.signingConfigs.getByName("debug")
            }
            extension.sourceSets.named(buildTypeName).configure {
                manifest.srcFile("src/benchmark/AndroidManifest.xml")
                kotlin.directories.add("src/benchmark/java")
            }
        }
    }
}

configurations.configureEach {
    if (name == "benchmarkReleaseImplementation") {
        project.dependencies.add(name, libs.androidx.compose.runtime.tracing)
    }
}

baselineProfile {
    automaticGenerationDuringBuild = false
    filter {
        exclude("com.autoaccounting.benchmark.**")
    }
}

composeCompiler {
    if (composeCompilerReportsEnabled) {
        val outputDirectory = layout.buildDirectory.dir("reports/compose-compiler")
        reportsDestination.set(outputDirectory)
        metricsDestination.set(outputDirectory)
    }
}

kapt {
    correctErrorTypes = true
    arguments {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
    forkEvery = 5

    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

tasks.register<JacocoReport>("jacocoDebugTestReport") {
    group = "verification"
    description = "Generates JaCoCo coverage for Android debug JVM and Robolectric tests."
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    val coverageExclusions = listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*_Impl*.class"
    )
    classDirectories.setFrom(
        files(
            fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
                exclude(coverageExclusions)
            },
            fileTree(
                layout.buildDirectory.dir(
                    "intermediates/javac/debug/compileDebugJavaWithJavac/classes"
                )
            ) {
                exclude(coverageExclusions)
            }
        )
    )
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include("jacoco/testDebugUnitTest.exec")
            include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        }
    )
}

dependencies {
    baselineProfile(project(":benchmarks:macrobenchmark"))

    implementation(project(":shared:api"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.wechat.sdk.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    kapt(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}

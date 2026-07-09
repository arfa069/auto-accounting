import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.autoaccounting"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.autoaccounting"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        val keystoreFile = file("release.jks")
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                val properties = Properties()
                val localProperties = project.rootProject.file("local.properties")
                if (localProperties.exists()) {
                    properties.load(FileInputStream(localProperties))
                }
                storePassword = properties.getProperty("RELEASE_STORE_PASSWORD") ?: System.getenv("RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = properties.getProperty("RELEASE_KEY_ALIAS") ?: System.getenv("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = properties.getProperty("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

kotlin {
    jvmToolchain(17)
}

kapt {
    correctErrorTypes = true
    arguments {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(project(":shared:api"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)

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
}

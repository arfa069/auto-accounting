import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    jacoco
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.autoaccounting.backend.ApplicationKt")
}

version = providers.environmentVariable("AUTO_ACCOUNTING_VERSION_NAME")
    .orElse("0.1.0")
    .get()

tasks.named<Tar>("distTar") {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
    archiveBaseName.set("auto-accounting-backend")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.named<Test>("test") {
    extensions.configure<JacocoTaskExtension> {
        val isCoverageRun = project.gradle.startParameter.taskNames.any { it.contains("jacoco", ignoreCase = true) }
        isEnabled = isCoverageRun
    }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

dependencies {
    implementation(project(":shared:api"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.logback.classic)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.h2)
}

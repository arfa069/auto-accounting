plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt)
}

allprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    detekt {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
        baseline = file(
            "${rootProject.projectDir}/config/detekt/" +
                "${project.path.removePrefix(":").replace(':', '-')}-baseline.xml"
        )
    }
}

tasks.register("coverageReport") {
    group = "verification"
    description = "Runs all module tests and generates their JaCoCo reports."
    dependsOn(
        ":apps:android:jacocoDebugTestReport",
        ":services:backend:jacocoTestReport",
        ":shared:api:jacocoTestReport"
    )
}

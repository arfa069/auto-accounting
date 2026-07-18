plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
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

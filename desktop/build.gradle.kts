import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Windows overlay that floats the live circle over a video stream
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)
    // Same circle drawing as the Android app; see app/build.gradle.kts
    sourceSets.getByName("main").kotlin.srcDir("../ui-shared/src")
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.org.json)
    implementation(libs.jna.platform)
}

compose.desktop {
    application {
        mainClass = "com.f1circle.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "F1 Circle Overlay"
            packageVersion = "0.1.0"
            modules("java.instrument", "java.prefs", "jdk.unsupported")
            windows {
                menuGroup = "F1 Circle"
                upgradeUuid = "6f1c2a4e-3b7d-4c8e-9a51-2d0f8e7b6c14"
            }
        }
    }
}

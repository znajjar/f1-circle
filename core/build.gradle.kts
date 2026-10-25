import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Live feed, OpenF1 and the circle model: plain Kotlin shared by the Android app and the desktop overlay
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

dependencies {
    implementation(libs.okhttp)
    // Android has org.json built in, so each app supplies it rather than the library
    compileOnly(libs.org.json)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

tasks.test {
    testLogging { showStandardStreams = true }
}

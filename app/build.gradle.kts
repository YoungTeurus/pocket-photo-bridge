plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    namespace = "dev.pocketphotobridge"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.pocketphotobridge"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { implementation(project(":sony")) }

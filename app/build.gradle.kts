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
        versionCode = 3
        versionName = "0.2.0"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2") }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":sony"))
    implementation(project(":printer"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

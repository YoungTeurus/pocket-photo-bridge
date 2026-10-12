plugins {
    kotlin("jvm")
    application
}

version = "0.4.1"
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":sony"))
    implementation(project(":printer"))
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("net.java.dev.jna:jna:5.17.0")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("dev.pocketphotobridge.bridge.BridgeCliKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true", "-Dfile.encoding=UTF-8")
}

distributions {
    main {
        contents {
            from(rootProject.file("LICENSE")) { into("licenses"); rename { "PocketPhotoBridge-LICENSE.txt" } }
            from(rootProject.file("app/src/main/assets/licenses/mizink-LICENSE.txt")) { into("licenses") }
            from(project.file("licenses")) { into("licenses/jna") }
            from(rootProject.file("docs/windows-simulation.md"))
            from(rootProject.file("docs/linux-bridge.md"))
            from(rootProject.file("docs/windows-live.md"))
            from(rootProject.file("deploy/linux")) { into("deploy/linux") }
        }
    }
}

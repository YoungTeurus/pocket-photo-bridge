plugins {
    kotlin("jvm")
    application
}

// The one version source for Gradle, the CLI, the packaging scripts, the installer and the workflow.
// Exactly N.N.N and one optional line end (CRLF in a Windows checkout), so Gradle, bash $(<) and PowerShell Trim() read the same string.
val versionText = file("VERSION").readText()
require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:\r?\n)?").matches(versionText)) { "bridge/VERSION must hold exactly N.N.N." }
version = versionText.trim()
// CI passes -PbuildCommit=<commit>; a build made without it reports dev.
val buildCommit = providers.gradleProperty("buildCommit").getOrElse("dev")
require(buildCommit == "dev" || Regex("[0-9a-f]{7,40}").matches(buildCommit)) { "buildCommit must be 7 to 40 lowercase hexadecimal characters." }
val buildIdentity = "$version+$buildCommit"
val buildInfo = tasks.register("buildInfo") {
    val output = layout.buildDirectory.dir("generated/build-info")
    inputs.property("build", buildIdentity)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.resolve("dev/pocketphotobridge/bridge")
        directory.mkdirs()
        directory.resolve("BUILD").writeText(buildIdentity + "\n")
    }
}
sourceSets.main { resources.srcDir(buildInfo) }
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
            from(buildInfo) { eachFile { path = name }; includeEmptyDirs = false }
            from(rootProject.file("docs/windows-simulation.md"))
            from(rootProject.file("docs/linux-bridge.md"))
            from(rootProject.file("docs/windows-live.md"))
            from(rootProject.file("docs/mvp5-deployment.md"))
            from(rootProject.file("deploy/linux")) { into("deploy/linux") }
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.flatlaf)
    implementation(libs.jackson.kotlin)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "dev.jaflaten.vmm.MainKt"
    applicationName = "valheim-mod-manager"
    applicationDefaultJvmArgs = listOf(
        "-Dapple.awt.application.name=Valheim Mod Manager",
        "-Dapple.laf.useScreenMenuBar=true",
    )
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    systemProperty("vmm.dataDir", layout.buildDirectory.dir("test-data").get().asFile.absolutePath)
}

// Builds a local double-clickable app: ./gradlew macApp → app/build/macApp/Valheim Mod Manager.app
val macApp by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Packages a local macOS .app with jpackage"
    dependsOn(tasks.named("installDist"))
    val libDir = layout.buildDirectory.dir("install/valheim-mod-manager/lib")
    val outDir = layout.buildDirectory.dir("macApp")
    val launcher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    doFirst {
        outDir.get().asFile.deleteRecursively()
        val jpackage = launcher.get().metadata.installationPath.file("bin/jpackage").asFile.absolutePath
        val mainJar = libDir.get().asFile.listFiles()!!.first { it.name.startsWith("app") }.name
        commandLine(
            jpackage, "--type", "app-image",
            "--name", "Valheim Mod Manager",
            "--input", libDir.get().asFile.absolutePath,
            "--main-jar", mainJar,
            "--main-class", "dev.jaflaten.vmm.MainKt",
            "--dest", outDir.get().asFile.absolutePath,
            "--java-options", "-Dapple.awt.application.name=Valheim\\ Mod\\ Manager",
        )
    }
}

// Manual end-to-end check against the real game: ./gradlew smoke --args="install" / --args="launch"
tasks.register<JavaExec>("smoke") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.jaflaten.vmm.SmokeKt"
}

// Renders each UI tab to PNG (visual check without screen-capture permission): ./gradlew uiSnapshot --args=/tmp
tasks.register<JavaExec>("uiSnapshot") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.jaflaten.vmm.UiSnapshotKt"
}

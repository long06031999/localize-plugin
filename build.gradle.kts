plugins {
    id("org.jetbrains.intellij") version "1.17.4"
    kotlin("jvm") version "2.0.0"
}

group = "com.paulbaker"

// Overridable so CI can stamp the version from the git tag: -PpluginVersion=1.2.0
// Falls back to gradle.properties for local builds.
version = (findProperty("pluginVersion") as String?) ?: "1.0.0"

repositories {
    mavenCentral()
}

/**
 * Extra jars needed only to run DevCheck outside the IDE. Deliberately NOT extending
 * `implementation`: the IntelliJ Platform supplies Gson at runtime, and shipping a second copy
 * inside the plugin zip would risk a version clash.
 */
val devCheckOnly: Configuration by configurations.creating

dependencies {
    implementation("org.apache.poi:poi-ooxml:5.2.3")
    devCheckOnly("com.google.code.gson:gson:2.10.1")   // version bundled with 2024.1
}

intellij {
    version.set("2024.1")
    type.set("IC")
}

kotlin {
    jvmToolchain(17)
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("")
    }
}

/**
 * Runs the DevCheck harness (end-to-end generator, preview and layout checks).
 *
 * Uses the main runtime classpath so the IntelliJ Platform jars come along; assembling that
 * classpath by hand is unreliable. The --add-opens flags are the ones the platform's UI classes
 * need under JDK 17 — without them the very first JBLabel throws.
 */
tasks.register<JavaExec>("devCheck") {
    group = "verification"
    description = "Runs the DevCheck verification harness."
    mainClass.set("com.paulbaker.localize.DevCheck")
    // compileClasspath carries the IntelliJ Platform jars (runtimeClasspath does not — the IDE
    // normally provides them), so both are needed to run outside a sandboxed IDE.
    classpath = sourceSets["main"].runtimeClasspath +
                sourceSets["main"].compileClasspath +
                devCheckOnly
    jvmArgs(
        "-Djava.awt.headless=true",
        "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
    )
}

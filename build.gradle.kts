plugins {
    id("org.jetbrains.intellij") version "1.17.4"
    kotlin("jvm") version "2.0.0"
}

group = "com.paulbaker"
version = "1.0.0"

repositories {
    mavenCentral()
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

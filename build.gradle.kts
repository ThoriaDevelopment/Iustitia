plugins {
    id("fabric-loom") version "1.17.11"
    kotlin("jvm") version "2.3.10"
    `java-library`
}

version = "0.2.0"
group = "dev.iustitia"

base { archivesName.set("iustitia") }

loom {
    mixin {
        // Kotlin mixins aren't seen by the legacy javac Mixin AP (no Java sources),
        // so it produces no refmap. The non-legacy path remaps mixin refs during
        // remapJar, which works for Kotlin mixin classes.
        useLegacyMixinAp = false
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

repositories {
    maven("https://maven.isxander.dev/releases")   // YACL
    maven("https://maven.fabricmc.net")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    modImplementation("dev.isxander:yet-another-config-lib:${property("yacl_version")}")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    withSourcesJar()
}

kotlin {
    jvmToolchain(21)
}
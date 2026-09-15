plugins {
    id("fabric-loom") version "1.17.11"
    kotlin("jvm") version "2.3.10"
    `java-library`
}

version = "1.4.0"
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

    // Pure-JVM unit tests (src/test) for config/preset logic that is separable from Minecraft
    // objects (docs/ai-assisted-development.md: add pure tests where the logic can be separated).
    // kotlin("test") resolves to its JUnit5 variant because the test task uses JUnitPlatform.
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    withSourcesJar()
}

kotlin {
    jvmToolchain(21)
}

// -----------------------------------------------------------------------------
// Automated live tests (docs/automated-live-testing.md)
//
// The self-test harness lives in the `gametest` source set and runs through
// Fabric API's client gametest framework: Loom boots a real client, creates a
// deterministic flat world, runs the scenarios, and exits with a report — no
// human at the keyboard, no server, no packets leaving the machine.
//
// `./gradlew runClientGameTest`  — full two-pass verification (legit + cheat)
// `./gradlew compileGametestKotlin` — just typecheck the harness
//
// The test mod carries no mixins of its own; it reaches Iustitia's pipeline
// through the public facade + SelfTestHooks. `eula = true` is required by Loom
// because the gametest runner can boot a dedicated server for server-side
// gametests; we only run client gametests, but the flag is global.
// -----------------------------------------------------------------------------
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "iustitia_testmod"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

// The gametest JVM runs headless-friendly and fails fast; the property arms the
// SelfTestHooks recording tap inside the main mod (see dev.iustitia.selftest).
//
// Optional scenario narrowing (scripts/live_selftest.py passes these through):
//   ./gradlew runClientGameTest -Pselftest.filter=reach      (name substring(s), comma-separated)
//   ./gradlew runClientGameTest -Pselftest.pass=CHEAT        (LEGIT | CHEAT | REPLAY)
//   ./gradlew runClientGameTest -Pselftest.source=Meteor     (reference-client provenance)
//   ./gradlew runClientGameTest -Pselftest.tag=world         (combat | movement | …)
//   ./gradlew runClientGameTest -Pselftest.shard=1/2         (shard I of N, by scenario name)
// A filter that matches nothing FAILS the run, so a typo can never look like a pass.
tasks.withType(JavaExec::class.java).named("runClientGameTest") {
    jvmArgs("-Dfabric.selftest=1")
    // Iustitia observes packets by hooking the netty pipeline (ClientPlayNetworkHandlerMixin),
    // which is exactly the "interfacing with packets at a lower level" case the Fabric client
    // gametest API's network synchronizer cannot model. With the synchronizer active, full runs
    // intermittently die inside the framework's own phase machine: the server thread parks
    // forever on its postRunTasks semaphore during world creation (spawn prep never advances,
    // world load times out, every later scenario cascade-fails), and packet-heavy scenarios can
    // trip the synchronizer's 10s "Detected interfacing with packets at a lower level" watch
    // outright. Disabling synchronization is the framework's documented escape for this class of
    // mod. Our drives publish synthetic signals on the client thread, so scenario timing does not
    // depend on packet/tick alignment; the ≤1-tick packet latency this reintroduces is already
    // tolerated by the engine's defer queue and the scenarios' window margins.
    jvmArgs("-Dfabric.client.gametest.disableNetworkSynchronizer=true")
    providers.gradleProperty("selftest.filter").orNull?.let { jvmArgs("-Diustitia.selftest.filter=$it") }
    providers.gradleProperty("selftest.pass").orNull?.let { jvmArgs("-Diustitia.selftest.pass=$it") }
    providers.gradleProperty("selftest.source").orNull?.let { jvmArgs("-Diustitia.selftest.source=$it") }
    providers.gradleProperty("selftest.tag").orNull?.let { jvmArgs("-Diustitia.selftest.tag=$it") }
    providers.gradleProperty("selftest.shard").orNull?.let { jvmArgs("-Diustitia.selftest.shard=$it") }
    // Arms the mod's verbose flag log so a run prints the sub-flag label + measured value for
    // every flag (the calibration loop's evidence). Off by default; the report is the summary.
    providers.gradleProperty("selftest.verbose").orNull?.let { jvmArgs("-Diustitia.selftest.verbose=$it") }
}
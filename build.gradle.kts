import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.0.3"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../boss-plugin-api"

// Single source of truth for the API pin, shared with test.yml. A version
// hardcoded in this file goes stale silently and surfaces as "Unresolved
// reference" against a jar that is simply not there — the failure mode
// boss-plugin-docker still carries.
val bossPluginApiVersion = rootProject.file(".boss-plugin-api-version").readText().trim()

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    // Local development reads the sibling repo's build output; CI reads the jar
    // test.yml downloads to build/downloaded-deps.
    val bossPluginApiJar = if (useLocalDependencies) {
        files("$bossPluginApiPath/build/libs/boss-plugin-api-$bossPluginApiVersion.jar")
    } else {
        files("build/downloaded-deps/boss-plugin-api.jar")
    }
    compileOnly(bossPluginApiJar)
    // compileOnly does not propagate to the test classpath, and DshMcpToolRbacTest
    // reflects over real McpToolDefinition instances — so the same jar has to be
    // there too. It cannot reach the shipped artifact by construction:
    // buildPluginJar takes sourceSets.main output plus src/main/resources, nothing else.
    testImplementation(bossPluginApiJar)

    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)

    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-deepseek-harness-$version.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Implementation-Title" to "BOSS DeepSeek Harness Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.deepseekharness.DshDynamicPlugin",
        )
    }

    from(sourceSets.main.get().output)
    from("src/main/resources")
}

// The default :jar task writes the same basename into build/libs and would
// clobber buildPluginJar's output depending on task order — a jar that loads
// with no panel and no tools. Give it a classifier so the two cannot collide.
tasks.jar {
    archiveClassifier.set("thin")
}

// Sync version from build.gradle.kts into plugin.json (single source of truth).
tasks.processResources {
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "$version"""")
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}

tasks.build {
    dependsOn("buildPluginJar")
}

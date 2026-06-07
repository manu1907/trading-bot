import com.diffplug.gradle.spotless.SpotlessExtension
import com.github.spotbugs.snom.SpotBugsExtension

plugins {
    // Spring Boot + dependency management
    id("org.springframework.boot") version "4.0.6" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.diffplug.spotless") version "8.4.0" apply false
    id("com.github.spotbugs") version "6.5.4" apply false
}

group = "io.github.manu"
version = "1.0-SNAPSHOT"

val chronicleJvmArgs = listOf(
    "--enable-native-access=ALL-UNNAMED",
    "--sun-misc-unsafe-memory-access=allow",
    "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.nio.channels.spi=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
)

ext {
    set("xchange.version", "5.2.4")
    set("bouncycastle.version", "1.84")
    set("disruptor.version", "4.0.0")
    set("chronicle-queue.version", "2026.2")
    set("agrona.version", "2.4.1")
    set("affinity.version", "2026.2")
    set("avro.version", "1.12.1")
    set("testcontainers.version", "2.0.5")
    set("redpanda.image", "docker.redpanda.com/redpandadata/redpanda:v26.1.9")
}

subprojects {
    repositories {
        mavenCentral()
    }

    pluginManager.apply("com.diffplug.spotless")
    pluginManager.apply("checkstyle")
    pluginManager.apply("com.github.spotbugs")

    extensions.configure<CheckstyleExtension> {
        toolVersion = "13.4.2"
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        isIgnoreFailures = false
    }

    extensions.configure<SpotBugsExtension> {
        excludeFilter.set(rootProject.file("config/spotbugs/exclude.xml"))
    }

    extensions.configure<SpotlessExtension> {
        java {
            target("src/**/*.java")
            removeUnusedImports()
            leadingTabsToSpaces(4)
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            target("*.gradle.kts")
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(25)
            }
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("-Xshare:off")
        jvmArgs(chronicleJvmArgs)
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }

    tasks.withType<JavaExec> {
        jvmArgs(chronicleJvmArgs)
    }

    tasks.named("check") {
        dependsOn("spotlessCheck")
    }
}

pluginManager.apply("com.diffplug.spotless")

extensions.configure<SpotlessExtension> {
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("projectDocs") {
        target("*.md", "*.txt", "docs/**/*.md", "ops/**/*.md")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("json") {
        target("config/**/*.json")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("yaml") {
        target(".github/**/*.yml", ".github/**/*.yaml", "*.yml", "*.yaml", "ops/**/*.yml", "ops/**/*.yaml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

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

ext {
    set("xchange.version", "5.2.4")
    set("bouncycastle.version", "1.84")
    set("disruptor.version", "4.0.0")
    set("chronicle-queue.version", "2026.2")
    set("agrona.version", "2.4.1")
    set("affinity.version", "2026.2")
}

subprojects {
    repositories {
        mavenCentral()
    }

    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "checkstyle")
    apply(plugin = "com.github.spotbugs")

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
        jvmArgs("-Xshare:off", "--enable-native-access=ALL-UNNAMED")
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }

    tasks.withType<JavaExec> {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }

    tasks.named("check") {
        dependsOn("spotlessCheck")
    }
}

apply(plugin = "com.diffplug.spotless")

extensions.configure<SpotlessExtension> {
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("projectDocs") {
        target("*.md", "*.txt", "docs/**/*.md")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("json") {
        target("config/**/*.json")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("yaml") {
        target(".github/**/*.yml", ".github/**/*.yaml", "*.yml", "*.yaml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

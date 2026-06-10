plugins {
    id("java")
    id("application")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

application {
    mainClass.set("io.github.manu.TradingBotApplication")
}

dependencies {
    implementation(project(":bot-core"))

    // Runtime assembly only: concrete provider and strategy plugins stay out of core.
    runtimeOnly(project(":bot-exchange-binance"))
    runtimeOnly(project(":bot-strategy-lfa"))

    implementation("org.springframework.boot:spring-boot-starter")
    runtimeOnly("io.netty:netty-resolver-dns-native-macos") {
        artifact {
            classifier = "osx-aarch_64"
        }
    }

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

plugins {
    id("java-library")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.6")
    }
}

dependencies {
    api(project(":bot-core"))

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

fun loadLocalEnv(file: File): Map<String, String> {
    if (!file.isFile) {
        return emptyMap()
    }

    return file.readLines()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) {
                null
            } else {
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
                key to value
            }
        }
        .toMap()
}

tasks.register<Test>("binanceDemoOrderSmokeTest") {
    group = "verification"
    description = "Runs the guarded Binance demo order lifecycle test against the active demo USD-M config."

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("io.github.manu.exchange.binance.BinanceDemoOrderLifecycleTest")
    }
    loadLocalEnv(rootProject.file("api.env")).forEach { (key, value) ->
        environment(key, value)
    }
    systemProperty("binance.demo.order.smoke", "true")
}

tasks.register<Test>("binanceDemoServerTimeSmokeTest") {
    group = "verification"
    description = "Runs the guarded Binance demo server-time sync smoke test against the active demo USD-M config."

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("io.github.manu.exchange.binance.BinanceDemoServerTimeSyncTest")
    }
    systemProperty("binance.demo.servertime.smoke", "true")
}

tasks.register<Test>("binanceDemoUserDataStreamSmokeTest") {
    group = "verification"
    description = "Runs the guarded Binance demo user data stream lifecycle test against the active demo USD-M config."

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("io.github.manu.exchange.binance.BinanceDemoUserDataStreamLifecycleTest")
    }
    loadLocalEnv(rootProject.file("api.env")).forEach { (key, value) ->
        environment(key, value)
    }
    systemProperty("binance.demo.userdata.smoke", "true")
}

tasks.register<Test>("binanceDemoWebSocketSmokeTest") {
    group = "verification"
    description = "Runs the guarded Binance demo WebSocket market stream smoke test against the active demo USD-M config."

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("io.github.manu.exchange.binance.BinanceDemoWebSocketMarketStreamTest")
    }
    loadLocalEnv(rootProject.file("api.env")).forEach { (key, value) ->
        environment(key, value)
    }
    systemProperty("binance.demo.websocket.smoke", "true")
}

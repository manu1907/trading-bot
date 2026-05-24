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

tasks.register<Test>("binanceLiveOrderSmokeTest") {
    group = "verification"
    description = "Runs the guarded Binance live order lifecycle test against the active Binance config."

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("io.github.manu.exchange.binance.BinanceLiveOrderLifecycleSmokeTest")
    }
    loadLocalEnv(rootProject.file("api.env")).forEach { (key, value) ->
        environment(key, value)
    }
    systemProperty("binance.live.order.smoke", "true")
}

tasks.register<Test>("binanceLiveServerTimeSmokeTest") {
    group = "verification"
    description = "Runs the guarded Binance live server-time sync smoke test against the active Binance config."

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("io.github.manu.exchange.binance.BinanceLiveServerTimeSyncSmokeTest")
    }
    systemProperty("binance.live.servertime.smoke", "true")
}

tasks.register<Test>("binanceLiveUserDataStreamSmokeTest") {
    group = "verification"
    description = "Runs the guarded Binance live user data stream lifecycle test against the active Binance config."

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("io.github.manu.exchange.binance.BinanceLiveUserDataStreamLifecycleSmokeTest")
    }
    loadLocalEnv(rootProject.file("api.env")).forEach { (key, value) ->
        environment(key, value)
    }
    systemProperty("binance.live.userdata.smoke", "true")
}

tasks.register<Test>("binanceLiveWebSocketSmokeTest") {
    group = "verification"
    description = "Runs the guarded Binance live WebSocket market stream smoke test against the active Binance config."

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("io.github.manu.exchange.binance.BinanceLiveWebSocketMarketStreamSmokeTest")
    }
    loadLocalEnv(rootProject.file("api.env")).forEach { (key, value) ->
        environment(key, value)
    }
    systemProperty("binance.live.websocket.smoke", "true")
}

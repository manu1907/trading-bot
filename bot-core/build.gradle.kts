plugins {
    id("java")
    id("application")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val avroTools by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

application {
    mainClass.set("io.github.manu.TradingBotApplication")
}

sourceSets {
    main {
        java.srcDir(layout.buildDirectory.dir("generated/sources/avro/main/java"))
        resources.srcDir("src/main/avro")
    }
}

dependencies {
    // ---------- MUST-HAVE ----------
    // Reactive REST client + embedded server + Spring ecosystem
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    runtimeOnly("io.netty:netty-resolver-dns-native-macos") {
        artifact {
            classifier = "osx-aarch_64"
        }
    }

    // Provider and strategy modules are runtime plugins discovered as Spring beans.
    runtimeOnly(project(":bot-exchange-binance"))
    runtimeOnly(project(":bot-strategy-lfa"))

    // ---------- ULTRA-LOW-LATENCY PIPELINE ----------
    // Lock‑free ring buffer for market data, signals, orders
    implementation("com.lmax:disruptor:${rootProject.ext["disruptor.version"]}")
    // GC‑free, memory‑mapped event journal (trades, orders)
    implementation("net.openhft:chronicle-queue:${rootProject.ext["chronicle-queue.version"]}")
    // High‑performance primitives (thread affinity, ring buffers, counters)
    implementation("org.agrona:agrona:${rootProject.ext["agrona.version"]}")

    // ---------- OPERATIONAL EXCELLENCE ----------
    // Async logging – never block the trading thread
    implementation("ch.qos.logback:logback-classic:1.5.32")
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")
    // Bean Validation implementation for runtime config validation
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Schema-first trading event contracts; Java types are generated at build time.
    implementation("org.apache.avro:avro:${rootProject.ext["avro.version"]}")
    implementation("org.apache.kafka:kafka-clients")
    avroTools("org.apache.avro:avro-tools:${rootProject.ext["avro.version"]}") {
        exclude(group = "org.apache.avro", module = "trevni-avro")
        exclude(group = "org.apache.avro", module = "trevni-core")
    }

    // ---------- ULTRA-LOW-LATENCY EXTRAS ----------
    // Thread pinning (requires isolcpus in production)
    implementation("net.openhft:affinity:${rootProject.ext["affinity.version"]}")

    // ---------- TESTING ----------
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.testcontainers:testcontainers-redpanda:${rootProject.ext["testcontainers.version"]}")
}

val generateAvroJava by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generates Java specific records from Avro trading event schemas."

    val schemaDir = layout.projectDirectory.dir("src/main/avro")
    val schemaFiles = fileTree(schemaDir) {
        include("**/*.avsc")
    }
    val outputDir = layout.buildDirectory.dir("generated/sources/avro/main/java")

    inputs.files(schemaFiles)
    outputs.dir(outputDir)

    classpath = avroTools
    mainClass.set("org.apache.avro.tool.Main")
    doFirst {
        val schemaPaths = schemaFiles.files
            .sortedBy(File::getAbsolutePath)
            .map(File::getAbsolutePath)
        setArgs(listOf("compile", "schema") + schemaPaths + outputDir.get().asFile.absolutePath)
    }
}

tasks.named("compileJava") {
    dependsOn(generateAvroJava)
}

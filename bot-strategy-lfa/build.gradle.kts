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

    implementation("org.springframework.boot:spring-boot")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-web")
    implementation("io.projectreactor:reactor-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("tools.jackson.core:jackson-databind")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

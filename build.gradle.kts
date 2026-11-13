// The service starter: the same Kotlin, Spring Boot, Gradle and JDK as the Week 8 lab.
// You add your own dependencies at the end of `dependencies`; leave the platform ones as they are.

plugins {
    id("org.springframework.boot") version "3.4.3"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    kotlin("plugin.jpa") version "2.1.0"
}

group = "ie.ul.cs4297.groupbuy"
version = "0.1.0"

kotlin { jvmToolchain(21) }

// Spring Boot 3.4.3 brings Testcontainers 1.20.5, which cannot talk to Docker 29 (Docker Desktop
// from late 2025): "Could not find a valid Docker environment", a 400 from Docker. 1.21.4 can.
extra["testcontainers.version"] = "1.21.4"

repositories {
    mavenCentral()
    // The contracts library. The hackathon-2026 organisation is private, so Gradle needs your
    // Forgejo token: put forgejoUser and forgejoToken in ~/.gradle/gradle.properties (README).
    maven {
        name = "forgejo"
        url = uri((findProperty("forgejoMavenUrl") as String?) ?: "https://cs4297.skynet.ie/api/packages/hackathon-2026/maven")
        isAllowInsecureProtocol = url.scheme == "http"
        credentials {
            username = (findProperty("forgejoUser") as String?) ?: System.getenv("FORGEJO_PACKAGES_USER") ?: ""
            password = (findProperty("forgejoToken") as String?) ?: System.getenv("FORGEJO_PACKAGES_TOKEN") ?: ""
        }
        content { includeGroup("ie.ul.cs4297.groupbuy") }
    }
}

dependencies {
    // The contracts: event classes, topics, Money, state moves, the contract-test kit
    implementation("ie.ul.cs4297.groupbuy:contracts:1.3.0")

    // Web, validation, health and metrics
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // MySQL with Flyway (schema changes only through migrations)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")

    // Kafka
    implementation("org.springframework.kafka:spring-kafka")

    // Who the user is: the platform's signed token
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // Email through the dev kit's Mailpit (Student experience sends; anyone may)
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Tests: JUnit 5, MockK, Testcontainers (real MySQL and Kafka), Awaitility
    testImplementation("org.springframework.boot:spring-boot-starter-test") { exclude(module = "mockito-core") }
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.mockk:mockk:1.13.14")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.awaitility:awaitility-kotlin")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Your own dependencies go here.
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict") }
}

// Three kinds of tests:
//   ./gradlew test           unit tests, then the contract tests (contractTest runs after it): fast, no Docker.
//                            CI runs them as two steps: "Unit tests" (test -x contractTest), then "Contract tests".
//   ./gradlew contractTest   only the tests tagged @Tag("contract") (ContractTest.kt).
//   ./gradlew containerTest  tests tagged @Tag("containers"): real MySQL and Kafka in Docker.
val contractTest by tasks.registering(Test::class) {
    description = "Runs the contract tests (tagged contract): every event you publish matches the contracts library."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("contract"); excludeTags("containers") }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    shouldRunAfter(tasks.test)
}

tasks.test {
    useJUnitPlatform { excludeTags("containers", "contract") }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    finalizedBy(contractTest)
}

tasks.check { dependsOn(contractTest) }

val containerTest by tasks.registering(Test::class) {
    description = "Runs the tests that need Docker (Testcontainers: MySQL and Kafka)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("containers") }
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    shouldRunAfter(tasks.test)
}

// The 2,000-connection live-update test (Student experience): ./gradlew liveConnections -Purl=... [-Pfinal=<confirmed>]
val liveConnections by tasks.registering(JavaExec::class) {
    description = "Opens many server-sent-event connections at once and checks every one ends on the final count."
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ie.ul.cs4297.groupbuy.service.load.LiveConnectionsKt")
    args(
        (findProperty("url") as String?) ?: "http://localhost:8080/api/experience/deals/00000000-0000-0000-0000-000000000000/live",
        (findProperty("connections") as String?) ?: "2000",
        (findProperty("seconds") as String?) ?: "60",
        (findProperty("final") as String?) ?: "",   // the final confirmed count; empty: read it from the stream at the end
    )
}

tasks.bootJar { archiveFileName.set("service.jar") }
tasks.jar { enabled = false }

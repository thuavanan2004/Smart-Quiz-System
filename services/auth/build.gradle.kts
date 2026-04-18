plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("jacoco")
    id("checkstyle")
}

description = "SmartQuiz Auth Service — JWT RS256 issuer + RBAC"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // --- Spring Boot starters ------------------------------------------------
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // --- Persistence ---------------------------------------------------------
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.1")

    // --- JWT ----------------------------------------------------------------
    implementation("com.nimbusds:nimbus-jose-jwt:9.41.2")

    // --- Password hashing ---------------------------------------------------
    // Argon2id wrapper theo design §6.1. Dùng native libsodium nếu có, fallback pure-Java.
    implementation("de.mkammerer:argon2-jvm:2.11")

    // --- Kafka --------------------------------------------------------------
    implementation("org.springframework.kafka:spring-kafka:3.3.0")

    // --- Observability ------------------------------------------------------
    implementation("io.micrometer:micrometer-registry-prometheus:1.14.1")
    // JSON structured logging (design §14.3). Bật qua Spring profile != dev; dev vẫn dùng text.
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // --- Lombok (chỉ các annotation được CLAUDE.md cho phép) ----------------
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")

    // --- Test ---------------------------------------------------------------
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.3")
    testImplementation("org.testcontainers:postgresql:1.20.3")
    testImplementation("com.h2database:h2:2.3.232")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

checkstyle {
    toolVersion = "10.18.2"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.60".toBigDecimal() // nâng dần khi service trưởng thành
            }
        }
    }
}

springBoot {
    mainClass.set("vn.smartquiz.auth.AuthServiceApplication")
    buildInfo()
}

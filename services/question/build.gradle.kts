plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("jacoco")
    id("checkstyle")
}

description = "SmartQuiz Question Service — question bank (Mongo) + taxonomy (PG) + search (ES)"

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
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // --- Persistence (PG cho taxonomy subjects) ------------------------------
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.1")

    // --- Elasticsearch client (KNN vector search) ----------------------------
    implementation("co.elastic.clients:elasticsearch-java:8.15.3")

    // --- Kafka (publish question_events + consume ai.embedding_generated) ----
    implementation("org.springframework.kafka:spring-kafka:3.3.0")

    // --- Observability ------------------------------------------------------
    implementation("io.micrometer:micrometer-registry-prometheus:1.14.1")

    // --- Lombok --------------------------------------------------------------
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")

    // --- Test ---------------------------------------------------------------
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.3")
    testImplementation("org.testcontainers:postgresql:1.20.3")
    testImplementation("org.testcontainers:mongodb:1.20.3")
    testImplementation("org.testcontainers:elasticsearch:1.20.3")
    testImplementation("org.testcontainers:kafka:1.20.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.h2database:h2:2.3.232")
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
                minimum = "0.60".toBigDecimal()
            }
        }
    }
}

springBoot {
    mainClass.set("vn.smartquiz.question.QuestionServiceApplication")
    buildInfo()
}

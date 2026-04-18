plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("jacoco")
    id("checkstyle")
}

description = "SmartQuiz Cheat-Detection Service — proctor signals → risk score + alerts"

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
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // --- ClickHouse (event stream + risk_score history) ----------------------
    implementation("com.clickhouse:clickhouse-jdbc:0.7.1:all") {
        exclude(group = "com.clickhouse", module = "clickhouse-cli-client")
    }

    // --- Kafka (consume proctor.signal + publish cheat.alert) ---------------
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
    testImplementation("org.testcontainers:mongodb:1.20.3")
    testImplementation("org.testcontainers:clickhouse:1.20.3")
    testImplementation("org.testcontainers:kafka:1.20.3")
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
                minimum = "0.60".toBigDecimal()
            }
        }
    }
}

springBoot {
    mainClass.set("vn.smartquiz.cheat.CheatServiceApplication")
    buildInfo()
}

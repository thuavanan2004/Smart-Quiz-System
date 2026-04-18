# syntax=docker/dockerfile:1.7
# Multi-stage — Gradle build → JRE 21 runtime

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY ../../gradle gradle
COPY ../../gradlew gradlew
COPY ../../gradlew.bat gradlew.bat
COPY ../../settings.gradle.kts settings.gradle.kts
COPY ../../build.gradle.kts build.gradle.kts
COPY ../../gradle.properties gradle.properties
COPY . services/exam
RUN chmod +x gradlew && ./gradlew :services:exam:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN adduser -D -u 10002 exam
USER exam
COPY --from=build /workspace/services/exam/build/libs/*.jar app.jar
EXPOSE 3002 9002
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

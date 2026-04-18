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
COPY . services/auth
RUN chmod +x gradlew && ./gradlew :services:auth:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN adduser -D -u 10001 auth
USER auth
COPY --from=build /workspace/services/auth/build/libs/*.jar app.jar
EXPOSE 3001 9001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

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
COPY . services/cheat
RUN chmod +x gradlew && ./gradlew :services:cheat:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN adduser -D -u 10005 cheat
USER cheat
COPY --from=build /workspace/services/cheat/build/libs/*.jar app.jar
# 3005 HTTP REST, 4005 gRPC, 9005 actuator (xem design doc §1.3)
EXPOSE 3005 4005 9005
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

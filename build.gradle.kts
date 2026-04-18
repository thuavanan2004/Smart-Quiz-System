// =============================================================================
// Root build script — chỉ chứa config chung, không declare dependency cụ thể.
// Mỗi service con có build.gradle.kts riêng kế thừa các plugin từ đây.
// =============================================================================

plugins {
    id("org.springframework.boot") version "3.3.5" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
    id("com.diffplug.spotless") version "6.25.0" apply false
    id("checkstyle")
    id("jacoco")
}

allprojects {
    group = "vn.smartquiz"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all"))
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "failed", "skipped")
                showStandardStreams = false
            }
        }
    }

    apply(plugin = "com.diffplug.spotless")
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            googleJavaFormat("1.22.0")
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
            target("src/**/*.java")
        }
        kotlinGradle {
            ktlint()
            target("*.gradle.kts")
        }
    }
}

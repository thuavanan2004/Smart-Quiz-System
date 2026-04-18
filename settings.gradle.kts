// =============================================================================
// SmartQuizSystem — Gradle multi-project settings
// Chỉ bao gồm backend Java service. Frontend (web/) dùng pnpm.
// AI Service (services/ai/) dùng uv (Python) — KHÔNG include vào Gradle.
// =============================================================================

rootProject.name = "smartquiz"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// --- Java services -----------------------------------------------------------
include(":services:auth")
project(":services:auth").projectDir = file("services/auth")

include(":services:exam")
project(":services:exam").projectDir = file("services/exam")

include(":services:question")
project(":services:question").projectDir = file("services/question")

include(":services:analytics")
project(":services:analytics").projectDir = file("services/analytics")

include(":services:cheat")
project(":services:cheat").projectDir = file("services/cheat")

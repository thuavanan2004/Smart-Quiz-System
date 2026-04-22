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
// (empty — services wiped for re-scaffold; add include() lines when each service is scaffolded)

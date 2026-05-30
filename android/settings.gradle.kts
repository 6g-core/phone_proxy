pluginManagement {
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application" -> {
                    useModule("com.android.tools.build:gradle:8.5.1")
                }
                "org.jetbrains.kotlin.android" -> {
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
                }
            }
        }
    }
    repositories {
        maven { url = uri(File(rootDir, ".local/local-maven")) }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(File(rootDir, ".local/local-maven")) }
        google()
        mavenCentral()
    }
}

rootProject.name = "PhoneProxy"
include(":app")

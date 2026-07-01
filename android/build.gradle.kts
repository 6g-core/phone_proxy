buildscript {
    repositories {
        maven { url = uri("$rootDir/.local/local-maven") }
        google()
        mavenCentral()
    }
    dependencies {
        val localKotlinGradlePluginJars = fileTree("$rootDir/.local/gradle-user-home/caches/modules-2/files-2.1/org.jetbrains.kotlin") {
            include("*/1.9.24/*/*.jar")
        }
        classpath("com.android.tools.build:gradle:8.5.1")
        classpath(files(localKotlinGradlePluginJars.files))
    }
}

import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "stacklane"

// Mismas versiones que Tasklane: ya estan en la cache de Gradle, asi que el primer
// build no descarga plugins nuevos.
pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.20"
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

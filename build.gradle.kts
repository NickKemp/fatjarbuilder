import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

group   = "com.github.nickkemp.fatjarbuilder"
version = "1.0.7"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(libs.junit)

    intellijPlatform {
        intellijIdeaUltimate("2026.1")
        testFramework(TestFrameworkType.Platform)
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241.0"
            untilBuild = provider { null }  // no upper limit — works with future versions
        }
    }

    publishing {
        token = providers.gradleProperty("publish.token")
            .orElse(providers.environmentVariable("PUBLISH_TOKEN"))
    }

}

kotlin {
    jvmToolchain(21)
}
import java.util.Properties
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.maapipeline.executionlens"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.isFile) {
    localPropertiesFile.inputStream().use(localProperties::load)
}

val ideaSdkPath = localProperties.getProperty("ideaSdkPath")
    ?: providers.environmentVariable("IDEA_SDK_PATH").orNull
    ?: error("Configure ideaSdkPath in local.properties or IDEA_SDK_PATH before running Gradle.")

val maaEndPath = localProperties.getProperty("maaendPath")
    ?: providers.gradleProperty("maaendPath").orNull
    ?: providers.environmentVariable("MAAEND_PATH").orNull

dependencies {
    intellijPlatform {
        local(file(ideaSdkPath))
        bundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
    }
}

kotlin {
    jvmToolchain(25)
}

tasks {
    wrapper {
        gradleVersion = "9.0.0"
        distributionType = Wrapper.DistributionType.ALL
    }

    runIde {
        maaEndPath?.let { args(it) }
    }
}


tasks.test {
    useJUnitPlatform()
    systemProperty("idea.load.plugins.id", "com.intellij.modules.json,com.maapipeline.json.execution.lens")
    maaEndPath?.let { environment("MAAEND_PATH", it) }
}

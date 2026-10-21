import java.util.Properties
import groovy.json.JsonSlurper
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "org.lintx.maapipeline.executionlens"
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

val requiredPluginIds = "com.intellij.modules.json,com.intellij.platform.images,org.lintx.maapipeline.executionlens"
val developmentPluginIds = setOf(
    "com.intellij", "com.intellij.modules.json", "com.intellij.platform.images", "com.intellij.zh",
    "com.jetbrains.remoteDevelopment",
    "com.intellij.java", "com.intellij.java.ide", "intellij.structuralSearch.plugin",
    "intellij.java.aetherDependencyResolver.plugin", "intellij.libraries.misc.plugin",
    "intellij.structureView.plugin", "intellij.testRunner.plugin", "intellij.todo.plugin",
)

dependencies {
    intellijPlatform {
        local(file(ideaSdkPath))
        bundledPlugin("com.intellij.modules.json")
        bundledPlugin("com.intellij.platform.images")
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

tasks.withType<PrepareSandboxTask>().configureEach {
    if (name == "prepareSandbox_runIde") {
        disabledPlugins.addAll(providers.provider {
            val product = JsonSlurper().parse(file("$ideaSdkPath/product-info.json"), "UTF-8") as Map<*, *>
            (product["bundledPlugins"] as List<*>).filterIsInstance<String>().filterNot { it in developmentPluginIds } +
                "org.jetbrains.plugins.wsl.remoteSdk"
        })
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("idea.load.plugins.id", requiredPluginIds)
    maaEndPath?.let { environment("MAAEND_PATH", it) }
}

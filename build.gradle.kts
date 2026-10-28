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
version = providers.gradleProperty("pluginVersion").getOrElse("0.1.0")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.isFile) {
    localPropertiesFile.reader(Charsets.UTF_8).use(localProperties::load)
}

val ideaSdkPath = localProperties.getProperty("ideaSdkPath")
    ?: providers.environmentVariable("IDEA_SDK_PATH").orNull
    ?: error("Configure ideaSdkPath in local.properties or IDEA_SDK_PATH before running Gradle.")

val maaEndPath = localProperties.getProperty("maaendPath")
    ?: providers.gradleProperty("maaendPath").orNull
    ?: providers.environmentVariable("MAAEND_PATH").orNull

val signingPrivateKeyPath = providers.environmentVariable("PRIVATE_KEY_FILE").orNull
    ?: localProperties.getProperty("signingPrivateKeyFile")
val signingCertificatePath = providers.environmentVariable("CERTIFICATE_CHAIN_FILE").orNull
    ?: localProperties.getProperty("signingCertificateChainFile")
val signingPassword = providers.environmentVariable("PRIVATE_KEY_PASSWORD")

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
    signing {
        signingPrivateKeyPath?.let { privateKeyFile = file(it) }
        signingCertificatePath?.let { certificateChainFile = file(it) }
        password = signingPassword
    }
}

kotlin {
    jvmToolchain(25)
}

tasks {
    jar {
        metaInf {
            from("LICENSE", "NOTICE")
        }
    }

    val validateManualRelease by registering {
        group = "intellij platform"
        description = "检查手动发布的版本及签名配置。"
        doLast {
            check(!project.version.toString().contains("SNAPSHOT", ignoreCase = true)) {
                "手动发布需要正式版本；请通过 -PpluginVersion 指定版本。"
            }
            check(signingPrivateKeyPath?.let { file(it).isFile } == true) {
                "请通过 local.properties 的 signingPrivateKeyFile 或 PRIVATE_KEY_FILE 环境变量指定签名私钥文件。"
            }
            check(signingCertificatePath?.let { file(it).isFile } == true) {
                "请通过 local.properties 的 signingCertificateChainFile 或 CERTIFICATE_CHAIN_FILE 环境变量指定签名证书文件。"
            }
            check(signingPassword.orNull?.isNotEmpty() == true) {
                "请通过 PRIVATE_KEY_PASSWORD 环境变量提供私钥密码，不要将密码写入工程文件。"
            }
        }
    }

    signPlugin {
        dependsOn(validateManualRelease)
    }

    verifyPluginSignature {
        dependsOn(signPlugin)
    }

    verifyPluginStructure {
        ignoreWarnings = false
    }

    register("prepareManualRelease") {
        group = "intellij platform"
        description = "构建、签名并验证手动上传到 Marketplace 的插件 ZIP。"
        dependsOn(verifyPluginStructure, verifyPluginSignature)
        doLast {
            logger.lifecycle("已生成并验签：${signPlugin.get().signedArchiveFile.get().asFile}")
        }
    }

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

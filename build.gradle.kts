import java.util.Properties
import groovy.json.JsonSlurper
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
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
val platformVersion = providers.gradleProperty("platformVersion").get()

val maaEndPath = localProperties.getProperty("maaendPath")
    ?: providers.gradleProperty("maaendPath").orNull
    ?: providers.environmentVariable("MAAEND_PATH").orNull

val signingPrivateKeyPath = providers.environmentVariable("PRIVATE_KEY_FILE").orNull
    ?: localProperties.getProperty("signingPrivateKeyFile")
val signingCertificatePath = providers.environmentVariable("CERTIFICATE_CHAIN_FILE").orNull
    ?: localProperties.getProperty("signingCertificateChainFile")
val signingPassword = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
val signingPrivateKey = providers.environmentVariable("PRIVATE_KEY")
val signingCertificate = providers.environmentVariable("CERTIFICATE_CHAIN")

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
        // 本机继续复用已安装的 IDEA；CI 没有本机路径时下载固定版本，避免 SDK 随时间漂移。
        if (!ideaSdkPath.isNullOrBlank()) {
            local(file(ideaSdkPath))
        } else {
            intellijIdea(platformVersion)
        }
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
        providers.gradleProperty("releaseNotesFile").orNull?.let {
            changeNotes = file(it).readText(Charsets.UTF_8)
        }
        ideaVersion {
            sinceBuild = "262"
        }
    }
    signing {
        // CI 直接从 Secrets 注入 PEM 内容，不把私钥写入仓库或构建产物。
        if (!signingPrivateKey.orNull.isNullOrBlank()) {
            privateKey = signingPrivateKey
        } else {
            signingPrivateKeyPath?.let { privateKeyFile = file(it) }
        }
        if (!signingCertificate.orNull.isNullOrBlank()) {
            certificateChain = signingCertificate
        } else {
            signingCertificatePath?.let { certificateChainFile = file(it) }
        }
        password = signingPassword
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf("default")
    }
    pluginVerification {
        ides {
            if (!ideaSdkPath.isNullOrBlank()) {
                local(ideaSdkPath)
            } else {
                create(IntelliJPlatformType.IntellijIdeaUltimate, platformVersion)
            }
            providers.gradleProperty("verificationIdeVersion").orNull?.let {
                create(IntelliJPlatformType.IntellijIdeaUltimate, it)
            }
        }
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
        description = "检查正式发布的版本及签名配置。"
        doLast {
            check(project.version.toString().matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) {
                "正式发布版本需要使用 x.y.z 格式；请通过 -PpluginVersion 指定版本。"
            }
            check(!signingPrivateKey.orNull.isNullOrBlank() || signingPrivateKeyPath?.let { file(it).isFile } == true) {
                "请通过 PRIVATE_KEY 提供私钥内容，或通过 signingPrivateKeyFile / PRIVATE_KEY_FILE 指定私钥文件。"
            }
            check(!signingCertificate.orNull.isNullOrBlank() || signingCertificatePath?.let { file(it).isFile } == true) {
                "请通过 CERTIFICATE_CHAIN 提供证书内容，或通过 signingCertificateChainFile / CERTIFICATE_CHAIN_FILE 指定证书文件。"
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

    publishPlugin {
        // 无论从 CI 还是本机发布，都必须先完成结构检查和签名验证。
        dependsOn(verifyPluginStructure, verifyPluginSignature)
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
    if (name == "prepareSandbox_runIde" && !ideaSdkPath.isNullOrBlank()) {
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

import java.io.File
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val echoMusicDirInput = providers.gradleProperty("echoMusicDir")
    .orElse(providers.environmentVariable("ECHO_MUSIC_DIR"))
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: rootProject.projectDir.resolve("../tv-ui").canonicalPath
val echoMusicDir = rootProject.file(echoMusicDirInput).canonicalFile
if (!echoMusicDir.isDirectory) {
    throw GradleException("The explicit Echo source is not a directory.")
}
val echoPackageJson = file("$echoMusicDir/package.json")
if (!echoPackageJson.isFile) {
    throw GradleException("The explicit Echo source has no package.json.")
}
if (!Regex("\\\"build:tv\\\"\\s*:").containsMatchIn(echoPackageJson.readText())) {
    throw GradleException("The explicit Echo package.json must define build:tv.")
}
val generatedTvUiAssetsDir = layout.buildDirectory.dir("generated/tv-ui").get().asFile
val bridgeFixtureSource = file("$echoMusicDir/contracts/tv-bridge/v1-fixtures.json")
if (!bridgeFixtureSource.isFile) {
    throw GradleException("The explicit Echo source has no Bridge v1 fixture.")
}
val generatedBridgeFixtureDir = layout.buildDirectory.dir("generated/bridge-test-fixture").get().asFile
// WSL exposes WSL_INTEROP/WSL_DISTRO_NAME while still running Linux binaries.
// Selecting cmd.exe there mixes Windows pnpm with Linux node_modules and makes
// reproducible WSL builds fail to resolve local tools such as vue-tsc.
val usesWindowsPnpm = System.getProperty("os.name").contains("Windows", ignoreCase = true)
val pnpmBuildCommand = if (usesWindowsPnpm) {
    listOf("cmd.exe", "/d", "/s", "/c", "pnpm build:tv")
} else {
    listOf("pnpm", "build:tv")
}

val echoTvVersion = "0.4.6-r54"

android {
    namespace = "com.kutv.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kutv.music"
        minSdk = 26 // Android 8.0，覆盖绝大多数 TV
        targetSdk = 35
        versionCode = 20
        versionName = echoTvVersion
        // P1（第十轮）：androidTest 正确 Runner（否则 AndroidJUnit4 测试无法被设备发现/运行）
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        getByName("debug") {
            // Keep instrumentation and exploratory installs isolated from the
            // signed TV build. UI tests intentionally stop playback and clear
            // their queue during cleanup, so sharing the release applicationId
            // would overwrite the installed app and mutate the real session.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // 第十轮质量项：R8 + 资源压缩（release 最小化）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 交付 APK 沿用已在电视上验证过的固定签名，避免覆盖安装失败。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(generatedTvUiAssetsDir)
        }
        getByName("test") {
            resources.srcDir(generatedBridgeFixtureDir)
        }
    }
}

val buildTvAssets = tasks.register<Exec>("buildTvAssets") {
    workingDir(echoMusicDir)
    commandLine(pnpmBuildCommand)
    environment("VITE_ECHO_TV_VERSION", echoTvVersion)
    inputs.property("echoTvVersion", echoTvVersion)
    inputs.dir(file("$echoMusicDir/src/tv"))
    inputs.files(
        file("$echoMusicDir/package.json"),
        file("$echoMusicDir/pnpm-lock.yaml"),
        file("$echoMusicDir/tv.html"),
        file("$echoMusicDir/tsconfig.json"),
        file("$echoMusicDir/tsconfig.tv.json"),
        file("$echoMusicDir/vite.tv.config.mts"),
    )
    outputs.dir(file("$echoMusicDir/dist-tv"))
}

val syncTvAssets = tasks.register<Sync>("syncTvAssets") {
    dependsOn(buildTvAssets)
    from(file("$echoMusicDir/dist-tv"))
    into(file("$generatedTvUiAssetsDir/tv-ui"))
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

fun sha256Tree(root: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    root.walkTopDown()
        .filter(File::isFile)
        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        .forEach { source ->
            digest.update(source.relativeTo(root).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            source.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.update(0.toByte())
        }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun resolveEchoCommit(): String {
    fun capture(vararg command: String): Pair<Int, String> {
        val output = providers.exec {
            commandLine(*command)
            isIgnoreExitValue = true
        }
        return output.result.get().exitValue to output.standardOutput.asText.get().trim()
    }

    var (exitCode, commit) = capture("git", "-C", echoMusicDir.absolutePath, "rev-parse", "--verify", "HEAD")
    if (exitCode != 0 && (System.getenv("WSL_INTEROP") != null || System.getenv("WSL_DISTRO_NAME") != null)) {
        val (pathExitCode, windowsPath) = capture("wslpath", "-w", echoMusicDir.absolutePath)
        if (pathExitCode == 0) {
            val fallback = capture("git.exe", "-C", windowsPath, "rev-parse", "--verify", "HEAD")
            exitCode = fallback.first
            commit = fallback.second
        }
    }
    if (exitCode != 0 || !Regex("[0-9a-fA-F]{40,64}").matches(commit)) {
        throw GradleException("The explicit Echo source must be a Git worktree.")
    }
    return commit.lowercase()
}

val echoCommit = providers.provider(::resolveEchoCommit)
val provenanceFile = file("$generatedTvUiAssetsDir/tv-ui/playback-gate-provenance.json")
val writeTvAssetProvenance = tasks.register("writeTvAssetProvenance") {
    dependsOn(syncTvAssets)
    inputs.dir(file("$echoMusicDir/dist-tv"))
    inputs.property("echoCommit", echoCommit)
    inputs.property("echoCanonicalPath", echoMusicDir.canonicalPath)
    outputs.file(provenanceFile)
    doLast {
        val canonicalSource = echoMusicDir.canonicalPath.replace(File.separatorChar, '/')
        val content = """
            {"schemaVersion":1,"echoCommit":"${echoCommit.get()}","echoPathSha256":"${sha256Hex(canonicalSource.toByteArray(Charsets.UTF_8))}","distSha256":"${sha256Tree(file("$echoMusicDir/dist-tv"))}"}
        """.trimIndent() + System.lineSeparator()
        provenanceFile.parentFile.mkdirs()
        provenanceFile.writeText(content, Charsets.UTF_8)
    }
}

val syncBridgeTestFixture = tasks.register<Sync>("syncBridgeTestFixture") {
    from(bridgeFixtureSource) {
        into("tv-bridge")
    }
    into(generatedBridgeFixtureDir)
}

tasks.configureEach {
    if (
        name == "mergeDebugAssets" ||
        name == "mergeReleaseAssets" ||
        name.startsWith("lint") ||
        name.contains("Lint")
    ) {
        dependsOn(writeTvAssetProvenance)
    }
    if (name.startsWith("process") && name.endsWith("UnitTestJavaRes")) {
        dependsOn(syncBridgeTestFixture)
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(syncBridgeTestFixture)
    systemProperty("echo.bridge.fixture", bridgeFixtureSource.absolutePath)
    systemProperty("tv.main.source", file("src/main/java").absolutePath)
}

dependencies {
    // Compose TV
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.tv:tv-foundation:1.0.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.webkit:webkit:1.16.0")

    // Media3 播放
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // QR 生成（扫码登录展示）
    implementation("com.google.zxing:core:3.5.3")
    // 会话持久化（P0-3：EncryptedSharedPreferences，Keystore 主密钥）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // P1-3（第十九轮）：Coil 图片加载（封面——采样/缓存/流关闭/状态码由库处理，替代自制加载器）
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Explicit dependency for cancellable API calls; same version already used by Coil.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ---- 测试（第九轮 P1：质量保障）----
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // JVM 单测用真实 org.json 实现（Android stub 的 optJSONArray 等未 mock——getLyrics/search 解析测试需要）
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // androidTest 骨架（需模拟器/真机执行：Compose UI + Media3 服务测试）
    // 测试依赖统一由 Compose BOM 管理版本（第十轮质量项：消除版本不一致）
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.media3:media3-test-utils:1.5.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

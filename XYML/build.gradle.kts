import space.minecraftstl.xyml.gradle.TerracottaConfigUpgradeTask
import space.minecraftstl.xyml.gradle.l10n.CheckTranslations
import space.minecraftstl.xyml.gradle.l10n.CreateLanguageList
import space.minecraftstl.xyml.gradle.l10n.CreateLocaleNamesResourceBundle
import space.minecraftstl.xyml.gradle.l10n.UpsideDownTranslate
import space.minecraftstl.xyml.gradle.mod.ParseModDataTask
import space.minecraftstl.xyml.gradle.pack.CreateDeb
import space.minecraftstl.xyml.gradle.pack.ReleaseType
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.jar.JarInputStream
import java.util.jar.Manifest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.shadow)
}

tasks.named("build") {
    dependsOn(":hello-nbt:build", ":lwjgl-unsafe-agent:build", ":mesa-loader-windows:build")
}

tasks.named("check") {
    dependsOn(":hello-nbt:check", ":lwjgl-unsafe-agent:check", ":mesa-loader-windows:check")
}

base {
    archivesName.set("XYML")
}

val currentReleaseType = ReleaseType.fromName(rootProject.extra["xymlReleaseChannel"] as String)
val currentBranchName = (rootProject.extra["xymlBranchName"] as String).takeIf { it.isNotEmpty() }

version = rootProject.extra["xymlReleaseVersion"] as String

val microsoftAuthId = System.getenv("MICROSOFT_AUTH_ID") ?: ""
val curseForgeApiKey = System.getenv("CURSEFORGE_API_KEY") ?: ""

// The bundled stub preserves the upstream launcher code and copyright metadata;
// only its Windows icon resources differ for this fork.
val launcherExe = System.getenv("XYML_LAUNCHER_EXE")
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?: layout.projectDirectory.file("image/XYMLLauncher.windows.stub").asFile

val embedResources = configurations.register("embedResources")

dependencies {
    implementation(project(":XYMLCore"))
    implementation(project(":XYMLBoot"))
    implementation(libs.jwebp)
    implementation(libs.java.info)
    implementation(libs.flatlaf)
    implementation(libs.flatlaf.extras)
    implementation(libs.miglayout.swing)
    implementation(libs.nayuki.qrcodegen)
    implementation(libs.uuid.tools)

    testImplementation(libs.jimfs)

    embedResources(libs.authlib.injector)
    embedResources(project(":lwjgl-unsafe-agent"))
}

fun digest(algorithm: String, bytes: ByteArray): ByteArray = MessageDigest.getInstance(algorithm).digest(bytes)

fun createChecksum(file: File) {
    val algorithms = linkedMapOf(
        "SHA-1" to "sha1",
        "SHA-256" to "sha256",
        "SHA-512" to "sha512"
    )

    algorithms.forEach { (algorithm, ext) ->
        File(file.parentFile, "${file.name}.$ext").writeText(
            digest(algorithm, file.readBytes()).joinToString(separator = "", postfix = "\n") { "%02x".format(it) }
        )
    }
}

fun attachSignature(jar: File) {
    val keyLocation = System.getenv("XYML_SIGNATURE_KEY")
    if (keyLocation == null) {
        logger.warn("Missing signature key")
        return
    }

    val privatekey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(File(keyLocation).readBytes()))
    val signer = Signature.getInstance("SHA512withRSA")
    signer.initSign(privatekey)
    ZipFile(jar).use { zip ->
        zip.stream()
            .sorted(Comparator.comparing { it.name })
            .filter { it.name != "META-INF/xyml_signature" }
            .forEach {
                signer.update(digest("SHA-512", it.name.toByteArray()))
                signer.update(digest("SHA-512", zip.getInputStream(it).readBytes()))
            }
    }
    val signature = signer.sign()
    FileSystems.newFileSystem(URI.create("jar:" + jar.toURI()), emptyMap<String, Any>()).use { zipfs ->
        Files.newOutputStream(zipfs.getPath("META-INF/xyml_signature")).use { it.write(signature) }
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

val compileExports = listOf(
    "java.base/java.lang",
    "java.base/java.lang.reflect",
    "java.base/jdk.internal.loader",
    "jdk.attach/sun.tools.attach",
)

val runtimeOpens = listOf(
    "java.base/java.lang",
    "java.base/java.lang.reflect",
    "java.base/jdk.internal.loader",
    "jdk.attach/sun.tools.attach",
)

tasks.compileJava {
    options.compilerArgs.addAll(compileExports.map { "--add-exports=$it=ALL-UNNAMED" })
}

val xymlProperties = buildList {
    add("xyml.version" to project.version.toString())
    System.getenv("GITHUB_SHA")?.let {
        add("xyml.version.hash" to it)
    }
    add("xyml.release.channel" to currentReleaseType.getName())
    add("xyml.microsoft.auth.id" to microsoftAuthId)
    add("xyml.curseforge.apikey" to curseForgeApiKey)
    add("xyml.authlib-injector.version" to libs.authlib.injector.get().version!!)
    add("xyml.lwjgl-unsafe-agent.version" to rootProject.extra["xymlReleaseVersion"] as String)
}

val xymlPropertiesFile = layout.buildDirectory.file("xyml.properties")
val createPropertiesFile = tasks.register("createPropertiesFile") {
    outputs.file(xymlPropertiesFile)
    xymlProperties.forEach { (k, v) -> inputs.property(k, v) }

    doLast {
        val targetFile = xymlPropertiesFile.get().asFile
        targetFile.parentFile.mkdir()
        targetFile.bufferedWriter().use {
            for ((k, v) in xymlProperties) {
                it.write("$k=$v\n")
            }
        }
    }
}

tasks.jar {
    enabled = false
    dependsOn(tasks["shadowJar"])
}

val jarPath = tasks.jar.get().archiveFile.get().asFile

tasks.shadowJar {
    dependsOn(createPropertiesFile)

    archiveClassifier.set(null as String?)

    exclude("**/package-info.class")
    exclude("META-INF/maven/**")

    exclude("META-INF/services/javax.imageio.spi.ImageReaderSpi")
    exclude("META-INF/services/javax.imageio.spi.ImageInputStreamSpi")

    listOf(
        "aix-*", "sunos-*", "openbsd-*", "dragonflybsd-*", "freebsd-*", "linux-*",
        "*-ppc", "*-ppc64le", "*-s390x", "*-armel",
    ).forEach { exclude("com/sun/jna/$it/**") }

    minimize {
        exclude(dependency("com.google.code.gson:.*:.*"))
        exclude(dependency("net.java.dev.jna:jna:.*"))
        exclude(dependency("com.formdev:flatlaf.*:.*"))
        exclude(dependency("com.miglayout:.*:.*"))
        exclude(project(":XYMLBoot"))
    }

    manifest.attributes(
        "Created-By" to "Copyright(c) 2013-2025 huangyuhui.",
        "Implementation-Version" to project.version.toString(),
        "Main-Class" to "space.minecraftstl.xyml.Main",
        "Multi-Release" to "true",
        "Add-Opens" to runtimeOpens.joinToString(" "),
        "Enable-Native-Access" to "ALL-UNNAMED",
        "Enable-Final-Field-Mutation" to "ALL-UNNAMED",
    )

    into("assets") {
        from(launcherExe) {
            rename { "XYMLLauncher.exe" }
        }
    }

    doLast {
        attachSignature(jarPath)
        createChecksum(jarPath)
    }
}

val embeddedAgentEntry = "assets/lwjgl-unsafe-agent-${project.version}.jar"

val requiredOfflineLibraryEntries = listOf(
    "com/formdev/flatlaf/FlatLaf.class",
    "com/formdev/flatlaf/FlatLaf.properties",
    "com/formdev/flatlaf/FlatLightLaf.properties",
    "com/formdev/flatlaf/FlatDarkLaf.properties",
    "com/formdev/flatlaf/extras/FlatSVGIcon.class",
    "com/github/weisj/jsvg/SVGDocument.class",
    "com/google/gson/Gson.class",
    "com/google/gson/GsonBuilder.class",
    "com/google/gson/reflect/TypeToken.class",
    "com/google/gson/stream/JsonReader.class",
    "kala/encdet/EncodingDetector.class",
    "net/miginfocom/layout/LC.class",
    "net/miginfocom/swing/MigLayout.class",
    "net/jpountz/lz4/LZ4BlockInputStream.class",
    "net/jpountz/lz4/LZ4Factory.class",
    "space/minecraftstl/xyml/library/nbt/NBTElement.class",
    "space/minecraftstl/xyml/library/nbt/chunk/ChunkRegion.class",
    "space/minecraftstl/xyml/library/nbt/io/NBTCodec.class",
    "space/minecraftstl/xyml/library/nbt/tag/CompoundTag.class",
    embeddedAgentEntry,
)

val requiredOfflineSwingIconEntries = listOf(
    "add.svg",
    "arrow-back.svg",
    "arrow-forward.svg",
    "create-new-folder.svg",
    "content-copy.svg",
    "delete.svg",
    "delete-forever.svg",
    "file-import.svg",
    "folder-open.svg",
    "format-list-bulleted.svg",
    "image.svg",
    "nav-accounts.svg",
    "nav-downloads.svg",
    "nav-home.svg",
    "nav-instances.svg",
    "nav-settings.svg",
    "open-in-new.svg",
    "output.svg",
    "refresh.svg",
    "restore.svg",
    "rocket-launch.svg",
    "save.svg",
    "script.svg",
).map { "assets/swing/icons/$it" }

val requiredOfflineInstanceIconEntries = listOf(
    "april_fools",
    "chest",
    "chicken",
    "cleanroom",
    "command",
    "craft_table",
    "fabric",
    "forge",
    "furnace",
    "grass",
    "legacyfabric",
    "neoforge",
    "optifine",
    "quilt",
).map { "assets/img/$it@2x.png" }

val requiredOfflineNbtIconEntries = listOf(
    "TAG_Byte.png",
    "TAG_Byte_Array.png",
    "TAG_Compound.png",
    "TAG_Double.png",
    "TAG_Float.png",
    "TAG_Int.png",
    "TAG_Int_Array.png",
    "TAG_List.png",
    "TAG_Long.png",
    "TAG_Long_Array.png",
    "TAG_Short.png",
    "TAG_String.png",
).map { "assets/img/nbt/$it" }

val requiredOfflineThemeEntries = listOf(
    "assets/themes/xyml.classic/manifest.json",
    "assets/themes/xyml.default/manifest.json",
    "assets/themes/xyml.default/assets/background-dark.png",
    "assets/themes/xyml.default/assets/background-light.png",
    "assets/img/wallpapers/2015-06-22.jpg",
    "assets/img/wallpapers/2016-02-25.jpg",
    "assets/img/wallpapers/2021-08-26.jpg",
)

val requiredOfflineChromeEntries = listOf(
    "assets/img/icon.png",
    "assets/img/icon@2x.png",
    "assets/img/icon@4x.png",
    "assets/img/icon@8x.png",
    "assets/img/icon-title.png",
    "assets/img/team-icon.png",
)

val requiredOfflineSkinEntries = listOf(
    "alex",
    "ari",
    "efe",
    "kai",
    "makena",
    "noor",
    "steve",
    "sunny",
    "zuri",
).flatMap { name ->
    listOf(
        "assets/img/skin/slim/$name.png",
        "assets/img/skin/wide/$name.png",
    )
}

val requiredOfflineSwingFeatureEntries = listOf(
    "space/minecraftstl/xyml/ui/swing/application/SwingApplicationComposition.class",
    "space/minecraftstl/xyml/ui/swing/dialog/EditablePathChooser.class",
    "space/minecraftstl/xyml/ui/swing/SwingWindowAppearanceRequest.class",
    "space/minecraftstl/xyml/ui/swing/page/accounts/AccountAvatarIconCache.class",
    "space/minecraftstl/xyml/ui/swing/page/accounts/AccountAvatarSource.class",
    "space/minecraftstl/xyml/ui/swing/page/accounts/AccountListCellRenderer.class",
    "space/minecraftstl/xyml/ui/swing/page/accounts/LauncherAccountStore.class",
    "space/minecraftstl/xyml/ui/swing/page/accounts/OfflineSkinPreviewPanel.class",
    "space/minecraftstl/xyml/ui/swing/page/accounts/SwingOfflineSkinManagementDialog.class",
    "space/minecraftstl/xyml/ui/swing/page/accounts/SwingOnlineSkinUploadDialog.class",
    "space/minecraftstl/xyml/ui/swing/page/downloads/WorldDownloadPanel.class",
    "space/minecraftstl/xyml/ui/swing/page/downloads/SwingRemoteWorldSaveTargetResolver.class",
    "space/minecraftstl/xyml/ui/swing/page/instances/importing/InstanceJsonImportPanel.class",
    "space/minecraftstl/xyml/ui/swing/page/instances/importing/RepositoryInstanceJsonImportService.class",
    "space/minecraftstl/xyml/ui/swing/page/instances/importing/SwingInstanceJsonImportLauncher.class",
    "space/minecraftstl/xyml/ui/swing/page/instances/management/maintenance/InstanceMaintenancePanel.class",
    "space/minecraftstl/xyml/ui/swing/page/instances/management/worlds/WorldQuickPlayActions.class",
    "space/minecraftstl/xyml/ui/swing/page/nbt/SwingNBTEditorDialog.class",
    "space/minecraftstl/xyml/ui/swing/page/settings/AppearanceSettingsPanel.class",
    "space/minecraftstl/xyml/ui/swing/page/settings/JavaRuntimeAcquisitionPanel.class",
    "space/minecraftstl/xyml/ui/swing/page/settings/theme/ThemeRuntimeController.class",
    "space/minecraftstl/xyml/ui/swing/page/settings/theme/ThemePackManagementPanel.class",
    "space/minecraftstl/xyml/ui/swing/shell/LauncherIconImages.class",
    "space/minecraftstl/xyml/ui/swing/shell/ShellNavigationRail.class",
    "space/minecraftstl/xyml/ui/swing/shell/SwingWindowBackgroundController.class",
    "space/minecraftstl/xyml/ui/swing/shell/WindowBackgroundPaint.class",
    "space/minecraftstl/xyml/ui/swing/shell/WindowBackgroundPaintParser.class",
    "space/minecraftstl/xyml/ui/swing/shell/WindowBackgroundVisual.class",
)

val requiredOfflineUiEntries = buildList {
    addAll(requiredOfflineLibraryEntries)
    addAll(requiredOfflineSwingIconEntries)
    addAll(requiredOfflineInstanceIconEntries)
    addAll(requiredOfflineNbtIconEntries)
    addAll(requiredOfflineThemeEntries)
    addAll(requiredOfflineChromeEntries)
    addAll(requiredOfflineSkinEntries)
    addAll(requiredOfflineSwingFeatureEntries)
}

fun findMissingOrEmptyOfflineEntries(jar: ZipFile): List<String> = requiredOfflineUiEntries.filter { name ->
    val entry = jar.getEntry(name)
    entry == null || entry.isDirectory || entry.size <= 0L
}

val forbiddenRuntimePatcherEntries = listOf(
    "assets/openjfx-dependencies.json",
    "space/minecraftstl/xyml/util/JavaFXPatcher.class",
    "space/minecraftstl/xyml/util/SelfDependencyPatcher.class",
)

val forbiddenRemovedUiEntryPrefixes = listOf(
    "assets/css/",
    "javafx/",
    "com/sun/javafx/",
    "com/jfoenix/",
    "org/glavo/monetfx/",
    "org/glavo/png/javafx/",
    "org/girod/javafx/svgimage/",
    "org/hildan/fxgson/",
    "org/glavo/nbt/",
)

fun findForbiddenRemovedUiEntries(jar: ZipFile): List<String> = jar.entries().asSequence()
    .map { it.name }
    .filter { entry ->
        entry in forbiddenRuntimePatcherEntries
            || forbiddenRemovedUiEntryPrefixes.any(entry::startsWith)
    }
    .sorted()
    .toList()

val verifyOfflineUiArtifact = tasks.register("verifyOfflineUiArtifact") {
    group = "verification"
    description = "Verifies that Swing runtime dependencies and feature resources are embedded in the launcher artifact."

    dependsOn(tasks.shadowJar)
    inputs.file(jarPath)

    doLast {
        ZipFile(jarPath).use { jar ->
            val missingEntries = findMissingOrEmptyOfflineEntries(jar)
            if (missingEntries.isNotEmpty()) {
                throw GradleException("Missing or empty offline runtime entries: ${missingEntries.joinToString()}")
            }
            val forbiddenEntries = findForbiddenRemovedUiEntries(jar)
            if (forbiddenEntries.isNotEmpty()) {
                throw GradleException("Retained removed UI entries: ${forbiddenEntries.joinToString()}")
            }

            val bundledMesaEntries = jar.entries().asSequence()
                .map { it.name }
                .filter {
                    it == "space/minecraftstl/xyml/library/mesa/Loader.class"
                        || it.substringAfterLast('/').startsWith("mesa-loader-windows-")
                }
                .toList()
            if (bundledMesaEntries.isNotEmpty()) {
                throw GradleException("Locally built Mesa artifacts must not be embedded: ${bundledMesaEntries.joinToString()}")
            }

            val embeddedAgent = jar.getEntry(embeddedAgentEntry)
                ?: throw GradleException("Missing locally built agent: $embeddedAgentEntry")
            JarInputStream(jar.getInputStream(embeddedAgent)).use { agentJar ->
                val attributes = agentJar.manifest?.mainAttributes
                    ?: throw GradleException("Embedded agent has no manifest")
                val agentClass = "space.minecraftstl.xyml.library.lwjgl.UnsafeAgent"
                if (attributes.getValue("Premain-Class") != agentClass
                    || attributes.getValue("Agent-Class") != agentClass
                ) {
                    throw GradleException("Embedded agent does not use the XYML namespace")
                }
                if (attributes.getValue("Lwjgl-Unsafe-Agent-Upstream-Version") != "2.0") {
                    throw GradleException("Embedded agent does not record upstream version 2.0")
                }
                val agentEntries = generateSequence { agentJar.nextJarEntry }
                    .map { it.name }
                    .toSet()
                if ("space/minecraftstl/xyml/library/lwjgl/UnsafeAgent.class" !in agentEntries) {
                    throw GradleException("Embedded agent is missing the namespaced entry point")
                }
                if (agentEntries.any { it.startsWith("org/glavo/lwjgl/") }) {
                    throw GradleException("Embedded agent retains the legacy org.glavo.lwjgl package")
                }
            }

            val manifestEntry = jar.getEntry("META-INF/MANIFEST.MF")
                ?: throw GradleException("Launcher artifact has no manifest")
            val runtimeAddOpens = jar.getInputStream(manifestEntry).use { input ->
                Manifest(input).mainAttributes.getValue("Add-Opens")
            }.orEmpty()
            if ("javafx." in runtimeAddOpens) {
                throw GradleException("Launcher manifest still opens JavaFX modules: $runtimeAddOpens")
            }
        }
    }
}

val packagingJavaHome = providers.gradleProperty("xyml.packaging.javaHome")
    .orElse(providers.environmentVariable("XYML_PACKAGING_JAVA_HOME"))
    .orElse(providers.provider { System.getProperty("java.home") })
    .map { file(it) }

val hostOperatingSystem = System.getProperty("os.name").lowercase()
val isWindowsHost = hostOperatingSystem.startsWith("windows")
val isMacHost = hostOperatingSystem.startsWith("mac")
val packagingExecutableSuffix = if (isWindowsHost) ".exe" else ""

fun packagingTool(javaHome: File, name: String): File =
    javaHome.resolve("bin").resolve("$name$packagingExecutableSuffix")

fun jdkFeatureVersion(javaHome: File): Int {
    val releaseFile = javaHome.resolve("release")
    if (!releaseFile.isFile) {
        throw GradleException("Packaging Java home has no release metadata: $releaseFile")
    }

    val release = releaseFile.readText()
    val version = Regex("""(?m)^JAVA_VERSION=\"([^\"]+)\"""")
        .find(release)
        ?.groupValues
        ?.get(1)
        ?: throw GradleException("Packaging JDK release file has no JAVA_VERSION: $releaseFile")
    return Regex("""^\d+""").find(version)?.value?.toInt()
        ?: throw GradleException("Unsupported packaging JDK version: $version")
}

fun numericPackageVersion(rawVersion: String): String {
    val components = Regex("""\d+""").findAll(rawVersion)
        .map { it.value.toLong().coerceAtMost(65535).toInt() }
        .take(3)
        .toMutableList()
    while (components.size < 3) {
        components += 0
    }
    if (components[0] == 0) {
        components[0] = 1
    }
    return components.joinToString(".")
}

val packagedRuntimeModules = listOf(
    "java.base",
    "java.compiler",
    "java.desktop",
    "java.instrument",
    "java.logging",
    "java.management",
    "java.management.rmi",
    "java.naming",
    "java.net.http",
    "java.prefs",
    "java.rmi",
    "java.scripting",
    "java.security.jgss",
    "java.security.sasl",
    "java.sql",
    "java.sql.rowset",
    "java.transaction.xa",
    "java.xml",
    "java.xml.crypto",
    "jdk.attach",
    "jdk.charsets",
    "jdk.crypto.cryptoki",
    "jdk.crypto.ec",
    "jdk.httpserver",
    "jdk.jartool",
    "jdk.jfr",
    "jdk.localedata",
    "jdk.management",
    "jdk.management.agent",
    "jdk.naming.dns",
    "jdk.naming.rmi",
    "jdk.net",
    "jdk.security.auth",
    "jdk.security.jgss",
    "jdk.unsupported",
    "jdk.xml.dom",
    "jdk.zipfs",
)

require(packagedRuntimeModules.none { it.startsWith("javafx.") }) {
    "The packaged runtime module list must not contain JavaFX"
}

val packagedJvmOptions = buildList {
    add("-Dxyml.packaged=true")
    add("-Dfile.encoding=UTF-8")
    addAll(runtimeOpens.map { "--add-opens=$it=ALL-UNNAMED" })
}

val nativePackageVersion = numericPackageVersion(project.version.toString())
val jpackageInputDirectory = layout.buildDirectory.dir("jpackage/input")
val jlinkRuntimeDirectory = layout.buildDirectory.dir("jpackage/runtime")
val appImageDirectory = layout.buildDirectory.dir("jpackage/app-image")
val installerDirectory = layout.buildDirectory.dir("jpackage/installer")
val stagedApplicationJar = jpackageInputDirectory.map { it.file("XYML.jar") }
val platformIcon = layout.projectDirectory.file(
    when {
        isWindowsHost -> "image/xyml.ico"
        isMacHost -> "image/xyml.icns"
        else -> "image/xyml.png"
    }
)

val validatePackagingJdk17 = tasks.register("validatePackagingJdk17") {
    group = "distribution"
    description = "Verifies that native application packaging uses a complete JDK 17 installation."

    inputs.property("packagingJavaHome", packagingJavaHome.map { it.absolutePath })

    doLast {
        val javaHome = packagingJavaHome.get()
        if (jdkFeatureVersion(javaHome) != 17) {
            throw GradleException(
                "Native packaging requires JDK 17, but ${javaHome.resolve("release")} describes " +
                    "Java ${jdkFeatureVersion(javaHome)}. Set XYML_PACKAGING_JAVA_HOME or " +
                    "-Pxyml.packaging.javaHome to a JDK 17 installation."
            )
        }

        val requiredFiles = listOf(
            javaHome.resolve("jmods/java.base.jmod"),
            packagingTool(javaHome, "java"),
            packagingTool(javaHome, "jlink"),
            packagingTool(javaHome, "jpackage"),
        )
        val missingFiles = requiredFiles.filterNot(File::isFile)
        if (missingFiles.isNotEmpty()) {
            throw GradleException("Incomplete packaging JDK 17; missing: ${missingFiles.joinToString()}")
        }
    }
}

val prepareJpackageInput = tasks.register<Sync>("prepareJpackageInput") {
    group = "distribution"
    description = "Stages the dependency-complete launcher JAR for jpackage without network access."

    dependsOn(verifyOfflineUiArtifact)
    from(tasks.shadowJar.flatMap { it.archiveFile }) {
        rename { "XYML.jar" }
    }
    into(jpackageInputDirectory)
}

val jlinkRuntimeImage = tasks.register<Exec>("jlinkRuntimeImage") {
    group = "distribution"
    description = "Builds the platform-native, JavaFX-free JDK 17 runtime image."

    dependsOn(validatePackagingJdk17)
    inputs.property("runtimeModules", packagedRuntimeModules)
    inputs.property("packagingJavaHome", packagingJavaHome.map { it.absolutePath })
    inputs.file(packagingJavaHome.map { it.resolve("release") })
    inputs.files(packagingJavaHome.map { javaHome ->
        packagedRuntimeModules.map { module -> javaHome.resolve("jmods/$module.jmod") }
    })
    outputs.dir(jlinkRuntimeDirectory)

    doFirst {
        val javaHome = packagingJavaHome.get()
        val outputDirectory = jlinkRuntimeDirectory.get().asFile
        delete(outputDirectory)
        commandLine(
            packagingTool(javaHome, "jlink"),
            "--module-path", javaHome.resolve("jmods"),
            "--add-modules", packagedRuntimeModules.joinToString(","),
            "--output", outputDirectory,
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages",
            "--compress=2",
        )
    }
}

fun runtimeModulesFromRelease(releaseFile: File): List<String> {
    val release = releaseFile.readText()
    return Regex("""(?m)^MODULES=\"([^\"]*)\"""")
        .find(release)
        ?.groupValues
        ?.get(1)
        ?.split(' ')
        ?.filter(String::isNotBlank)
        ?: throw GradleException("Runtime release file has no MODULES entry: $releaseFile")
}

val verifyJlinkRuntime = tasks.register("verifyJlinkRuntime") {
    group = "verification"
    description = "Verifies the linked runtime is Java 17 and contains no JavaFX modules."

    dependsOn(jlinkRuntimeImage)
    inputs.dir(jlinkRuntimeDirectory)

    doLast {
        val runtimeHome = jlinkRuntimeDirectory.get().asFile
        if (jdkFeatureVersion(runtimeHome) != 17) {
            throw GradleException("Linked runtime is not Java 17: ${runtimeHome.resolve("release")}")
        }

        val modules = runtimeModulesFromRelease(runtimeHome.resolve("release"))
        val missingModules = packagedRuntimeModules.filterNot(modules::contains)
        if (missingModules.isNotEmpty()) {
            throw GradleException("Linked runtime is missing modules: ${missingModules.joinToString()}")
        }
        val javaFxModules = modules.filter { it.startsWith("javafx.") }
        if (javaFxModules.isNotEmpty()) {
            throw GradleException("Linked runtime still contains JavaFX modules: ${javaFxModules.joinToString()}")
        }
    }
}

fun jpackageArguments(destination: File, packageType: String): List<Any> = buildList {
    add("--type")
    add(packageType)
    add("--name")
    add("XYML")
    add("--app-version")
    add(nativePackageVersion)
    add("--vendor")
    add("MinecraftSTL")
    add("--description")
    add("XYML Minecraft Launcher")
    add("--dest")
    add(destination)
    add("--input")
    add(jpackageInputDirectory.get().asFile)
    add("--main-jar")
    add(stagedApplicationJar.get().asFile.name)
    add("--main-class")
    add("space.minecraftstl.xyml.Main")
    add("--runtime-image")
    add(jlinkRuntimeDirectory.get().asFile)
    if (platformIcon.asFile.isFile) {
        add("--icon")
        add(platformIcon.asFile)
    }
    packagedJvmOptions.forEach { option ->
        add("--java-options")
        add(option)
    }
}

val jpackageAppImage = tasks.register<Exec>("jpackageAppImage") {
    group = "distribution"
    description = "Builds a self-contained native app-image for the current host platform."

    dependsOn(prepareJpackageInput, verifyJlinkRuntime)
    inputs.file(stagedApplicationJar)
    inputs.dir(jlinkRuntimeDirectory)
    inputs.file(platformIcon)
    inputs.property("nativePackageVersion", nativePackageVersion)
    inputs.property("packagedJvmOptions", packagedJvmOptions)
    outputs.dir(appImageDirectory)

    doFirst {
        val destination = appImageDirectory.get().asFile
        delete(destination)
        commandLine(
            listOf(packagingTool(packagingJavaHome.get(), "jpackage")) +
                jpackageArguments(destination, "app-image")
        )
    }
}

val verifyPackagedRuntime = tasks.register("verifyPackagedRuntime") {
    group = "verification"
    description = "Verifies the app-image embeds the fat JAR, JDK 17 runtime, and packaged-mode option."

    dependsOn(jpackageAppImage)
    inputs.dir(appImageDirectory)

    doLast {
        val appImageRoot = appImageDirectory.get().asFile
        val configFile = appImageRoot.walkTopDown()
            .firstOrNull { it.isFile && it.name == "XYML.cfg" }
            ?: throw GradleException("Packaged app-image has no XYML.cfg")
        if ("-Dxyml.packaged=true" !in configFile.readText()) {
            throw GradleException("Packaged app-image does not enable -Dxyml.packaged=true: $configFile")
        }

        val packagedJar = appImageRoot.walkTopDown()
            .firstOrNull { it.isFile && it.name == "XYML.jar" }
            ?: throw GradleException("Packaged app-image has no embedded XYML.jar")
        if (!digest("SHA-256", packagedJar.readBytes()).contentEquals(
                digest("SHA-256", stagedApplicationJar.get().asFile.readBytes())
            )
        ) {
            throw GradleException("Packaged app-image does not contain the staged shadow JAR")
        }
        ZipFile(packagedJar).use { jar ->
            val missingEntries = findMissingOrEmptyOfflineEntries(jar)
            if (missingEntries.isNotEmpty()) {
                throw GradleException(
                    "Packaged app-image has missing or empty offline runtime entries: ${missingEntries.joinToString()}"
                )
            }
            val forbiddenEntries = findForbiddenRemovedUiEntries(jar)
            if (forbiddenEntries.isNotEmpty()) {
                throw GradleException("Packaged app-image retains legacy UI entries: ${forbiddenEntries.joinToString()}")
            }
        }

        val runtimeRelease = appImageRoot.walkTopDown()
            .firstOrNull { it.isFile && it.name == "release" && "runtime" in it.invariantSeparatorsPath }
            ?: throw GradleException("Packaged app-image has no linked runtime release metadata")
        if (jdkFeatureVersion(runtimeRelease.parentFile) != 17) {
            throw GradleException("Packaged app-image runtime is not Java 17: $runtimeRelease")
        }
        val javaFxModules = runtimeModulesFromRelease(runtimeRelease).filter { it.startsWith("javafx.") }
        if (javaFxModules.isNotEmpty()) {
            throw GradleException("Packaged app-image runtime contains JavaFX: ${javaFxModules.joinToString()}")
        }
    }
}

val defaultInstallerType = when {
    isWindowsHost -> "exe"
    isMacHost -> "dmg"
    else -> "deb"
}
val nativeInstallerType = providers.gradleProperty("xyml.package.type").orElse(defaultInstallerType)
val packagedAppImage = appImageDirectory.map { directory ->
    directory.dir(if (isMacHost) "XYML.app" else "XYML")
}

val jpackageInstaller = tasks.register<Exec>("jpackageInstaller") {
    group = "distribution"
    description = "Builds the current platform installer (exe, dmg, or deb) using native packaging tools."

    dependsOn(verifyPackagedRuntime)
    inputs.dir(packagedAppImage)
    inputs.file(platformIcon)
    inputs.property("nativeInstallerType", nativeInstallerType)
    inputs.property("nativePackageVersion", nativePackageVersion)
    outputs.dir(installerDirectory)

    doFirst {
        val destination = installerDirectory.get().asFile
        delete(destination)
        val packageType = nativeInstallerType.get()
        val arguments = mutableListOf<Any>(
            "--type", packageType,
            "--name", "XYML",
            "--app-version", nativePackageVersion,
            "--vendor", "MinecraftSTL",
            "--description", "XYML Minecraft Launcher",
            "--dest", destination,
            "--app-image", packagedAppImage.get().asFile,
        )
        if (platformIcon.asFile.isFile) {
            arguments.addAll(listOf("--icon", platformIcon.asFile))
        }
        when {
            isWindowsHost -> arguments.addAll(listOf(
                "--win-dir-chooser",
                "--win-menu",
                "--win-menu-group", "XYML",
                "--win-shortcut",
            ))
            isMacHost -> arguments.addAll(listOf(
                "--mac-package-identifier", "space.minecraftstl.xyml",
                "--mac-package-name", "XYML",
            ))
            else -> arguments.addAll(listOf(
                "--linux-package-name", "xyml",
                "--linux-menu-group", "Game",
                "--linux-app-category", "Game",
                "--linux-shortcut",
            ))
        }
        commandLine(listOf(packagingTool(packagingJavaHome.get(), "jpackage")) + arguments)
    }
}

tasks.register("nativePackage") {
    group = "distribution"
    description = "Builds and verifies the app-image and then builds the native installer."
    dependsOn(verifyPackagedRuntime, jpackageInstaller)
}

tasks.check {
    dependsOn("validateReleaseMetadata")
    dependsOn(verifyOfflineUiArtifact)
}

tasks.register("validateReleaseMetadata") {
    group = "verification"
    description = "Validates and prints the canonical XYML release channel and version."
    inputs.property("releaseChannel", currentReleaseType.getName())
    inputs.property("releaseVersion", project.version.toString())
    inputs.property("gitBranch", currentBranchName ?: "<detached-or-unknown>")

    doLast {
        logger.lifecycle("XYML Git branch: ${currentBranchName ?: "<detached-or-unknown>"}")
        logger.lifecycle("XYML release channel: ${currentReleaseType.getName()}")
        logger.lifecycle("XYML release version: ${project.version}")
    }
}

tasks.processResources {
    dependsOn(createPropertiesFile)
    dependsOn(upsideDownTranslate)
    dependsOn(createLocaleNamesResourceBundle)
    dependsOn(createLanguageList)

    into("assets/") {
        from(xymlPropertiesFile)
        from(embedResources)
    }

    into("assets/lang") {
        from(createLanguageList.map { it.outputFile })
        from(upsideDownTranslate.map { it.outputFile })
        from(createLocaleNamesResourceBundle.map { it.outputDirectory })
    }

    inputs.property("terracotta_version", libs.versions.terracotta)
    doLast {
        upgradeTerracottaConfig.get().checkValid()
    }
}

fun artifactFile(ext: String) = jarPath.resolveSibling(jarPath.nameWithoutExtension + '.' + ext)

val makeExecutables = tasks.register("makeExecutables") {
    val extensions = listOf("exe", "sh")

    dependsOn(tasks.jar)

    inputs.file(jarPath)
    outputs.files(extensions.map { artifactFile(it) })

    doLast {
        val jarContent = jarPath.readBytes()

        ZipFile(jarPath).use { zipFile ->
            for (extension in extensions) {
                val output = artifactFile(extension)
                val entry = zipFile.getEntry("assets/XYMLLauncher.$extension")
                    ?: throw GradleException("XYMLLauncher.$extension not found")

                output.outputStream().use { outputStream ->
                    zipFile.getInputStream(entry).use { it.copyTo(outputStream) }
                    outputStream.write(jarContent)
                }

                createChecksum(output)
            }
        }
    }
}

val makeDeb = tasks.register("makeDeb", CreateDeb::class) {
    dependsOn(makeExecutables)

    val debFile = layout.file(provider { artifactFile("deb") })

    version.set(project.version.toString())
    releaseType.set(currentReleaseType)
    launcherClassName.set("space.minecraftstl.xyml.Launcher")
    appShFile.set(layout.file(provider { artifactFile("sh") }))
    iconFile.set(layout.projectDirectory.file("image/xyml.png"))
    outputFile.set(debFile)

    doLast {
        createChecksum(debFile.get().asFile)
    }
}

tasks.build {
    dependsOn(makeExecutables)
    dependsOn(makeDeb)
}

fun parseToolOptions(options: String?): MutableList<String> {
    if (options == null)
        return mutableListOf()

    val builder = StringBuilder()
    val result = mutableListOf<String>()

    var offset = 0

    loop@ while (offset < options.length) {
        val ch = options[offset]
        if (Character.isWhitespace(ch)) {
            if (builder.isNotEmpty()) {
                result += builder.toString()
                builder.clear()
            }

            while (offset < options.length && Character.isWhitespace(options[offset])) {
                offset++
            }

            continue@loop
        }

        if (ch == '\'' || ch == '"') {
            offset++

            while (offset < options.length) {
                val ch2 = options[offset++]
                if (ch2 != ch) {
                    builder.append(ch2)
                } else {
                    continue@loop
                }
            }

            throw GradleException("Unmatched quote in $options")
        }

        builder.append(ch)
        offset++
    }

    if (builder.isNotEmpty()) {
        result += builder.toString()
    }

    return result
}

// For IntelliJ IDEA
tasks.withType<JavaExec> {
    if (name != "run") {
        jvmArgs(runtimeOpens.map { "--add-opens=$it=ALL-UNNAMED" })
//        if (javaVersion >= JavaVersion.VERSION_24) {
//            jvmArgs("--enable-native-access=ALL-UNNAMED")
//        }
    }
}

tasks.register<JavaExec>("run") {
    dependsOn(tasks.jar)

    group = "application"

    classpath = files(jarPath)
    workingDir = rootProject.rootDir

    val vmOptions = parseToolOptions(System.getenv("XYML_JAVA_OPTS") ?: "-Xmx1g")
    if (vmOptions.none { it.startsWith("-Dxyml.offline.auth.restricted=") })
        vmOptions += "-Dxyml.offline.auth.restricted=false"

    jvmArgs(vmOptions)

    val xymlJavaHome = System.getenv("XYML_JAVA_HOME")
    if (xymlJavaHome != null) {
        this.executable(
            file(xymlJavaHome).resolve("bin")
                .resolve(if (System.getProperty("os.name").lowercase().startsWith("windows")) "java.exe" else "java")
        )
    }

    doFirst {
        logger.quiet("XYML_JAVA_OPTS: {}", vmOptions)
        logger.quiet("XYML_JAVA_HOME: {}", xymlJavaHome ?: System.getProperty("java.home"))
    }
}

// terracotta

val upgradeTerracottaConfig = tasks.register<TerracottaConfigUpgradeTask>("upgradeTerracottaConfig") {
    val destination = layout.projectDirectory.file("src/main/resources/assets/terracotta.json")
    val source = layout.projectDirectory.file("terracotta-template.json");

    classifiers.set(
        listOf(
            "windows-x86_64", "windows-arm64",
            "macos-x86_64", "macos-arm64",
            "linux-x86_64", "linux-arm64", "linux-loongarch64", "linux-riscv64",
            "freebsd-x86_64"
        )
    )

    version.set(libs.versions.terracotta)
    downloadURL.set($$"https://github.com/burningtnt/Terracotta/releases/download/v${version}/terracotta-${version}-${classifier}-pkg.tar.gz")

    templateFile.set(source)
    outputFile.set(destination)
}

// Check Translations

tasks.register<CheckTranslations>("checkTranslations") {
    val dir = layout.projectDirectory.dir("src/main/resources/assets/lang")

    englishFile.set(dir.file("I18N.properties"))
    simplifiedChineseFile.set(dir.file("I18N_zh_CN.properties"))
    traditionalChineseFile.set(dir.file("I18N_zh.properties"))
    classicalChineseFile.set(dir.file("I18N_lzh.properties"))
}

// l10n

val generatedDir = layout.buildDirectory.dir("generated")

val upsideDownTranslate = tasks.register<UpsideDownTranslate>("upsideDownTranslate") {
    inputFile.set(layout.projectDirectory.file("src/main/resources/assets/lang/I18N.properties"))
    outputFile.set(generatedDir.map { it.file("generated/i18n/I18N_en_Qabs.properties") })
}

val createLanguageList = tasks.register<CreateLanguageList>("createLanguageList") {
    resourceBundleDir.set(layout.projectDirectory.dir("src/main/resources/assets/lang"))
    resourceBundleBaseName.set("I18N")
    additionalLanguages.set(listOf("en-Qabs"))
    outputFile.set(generatedDir.map { it.file("languages.json") })
}

val createLocaleNamesResourceBundle = tasks.register<CreateLocaleNamesResourceBundle>("createLocaleNamesResourceBundle") {
    dependsOn(createLanguageList)

    languagesFile.set(createLanguageList.flatMap { it.outputFile })
    outputDirectory.set(generatedDir.map { it.dir("generated/LocaleNames") })
}

// mcmod data

tasks.register<ParseModDataTask>("parseModData") {
    inputFile.set(layout.projectDirectory.file("mod.json"))
    outputFile.set(layout.projectDirectory.file("src/main/resources/assets/mod_data.txt"))
}

tasks.register<ParseModDataTask>("parseModPackData") {
    inputFile.set(layout.projectDirectory.file("modpack.json"))
    outputFile.set(layout.projectDirectory.file("src/main/resources/assets/modpack_data.txt"))
}

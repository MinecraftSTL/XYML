import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    base
}

group = "space.minecraftstl.xyml"
version = rootProject.extra["xymlReleaseVersion"] as String
description = "XYMLL native Windows launcher for XYML."

val upstreamVersion = "3.7.0.1"
val isWindowsHost = System.getProperty("os.name").lowercase().startsWith("windows")
val executableSuffix = if (isWindowsHost) ".exe" else ""
val cmakeExecutable = providers.gradleProperty("xyml.cmake.executable")
    .orElse(providers.environmentVariable("XYML_CMAKE"))
    .orElse(if (isWindowsHost) "C:\\Program Files\\CMake\\bin\\cmake.exe" else "cmake")
val sanitizedProcessEnvironment = buildMap<String, String> {
    var processPath: String? = null
    System.getenv().forEach { (name, value) ->
        if (name.equals("PATH", ignoreCase = true)) {
            if (processPath == null) {
                processPath = value
            }
        } else {
            put(name, value)
        }
    }
    processPath?.let { put("Path", it) }
}

fun windowsProductVersion(rawVersion: String): String {
    val components = Regex("""\d+""").findAll(rawVersion)
        .map { it.value.toLong().coerceAtMost(65535).toInt() }
        .take(4)
        .toMutableList()
    while (components.size < 4) {
        components += 0
    }
    return components.joinToString(".")
}

fun sha256(file: File): String = HexFormat.of().formatHex(
    file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var count: Int
        while (input.read(buffer).also { count = it } >= 0) {
            if (count > 0) {
                digest.update(buffer, 0, count)
            }
        }
        digest.digest()
    },
)

val launcherSourceFiles = files(
    layout.projectDirectory.file("CMakeLists.txt"),
    layout.projectDirectory.file("XYMLL.ico"),
    fileTree("XYMLL") {
        include("**/*.cpp", "**/*.h", "**/*.in")
    },
)

fun launcherSourceFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    launcherSourceFiles.files.sortedBy { it.relativeTo(projectDir).invariantSeparatorsPath }.forEach { sourceFile ->
        val relativePath = sourceFile.relativeTo(projectDir).invariantSeparatorsPath
        digest.update(relativePath.toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
        val content = if (sourceFile.extension == "ico") {
            sourceFile.readBytes()
        } else {
            sourceFile.readText(Charsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .toByteArray(StandardCharsets.UTF_8)
        }
        digest.update(content)
        digest.update(0.toByte())
    }
    return HexFormat.of().formatHex(digest.digest())
}

val productVersion = windowsProductVersion(project.version.toString())
val cmakeBuildDirectory = layout.buildDirectory.dir("cmake")
val nativeExecutable = cmakeBuildDirectory.map { it.file("Release/XYMLL.exe") }
val fallbackExecutable = layout.projectDirectory.file("fallback/XYMLL.exe")
val fallbackChecksum = layout.projectDirectory.file("fallback/XYMLL.exe.sha256")
val fallbackSourceFingerprint = layout.projectDirectory.file("fallback/source.sha256")
val preparedDirectory = layout.buildDirectory.dir("distributions")
val preparedExecutable = preparedDirectory.map { it.file("XYMLL.exe") }

val configureXYMLL = tasks.register<Exec>("configureXYMLL") {
    group = "build"
    description = "Configures XYMLL with the Visual Studio 2022 Win32 generator."
    onlyIf { isWindowsHost }

    inputs.files(launcherSourceFiles)
    inputs.property("cmakeExecutable", cmakeExecutable)
    inputs.property("productVersion", productVersion)
    inputs.property("displayVersion", project.version.toString())
    inputs.property("upstreamVersion", upstreamVersion)
    outputs.dir(cmakeBuildDirectory)
    setEnvironment(sanitizedProcessEnvironment)

    doFirst {
        val outputDirectory = cmakeBuildDirectory.get().asFile
        delete(outputDirectory)
        commandLine(
            cmakeExecutable.get(),
            "-S", projectDir,
            "-B", outputDirectory,
            "-G", "Visual Studio 17 2022",
            "-A", "Win32",
            "-DXYMLL_PRODUCT_VERSION=$productVersion",
            "-DXYMLL_DISPLAY_VERSION=${project.version}",
            "-DXYMLL_UPSTREAM_VERSION=$upstreamVersion",
        )
    }
}

val buildNativeXYMLL = tasks.register<Exec>("buildNativeXYMLL") {
    group = "build"
    description = "Builds XYMLL with Microsoft Visual C++."
    onlyIf { isWindowsHost }
    dependsOn(configureXYMLL)
    outputs.file(nativeExecutable)
    setEnvironment(sanitizedProcessEnvironment)

    doFirst {
        commandLine(
            cmakeExecutable.get(),
            "--build", cmakeBuildDirectory.get().asFile,
            "--config", "Release",
            "--target", "XYMLL",
        )
    }
}

val updateXYMLLFallback = tasks.register("updateXYMLLFallback") {
    group = "build setup"
    description = "Refreshes the checked-in XYMLL fallback from the current MSVC build."
    onlyIf { isWindowsHost }
    dependsOn(buildNativeXYMLL)
    inputs.files(launcherSourceFiles)
    inputs.file(nativeExecutable)
    outputs.upToDateWhen { false }

    doLast {
        val source = nativeExecutable.get().asFile
        val target = fallbackExecutable.asFile
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
        fallbackChecksum.asFile.writeText("${sha256(target)}\n", Charsets.UTF_8)
        fallbackSourceFingerprint.asFile.writeText("${launcherSourceFingerprint()}\n", Charsets.UTF_8)
    }
}

val verifyXYMLLFallback = tasks.register("verifyXYMLLFallback") {
    group = "verification"
    description = "Verifies the fallback checksum and same-source fingerprint."
    inputs.files(launcherSourceFiles, fallbackExecutable, fallbackChecksum, fallbackSourceFingerprint)

    doLast {
        check(fallbackExecutable.asFile.isFile) { "Missing checked-in XYMLL fallback executable" }
        val expectedChecksum = fallbackChecksum.asFile.readText(Charsets.UTF_8).trim()
        check(expectedChecksum == sha256(fallbackExecutable.asFile)) { "XYMLL fallback checksum mismatch" }
        val expectedSourceFingerprint = fallbackSourceFingerprint.asFile.readText(Charsets.UTF_8).trim()
        check(expectedSourceFingerprint == launcherSourceFingerprint()) {
            "XYMLL fallback was not generated from the current native source"
        }
    }
}

val selectedExecutable = if (isWindowsHost) nativeExecutable else fallbackExecutable
val prepareXYMLLExecutable = tasks.register<Sync>("prepareXYMLLExecutable") {
    group = "build"
    description = "Stages the independently built or verified fallback XYMLL executable."
    if (isWindowsHost) {
        dependsOn(buildNativeXYMLL)
    } else {
        dependsOn(verifyXYMLLFallback)
    }
    from(selectedExecutable)
    into(preparedDirectory)
    rename { "XYMLL.exe" }
}

fun readUnsignedShortLittleEndian(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

fun readIntLittleEndian(bytes: ByteArray, offset: Int): Int =
    readUnsignedShortLittleEndian(bytes, offset) or (readUnsignedShortLittleEndian(bytes, offset + 2) shl 16)

fun containsBytes(bytes: ByteArray, expected: ByteArray): Boolean {
    if (expected.isEmpty()) {
        return true
    }
    return (0..bytes.size - expected.size).any { offset ->
        expected.indices.all { index -> bytes[offset + index] == expected[index] }
    }
}

fun verifyXYMLLExecutable(executable: File) {
    val bytes = executable.readBytes()
    check(bytes.size >= 4096 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte()) {
        "XYMLL does not have a valid DOS header: $executable"
    }
    val peOffset = readIntLittleEndian(bytes, 0x3C)
    check(peOffset >= 0 && peOffset + 6 <= bytes.size) { "XYMLL PE header is outside the executable" }
    check(String(bytes, peOffset, 4, StandardCharsets.ISO_8859_1) == "PE\u0000\u0000") {
        "XYMLL does not have a valid PE signature"
    }
    check(readUnsignedShortLittleEndian(bytes, peOffset + 4) == 0x014C) {
        "XYMLL must remain an x86 executable that can launch on all supported Windows architectures"
    }

    listOf(
        "XYMLL",
        "XYML Launcher for Windows",
        "Copyright (C) 2025 huangyuhui",
        "HMCLauncher $upstreamVersion",
        project.version.toString(),
    ).forEach { resourceValue ->
        check(containsBytes(bytes, resourceValue.toByteArray(StandardCharsets.UTF_16LE))) {
            "XYMLL resource metadata is missing: $resourceValue"
        }
    }
}

val verifyXYMLLSource = tasks.register("verifyXYMLLSource") {
    group = "verification"
    description = "Verifies the license notices and XYML runtime branding in the native source."
    inputs.files(launcherSourceFiles, "README.md", "LICENSE", "SOURCE.md")

    doLast {
        val license = file("LICENSE").readText(Charsets.UTF_8)
        check("GNU GENERAL PUBLIC LICENSE" in license && "Version 3, 29 June 2007" in license) {
            "XYMLL must retain the complete upstream GPLv3 license"
        }
        val readme = file("README.md").readText(Charsets.UTF_8)
        check("Additional terms under GPLv3 Section 7" in readme) { "XYMLL must retain the upstream additional terms" }
        check("must change the software name or the version number" in readme) { "Missing upstream rename condition" }
        check("must not remove the copyright declaration" in readme) { "Missing upstream copyright condition" }

        val runtimeSources = fileTree("XYMLL") { include("**/*.cpp", "**/*.h", "**/*.in") }
            .files.joinToString("\n") { it.readText(Charsets.UTF_8) }
        listOf("HMCL_JAVA_HOME", "HMCL_JAVA_OPTS", ".hmcl\\\\java", "[HMCLauncher]").forEach { legacyBrand ->
            check(legacyBrand !in runtimeSources) { "XYMLL retains legacy runtime branding: $legacyBrand" }
        }
        check("XYML_JAVA_HOME" in runtimeSources) { "XYMLL source does not expose XYML_JAVA_HOME" }
        check("XYML_JAVA_OPTS" in runtimeSources) { "XYMLL source does not expose XYML_JAVA_OPTS" }
        check(".xyml\\\\java" in runtimeSources) { "XYMLL source does not search .xyml/java" }
    }
}

val verifyXYMLLArtifact = tasks.register("verifyXYMLLArtifact") {
    group = "verification"
    description = "Verifies the selected XYMLL PE architecture and resource metadata."
    dependsOn(prepareXYMLLExecutable, verifyXYMLLFallback, verifyXYMLLSource)
    inputs.file(preparedExecutable)

    doLast {
        verifyXYMLLExecutable(preparedExecutable.get().asFile)
    }
}

val smokeSource = layout.buildDirectory.file("generated/smoke/XYMLLSmokeProbe.java")
val smokeClasses = layout.buildDirectory.dir("smoke/classes")
val smokeJarFile = layout.buildDirectory.file("smoke/xyml-launcher-smoke.jar")
val smokeExecutable = layout.buildDirectory.file("smoke/XYMLL-smoke.exe")
val smokeResult = layout.buildDirectory.file("smoke/result.txt")

val generateXYMLLSmokeSource = tasks.register("generateXYMLLSmokeSource") {
    outputs.file(smokeSource)

    doLast {
        val output = smokeSource.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.util.List;

            public final class XYMLLSmokeProbe {
                private XYMLLSmokeProbe() {
                }

                public static void main(String[] args) throws Exception {
                    if (args.length != 2) {
                        System.exit(91);
                    }
                    Files.write(
                            Path.of(args[0]),
                            List.of(
                                    System.getProperty("xyml.smoke", "missing"),
                                    Path.of("").toAbsolutePath().normalize().toString(),
                                    Integer.toString(args.length),
                                    args[1]),
                            StandardCharsets.UTF_8);
                    System.exit(37);
                }
            }
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
    }
}

val compileXYMLLSmokeProbe = tasks.register<Exec>("compileXYMLLSmokeProbe") {
    dependsOn(generateXYMLLSmokeSource)
    inputs.file(smokeSource)
    outputs.dir(smokeClasses)

    doFirst {
        val classesDirectory = smokeClasses.get().asFile
        delete(classesDirectory)
        classesDirectory.mkdirs()
        val javac = File(System.getProperty("java.home"), "bin/javac$executableSuffix")
        check(javac.isFile) { "The Gradle runtime must be a JDK with javac: $javac" }
        commandLine(javac, "--release", "17", "-d", classesDirectory, smokeSource.get().asFile)
    }
}

val createXYMLLSmokeJar = tasks.register<Jar>("createXYMLLSmokeJar") {
    dependsOn(compileXYMLLSmokeProbe)
    archiveFileName.set("xyml-launcher-smoke.jar")
    destinationDirectory.set(layout.buildDirectory.dir("smoke"))
    from(smokeClasses)
    manifest.attributes("Main-Class" to "XYMLLSmokeProbe")
}

val prepareXYMLLSmokeExecutable = tasks.register("prepareXYMLLSmokeExecutable") {
    dependsOn(prepareXYMLLExecutable, createXYMLLSmokeJar)
    inputs.files(preparedExecutable, smokeJarFile)
    outputs.file(smokeExecutable)

    doLast {
        val output = smokeExecutable.get().asFile
        output.parentFile.mkdirs()
        output.outputStream().use { stream ->
            preparedExecutable.get().asFile.inputStream().use { it.copyTo(stream) }
            smokeJarFile.get().asFile.inputStream().use { it.copyTo(stream) }
        }
    }
}

val smokeTestXYMLL = tasks.register<Exec>("smokeTestXYMLL") {
    group = "verification"
    description = "Launches an appended Java 17 test JAR through XYMLL and verifies its process contract."
    onlyIf { isWindowsHost }
    dependsOn(prepareXYMLLSmokeExecutable)
    inputs.file(smokeExecutable)
    outputs.file(smokeResult)
    isIgnoreExitValue = true

    doFirst {
        val resultFile = smokeResult.get().asFile
        resultFile.delete()
        environment("XYML_JAVA_HOME", System.getProperty("java.home"))
        environment("XYML_JAVA_OPTS", "-Dxyml.smoke=from-xyml-java-opts")
        environment("XYML_LAUNCHER_VERBOSE_OUTPUT", "false")
        commandLine(smokeExecutable.get().asFile, resultFile, "argument with spaces")
    }

    doLast {
        check(executionResult.get().exitValue == 37) {
            "XYMLL did not propagate the Java process exit code 37: ${executionResult.get().exitValue}"
        }
        val lines = smokeResult.get().asFile.readLines(Charsets.UTF_8)
        check(lines.size == 4) { "Unexpected XYMLL smoke output: $lines" }
        check(lines[0] == "from-xyml-java-opts") { "XYML_JAVA_OPTS was not forwarded: $lines" }
        check(File(lines[1]).canonicalFile == smokeExecutable.get().asFile.parentFile.canonicalFile) {
            "XYMLL did not use its executable directory as the Java working directory: ${lines[1]}"
        }
        check(lines[2] == "2" && lines[3] == "argument with spaces") {
            "XYMLL did not preserve application arguments: $lines"
        }
    }
}

val xymlLauncherExecutable = configurations.create("xymlLauncherExecutable") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts.add(xymlLauncherExecutable.name, preparedExecutable) {
    builtBy(prepareXYMLLExecutable)
    type = "exe"
}

tasks.assemble {
    dependsOn(prepareXYMLLExecutable)
}

tasks.check {
    dependsOn(verifyXYMLLArtifact)
    if (isWindowsHost) {
        dependsOn(smokeTestXYMLL)
    }
}

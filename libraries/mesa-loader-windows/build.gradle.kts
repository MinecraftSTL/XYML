import com.google.gson.JsonParser
import java.io.DataInputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile

plugins {
    `java-library`
}

val mesaVersion = "26.0.4"

group = "space.minecraftstl.xyml"
version = rootProject.extra["xymlReleaseVersion"] as String
description = "XYML's namespaced fork of Mesa Loader for Windows."

base {
    archivesName.set("mesa-loader-windows")
}

dependencies {
    compileOnly(libs.jetbrains.annotations)
    testImplementation(libs.jetbrains.annotations)
}

java {
    withSourcesJar()
    withJavadocJar()
}

val packageName = "space.minecraftstl.xyml.library.mesa"
val packagePath = packageName.replace('.', '/')

tasks.withType<JavaCompile> {
    options.release.set(8)
}

tasks.compileTestJava {
    options.release.set(17)
}

// Preserve the upstream Java 6 artifact contract after compiling against the Java 8 API surface.
tasks.compileJava {
    doLast {
        fileTree(destinationDirectory).matching { include("**/*.class") }.forEach { classFile ->
            RandomAccessFile(classFile, "rw").use { output ->
                output.seek(7)
                output.write(50)
            }
        }
    }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).also {
        it.jFlags!!.addAll(listOf("-Duser.language=en", "-Duser.country=", "-Duser.variant="))
        it.encoding("UTF-8")
        it.addStringOption("link", "https://docs.oracle.com/javase/8/docs/api/")
        it.addBooleanOption("html5", true)
        it.addStringOption("Xdoclint:none", "-quiet")
    }
}

enum class MesaArch(val classifier: String, val icdName: String, val peMachine: Int) {
    X86("x86", "i686", 0x014C),
    X64("x64", "x86_64", 0x8664),
    ARM64("arm64", "aarch64", 0xAA64),
}

enum class RenderingApi {
    OPENGL,
    VULKAN,
}

enum class MesaDriver(val api: RenderingApi, val archiveName: String, val icdName: String? = null) {
    LLVMPIPE(RenderingApi.OPENGL, "llvmpipe"),
    D3D12(RenderingApi.OPENGL, "d3d12"),
    ZINK(RenderingApi.OPENGL, "zink"),
    LAVAPIPE(RenderingApi.VULKAN, "lavapipe", "lvp"),
    DZN(RenderingApi.VULKAN, "dzn", "dzn"),
    ;

    init {
        if (api == RenderingApi.VULKAN && icdName == null) {
            error("Vulkan driver $name must have an icdName")
        }
    }
}

val upstreamMesaConfigurations = MesaArch.entries.associateWith { arch ->
    configurations.create("upstreamMesa${arch.classifier.replaceFirstChar { it.uppercase() }}") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }
}

dependencies {
    upstreamMesaConfigurations.forEach { (arch, configuration) ->
        add(
            configuration.name,
            "org.glavo:mesa-loader-windows:$mesaVersion:${arch.classifier}@jar",
        )
    }
}

val upstreamMesaFiles = upstreamMesaConfigurations.mapValues { (_, configuration) ->
    providers.provider { configuration.singleFile }
}

data class UpstreamMesaArtifact(val size: Long, val sha1: String)

val expectedUpstreamMesaArtifacts = mapOf(
    MesaArch.X86 to UpstreamMesaArtifact(41_742_113L, "ac6afaa8baa7c17468267c09e77e1296ee92d5ed"),
    MesaArch.X64 to UpstreamMesaArtifact(49_873_846L, "f8da709c59ef61f531c91434ca0e3b4f39202981"),
    MesaArch.ARM64 to UpstreamMesaArtifact(43_504_189L, "5c761a344700a07eaded51c5cf0cde36ee614706"),
)

val verifyUpstreamMesaNativeInputs = tasks.register("verifyUpstreamMesaNativeInputs") {
    group = "verification"
    description = "Verifies the locked upstream classifiers used as native payload inputs."
    inputs.files(upstreamMesaConfigurations.values)
    inputs.property("expectedArtifacts", expectedUpstreamMesaArtifacts)

    doLast {
        check(expectedUpstreamMesaArtifacts.keys == MesaArch.entries.toSet()) {
            "Upstream Mesa artifact table does not cover every architecture"
        }
        MesaArch.entries.forEach { arch ->
            val artifact = upstreamMesaFiles.getValue(arch).get()
            val expected = expectedUpstreamMesaArtifacts.getValue(arch)
            check(artifact.length() == expected.size) {
                "Unexpected size for ${artifact.name}: ${artifact.length()}"
            }
            val digest = MessageDigest.getInstance("SHA-1")
            val actualSha1 = artifact.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var count: Int
                while (input.read(buffer).also { count = it } >= 0) {
                    if (count > 0) {
                        digest.update(buffer, 0, count)
                    }
                }
                HexFormat.of().formatHex(digest.digest())
            }
            check(actualSha1 == expected.sha1) { "Unexpected SHA-1 for ${artifact.name}: $actualSha1" }
        }
    }
}

val versionFile = layout.buildDirectory.file("generated/mesa/version.properties")
val createVersionFile = tasks.register("createMesaVersionFile") {
    outputs.file(versionFile)
    inputs.property("loaderVersion", project.version.toString())
    inputs.property("mesaVersion", mesaVersion)

    doLast {
        val output = versionFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            "loader.version=${project.version}\nmesa.version=$mesaVersion\n",
            Charsets.UTF_8,
        )
    }
}

fun Jar.addMesaNatives(arch: MesaArch) {
    MesaDriver.entries.forEach { driver ->
        val sourcePrefix = "org/glavo/mesa/${arch.classifier}/${driver.archiveName}/"
        from(upstreamMesaFiles.getValue(arch).map { zipTree(it) }) {
            include("$sourcePrefix*")
            eachFile {
                path = "$packagePath/${arch.classifier}/${driver.archiveName}/$name"
            }
            includeEmptyDirs = false
        }
    }
}

fun Jar.configureMesaManifest() {
    manifest.attributes(
        "Premain-Class" to "$packageName.Loader",
        "Automatic-Module-Name" to packageName,
        "Implementation-Version" to project.version.toString(),
        "Mesa-Upstream-Version" to mesaVersion,
    )
}

tasks.withType<Jar> {
    dependsOn(createVersionFile)
    configureMesaManifest()
    into(packagePath) {
        from(versionFile)
    }
}

tasks.jar {
    dependsOn(verifyUpstreamMesaNativeInputs)
    MesaArch.entries.forEach { arch ->
        addMesaNatives(arch)
    }
}

val classifierJars = MesaArch.entries.associateWith { arch ->
    tasks.register<Jar>("jar-${arch.classifier}") {
        dependsOn(verifyUpstreamMesaNativeInputs, tasks.classes)
        archiveClassifier.set(arch.classifier)
        from(sourceSets.main.get().output)
        addMesaNatives(arch)
    }
}

tasks.assemble {
    dependsOn(classifierJars.values)
}

tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

publishing {
    publications.named<MavenPublication>("maven") {
        artifactId = "mesa-loader-windows"
        classifierJars.values.forEach { classifierJar ->
            artifact(classifierJar)
        }
        pom {
            name.set("mesa-loader-windows")
            description.set(project.description)
            url.set("https://github.com/MinecraftSTL/XYML/tree/dev/libraries/mesa-loader-windows")
            licenses {
                license {
                    name.set("Apache License 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
            developers {
                developer {
                    id.set("glavo")
                    name.set("Glavo")
                    email.set("zjx001202@gmail.com")
                }
            }
            scm {
                url.set("https://github.com/MinecraftSTL/XYML")
            }
        }
    }
}

val mesaJavaFiles = fileTree("src") {
    include("**/*.java")
}

val verifyMesaLicenseHeaders = tasks.register("verifyMesaLicenseHeaders") {
    group = "verification"
    description = "Verifies preserved Apache-2.0 headers and XYML modification notices."
    inputs.files(mesaJavaFiles)

    doLast {
        mesaJavaFiles.files.sorted().forEach { sourceFile ->
            val source = sourceFile.readText(Charsets.UTF_8)
            val preamble = source.substring(0, minOf(source.length, 1_500))
            check("Licensed under the Apache License, Version 2.0" in preamble) {
                "Missing preserved Apache-2.0 license in $sourceFile"
            }
            check("Copyright" in preamble) { "Missing preserved copyright in $sourceFile" }
            check("Modified by MinecraftSTL" in preamble || "Added by MinecraftSTL" in preamble) {
                "Missing XYML modification notice in $sourceFile"
            }
        }
    }
}

fun readUnsignedShortLittleEndian(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

fun readIntLittleEndian(bytes: ByteArray, offset: Int): Int =
    readUnsignedShortLittleEndian(bytes, offset) or (readUnsignedShortLittleEndian(bytes, offset + 2) shl 16)

fun verifyPortableExecutable(jar: JarFile, entryName: String, expectedMachine: Int) {
    val entry = jar.getJarEntry(entryName) ?: error("Missing Mesa DLL: $entryName")
    val header = jar.getInputStream(entry).use { it.readNBytes(4096) }
    check(header.size >= 64 && header[0] == 'M'.code.toByte() && header[1] == 'Z'.code.toByte()) {
        "Invalid PE header: $entryName"
    }
    val peOffset = readIntLittleEndian(header, 0x3C)
    check(peOffset >= 0 && peOffset + 6 <= header.size) { "PE header is outside sampled bytes: $entryName" }
    check(String(header, peOffset, 4, StandardCharsets.ISO_8859_1) == "PE\u0000\u0000") {
        "Missing PE signature: $entryName"
    }
    val machine = readUnsignedShortLittleEndian(header, peOffset + 4)
    check(machine == expectedMachine) {
        "Unexpected PE machine for $entryName: 0x${machine.toString(16)}"
    }
}

fun verifyMesaJar(archive: File, expectedArchitectures: Set<MesaArch>) {
    JarFile(archive).use { jar ->
        val attributes = jar.manifest.mainAttributes
        check(attributes.getValue("Premain-Class") == "$packageName.Loader") { "Unexpected Premain-Class" }
        check(attributes.getValue("Implementation-Version") == project.version.toString()) {
            "Mesa manifest does not contain the XYML release version"
        }
        check(attributes.getValue("Mesa-Upstream-Version") == mesaVersion) {
            "Mesa manifest does not record upstream $mesaVersion"
        }
        check(jar.getJarEntry("$packagePath/Loader.class") != null) { "Missing namespaced Mesa Loader" }
        check(jar.entries().asSequence().none { it.name.startsWith("org/glavo/mesa/") }) {
            "Mesa artifact retains the legacy org.glavo.mesa package"
        }

        val versionEntry = jar.getJarEntry("$packagePath/version.properties")
            ?: error("Missing Mesa version resource")
        val versionText = jar.getInputStream(versionEntry).reader(Charsets.UTF_8).use { it.readText() }
        check("loader.version=${project.version}" in versionText) { "Missing loader publication version" }
        check("mesa.version=$mesaVersion" in versionText) { "Missing upstream Mesa version" }

        val loaderEntry = jar.getJarEntry("$packagePath/Loader.class")
        DataInputStream(jar.getInputStream(loaderEntry)).use { input ->
            check(input.readInt() == 0xCAFEBABE.toInt()) { "Invalid Loader class header" }
            input.readUnsignedShort()
            val majorVersion = input.readUnsignedShort()
            check(majorVersion == 50) { "Mesa Loader must remain Java 6 bytecode, found major $majorVersion" }
        }

        MesaArch.entries.forEach { arch ->
            val prefix = "$packagePath/${arch.classifier}/"
            val containsArchitecture = jar.entries().asSequence().any { it.name.startsWith(prefix) }
            check(containsArchitecture == (arch in expectedArchitectures)) {
                "Unexpected ${arch.classifier} resource isolation in ${archive.name}"
            }
            if (arch !in expectedArchitectures) {
                return@forEach
            }

            MesaDriver.entries.forEach { driver ->
                val driverPrefix = "$prefix${driver.archiveName}/"
                when (driver.api) {
                    RenderingApi.OPENGL -> {
                        val dll = "${driverPrefix}opengl32.dll"
                        verifyPortableExecutable(jar, dll, arch.peMachine)
                    }
                    RenderingApi.VULKAN -> {
                        val jsonName = "${driverPrefix}${driver.icdName}_icd.json"
                        val jsonEntry = jar.getJarEntry(jsonName) ?: error("Missing Mesa ICD JSON: $jsonName")
                        val json = jar.getInputStream(jsonEntry).reader(Charsets.UTF_8).use { it.readText() }
                        check(""".\\vulkan_${driver.icdName}.dll""" in json) {
                            "Mesa ICD JSON does not use a local Windows DLL path: $jsonName"
                        }
                        verifyPortableExecutable(
                            jar,
                            "${driverPrefix}vulkan_${driver.icdName}.dll",
                            arch.peMachine,
                        )
                    }
                }
            }
        }
    }
}

val verifyMesaArtifacts = tasks.register("verifyMesaArtifacts") {
    group = "verification"
    description = "Verifies manifests, resources, PE architectures, and classifier isolation."
    dependsOn(tasks.jar, classifierJars.values)
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    inputs.files(classifierJars.values.map { it.flatMap { jar -> jar.archiveFile } })

    doLast {
        verifyMesaJar(tasks.jar.get().archiveFile.get().asFile, MesaArch.entries.toSet())
        classifierJars.forEach { (arch, jarTask) ->
            verifyMesaJar(jarTask.get().archiveFile.get().asFile, setOf(arch))
        }
    }
}

val verifyUpstreamMesaRuntimeCoordinates = tasks.register("verifyUpstreamMesaRuntimeCoordinates") {
    group = "verification"
    description = "Guards the deliberate exception that XYML runtime metadata keeps upstream Mesa coordinates."
    val nativesFile = rootProject.layout.projectDirectory.file("XYML/src/main/resources/assets/natives.json")
    val launcherFile = rootProject.layout.projectDirectory.file(
        "XYMLCore/src/main/java/space/minecraftstl/xyml/launch/DefaultLauncher.java",
    )
    inputs.files(nativesFile, launcherFile)

    doLast {
        val natives = nativesFile.asFile.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
        mapOf(
            "windows-x86_64" to "x64",
            "windows-x86" to "x86",
            "windows-arm64" to "arm64",
        ).forEach { (platform, classifier) ->
            val artifact = natives.getAsJsonObject(platform)
                .getAsJsonObject("mesa-loader")
            val expectedCoordinate = "org.glavo:mesa-loader-windows:$mesaVersion:$classifier"
            check(artifact.get("name").asString == expectedCoordinate) {
                "XYML Mesa runtime coordinate changed for $platform"
            }
            val download = artifact.getAsJsonObject("downloads").getAsJsonObject("artifact")
            val expectedPath = "org/glavo/mesa-loader-windows/$mesaVersion/" +
                "mesa-loader-windows-$mesaVersion-$classifier.jar"
            check(download.get("path").asString == expectedPath) { "Unexpected Mesa runtime path for $platform" }
            check(download.get("url").asString.endsWith(expectedPath)) { "Unexpected Mesa runtime URL for $platform" }
        }

        val launcherSource = launcherFile.asFile.readText(Charsets.UTF_8)
        check("org.glavo.mesa.loader.nativeDir" in launcherSource) {
            "XYML must retain the upstream Mesa native-directory system property"
        }
    }
}

tasks.named("checkstyle") {
    dependsOn(verifyMesaLicenseHeaders)
}

tasks.check {
    dependsOn(verifyMesaArtifacts, verifyMesaLicenseHeaders, verifyUpstreamMesaRuntimeCoordinates)
}

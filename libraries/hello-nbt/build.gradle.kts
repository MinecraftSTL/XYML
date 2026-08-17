import space.minecraftstl.xyml.gradle.resource.UncompressResources
import java.io.DataInputStream
import java.lang.management.ManagementFactory
import java.lang.module.ModuleFinder
import java.util.jar.JarFile
import kotlin.math.max

plugins {
    `java-library`
    jacoco
}

group = "space.minecraftstl.xyml"
version = rootProject.extra["xymlReleaseVersion"] as String
description = "XYML's namespaced fork of HelloNBT."

base {
    archivesName.set("hello-nbt")
}

dependencies {
    compileOnlyApi(libs.jetbrains.annotations)
    compileOnly(libs.lz4)

    testImplementation(libs.opennbt)
    testImplementation(libs.lz4)
    testImplementation(libs.xz)
    testImplementation(libs.commons.io)
    testImplementation(libs.jimfs)
}

java {
    withSourcesJar()
    withJavadocJar()
}

val mainClassName = "space.minecraftstl.xyml.library.nbt.internal.cli.Main"

tasks.jar {
    manifest.attributes(
        "Main-Class" to mainClassName,
        "HelloNBT-Version" to project.version.toString(),
        "HelloNBT-Upstream-Version" to "0.4.0",
        "Implementation-Version" to project.version.toString(),
    )
}

tasks.withType<JavaCompile> {
    options.release.set(17)
    options.javaModuleMainClass.set(mainClassName)
    options.javaModuleVersion.set(project.version.toString())
}

// Checkstyle 10.24 cannot parse this qualified module descriptor; javac and artifact verification cover it.
tasks.withType<Checkstyle> {
    exclude("module-info.java")
}

val runNbtCli = tasks.register<JavaExec>("runNbtCli") {
    description = "Runs the HelloNBT command-line reader without colliding with the root XYML run task."
    dependsOn(tasks.jar)
    mainModule = "space.minecraftstl.xyml.library.nbt"
    classpath(tasks.jar.map { it.archiveFile })
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).also {
        it.jFlags!!.addAll(listOf("-Duser.language=en", "-Duser.country=", "-Duser.variant="))
        it.encoding("UTF-8")
        it.addStringOption("link", "https://docs.oracle.com/en/java/javase/17/docs/api/")
        it.addBooleanOption("html5", true)
        it.addStringOption("Xdoclint:none", "-quiet")
        it.tags!!.addAll(
            listOf(
                "apiNote:a:API Note:",
                "implNote:a:Implementation Note:",
                "implSpec:a:Implementation Specification:",
            )
        )
    }
}

tasks.test {
    dependsOn(tasks.jar)
    testLogging.showStandardStreams = true
    doFirst {
        systemProperty("xyml.helloNbt.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
    }
    if ((ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize
        >= 14L * 1024L * 1024L * 1024L
    ) {
        maxParallelForks = max(1, Runtime.getRuntime().availableProcessors() / 2)
    }
}

val uncompressResources = tasks.register<UncompressResources>("uncompressResources") {
    inputDirectory.set(layout.projectDirectory.dir("src/test/resources-compressed"))
    outputDirectory.set(layout.buildDirectory.dir("generated/test/resources-uncompressed"))
}

tasks.processTestResources {
    dependsOn(uncompressResources)
    from(uncompressResources.map { it.outputDirectory })
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        csv.required.set(true)
        html.required.set(true)
    }
}

tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

publishing {
    publications.named<MavenPublication>("maven") {
        artifactId = "hello-nbt"
        pom {
            name.set("hello-nbt")
            description.set(project.description)
            url.set("https://github.com/MinecraftSTL/XYML/tree/dev/libraries/hello-nbt")
            licenses {
                license {
                    name.set("Apache License 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
                license {
                    name.set("GNU General Public License v3.0 or later (file-level upstream exceptions)")
                    url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                }
            }
            scm {
                url.set("https://github.com/MinecraftSTL/XYML")
            }
        }
    }
}

val helloNbtJavaFiles = fileTree("src") {
    include("**/*.java")
}

val verifyHelloNbtLicenseHeaders = tasks.register("verifyHelloNbtLicenseHeaders") {
    group = "verification"
    description = "Verifies preserved upstream license headers and XYML modification notices."
    inputs.files(helloNbtJavaFiles)

    doLast {
        helloNbtJavaFiles.files.sorted().forEach { sourceFile ->
            val source = sourceFile.readText(Charsets.UTF_8)
            val preamble = source.substring(0, minOf(source.length, 1_500))
            val hasKnownLicense = "Licensed under the Apache License, Version 2.0" in preamble
                || "GNU General Public License as published by" in preamble
            check(hasKnownLicense) { "Missing preserved upstream license in $sourceFile" }
            check("Copyright" in preamble) { "Missing preserved copyright in $sourceFile" }
            check("Modified by MinecraftSTL" in preamble || "Added by MinecraftSTL" in preamble) {
                "Missing XYML modification notice in $sourceFile"
            }
        }
    }
}

val verifyHelloNbtArtifact = tasks.register("verifyHelloNbtArtifact") {
    group = "verification"
    description = "Verifies HelloNBT module identity, packages, manifest metadata, and Java 17 bytecode."
    dependsOn(tasks.jar)
    val archiveFile = tasks.jar.flatMap { it.archiveFile }
    inputs.file(archiveFile)

    doLast {
        val jarFile = archiveFile.get().asFile
        val moduleDescriptor = ModuleFinder.of(jarFile.toPath()).findAll().single().descriptor()
        check(moduleDescriptor.name() == "space.minecraftstl.xyml.library.nbt") {
            "Unexpected HelloNBT module name: ${moduleDescriptor.name()}"
        }

        JarFile(jarFile).use { jar ->
            val requiredEntries = listOf(
                "space/minecraftstl/xyml/library/nbt/NBTElement.class",
                "space/minecraftstl/xyml/library/nbt/io/NBTCodec.class",
                "space/minecraftstl/xyml/library/nbt/tag/CompoundTag.class",
            )
            requiredEntries.forEach { entry ->
                check(jar.getJarEntry(entry) != null) { "Missing HelloNBT entry: $entry" }
            }
            check(jar.entries().asSequence().none { it.name.startsWith("org/glavo/nbt/") }) {
                "HelloNBT artifact retains the legacy org.glavo.nbt package"
            }
            check(jar.manifest.mainAttributes.getValue("HelloNBT-Upstream-Version") == "0.4.0") {
                "HelloNBT manifest does not record upstream 0.4.0"
            }

            val classEntry = jar.getJarEntry(requiredEntries.first())
            DataInputStream(jar.getInputStream(classEntry)).use { input ->
                check(input.readInt() == 0xCAFEBABE.toInt()) { "Invalid class file header" }
                input.readUnsignedShort()
                val majorVersion = input.readUnsignedShort()
                check(majorVersion == 61) { "HelloNBT must remain Java 17 bytecode, found major $majorVersion" }
            }
        }
    }
}

tasks.named("checkstyle") {
    dependsOn(verifyHelloNbtLicenseHeaders)
}

tasks.check {
    dependsOn(verifyHelloNbtArtifact, verifyHelloNbtLicenseHeaders)
}

import java.io.DataInputStream
import java.lang.module.ModuleFinder
import java.util.jar.JarFile

plugins {
    `java-library`
    jacoco
}

group = "space.minecraftstl.xyml"
version = rootProject.extra["xymlReleaseVersion"] as String
description = "XoyzMCP is a compact Java MCP server library with Streamable HTTP transport."

base {
    archivesName.set("xoyz-mcp")
}

dependencies {
    api(libs.nanohttpd)
    api(libs.gson)
    compileOnlyApi(libs.jetbrains.annotations)
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.jar {
    manifest.attributes(
        "XoyzMCP-Version" to project.version.toString(),
        "Implementation-Version" to project.version.toString(),
    )
}

tasks.withType<JavaCompile> {
    options.release.set(17)
    options.javaModuleVersion.set(project.version.toString())
}

// NanoHTTPD has no module descriptor or Automatic-Module-Name, so javac must derive its module name on an explicit
// module path. Gradle otherwise leaves that dependency on the classpath and the descriptor cannot require it.
tasks.compileJava {
    classpath = files()
    options.compilerArgs.addAll(listOf("--module-path", configurations.compileClasspath.get().asPath))
}

// Checkstyle 10.24 cannot parse module descriptors; javac and artifact verification cover this file.
tasks.withType<Checkstyle> {
    exclude("module-info.java")
}

tasks.javadoc {
    modularity.inferModulePath.set(false)
    classpath = files()
    (options as StandardJavadocDocletOptions).also {
        it.jFlags!!.addAll(listOf("-Duser.language=en", "-Duser.country=", "-Duser.variant="))
        it.encoding("UTF-8")
        it.modulePath(configurations.compileClasspath.get().files.toList())
        it.addStringOption("link", "https://docs.oracle.com/en/java/javase/17/docs/api/")
        it.addBooleanOption("html5", true)
        it.addStringOption("Xdoclint:none", "-quiet")
    }
}

tasks.test {
    dependsOn(tasks.jar, "generatePomFileForMavenPublication")
    doFirst {
        systemProperty("xyml.xoyzMcp.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
        systemProperty(
            "xyml.xoyzMcp.pom",
            layout.buildDirectory.file("publications/maven/pom-default.xml").get().asFile.absolutePath
        )
    }
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
        artifactId = "xoyz-mcp"
        pom {
            name.set("XoyzMCP")
            description.set(project.description)
            url.set("https://github.com/MinecraftSTL/XYML/tree/dev/libraries/xoyz-mcp")
            licenses {
                license {
                    name.set("GNU General Public License v3.0 or later")
                    url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                }
            }
            scm {
                url.set("https://github.com/MinecraftSTL/XYML")
            }
        }
    }
}

val expectedLicenseHeader = """
    /*
     * Hello Minecraft! Launcher
     * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
     *
     * This program is free software: you can redistribute it and/or modify
     * it under the terms of the GNU General Public License as published by
     * the Free Software Foundation, either version 3 of the License, or
     * (at your option) any later version.
     *
     * This program is distributed in the hope that it will be useful,
     * but WITHOUT ANY WARRANTY; without even the implied warranty of
     * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
     * GNU General Public License for more details.
     *
     * You should have received a copy of the GNU General Public License
     * along with this program.  If not, see <https://www.gnu.org/licenses/>.
     */
""".trimIndent()

val xoyzMcpJavaFiles = fileTree("src") {
    include("**/*.java")
}

val verifyXoyzMcpLicenseHeaders = tasks.register("verifyXoyzMcpLicenseHeaders") {
    group = "verification"
    description = "Verifies the complete GPLv3-or-later header on every XoyzMCP Java source."
    inputs.files(xoyzMcpJavaFiles)

    doLast {
        xoyzMcpJavaFiles.files.sorted().forEach { sourceFile ->
            check(sourceFile.readText(Charsets.UTF_8).startsWith(expectedLicenseHeader)) {
                "Missing complete GPLv3-or-later license header in $sourceFile"
            }
        }
    }
}

val verifyXoyzMcpArtifact = tasks.register("verifyXoyzMcpArtifact") {
    group = "verification"
    description = "Verifies XoyzMCP module identity, packages, manifest metadata, and Java 17 bytecode."
    dependsOn(tasks.jar)
    val archiveFile = tasks.jar.flatMap { it.archiveFile }
    inputs.file(archiveFile)

    doLast {
        val jarFile = archiveFile.get().asFile
        check(jarFile.name == "xoyz-mcp-${project.version}.jar") {
            "Unexpected XoyzMCP artifact name: ${jarFile.name}"
        }
        val moduleDescriptor = ModuleFinder.of(jarFile.toPath()).findAll().single().descriptor()
        check(moduleDescriptor.name() == "space.minecraftstl.xyml.library.mcp") {
            "Unexpected XoyzMCP module name: ${moduleDescriptor.name()}"
        }

        JarFile(jarFile).use { jar ->
            val requiredEntries = listOf(
                "space/minecraftstl/xyml/library/mcp/McpServer.class",
                "space/minecraftstl/xyml/library/mcp/McpToolProvider.class",
                "space/minecraftstl/xyml/library/mcp/McpResourceProvider.class",
                "space/minecraftstl/xyml/library/mcp/McpPromptProvider.class",
            )
            requiredEntries.forEach { entry ->
                check(jar.getJarEntry(entry) != null) { "Missing XoyzMCP entry: $entry" }
            }
            check(jar.entries().asSequence().none { it.name.startsWith("space/minecraftstl/xyml/mcp/") }) {
                "XoyzMCP artifact contains launcher-specific MCP classes"
            }
            val manifestAttributes = jar.manifest.mainAttributes
            check(manifestAttributes.getValue("XoyzMCP-Version") == project.version.toString()) {
                "XoyzMCP manifest does not record project version ${project.version}"
            }

            val classEntry = jar.getJarEntry(requiredEntries.first())
            DataInputStream(jar.getInputStream(classEntry)).use { input ->
                check(input.readInt() == 0xCAFEBABE.toInt()) { "Invalid class file header" }
                input.readUnsignedShort()
                val majorVersion = input.readUnsignedShort()
                check(majorVersion == 61) { "XoyzMCP must remain Java 17 bytecode, found major $majorVersion" }
            }
        }
    }
}

tasks.named("checkstyle") {
    dependsOn(verifyXoyzMcpLicenseHeaders)
}

tasks.check {
    dependsOn(verifyXoyzMcpArtifact, verifyXoyzMcpLicenseHeaders)
}

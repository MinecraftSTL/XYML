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
package space.minecraftstl.xyml.library.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the public identity of the XoyzMCP artifact.
@NotNullByDefault
public final class LibraryIdentityTest {
    /// Verifies the module name and exported public package.
    @Test
    void moduleAndPublicPackageUseXoyzMcpIdentity() {
        Path artifact = resolveArtifact(
                "xyml.xoyzMcp.jar", "xoyz-mcp", "space.minecraftstl.xyml.library.mcp");
        ModuleDescriptor descriptor = ModuleFinder.of(artifact).findAll().iterator().next().descriptor();

        assertEquals("space.minecraftstl.xyml.library.mcp", descriptor.name());
        assertTrue(descriptor.exports().stream().anyMatch(export ->
                export.source().equals("space.minecraftstl.xyml.library.mcp")));
        assertEquals("space.minecraftstl.xyml.library.mcp", McpServer.class.getPackageName());
    }

    /// Verifies the published JAR records the XoyzMCP implementation version.
    ///
    /// @throws IOException when the built artifact cannot be read
    @Test
    void manifestUsesXoyzMcpIdentity() throws IOException {
        Path artifact = resolveArtifact(
                "xyml.xoyzMcp.jar", "xoyz-mcp", "space.minecraftstl.xyml.library.mcp");
        ModuleDescriptor descriptor = ModuleFinder.of(artifact).findAll().iterator().next().descriptor();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            assertEquals(descriptor.rawVersion().orElseThrow(),
                    jar.getManifest().getMainAttributes().getValue("XoyzMCP-Version"));
        }
    }

    /// Verifies dependencies required by the module descriptor are available to compiling Maven consumers.
    ///
    /// @throws Exception when the generated publication metadata cannot be parsed
    @Test
    void pomPublishesRequiredModulesForConsumerCompilation() throws Exception {
        Path pom = resolveBuildFile("xyml.xoyzMcp.pom", "build/publications/maven/pom-default.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(pom.toFile());

        assertEquals("compile", dependencyScope(document, "org.nanohttpd", "nanohttpd"));
        assertEquals("compile", dependencyScope(document, "com.google.code.gson", "gson"));
    }

    /// Resolves a Gradle-injected artifact path or the artifact matching the compiled module
    /// for IntelliJ's JUnit runner.
    ///
    /// @param propertyName Gradle system property containing the artifact path
    /// @param artifactName archive base name
    /// @param moduleName compiled module name
    /// @return executable library artifact
    /// @throws IllegalStateException when no local artifact can be found
    private static Path resolveArtifact(String propertyName, String artifactName, String moduleName) {
        @Nullable String configuredPath = System.getProperty(propertyName);
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }

        Path libraryDirectory = moduleDirectory();
        Path mainClasses = libraryDirectory.resolve("build/classes/java/main");
        if (!Files.isDirectory(mainClasses)) {
            throw new IllegalStateException("Compiled module classes were not found: " + mainClasses);
        }

        ModuleDescriptor descriptor = ModuleFinder.of(mainClasses).find(moduleName)
                .orElseThrow(() -> new IllegalStateException(
                        "Compiled module descriptor was not found: " + moduleName))
                .descriptor();
        String version = descriptor.rawVersion()
                .orElseThrow(() -> new IllegalStateException(
                        "Compiled module has no version: " + moduleName));
        Path artifact = libraryDirectory.resolve("build/libs").resolve(artifactName + "-" + version + ".jar");
        if (!Files.isRegularFile(artifact)) {
            throw new IllegalStateException("Artifact for compiled version was not found: " + artifact);
        }
        return artifact;
    }

    /// Resolves a Gradle-injected path or a file relative to this library module.
    ///
    /// @param propertyName Gradle system property containing the file path
    /// @param relativePath file path under the module directory
    /// @return resolved file
    /// @throws IllegalStateException when the local file does not exist
    private static Path resolveBuildFile(String propertyName, String relativePath) {
        @Nullable String configuredPath = System.getProperty(propertyName);
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }

        Path file = moduleDirectory().resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Required build file was not found: " + file);
        }
        return file;
    }

    /// Locates the module containing the compiled test class.
    ///
    /// @return module directory
    /// @throws IllegalStateException when the test output location cannot be mapped to a module
    private static Path moduleDirectory() {
        try {
            @Nullable CodeSource codeSource = LibraryIdentityTest.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                @Nullable URL location = codeSource.getLocation();
                if (location != null) {
                    Path outputLocation = Path.of(location.toURI()).toAbsolutePath().normalize();
                    for (@Nullable Path current = outputLocation;
                         current != null;
                         current = current.getParent()) {
                        @Nullable Path fileName = current.getFileName();
                        if (fileName != null && "build".equals(fileName.toString()) && Files.isDirectory(current)) {
                            @Nullable Path moduleDirectory = current.getParent();
                            if (moduleDirectory != null) {
                                return moduleDirectory;
                            }
                        }
                    }
                }
            }
        } catch (URISyntaxException
                 | FileSystemNotFoundException
                 | IllegalArgumentException
                 | SecurityException exception) {
            throw new IllegalStateException("Unable to locate test output directory", exception);
        }

        Path workingDirectory = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path modulePath = workingDirectory.resolve("libraries/xoyz-mcp");
        if (Files.isDirectory(modulePath.resolve("build"))) {
            return modulePath;
        }
        if (Files.isDirectory(workingDirectory.resolve("build"))) {
            return workingDirectory;
        }
        throw new IllegalStateException("Unable to locate xoyz-mcp module directory from the test classpath");
    }

    /// Reads one dependency scope from Maven publication metadata.
    ///
    /// @param document parsed Maven POM
    /// @param groupId dependency group identifier
    /// @param artifactId dependency artifact identifier
    /// @return published dependency scope, or an empty string when the dependency is absent
    /// @throws XPathExpressionException when the fixed XPath cannot be evaluated
    private static String dependencyScope(Document document, String groupId, String artifactId)
            throws XPathExpressionException {
        String expression = "/*[local-name()='project']/*[local-name()='dependencies']"
                + "/*[local-name()='dependency'][*[local-name()='groupId']='" + groupId + "']"
                + "[*[local-name()='artifactId']='" + artifactId + "']/*[local-name()='scope']";
        return XPathFactory.newInstance().newXPath().evaluate(expression, document);
    }
}

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
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the public identity of the XoyzMCP artifact.
@NotNullByDefault
public final class LibraryIdentityTest {
    /// Verifies the module name and exported public package.
    @Test
    void moduleAndPublicPackageUseXoyzMcpIdentity() {
        Path artifact = Path.of(System.getProperty("xyml.xoyzMcp.jar"));
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
        Path artifact = Path.of(System.getProperty("xyml.xoyzMcp.jar"));
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
        Path pom = Path.of(System.getProperty("xyml.xoyzMcp.pom"));
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(pom.toFile());

        assertEquals("compile", dependencyScope(document, "org.nanohttpd", "nanohttpd"));
        assertEquals("compile", dependencyScope(document, "com.google.code.gson", "gson"));
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

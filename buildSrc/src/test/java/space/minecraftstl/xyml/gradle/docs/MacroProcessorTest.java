/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.gradle.docs;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies fragment preservation and locale selection in generated Markdown links.
@NotNullByDefault
final class MacroProcessorTest {
    /// Isolated root for documentation and Gradle project fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// Creates translated files that would otherwise attract automatic link rewriting.
    ///
    /// @throws IOException when fixture documents cannot be written
    @BeforeEach
    void createDocuments() throws IOException {
        writeDocument("README.md", "## 开源协议\n");
        writeDocument("README_en.md", "## License\n");
        writeDocument("README_ja.md", "## ライセンス\n");
        writeDocument("README_zh_Hant.md", "## 開源協議\n");
        writeDocument("README_lzh.md", "## 開源之約\n");
        writeDocument("Guide.md", "## 指南\n");
        writeDocument("Guide_en.md", "## Guide\n");
        writeDocument("Guide_ja.md", "## ガイド\n");
    }

    /// Keeps an explicitly selected target whenever its URL contains a fragment delimiter.
    ///
    /// @param suffix query and fragment suffix appended to the English README path
    /// @throws IOException when the fixture document tree cannot be loaded
    @ParameterizedTest
    @ValueSource(strings = {
            "#license", "#开源协议", "#%E5%BC%80%E6%BA%90", "#", "?view=full#license", "?#", "?q=$1#license"
    })
    void preservesFragments(String suffix) throws IOException {
        String link = "[license](README_en.md" + suffix + ")";
        assertEquals(link + "\n", process("README_ja.md", link));
    }

    /// Preserves a Traditional Chinese license target referenced from Literary Chinese.
    ///
    /// @throws IOException when the fixture document tree cannot be loaded
    @Test
    void preservesLiteraryChineseLicenseTarget() throws IOException {
        String link = "[許可](README_zh_Hant.md#開源協議)";
        assertEquals(link + "\n", process("README_lzh.md", link));
    }

    /// Retains locale selection and query text for whole-document links.
    ///
    /// @throws IOException when the fixture document tree cannot be loaded
    @Test
    void localizesWholeDocumentsAndPreservesQueries() throws IOException {
        assertEquals("[guide](Guide_ja.md)\n", process("README_ja.md", "[guide](Guide_en.md)"));
        assertEquals("[guide](Guide_ja.md?view=full&value=$1)\n",
                process("README_ja.md", "[guide](Guide_en.md?view=full&value=$1)"));
        assertEquals("[guide](Guide_ja.md?q=$1\\literal)\n",
                process("README_ja.md", "[guide](Guide_en.md?q=$1\\literal)"));
        assertEquals("[guide](Guide_ja.md?q=%23license)\n",
                process("README_ja.md", "[guide](Guide_en.md?q=%23license)"));
        assertEquals("[guide](Guide_ja.md?)\n", process("README_ja.md", "[guide](Guide_en.md?)"));
    }

    /// Falls back to English and then Simplified Chinese when the current locale is missing.
    ///
    /// @throws IOException when fixture documents cannot be written or loaded
    @Test
    void retainsLocaleFallbackOrder() throws IOException {
        writeDocument("Fallback.md", "default\n");
        writeDocument("Fallback_en.md", "English\n");
        writeDocument("OnlyDefault.md", "default\n");
        assertEquals("[guide](Fallback_en.md?view=full)\n",
                process("README_ja.md", "[guide](Fallback.md?view=full)"));
        assertEquals("[guide](OnlyDefault.md)\n", process("README_ja.md", "[guide](OnlyDefault.md)"));
        assertEquals("[guide](Guide.md)\n", process("README.md", "[guide](Guide_en.md)"));
    }

    /// Handles each link independently without skipping an entire line that contains a fragment.
    ///
    /// @throws IOException when the fixture document tree cannot be loaded
    @Test
    void processesMixedLinksIndependently() throws IOException {
        assertEquals("[license](README_en.md#license) [guide](Guide_ja.md) [query](Guide_ja.md?q=1)\n",
                process("README_ja.md",
                        "[license](README_en.md#license) [guide](Guide_en.md) [query](Guide_en.md?q=1)"));
    }

    /// Resolves relative directory components without changing fragment targets.
    ///
    /// @throws IOException when fixture documents cannot be written or loaded
    @Test
    void handlesRelativeDirectories() throws IOException {
        writeDocument("sub/Guide_en.md", "English\n");
        writeDocument("sub/Guide_ja.md", "Japanese\n");
        assertEquals("[guide](./sub/Guide_ja.md) [section](./sub/Guide_en.md#guide)\n",
                process("README_ja.md", "[guide](./sub/Guide_en.md) [section](./sub/Guide_en.md#guide)"));
        assertEquals("[guide](sub/../Guide_ja.md) [section](sub/../Guide_en.md#guide)\n",
                process("README_ja.md", "[guide](sub/../Guide_en.md) [section](sub/../Guide_en.md#guide)"));
    }

    /// Leaves external URLs, same-page fragments, and unresolved paths untouched.
    ///
    /// @param link target that must not be localized
    /// @throws IOException when the fixture document tree cannot be loaded
    @ParameterizedTest
    @ValueSource(strings = {
            "[external](https://example.invalid/Guide_en.md)",
            "[external](https://example.invalid/Guide_en.md#guide)",
            "[external](//example.invalid/Guide_en.md)",
            "[section](#license)",
            "[missing](Missing_en.md)",
            "[missing](missing/Guide_en.md)",
            "[outside](../Guide_en.md)",
            "[absolute](/Guide_en.md)"
    })
    void preservesOtherTargets(String link) throws IOException {
        assertEquals(link + "\n", process("README_ja.md", link));
    }

    /// Exercises the actual Gradle task across plain text, both macros, and repeated generation.
    ///
    /// @throws IOException when fixture documents cannot be written or generated
    @Test
    void generatesDocumentsIdempotentlyWithSharedLinkRules() throws IOException {
        String links = "[license](README_en.md#license) [guide](Guide_en.md)";
        String localizedLinks = "[license](README_en.md#license) [guide](Guide_ja.md)";
        writeDocument("README_en.md", """
                ## License
                <!-- #BEGIN BLOCK -->
                <!-- #PROPERTY NAME=LINKS -->
                %s
                <!-- #END BLOCK -->
                """.formatted(links));
        writeDocument("README_ja.md", """
                %s
                <!-- #BEGIN BLOCK -->
                <!-- #PROPERTY NAME=LOCAL -->
                %s
                <!-- #END BLOCK -->
                <!-- #BEGIN COPY -->
                <!-- #PROPERTY NAME=LINKS -->
                stale content
                <!-- #END COPY -->
                <!-- #BEGIN BLOCK -->
                <!-- #PROPERTY PROCESS_LINK=FALSE -->
                %s
                <!-- #END BLOCK -->
                <!-- #BEGIN COPY -->
                <!-- #PROPERTY NAME=LINKS -->
                <!-- #PROPERTY PROCESS_LINK=FALSE -->
                stale content
                <!-- #END COPY -->
                """.formatted(links, links, links));
        Path projectDirectory = Files.createDirectories(temporaryDirectory.resolve("project"));
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build();
        UpdateDocuments task = project.getTasks().create("updateDocuments", UpdateDocuments.class);
        task.getDocumentsDir().set(temporaryDirectory.resolve("docs").toFile());

        task.run();

        String generated = Files.readString(temporaryDirectory.resolve("docs/README_ja.md"));
        assertEquals("""
                %s
                <!-- #BEGIN BLOCK -->
                <!-- #PROPERTY NAME=LOCAL -->
                %s
                <!-- #END BLOCK -->
                <!-- #BEGIN COPY -->
                <!-- #PROPERTY NAME=LINKS -->
                %s
                <!-- #END COPY -->
                <!-- #BEGIN BLOCK -->
                <!-- #PROPERTY PROCESS_LINK=FALSE -->
                %s
                <!-- #END BLOCK -->
                <!-- #BEGIN COPY -->
                <!-- #PROPERTY NAME=LINKS -->
                <!-- #PROPERTY PROCESS_LINK=FALSE -->
                %s
                <!-- #END COPY -->
                """.formatted(localizedLinks, localizedLinks, localizedLinks, links, links), generated);
        @Unmodifiable Map<Path, String> firstOutput = snapshotDocuments();
        task.run();
        assertEquals(firstOutput, snapshotDocuments());
    }

    /// Writes one UTF-8 document beneath the isolated documentation directory.
    ///
    /// @param name relative document path
    /// @param content complete Markdown content
    /// @throws IOException when directories or the document cannot be written
    private void writeDocument(String name, String content) throws IOException {
        Path file = temporaryDirectory.resolve("docs").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /// Processes a single line in the language context of an existing fixture README.
    ///
    /// @param sourceName fixture README file name
    /// @param line Markdown line to process
    /// @return the processed line including its trailing newline
    /// @throws IOException when the document tree cannot be loaded
    private String process(String sourceName, String line) throws IOException {
        DocumentFileTree tree = DocumentFileTree.load(temporaryDirectory.resolve("docs"));
        DocumentLocale.LocaleAndName source = DocumentLocale.parseFileName(
                sourceName.substring(0, sourceName.length() - ".md".length()));
        LocalizedDocument localized = Objects.requireNonNull(tree.getFiles().get(source.name()));
        Document document = Objects.requireNonNull(localized.getDocuments().get(source.locale()));
        StringBuilder output = new StringBuilder();
        MacroProcessor.processLine(output, line, document);
        return output.toString();
    }

    /// Captures immutable document contents for an idempotence comparison.
    ///
    /// @return an immutable snapshot keyed by document path
    /// @throws IOException when fixture documents cannot be read
    private @Unmodifiable Map<Path, String> snapshotDocuments() throws IOException {
        Map<Path, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(temporaryDirectory.resolve("docs"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                assertTrue(path.toString().endsWith(".md"));
                result.put(path, Files.readString(path));
            }
        }
        return Map.copyOf(result);
    }
}

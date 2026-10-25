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
package space.minecraftstl.xyml.gradle.mod;

import com.google.gson.Gson;
import org.gradle.testfixtures.ProjectBuilder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies grapheme filtering through the existing six-field resource generator without network input.
@NotNullByDefault
final class ParseModDataTaskTest {
    /// Isolated input, output, and Gradle project directory.
    @TempDir
    private Path temporaryDirectory;

    /// Complete emoji sequences disappear without removing ordinary numbers, punctuation, or combining marks.
    ///
    /// @throws IOException when fixture files cannot be read or written
    @Test
    void filtersCompleteEmojiAndPreservesText() throws IOException {
        String output = generate(List.of("20W14✨", "钓🐟🐠", "家👩‍👩‍👧‍👦庭🇨🇳1️⃣👍🏽", "123 # * © e\u0301"));
        @Unmodifiable List<String> rows = output.lines().filter(line -> !line.startsWith("#")).toList();
        assertEquals(4, rows.size());
        assertEquals("20W14", rows.get(0).split(";", -1)[3]);
        assertEquals("钓", rows.get(1).split(";", -1)[3]);
        assertEquals("家庭", rows.get(2).split(";", -1)[3]);
        assertEquals("123 # * © e\u0301", rows.get(3).split(";", -1)[3]);
        assertTrue(rows.stream().allMatch(row -> row.split(";", -1).length == 6));
    }

    /// Tabs and repeated spaces normalize while existing full-width bracket handling remains intact.
    ///
    /// @throws IOException when fixture files cannot be read or written
    @Test
    void normalizesWhitespaceAndBrackets() throws IOException {
        String output = generate(List.of("  名称  （扩展）\t 123  ", "名称（扩展）", "", "👩🏽‍💻"));
        @Unmodifiable List<String> rows = output.lines().filter(line -> !line.startsWith("#")).toList();
        assertEquals("名称 (扩展) 123", rows.get(0).split(";", -1)[3]);
        assertEquals("名称 (扩展)", rows.get(1).split(";", -1)[3]);
        assertEquals("", rows.get(2).split(";", -1)[3]);
        assertEquals("", rows.get(3).split(";", -1)[3]);
    }

    /// Identical fixtures produce identical resources on repeated generation.
    ///
    /// @throws IOException when fixture files cannot be read or written
    @Test
    void generationIsRepeatable() throws IOException {
        List<String> names = List.of("普通模组", "emoji✨ 名称", "数字123");
        assertEquals(generate(names), generate(names));
    }

    /// A name containing the resource delimiter retains the established skip behavior.
    ///
    /// @throws IOException when fixture files cannot be read or written
    @Test
    void rejectsUnrepresentableNames() throws IOException {
        String output = generate(List.of("非法;名称", "合法名称"));
        assertEquals(1, output.lines().filter(line -> !line.startsWith("#")).count());
        assertTrue(output.contains("合法名称"));
        assertFalse(output.contains("非法"));
    }

    /// An input read failure does not retain file handles or prevent a corrected rerun.
    ///
    /// @throws IOException when fixture files cannot be read or written
    @Test
    void recoversAfterMissingInput() throws IOException {
        ParseModDataTask task = task();
        assertThrows(IOException.class, task::run);
        Files.writeString(temporaryDirectory.resolve("input.json"), "[]");
        task.run();
        assertTrue(Files.readString(temporaryDirectory.resolve("output.txt")).startsWith("#"));
    }

    /// Generates a resource from synthetic mcmod entries with stable identifiers.
    ///
    /// @param names source Chinese display names
    /// @return generated six-field resource, including its license header
    /// @throws IOException when fixture files cannot be read or written
    private String generate(List<String> names) throws IOException {
        List<Map<String, Object>> records = new ArrayList<>();
        for (int index = 0; index < names.size(); index++) {
            records.add(Map.of(
                    "name", Map.of("main", names.get(index), "sub", "Example", "abbr", "E"),
                    "modid", List.of("example" + index),
                    "links", Map.of("list", Map.of("mcmod", List.of(Map.of(
                            "url", "https://www.mcmod.cn/class/" + (100 + index) + ".html"))))));
        }
        Files.writeString(temporaryDirectory.resolve("input.json"), new Gson().toJson(records));
        task().run();
        return Files.readString(temporaryDirectory.resolve("output.txt"));
    }

    /// Creates a task whose files are isolated to this test's temporary directory.
    ///
    /// @return configured task without executing Gradle or downloading data
    private ParseModDataTask task() {
        ParseModDataTask task = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build()
                .getTasks().create("parseFixture", ParseModDataTask.class);
        task.getInputFile().set(temporaryDirectory.resolve("input.json").toFile());
        task.getOutputFile().set(temporaryDirectory.resolve("output.txt").toFile());
        return task;
    }
}

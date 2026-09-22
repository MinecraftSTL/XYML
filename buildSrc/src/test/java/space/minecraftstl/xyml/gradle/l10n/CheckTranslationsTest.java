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
package space.minecraftstl.xyml.gradle.l10n;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies strict translation validation for duplicate and source-referenced crash keys.
@NotNullByDefault
final class CheckTranslationsTest {
    /// Temporary filesystem root for resource and source fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// Rejects duplicate declarations before `Properties` can silently overwrite them.
    ///
    /// @throws IOException when a test fixture cannot be written
    @Test
    void rejectsDuplicateKeys() throws IOException {
        List<Path> bundles = bundles(
                "sample=value\nsample=overwritten\n", "sample=值\n", "sample=值\n", "sample=值\n");
        assertThrows(org.gradle.api.GradleException.class, () -> validate(bundles, Set.of()));
    }

    /// Rejects a complete crash-message key literal that is absent from the primary bundles.
    ///
    /// @throws IOException when a test fixture cannot be written
    @Test
    void rejectsMissingCrashSourceKey() throws IOException {
        List<Path> bundles = bundles("sample=value\n", "sample=值\n", "sample=值\n", "sample=值\n");
        Path source = writeCrashSource("new TextSolver(\"game.crash.reason.log.missing\", \"fallback\");");
        assertThrows(org.gradle.api.GradleException.class, () -> validate(bundles, Set.of(source.toFile())));
    }

    /// Accepts a source-referenced crash key present in English, Simplified Chinese, and Traditional Chinese.
    ///
    /// @throws IOException when a test fixture cannot be written
    @Test
    void acceptsTranslatedCrashSourceKey() throws IOException {
        String key = "game.crash.reason.log.complete";
        List<Path> bundles = bundles(key + "=Complete\n", key + "=完整\n", key + "=完整\n", "sample=值\n");
        Path source = writeCrashSource("new TextSolver(\"" + key + "\", \"fallback\");");
        assertDoesNotThrow(() -> validate(bundles, Set.of(source.toFile())));
    }

    /// Creates four raw resource bundles in validator parameter order.
    ///
    /// @param english English bundle contents
    /// @param simplifiedChinese Simplified Chinese bundle contents
    /// @param traditionalChinese Traditional Chinese bundle contents
    /// @param classicalChinese Classical Chinese bundle contents
    /// @return bundle paths in validator parameter order
    /// @throws IOException when a resource fixture cannot be written
    private List<Path> bundles(
            String english,
            String simplifiedChinese,
            String traditionalChinese,
            String classicalChinese) throws IOException {
        Path languageDirectory = temporaryDirectory.resolve("XYML/src/main/resources/assets/lang");
        Files.createDirectories(languageDirectory);
        Path englishFile = Files.writeString(languageDirectory.resolve("I18N.properties"), english);
        Path simplifiedChineseFile = Files.writeString(
                languageDirectory.resolve("I18N_zh_CN.properties"), simplifiedChinese);
        Path traditionalChineseFile = Files.writeString(
                languageDirectory.resolve("I18N_zh.properties"), traditionalChinese);
        Path classicalChineseFile = Files.writeString(
                languageDirectory.resolve("I18N_lzh.properties"), classicalChinese);
        return List.of(englishFile, simplifiedChineseFile, traditionalChineseFile, classicalChineseFile);
    }

    /// Runs the production validator against one ordered bundle list.
    ///
    /// @param bundles bundle paths in English, Simplified Chinese, Traditional Chinese, Classical Chinese order
    /// @param sources Java source files to inspect
    /// @throws IOException when a fixture cannot be read
    private static void validate(List<Path> bundles, Set<File> sources) throws IOException {
        CheckTranslations.validate(
                bundles.get(0), bundles.get(1), bundles.get(2), bundles.get(3), sources);
    }

    /// Writes one analyzer source containing the supplied expression.
    ///
    /// @param expression Java expression embedded in a dummy declaration
    /// @return written source path
    /// @throws IOException when the fixture cannot be written
    private Path writeCrashSource(String expression) throws IOException {
        Path sourceDirectory = temporaryDirectory.resolve(
                "XYMLCore/src/main/java/space/minecraftstl/xyml/game/analyzer");
        Files.createDirectories(sourceDirectory);
        return Files.writeString(
                sourceDirectory.resolve("Fixture.java"),
                "class Fixture { Object value = " + expression + " }");
    }
}

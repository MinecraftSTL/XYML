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
package space.minecraftstl.xyml.util.io;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Verifies text readers used by log export and crash analysis.
@NotNullByDefault
final class TextEncodingCompatibilityTest {
    /// Empty files remain readable even when the encoding detector has no sample bytes.
    @Test
    void readsEmptyFile(@TempDir Path tempDirectory) throws IOException {
        Path file = Files.createFile(tempDirectory.resolve("empty.log"));

        try (BufferedReader reader = IOUtils.newBufferedReaderMaybeNativeEncoding(file)) {
            assertNull(reader.readLine());
        }
        assertEquals("", FileUtils.readTextMaybeNativeEncoding(file));
    }

    /// Modern UTF-8 text is preserved by both streaming and whole-file readers.
    @Test
    void readsUtf8Text(@TempDir Path tempDirectory) throws IOException {
        String content = "XYML UTF-8 log: \u4f60\u597d";
        Path file = tempDirectory.resolve("utf8.log");
        Files.writeString(file, content, StandardCharsets.UTF_8);

        try (BufferedReader reader = IOUtils.newBufferedReaderMaybeNativeEncoding(file)) {
            assertEquals(content, reader.readLine());
        }
        assertEquals(content, FileUtils.readTextMaybeNativeEncoding(file));
    }
}

// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

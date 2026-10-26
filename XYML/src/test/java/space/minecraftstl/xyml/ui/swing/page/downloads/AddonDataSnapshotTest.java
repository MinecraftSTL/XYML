// SPDX-FileCopyrightText: Copyright (C) 2025 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/// Guards bundled addon formats and the primary and Russian translations used by merged UI behavior.
@NotNullByDefault
final class AddonDataSnapshotTest {
    /// Both addon snapshots retain exactly six fields and unique numeric mcmod identifiers.
    ///
    /// @throws IOException when bundled resources cannot be read
    @Test
    void validatesAddonSnapshots() throws IOException {
        for (String kind : List.of("mod", "modpack")) {
            Set<String> ids = new HashSet<>();
            List<String> rows = resourceRows("/assets/" + kind + "_data.txt");
            assertFalse(rows.isEmpty());
            for (String row : rows) {
                String[] fields = row.split(";", -1);
                assertEquals(6, fields.length, kind);
                assertTrue(fields[1].matches("[0-9]+"), kind);
                assertTrue(ids.add(fields[1]), "Duplicate mcmod ID: " + fields[1]);
            }
            assertEquals(rows.size(), ids.size());
        }
    }

    /// CPU and skin keys exist in the primary and Russian bundles with identical formatting placeholders.
    ///
    /// @throws IOException when bundled resources cannot be read
    @Test
    void synchronizesUiLanguagePlaceholders() throws IOException {
        Pattern placeholders = Pattern.compile("%[sd]");
        List<String> keys = List.of("about.environment.cpu.n", "about.environment.cpu.name",
                "about.environment.cpu.name.cores", "about.environment.cpu.name.cores.hyper_threading",
                "account.skin.edit", "account.skin.upload");
        Properties english = language("");
        assertEquals("Изменить скин", language("_ru").getProperty("account.skin.edit"));
        assertEquals("Загрузить скин", language("_ru").getProperty("account.skin.upload"));
        for (String suffix : List.of("_zh_CN", "_zh", "_ru")) {
            Properties localized = language(suffix);
            for (String key : keys) {
                String expected = Objects.requireNonNull(english.getProperty(key), key);
                String actual = Objects.requireNonNull(localized.getProperty(key), key);
                assertFalse(actual.isBlank(), key);
                assertEquals(placeholders.matcher(expected).results().map(match -> match.group()).toList(),
                        placeholders.matcher(actual).results().map(match -> match.group()).toList(), key);
            }
        }
    }

    /// Reads non-comment records from one required bundled resource.
    ///
    /// @param resource absolute classpath resource name
    /// @return immutable ordered records
    /// @throws IOException when the resource cannot be read
    private static @Unmodifiable List<String> resourceRows(String resource) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(AddonDataSnapshotTest.class.getResourceAsStream(resource)),
                StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        }
    }

    /// Loads one primary UTF-8 translation bundle.
    ///
    /// @param suffix locale suffix, or an empty string for English
    /// @return parsed properties
    /// @throws IOException when the resource cannot be read
    private static Properties language(String suffix) throws IOException {
        Properties properties = new Properties();
        try (InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(
                AddonDataSnapshotTest.class.getResourceAsStream("/assets/lang/I18N" + suffix + ".properties")),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}

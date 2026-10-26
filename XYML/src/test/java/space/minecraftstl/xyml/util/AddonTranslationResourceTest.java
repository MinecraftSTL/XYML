// SPDX-FileCopyrightText: Copyright (C) 2025 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// Protects bundled legacy addon resource structure independently of the upstream CSV representation.
@NotNullByDefault
public final class AddonTranslationResourceTest {
    /// Resources remain valid UTF-8 six-field records with unique MCMOD identifiers.
    ///
    /// @param kind bundled addon category
    /// @throws IOException if resources cannot be read as valid UTF-8
    @ParameterizedTest
    @ValueSource(strings = {"mod", "modpack"})
    public void preservesLegacyResourceSchema(String kind) throws IOException {
        String resource = "/assets/" + kind + "_data.txt";
        try (@Nullable InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(input),
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)))) {
                Set<String> identifiers = new HashSet<>();
                @Nullable String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#"))
                        continue;
                    String[] fields = line.split(";", -1);
                    assertEquals(6, fields.length, resource);
                    assertTrue(fields[1].matches("[0-9]+"), resource + ": invalid MCMOD identifier");
                    assertTrue(identifiers.add(fields[1]), resource + ": duplicate " + fields[1]);
                }
                assertFalse(identifiers.isEmpty(), resource);
            }
        }
    }
}

// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;

/// Guards the production launch helper against reintroducing removed toolkit and legacy dialog linkage.
///
/// Toolkit-neutral launch operations, including standalone script generation through
/// `XYMLGameLauncher`, remain valid responsibilities of this helper.
@NotNullByDefault
final class LauncherHelperToolkitDependencyTest {
    /// Compiled launch helper constants must not reference removed UI toolkits or controllers.
    @Test
    void compiledHelperHasNoRemovedToolkitReferences() throws IOException {
        String constants = new String(readClassBytes(), StandardCharsets.ISO_8859_1);
        assertFalse(constants.contains("javafx/"));
        assertFalse(constants.contains("com/jfoenix/"));
        assertFalse(constants.contains("space/minecraftstl/xyml/ui/Controllers"));
        assertFalse(constants.contains("javafx.stage.Stage"));
    }

    /// Reads the compiled helper without initializing it.
    ///
    /// @return helper class bytes
    /// @throws IOException when the class resource cannot be read
    private static byte[] readClassBytes() throws IOException {
        String resource = "/" + LauncherHelper.class.getName().replace('.', '/') + ".class";
        try (InputStream stream = Objects.requireNonNull(
                LauncherHelper.class.getResourceAsStream(resource),
                "Missing class resource " + resource)) {
            return stream.readAllBytes();
        }
    }
}

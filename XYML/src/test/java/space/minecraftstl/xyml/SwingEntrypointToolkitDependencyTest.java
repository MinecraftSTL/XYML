// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;

/// Guards the production entrypoint against restoring JavaFX application, Stage, or online patcher linkage.
@NotNullByDefault
final class SwingEntrypointToolkitDependencyTest {
    /// Production startup types that must remain free of the removed host toolkit.
    private static final @Unmodifiable List<Class<?>> STARTUP_TYPES = List.of(
            EntryPoint.class,
            Launcher.class,
            LauncherStateDispatcher.class);

    /// Compiled entrypoint constant pools contain no removed JavaFX host or dependency patcher symbols.
    @Test
    void compiledEntrypointHasNoJavaFxHostReferences() throws IOException {
        for (Class<?> type : STARTUP_TYPES) {
            String constants = new String(readClassBytes(type), StandardCharsets.ISO_8859_1);
            assertFalse(constants.contains("javafx/application"), type.getName());
            assertFalse(constants.contains("javafx/stage"), type.getName());
            assertFalse(constants.contains("SelfDependencyPatcher"), type.getName());
            assertFalse(constants.contains("space/minecraftstl/xyml/ui/Controllers"), type.getName());
        }
    }

    /// Reads one compiled class resource without initializing it.
    ///
    /// @param type class to inspect
    /// @return classfile bytes
    /// @throws IOException when the class resource cannot be read
    private static byte[] readClassBytes(Class<?> type) throws IOException {
        String resource = '/' + type.getName().replace('.', '/') + ".class";
        try (InputStream input = Objects.requireNonNull(
                type.getResourceAsStream(resource),
                "Missing class resource " + resource)) {
            return input.readAllBytes();
        }
    }
}

// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.auth.offline;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;

/// Guards the compiled offline-skin boundary against reintroducing JavaFX image dependencies.
@NotNullByDefault
final class OfflineSkinToolkitDependencyTest {
    /// Core entry points whose complete nested-class trees must stay toolkit-neutral.
    private static final @Unmodifiable List<Class<?>> ROOT_TYPES = List.of(
            Texture.class,
            Skin.class,
            YggdrasilServer.class);

    /// Compiled constant pools contain neither JavaFX symbols nor the removed PNG adapter.
    @Test
    void compiledClassesHaveNoJavaFxOrSimplePngReferences() throws IOException {
        for (Class<?> rootType : ROOT_TYPES) {
            for (Class<?> type : classTree(rootType)) {
                String constants = new String(readClassBytes(type), StandardCharsets.ISO_8859_1);
                assertFalse(constants.contains("javafx/"), type.getName());
                assertFalse(constants.contains("org/glavo/png/"), type.getName());
                assertFalse(constants.contains("PNGJavaFXUtils"), type.getName());
            }
        }
    }

    /// Collects one class and all recursively declared nested classes.
    ///
    /// @param root root class
    /// @return immutable depth-first class list
    private static @Unmodifiable List<Class<?>> classTree(Class<?> root) {
        List<Class<?>> result = new ArrayList<>();
        result.add(root);
        for (Class<?> nested : root.getDeclaredClasses()) {
            result.addAll(classTree(nested));
        }
        return List.copyOf(result);
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

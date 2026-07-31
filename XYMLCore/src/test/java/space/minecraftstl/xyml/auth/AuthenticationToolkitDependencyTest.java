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
package space.minecraftstl.xyml.auth;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorServer;
import space.minecraftstl.xyml.auth.microsoft.MicrosoftAccount;
import space.minecraftstl.xyml.auth.offline.OfflineAccount;
import space.minecraftstl.xyml.auth.yggdrasil.YggdrasilAccount;
import space.minecraftstl.xyml.observable.cache.ObservableCache;
import space.minecraftstl.xyml.observable.cache.ObservableOptionalCache;
import space.minecraftstl.xyml.observable.property.MappedObservableValue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;

/// Guards the compiled authentication and observable-cache boundary against JavaFX linkage.
@NotNullByDefault
final class AuthenticationToolkitDependencyTest {
    /// Root types whose complete nested-class trees must remain toolkit-neutral.
    private static final @Unmodifiable List<Class<?>> ROOT_TYPES = List.of(
            Account.class,
            AuthlibInjectorServer.class,
            MicrosoftAccount.class,
            OfflineAccount.class,
            YggdrasilAccount.class,
            ObservableCache.class,
            ObservableOptionalCache.class,
            MappedObservableValue.class);

    /// Compiled constant pools contain neither JavaFX symbols nor the removed utility package.
    @Test
    void compiledClassesHaveNoJavaFxReferences() throws IOException {
        for (Class<?> rootType : ROOT_TYPES) {
            for (Class<?> type : classTree(rootType)) {
                String constants = new String(readClassBytes(type), StandardCharsets.ISO_8859_1);
                assertFalse(constants.contains("javafx/"), type.getName());
                assertFalse(constants.contains("space/minecraftstl/xyml/util/javafx"), type.getName());
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

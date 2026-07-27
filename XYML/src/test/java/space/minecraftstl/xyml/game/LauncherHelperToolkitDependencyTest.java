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

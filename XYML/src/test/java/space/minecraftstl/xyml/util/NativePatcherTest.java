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
package space.minecraftstl.xyml.util;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.Artifact;
import space.minecraftstl.xyml.game.Library;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies filtering of bundled LWJGL natives when system libraries are selected.
@NotNullByDefault
final class NativePatcherTest {
    /// GLFW, SDL, and OpenAL follow their corresponding settings without matching unrelated artifacts.
    @Test
    void filtersEnabledSystemNativeFamilies() {
        assertAll(
                () -> assertTrue(NativePatcher.shouldFilterBundledNative(
                        nativeLibrary("org.lwjgl", "lwjgl-glfw"), true, false)),
                () -> assertTrue(NativePatcher.shouldFilterBundledNative(
                        nativeLibrary("org.lwjgl", "lwjgl-sdl3"), true, false)),
                () -> assertTrue(NativePatcher.shouldFilterBundledNative(
                        nativeLibrary("org.lwjgl", "lwjgl-openal"), false, true)),
                () -> assertFalse(NativePatcher.shouldFilterBundledNative(
                        nativeLibrary("org.lwjgl", "lwjgl-sdl3"), false, true)),
                () -> assertFalse(NativePatcher.shouldFilterBundledNative(
                        nativeLibrary("org.example", "lwjgl-sdl3"), true, false)),
                () -> assertFalse(NativePatcher.shouldFilterBundledNative(
                        new Library(new Artifact("org.lwjgl", "lwjgl-sdl3", "3.3.6")), true, false)));
    }

    /// Creates one Linux-native library fixture.
    ///
    /// @param groupId Maven group identifier
    /// @param artifactId Maven artifact identifier
    /// @return native-classifier library fixture
    private static Library nativeLibrary(String groupId, String artifactId) {
        return new Library(new Artifact(groupId, artifactId, "3.3.6", "natives-linux"));
    }
}

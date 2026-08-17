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
package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests launcher-radius mapping for Windows native window corners.
@NotNullByDefault
public final class WindowsNativeUtilsTest {
    /// Zero requests square corners while every positive radius restores the system corner policy.
    @Test
    public void mapsLauncherRadiusToNativeWindowPreference() {
        assertAll(
                () -> assertEquals(1, WindowsNativeUtils.nativeWindowCornerPreference(0)),
                () -> assertEquals(0, WindowsNativeUtils.nativeWindowCornerPreference(1)),
                () -> assertEquals(0, WindowsNativeUtils.nativeWindowCornerPreference(20)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> WindowsNativeUtils.nativeWindowCornerPreference(-1)));
    }
}

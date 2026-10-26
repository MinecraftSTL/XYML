// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

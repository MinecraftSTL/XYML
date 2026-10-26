// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/// Verifies that every production Swing icon surface reads the bundled XYML icon family.
@NotNullByDefault
public final class LauncherIconImagesTest {
    /// The classpath contains all bundled application icon resolutions and a compact header icon.
    @Test
    public void loadsBundledIconFamily() {
        Icon headerIcon = Objects.requireNonNull(LauncherIconImages.headerIcon(), "header icon");
        Icon communityIcon = Objects.requireNonNull(LauncherIconImages.communityIcon(), "community icon");
        assertEquals(4, LauncherIconImages.windowIcons().size());
        assertNotNull(headerIcon);
        assertAll(
                () -> assertEquals(24, headerIcon.getIconWidth()),
                () -> assertEquals(24, headerIcon.getIconHeight()),
                () -> assertEquals(24, communityIcon.getIconWidth()),
                () -> assertEquals(24, communityIcon.getIconHeight()));
    }
}

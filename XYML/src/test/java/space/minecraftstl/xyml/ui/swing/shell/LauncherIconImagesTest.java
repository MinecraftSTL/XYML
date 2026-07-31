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

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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/// Supplies locally installed font families without requiring network access.
@FunctionalInterface
@NotNullByDefault
interface FontFamilyCatalog {
    /// Enumerates local font families outside the Swing event dispatch thread.
    ///
    /// @return immutable family names in stable display order
    @Unmodifiable List<String> loadFamilies();

    /// Creates the production catalogue backed by the local AWT graphics environment.
    ///
    /// @return local system font catalogue
    static FontFamilyCatalog system() {
        return () -> {
            if (SwingUtilities.isEventDispatchThread()) {
                throw new IllegalStateException("System font enumeration must not run on the EDT");
            }
            return Arrays.stream(GraphicsEnvironment
                            .getLocalGraphicsEnvironment()
                            .getAvailableFontFamilyNames(Locale.ROOT))
                    .filter(name -> !name.isBlank())
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        };
    }
}

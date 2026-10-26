// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

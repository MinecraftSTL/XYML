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

import javax.swing.JToggleButton;
import java.awt.Cursor;
import java.awt.Insets;
import java.util.Objects;

/// Provides a stable icon-and-label target for one top-level destination.
@NotNullByDefault
final class ShellNavigationButton extends JToggleButton {
    /// Destination selected by this button.
    private final ShellPageId page;

    /// Creates an accessible navigation target with a keyboard mnemonic.
    ///
    /// @param page the represented destination
    /// @param presentation the localized button presentation
    ShellNavigationButton(ShellPageId page, ShellPagePresentation presentation) {
        super(Objects.requireNonNull(presentation).label(), new ShellNavIcon(page));
        this.page = page;
        setMnemonic(presentation.mnemonic());
        setHorizontalAlignment(LEFT);
        setIconTextGap(12);
        setMargin(new Insets(10, 14, 10, 14));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFocusable(true);
        setFocusPainted(true);
        putClientProperty("JButton.buttonType", "toolBarButton");
        getAccessibleContext().setAccessibleName(presentation.label());
    }

    /// Returns the represented destination.
    ///
    /// @return the destination selected by this button
    ShellPageId page() {
        return page;
    }
}

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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.JToggleButton;
import java.awt.Color;
import java.awt.Component;
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
        super(Objects.requireNonNull(presentation).label(), createNavigationIcon(page));
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

    /// Creates a theme-aware legacy navigation icon for one destination.
    ///
    /// @param page destination represented by the returned icon
    /// @return configured 20-pixel SVG icon
    private static FlatSVGIcon createNavigationIcon(ShellPageId page) {
        FlatSVGIcon icon = new FlatSVGIcon(iconResource(page), 20, 20);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(ShellNavigationButton::resolveIconColor));
        return icon;
    }

    /// Resolves a navigation icon color from the owning button's active theme state.
    ///
    /// @param component owning component, or null during standalone image rendering
    /// @param originalColor SVG-authored fallback color
    /// @return component foreground when available, otherwise the SVG fallback
    private static Color resolveIconColor(@Nullable Component component, Color originalColor) {
        Color fallback = Objects.requireNonNull(originalColor, "originalColor");
        @Nullable Color foreground = component == null ? null : component.getForeground();
        return foreground == null ? fallback : foreground;
    }

    /// Maps one destination to the corresponding bundled legacy Material SVG asset.
    ///
    /// @param page destination represented by the requested icon
    /// @return classpath-relative SVG resource path
    private static String iconResource(ShellPageId page) {
        return switch (Objects.requireNonNull(page, "page")) {
            case INSTANCES -> "assets/swing/icons/nav-instances.svg";
            case DOWNLOADS -> "assets/swing/icons/nav-downloads.svg";
            case ACCOUNTS -> "assets/swing/icons/nav-accounts.svg";
            case SETTINGS -> "assets/swing/icons/nav-settings.svg";
        };
    }
}

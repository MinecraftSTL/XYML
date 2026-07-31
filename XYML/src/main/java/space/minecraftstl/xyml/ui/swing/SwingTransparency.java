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

import com.formdev.flatlaf.FlatClientProperties;
import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import java.util.Map;
import java.util.Objects;

/// Applies the shared background-visibility policy to Swing layout controls.
///
/// Editable fields, popup surfaces, selected rows, and modal overlays deliberately stay outside this policy because
/// their solid surfaces communicate input or interaction state. Tab strips and layout-only scroll containers use
/// these methods so the window wallpaper remains visible without removing focus, hover, or selection feedback.
@NotNullByDefault
public final class SwingTransparency {
    /// Prevents construction of the stateless policy holder.
    private SwingTransparency() {
    }

    /// Makes a tab strip and its content separator transparent while retaining the selected-tab underline.
    ///
    /// FlatLaf paints a tab-area background independently of [JTabbedPane#isOpaque()], so `tabsOpaque` must also be
    /// disabled explicitly. Other look and feels still honor the standard non-opaque flag.
    ///
    /// @param tabs tabbed navigation surface to configure
    public static void revealBackgroundThroughTabs(JTabbedPane tabs) {
        JTabbedPane target = Objects.requireNonNull(tabs, "tabs");
        target.setOpaque(false);
        target.putClientProperty(
                FlatClientProperties.TABBED_PANE_TAB_TYPE,
                FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED);
        target.putClientProperty(FlatClientProperties.TABBED_PANE_SHOW_CONTENT_SEPARATOR, false);
        target.putClientProperty(FlatClientProperties.STYLE, Map.of("tabsOpaque", false));
    }

    /// Makes a layout-only scroll pane and its viewport reveal their parent's background.
    ///
    /// The hosted view is intentionally unchanged. Callers may therefore keep editors, trees, or other readability
    /// surfaces opaque while using this policy only when their own content is already transparent.
    ///
    /// @param scrollPane layout-only scroll container to configure
    public static void revealBackgroundThroughScrollPane(JScrollPane scrollPane) {
        JScrollPane target = Objects.requireNonNull(scrollPane, "scrollPane");
        target.setOpaque(false);
        target.getViewport().setOpaque(false);
    }
}

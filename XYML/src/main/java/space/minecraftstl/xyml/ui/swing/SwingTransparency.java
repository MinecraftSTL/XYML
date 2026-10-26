// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.ui.FlatScrollPaneBorder;
import com.formdev.flatlaf.ui.FlatScrollPaneUI;
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
        if (target.getUI() instanceof FlatScrollPaneUI
                && target.getBorder() instanceof FlatScrollPaneBorder) {
            // FlatLaf paints the hosted view's background whenever ScrollPane.arc is positive, even if this
            // container is non-opaque. A local zero arc is therefore part of the background-reveal contract.
            target.putClientProperty(FlatClientProperties.STYLE, Map.of("arc", 0));
        }
        target.setOpaque(false);
        target.getViewport().setOpaque(false);
    }
}

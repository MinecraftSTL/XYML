// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.UIDefaults;
import java.util.Objects;

/// Holds visual measurements shared by the Swing look and feel.
///
/// @param cornerRadius the component corner radius in logical pixels
@NotNullByDefault
public record SwingDesignTokens(int cornerRadius) {
    /// Largest FlatLaf checkbox arc diameter that retains a rounded-square silhouette.
    private static final int MAXIMUM_CHECK_BOX_ARC_DIAMETER = 6;

    /// Creates validated Swing design tokens.
    ///
    /// @param cornerRadius the component corner radius in logical pixels
    public SwingDesignTokens {
        if (cornerRadius < 0) {
            throw new IllegalArgumentException("cornerRadius must not be negative");
        }
        if (cornerRadius > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("cornerRadius is too large to convert to an arc diameter");
        }
    }

    /// Returns a copy with a different component corner radius.
    ///
    /// @param value the new radius in logical pixels
    /// @return design tokens containing the requested radius
    public SwingDesignTokens withCornerRadius(int value) {
        return new SwingDesignTokens(value);
    }

    /// Applies the radius to FlatLaf's supported component defaults.
    ///
    /// @param defaults the active Swing defaults to update
    public void applyTo(UIDefaults defaults) {
        Objects.requireNonNull(defaults);

        int arcDiameter = cornerRadius * 2;
        defaults.put("Component.arc", arcDiameter);
        defaults.put("Button.arc", arcDiameter);
        defaults.put("CheckBox.arc", Math.min(arcDiameter, MAXIMUM_CHECK_BOX_ARC_DIAMETER));
        defaults.put("ComboBox.borderCornerRadius", cornerRadius);
        defaults.put("ComboBox.selectionArc", arcDiameter);
        defaults.put("List.selectionArc", arcDiameter);
        defaults.put("MenuBar.selectionArc", arcDiameter);
        defaults.put("MenuItem.selectionArc", arcDiameter);
        defaults.put("Popup.borderCornerRadius", cornerRadius);
        defaults.put("PopupMenu.borderCornerRadius", cornerRadius);
        defaults.put("TextComponent.arc", arcDiameter);
        defaults.put("ProgressBar.arc", arcDiameter);
        defaults.put("ScrollBar.thumbArc", arcDiameter);
        defaults.put("ScrollBar.trackArc", arcDiameter);
        defaults.put("ScrollPane.arc", arcDiameter);
        defaults.put("TabbedPane.tabArc", arcDiameter);
        defaults.put("TabbedPane.tabSelectionArc", arcDiameter);
        defaults.put("TabbedPane.cardTabArc", arcDiameter);
        defaults.put("TabbedPane.buttonArc", arcDiameter);
        defaults.put("TabbedPane.closeArc", arcDiameter);
        defaults.put("TitlePane.buttonArc", arcDiameter);
        defaults.put("ToolBar.hoverButtonGroupArc", arcDiameter);
        defaults.put("ToolTip.borderCornerRadius", cornerRadius);
        defaults.put("Tree.selectionArc", arcDiameter);
    }
}

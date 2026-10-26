// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.ui.swing.SwingOverlayColors;

import javax.swing.JButton;
import javax.swing.UIManager;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.OptionalDouble;

/// Renders the ordinary launch command with a compact left-to-right progress fill.
@NotNullByDefault
final class LaunchProgressButton extends JButton {
    /// Whether the progress strip is currently visible.
    private boolean progressVisible;

    /// Latest normalized task progress, or empty while the task is indeterminate.
    private OptionalDouble progress = OptionalDouble.empty();

    /// Updates whether this button represents an active ordinary launch task.
    ///
    /// @param visible whether to show the progress fill
    void setProgressVisible(boolean visible) {
        if (progressVisible == visible) {
            return;
        }
        progressVisible = visible;
        repaint();
    }

    /// Returns whether the progress fill is currently visible.
    ///
    /// @return true while the ordinary launch task is active
    boolean isProgressVisible() {
        return progressVisible;
    }

    /// Updates the normalized progress represented by the fill.
    ///
    /// @param replacement normalized progress, or empty for an indeterminate task
    void setProgress(OptionalDouble replacement) {
        progress = replacement;
        repaint();
    }

    /// Returns the number of horizontal pixels filled by the current progress.
    ///
    /// @param availableWidth width available inside the button in pixels
    /// @return filled width, or zero while hidden or indeterminate
    int filledProgressWidth(int availableWidth) {
        if (progressVisible == false || progress.isEmpty()) {
            return 0;
        }
        double fraction = Math.max(0.0D, Math.min(1.0D, progress.orElseThrow()));
        return (int) Math.ceil(fraction * Math.max(0, availableWidth));
    }

    /// Paints the native button first, then overlays a translucent theme-contrast progress fill.
    ///
    /// @param graphics destination graphics
    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (progressVisible == false || getWidth() <= 0 || getHeight() <= 0 || progress.isEmpty()) {
            return;
        }

        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int availableWidth = getWidth();
            int filledWidth = filledProgressWidth(availableWidth);
            if (filledWidth <= 0) {
                return;
            }
            int arc = Math.max(0, Math.min(
                    Math.min(availableWidth, getHeight()),
                    UIManager.getInt("Button.arc")));
            copy.clip(new RoundRectangle2D.Double(0, 0, availableWidth, getHeight(), arc, arc));
            copy.setColor(SwingOverlayColors.contrastOverlay(this));
            copy.fillRect(0, 0, filledWidth, getHeight());
        } finally {
            copy.dispose();
        }
    }

}

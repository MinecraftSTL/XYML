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

import javax.swing.JButton;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.OptionalDouble;

/// Renders the ordinary launch command with a compact left-to-right segmented progress indicator.
@NotNullByDefault
final class LaunchProgressButton extends JButton {
    /// Number of gray progress blocks shown inside the launch command.
    static final int SEGMENT_COUNT = 8;

    /// Gray used for completed progress blocks.
    private static final Color COMPLETED_BLOCK = new Color(128, 128, 128, 190);

    /// Gray used for blocks that remain in the current launch task.
    private static final Color REMAINING_BLOCK = new Color(128, 128, 128, 58);

    /// Whether the progress strip is currently visible.
    private boolean progressVisible;

    /// Latest normalized task progress, or empty while the task is indeterminate.
    private OptionalDouble progress = OptionalDouble.empty();

    /// Updates whether this button represents an active ordinary launch task.
    ///
    /// @param visible whether to show the segmented strip
    void setProgressVisible(boolean visible) {
        if (progressVisible == visible) {
            return;
        }
        progressVisible = visible;
        repaint();
    }

    /// Returns whether the segmented strip is currently visible.
    ///
    /// @return true while the ordinary launch task is active
    boolean isProgressVisible() {
        return progressVisible;
    }

    /// Updates the normalized progress represented by the segmented strip.
    ///
    /// @param replacement normalized progress, or empty for an indeterminate task
    void setProgress(OptionalDouble replacement) {
        progress = replacement;
        repaint();
    }

    /// Returns the number of completed blocks for focused rendering tests.
    ///
    /// @return completed block count
    int filledSegmentCount() {
        if (!progressVisible) {
            return 0;
        }
        if (progress.isEmpty()) {
            return 1;
        }
        return (int) Math.round(progress.orElseThrow() * SEGMENT_COUNT);
    }

    /// Paints the native button first, then overlays a low-profile gray progress strip.
    ///
    /// @param graphics destination graphics
    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (!progressVisible || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int left = Math.max(4, getInsets().left);
            int right = Math.max(left, getWidth() - Math.max(4, getInsets().right));
            int gap = 2;
            int blockWidth = Math.max(1, (right - left - gap * (SEGMENT_COUNT - 1)) / SEGMENT_COUNT);
            int blockHeight = 5;
            int y = Math.max(0, getHeight() - blockHeight - 3);
            int filled = filledSegmentCount();
            for (int index = 0; index < SEGMENT_COUNT; index++) {
                int x = left + index * (blockWidth + gap);
                copy.setColor(index < filled ? COMPLETED_BLOCK : REMAINING_BLOCK);
                copy.fillRoundRect(x, y, blockWidth, blockHeight, 3, 3);
            }
        } finally {
            copy.dispose();
        }
    }
}

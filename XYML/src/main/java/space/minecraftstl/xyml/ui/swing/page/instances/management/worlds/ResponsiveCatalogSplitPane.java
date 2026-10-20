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
package space.minecraftstl.xyml.ui.swing.page.instances.management.worlds;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.ui.swing.SwingHorizontalScrollPane;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JSplitPane;
import javax.swing.JViewport;
import java.awt.Component;
import java.awt.Dimension;

/// Keeps the world catalog split horizontal while preserving user-adjustable minimum widths.
@NotNullByDefault
final class ResponsiveCatalogSplitPane extends JSplitPane {
    /// Original responsive breakpoint retained from the pre-existing page layout.
    private static final int WIDE_LAYOUT_MINIMUM_WIDTH = 720;

    /// Whether the divider ratio has been initialized.
    private boolean dividerInitialized;

    /// Whether the configured side minima currently fit the allocated width.
    private boolean minimumsApplied;

    /// List surface whose minimum width is applied when space permits.
    private final JComponent leftComponent;

    /// Details surface whose minimum width is applied when space permits.
    private final JComponent rightComponent;

    /// Computed minimum width of the list surface.
    private final int leftMinimumWidth;

    /// Computed minimum width of the details surface.
    private final int rightMinimumWidth;

    /// Creates a horizontal split whose children may shrink when the host is narrower than their minima.
    ///
    /// @param list list surface
    /// @param details selected-world details surface
    ResponsiveCatalogSplitPane(JComponent list, JComponent details) {
        super(JSplitPane.VERTICAL_SPLIT, list, details);
        setName("worldsCatalogSplit");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());
        setContinuousLayout(true);
        setResizeWeight(0.46D);
        leftComponent = list;
        rightComponent = details;
        leftMinimumWidth = list.getMinimumSize().width;
        rightMinimumWidth = details.getMinimumSize().width;
        leftComponent.setMinimumSize(new Dimension(0, 0));
        rightComponent.setMinimumSize(new Dimension(0, 0));
    }

    /// Returns the nearest outer viewport width or the split width without a viewport.
    ///
    /// @return available host width
    private int availableViewportWidth() {
        Component parent = getParent();
        while (parent != null) {
            if (parent instanceof JViewport viewport
                    && viewport.getWidth() > 0) {
                return viewport.getWidth();
            }
            if (parent instanceof SwingHorizontalScrollPane scroll) {
                return scroll.getWidth();
            }
            parent = parent.getParent();
        }
        return getWidth();
    }

    /// Enables the page-level horizontal fallback only while this split is horizontal.
    ///
    /// @param horizontal whether the original page threshold selects horizontal presentation
    private void updateOuterHorizontalScroll(boolean horizontal) {
        Component parent = getParent();
        while (parent != null) {
            if (parent instanceof SwingHorizontalScrollPane scroll) {
                scroll.setMinimumContentWidth(horizontal ? requiredMinimumWidth() : 0);
                return;
            }
            parent = parent.getParent();
        }
    }

    /// Returns the width required by both columns and the divider.
    ///
    /// @return complete workspace minimum width
    int requiredMinimumWidth() {
        return leftMinimumWidth + rightMinimumWidth + Math.max(1, getDividerSize());
    }

    /// Applies side minima when possible and clamps a user-adjusted divider without changing orientation.
    @Override
    public void doLayout() {
        int availableWidth = availableViewportWidth();
        boolean horizontal = availableWidth >= WIDE_LAYOUT_MINIMUM_WIDTH;
        int desired = horizontal ? HORIZONTAL_SPLIT : VERTICAL_SPLIT;
        if (getOrientation() != desired) {
            setOrientation(desired);
            dividerInitialized = false;
            minimumsApplied = false;
        }
        updateOuterHorizontalScroll(horizontal);
        boolean canApplyMinimums = getOrientation() == HORIZONTAL_SPLIT
                && getWidth() >= leftMinimumWidth + rightMinimumWidth + getDividerSize();
        if (canApplyMinimums != minimumsApplied) {
            minimumsApplied = canApplyMinimums;
            leftComponent.setMinimumSize(canApplyMinimums
                    ? new Dimension(leftMinimumWidth, 0)
                    : new Dimension(0, 0));
            rightComponent.setMinimumSize(canApplyMinimums
                    ? new Dimension(rightMinimumWidth, 0)
                    : new Dimension(0, 0));
        }
        if (!dividerInitialized && getWidth() > 1) {
            setDividerLocation((int) Math.round(
                    (getWidth() - getDividerSize()) * 0.46D));
            dividerInitialized = true;
        }
        if (canApplyMinimums && getWidth() > 0) {
            int maximum = Math.max(leftMinimumWidth, getWidth() - getDividerSize() - rightMinimumWidth);
            int location = Math.max(leftMinimumWidth, Math.min(getDividerLocation(), maximum));
            if (location != getDividerLocation()) {
                setDividerLocation(location);
            }
        }
        super.doLayout();
    }

    /// Allows the shell to constrain both children without honoring their preferred widths.
    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, 0);
    }
}

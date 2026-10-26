// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Dimension;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the shared whole-page horizontal scroll fallback.
@NotNullByDefault
class SwingHorizontalScrollPaneTest {
    /// Shows one scrollbar only after the complete workspace exceeds the viewport.
    @Test
    void scrollsCompleteWorkspaceOnlyWhenNeeded() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel content = new JPanel();
            SwingHorizontalScrollPane scroll = new SwingHorizontalScrollPane(
                    content,
                    "testHorizontalScroll",
                    400);
            scroll.setSize(new Dimension(500, 200));
            scroll.doLayout();
            scroll.getViewport().doLayout();
            assertTrue(scroll.getHorizontalScrollBar().getMaximum()
                    <= scroll.getHorizontalScrollBar().getVisibleAmount());

            scroll.setSize(new Dimension(300, 200));
            scroll.doLayout();
            scroll.getViewport().doLayout();
            assertTrue(scroll.getHorizontalScrollBar().getMaximum()
                    > scroll.getHorizontalScrollBar().getVisibleAmount());
        });
    }
}

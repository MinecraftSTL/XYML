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

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
package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks actual allocated catalog geometry rather than layout strings or detached controls.
@NotNullByDefault
public final class CatalogLayoutAssertions {
    /// Prevents construction of the stateless test helper.
    private CatalogLayoutAssertions() {
    }

    /// Checks full-width headings, horizontal columns, and a reachable status band on the EDT.
    ///
    /// @param panel populated management page
    /// @param prefix stable component-name prefix
    /// @param width page width in pixels
    /// @param height page height in pixels
    public static void assertHorizontalWorkspace(JComponent panel, String prefix, int width, int height) {
        assertTrue(SwingUtilities.isEventDispatchThread());
        panel.setSize(width, height);
        for (int pass = 0; pass < 4; pass++) layoutTree(panel);
        JComponent heading = requireNamed(panel, prefix + "Heading", JComponent.class);
        JScrollPane workspace = requireNamed(panel, prefix + "WorkspaceScroll", JScrollPane.class);
        JScrollPane list = requireNamed(panel, prefix + "ListScroll", JScrollPane.class);
        JScrollPane details = requireNamed(panel, prefix + "DetailsScroll", JScrollPane.class);
        JComponent status = requireNamed(panel, prefix + "Status", JComponent.class);
        Rectangle headingBounds = SwingUtilities.convertRectangle(heading.getParent(), heading.getBounds(), panel);
        Rectangle workspaceBounds = SwingUtilities.convertRectangle(workspace.getParent(), workspace.getBounds(), panel);
        Rectangle statusBounds = SwingUtilities.convertRectangle(status.getParent(), status.getBounds(), panel);
        assertTrue(headingBounds.width >= width - 2, "The heading must span the page, not form a left column");
        assertTrue(headingBounds.y + headingBounds.height <= workspaceBounds.y, "Heading is above the workspace");
        assertTrue(workspaceBounds.width <= width, "Only the workspace viewport may scroll horizontally");
        assertTrue(workspaceBounds.height > 120, "The catalog must not collapse into a narrow vertical strip");
        assertTrue(workspaceBounds.y + workspaceBounds.height <= statusBounds.y, "Status remains below the workspace");
        assertTrue(statusBounds.y + statusBounds.height <= height, "Status remains reachable at compact heights");
        assertEquals(list.getY(), details.getY(), "The list and details must remain side by side");
        assertTrue(list.getX() + list.getWidth() <= details.getX());
        assertTrue(list.getWidth() >= 100);
        assertTrue(details.getWidth() >= 280);
        assertFalse(list.isOpaque());
        assertFalse(details.isOpaque());
        assertFalse(list.getViewport().isOpaque());
        assertFalse(details.getViewport().isOpaque());
        assertEquals(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, details.getVerticalScrollBarPolicy());
        if (width < 600) assertTrue(workspace.getHorizontalScrollBar().isVisible());
    }

    /// Finds a required named descendant without assuming component nesting.
    ///
    /// @param root component hierarchy root
    /// @param name stable component name
    /// @param type expected component type
    /// @param <T> expected component type
    /// @return matching component
    public static <T extends JComponent> T requireNamed(Container root, String name, Class<T> type) {
        @Nullable T component = findNamed(root, name, type);
        assertNotNull(component, name);
        return component;
    }

    /// Writes an offscreen component preview; this does not exercise a real launcher window.
    ///
    /// @param panel laid-out management page
    /// @param fileName report file name
    /// @throws IOException when the report cannot be written
    public static void writePreview(JComponent panel, String fileName) throws IOException {
        BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(245, 246, 249));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            panel.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        Path path = Path.of("build", "reports", "catalog-presentation", fileName);
        Files.createDirectories(path.getParent());
        assertTrue(ImageIO.write(image, "png", path.toFile()));
    }

    /// Recursively allocates descendants after each ancestor's layout.
    ///
    /// @param root hierarchy to allocate
    private static void layoutTree(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) {
            if (child instanceof Container container) layoutTree(container);
        }
    }

    /// Finds a named descendant or returns null when absent.
    ///
    /// @param root component hierarchy root
    /// @param name stable component name
    /// @param type expected component type
    /// @param <T> expected component type
    /// @return matching descendant, or null
    private static <T extends JComponent> @Nullable T findNamed(Container root, String name, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) return type.cast(component);
            if (component instanceof Container container) {
                @Nullable T match = findNamed(container, name, type);
                if (match != null) return match;
            }
        }
        return null;
    }
}

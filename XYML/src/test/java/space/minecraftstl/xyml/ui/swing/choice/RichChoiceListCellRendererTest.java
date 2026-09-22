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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JList;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies rich sparse-row metadata, state badges, icons, and responsive clipping.
@NotNullByDefault
public final class RichChoiceListCellRendererTest {
    /// A loaded row exposes every supplied presentation channel and respects the list width.
    @Test
    public void rendersLoadedMetadataAndClipsToAllocatedWidth() {
        Icon icon = new TestIcon();
        RichChoiceListCellRenderer<String> renderer = new RichChoiceListCellRenderer<>(
                value -> value,
                value -> "Description and metadata that should be clipped",
                value -> "Enabled",
                value -> icon,
                value -> "full tooltip");
        JList<ChoiceListEntry<String>> list = new JList<>();
        list.setSize(new Dimension(180, RichChoiceListCellRenderer.ROW_HEIGHT));

        EdtDispatcher.executeAndWait(() -> {
            Component component = renderer.getListCellRendererComponent(
                    list,
                    ChoiceListEntry.loaded(0, "Mod name"),
                    0,
                    false,
                    false);
            assertEquals(RichChoiceListCellRenderer.ROW_HEIGHT, component.getPreferredSize().height);
            assertSame(icon, label(renderer, "richChoiceListIcon").getIcon());
            assertTrue(label(renderer, "richChoiceListPrimary").getText().startsWith("M"));
            assertTrue(label(renderer, "richChoiceListBadge").getText().startsWith("E"));
            assertTrue(label(renderer, "richChoiceListSecondary").getText().length() < 50);
            assertNotNull(renderer.getToolTipText());
            assertEquals("Mod name", renderer.getAccessibleContext().getAccessibleName());
            assertEquals("Enabled", label(renderer, "richChoiceListBadge")
                    .getAccessibleContext().getAccessibleName());
            assertEquals("full tooltip", renderer.getAccessibleContext().getAccessibleDescription());
        });
    }

    /// Loading and error placeholders keep the same row geometry as loaded values.
    @Test
    public void keepsStableHeightForLoadingAndErrorStates() {
        RichChoiceListCellRenderer<String> renderer = new RichChoiceListCellRenderer<>(
                value -> value,
                value -> "detail",
                value -> "state",
                value -> new TestIcon(),
                value -> "tooltip");
        JList<ChoiceListEntry<String>> list = new JList<>();
        EdtDispatcher.executeAndWait(() -> {
            Component loading = renderer.getListCellRendererComponent(
                    list,
                    ChoiceListEntry.loading(0),
                    0,
                    false,
                    false);
            Component failed = renderer.getListCellRendererComponent(
                    list,
                    ChoiceListEntry.failed(0, new IllegalStateException("fixture")),
                    0,
                    false,
                    false);
            assertEquals(RichChoiceListCellRenderer.ROW_HEIGHT, loading.getPreferredSize().height);
            assertEquals(RichChoiceListCellRenderer.ROW_HEIGHT, failed.getPreferredSize().height);
        });
    }

    /// Keeps the enabled-state badge visible and all direct children inside a narrow row.
    @Test
    public void keepsBadgeVisibleWithoutChildOverflowAtNarrowWidths() {
        RichChoiceListCellRenderer<String> renderer = new RichChoiceListCellRenderer<>(
                value -> "A long mod name",
                value -> "A long description that should yield to the state badge",
                value -> "Enabled",
                value -> new TestIcon(),
                value -> "tooltip");
        JList<ChoiceListEntry<String>> list = new JList<>();
        EdtDispatcher.executeAndWait(() -> {
            for (int width : new int[]{1, 2, 4, 8, 16, 24, 32, 40, 48, 64, 80, 96, 120}) {
                list.setSize(new Dimension(width, RichChoiceListCellRenderer.ROW_HEIGHT));
                renderer.getListCellRendererComponent(
                        list,
                        ChoiceListEntry.loaded(0, "mod"),
                        0,
                        false,
                        false);
                if (width >= 32) {
                    assertTrue(label(renderer, "richChoiceListBadge").getText().startsWith("E"));
                }
                assertChildrenInsideRow(renderer);
            }
        });
    }

    /// Keeps a disabled-state badge visible when the row is rendered in a narrow list.
    @Test
    public void rendersDisabledBadgeAtNarrowWidth() {
        RichChoiceListCellRenderer<String> renderer = new RichChoiceListCellRenderer<>(
                value -> "Disabled mod",
                value -> "disabled metadata",
                value -> "Disabled",
                value -> new TestIcon(),
                value -> "tooltip");
        JList<ChoiceListEntry<String>> list = new JList<>();
        list.setSize(new Dimension(48, RichChoiceListCellRenderer.ROW_HEIGHT));
        EdtDispatcher.executeAndWait(() -> {
            renderer.getListCellRendererComponent(
                    list,
                    ChoiceListEntry.loaded(0, "disabled"),
                    0,
                    false,
                    false);
            assertTrue(label(renderer, "richChoiceListBadge").getText().startsWith("D"));
            assertChildrenInsideRow(renderer);
        });
    }

    /// Paints disabled rows with a surface-relative contrast overlay.
    @Test
    public void paintsDisabledSurfaceWithThemeContrastOverlay() {
        RichChoiceListCellRenderer<String> renderer = new RichChoiceListCellRenderer<>(
                value -> "Disabled mod",
                value -> "disabled metadata",
                value -> "",
                value -> new TestIcon(),
                value -> "tooltip",
                value -> true);
        JList<ChoiceListEntry<String>> list = new JList<>();
        list.setSize(new Dimension(240, RichChoiceListCellRenderer.ROW_HEIGHT));
        EdtDispatcher.executeAndWait(() -> {
            Color light = paintRowBackground(renderer, list, Color.WHITE, false);
            Color dark = paintRowBackground(renderer, list, Color.BLACK, false);
            Color selected = paintRowBackground(renderer, list, Color.WHITE, true);
            assertAll(
                    () -> assertFalse(renderer.isOpaque()),
                    () -> assertTrue(light.getRed() < Color.WHITE.getRed()),
                    () -> assertTrue(dark.getRed() > Color.BLACK.getRed()),
                    () -> assertEquals(list.getSelectionBackground(), selected));
        });
    }

    /// Paints one reusable row over a surface and samples an unobstructed background pixel.
    ///
    /// @param renderer reusable row renderer
    /// @param list owning selection list
    /// @param surfaceColor surface beneath the transparent row
    /// @param selected whether the row uses the list selection surface
    /// @return sampled painted color
    private static Color paintRowBackground(
            RichChoiceListCellRenderer<String> renderer,
            JList<ChoiceListEntry<String>> list,
            Color surfaceColor,
            boolean selected) {
        list.setBackground(surfaceColor);
        renderer.getListCellRendererComponent(
                list,
                ChoiceListEntry.loaded(0, "disabled"),
                0,
                selected,
                false);
        BufferedImage image = new BufferedImage(
                renderer.getWidth(),
                renderer.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(surfaceColor);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            renderer.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return new Color(image.getRGB(image.getWidth() - 4, image.getHeight() / 2), true);
    }

    /// Reserves a full-width localized disabled glyph before shrinking the icon slot.
    @Test
    public void rendersWideLocalizedDisabledBadgeAtExtremeWidth() {
        RichChoiceListCellRenderer<String> renderer = new RichChoiceListCellRenderer<>(
                value -> "\u6A21\u7EC4",
                value -> "\u8BE6\u7EC6\u4FE1\u606F",
                value -> "\u5DF2\u7981\u7528",
                value -> new TestIcon(),
                value -> "tooltip");
        JList<ChoiceListEntry<String>> list = new JList<>();
        list.setSize(new Dimension(32, RichChoiceListCellRenderer.ROW_HEIGHT));
        EdtDispatcher.executeAndWait(() -> {
            renderer.getListCellRendererComponent(
                    list,
                    ChoiceListEntry.loaded(0, "disabled"),
                    0,
                    false,
                    false);
            assertTrue(label(renderer, "richChoiceListBadge").getText().startsWith("\u5DF2"));
            assertChildrenInsideRow(renderer);
        });
    }

    /// Finds one named label in the reusable renderer hierarchy.
    ///
    /// @param root renderer root
    /// @param name deterministic child name
    /// @return named label
    private static JLabel label(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && name.equals(label.getName())) {
                return label;
            }
            if (child instanceof Container container) {
                JLabel found = findLabel(container, name);
                if (found != null) {
                    return found;
                }
            }
        }
        throw new AssertionError("Missing label: " + name);
    }

    /// Recursively finds a named label or returns null when absent.
    ///
    /// @param root current container
    /// @param name target name
    /// @return matching label, or null
    private static @Nullable JLabel findLabel(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && name.equals(label.getName())) {
                return label;
            }
            if (child instanceof Container container) {
                JLabel found = findLabel(container, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /// Verifies that direct renderer children do not escape the allocated row bounds.
    ///
    /// @param renderer configured rich renderer
    private static void assertChildrenInsideRow(RichChoiceListCellRenderer<?> renderer) {
        Rectangle row = new Rectangle(0, 0, renderer.getWidth(), renderer.getHeight());
        for (Component child : renderer.getComponents()) {
            Rectangle bounds = child.getBounds();
            assertTrue(bounds.x >= row.x
                            && bounds.y >= row.y
                            && bounds.width >= 0
                            && bounds.height >= 0
                            && bounds.x + bounds.width <= row.x + row.width
                            && bounds.y + bounds.height <= row.y + row.height,
                    () -> child.getName() + " escaped row bounds: " + child.getBounds() + " / " + row);
        }
    }

    /// Minimal deterministic icon used by the headless renderer test.
    @NotNullByDefault
    private static final class TestIcon implements Icon {
        /// Returns the fixed icon width.
        ///
        /// @return icon width in pixels
        @Override
        public int getIconWidth() {
            return 16;
        }

        /// Returns the fixed icon height.
        ///
        /// @return icon height in pixels
        @Override
        public int getIconHeight() {
            return 16;
        }

        /// Leaves the test icon transparent because only identity is under test.
        ///
        /// @param component ignored icon host
        /// @param graphics destination graphics
        /// @param x horizontal origin
        /// @param y vertical origin
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            graphics.drawLine(x, y, x, y);
        }
    }
}

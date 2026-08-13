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
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the shared policy against FlatLaf painting rather than only Swing opacity flags.
@NotNullByDefault
public final class SwingTransparencyTest {
    /// Distinct opaque color used to detect whether a component painted over its parent.
    private static final Color BACKDROP = new Color(0x19, 0xB7, 0x6B);

    /// Tab strips expose the prepainted wallpaper under both production palettes.
    @Test
    public void leavesFlatLafTabAreaAndContentBoundaryTransparent() {
        EdtDispatcher.executeAndWait(() -> {
            for (boolean dark : List.of(false, true)) {
                assertTrue(dark ? FlatDarkLaf.setup() : FlatLightLaf.setup());
                JTabbedPane tabs = new JTabbedPane();
                JPanel content = new JPanel();
                content.setOpaque(false);
                tabs.addTab("General", content);
                SwingTransparency.revealBackgroundThroughTabs(tabs);
                tabs.setSize(360, 180);
                tabs.doLayout();

                BufferedImage rendered = renderOnBackdrop(tabs, 360, 180);
                assertAll(
                        () -> assertFalse(tabs.isOpaque()),
                        () -> assertEquals(
                                FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED,
                                tabs.getClientProperty(FlatClientProperties.TABBED_PANE_TAB_TYPE)),
                        () -> assertEquals(
                                Boolean.FALSE,
                                tabs.getClientProperty(FlatClientProperties.TABBED_PANE_SHOW_CONTENT_SEPARATOR)),
                        () -> assertEquals(
                                Map.of("tabsOpaque", false),
                                tabs.getClientProperty(FlatClientProperties.STYLE)),
                        () -> assertEquals(BACKDROP.getRGB(), rendered.getRGB(340, 12)),
                        () -> assertEquals(BACKDROP.getRGB(), rendered.getRGB(340, 120)));
            }
            FlatLightLaf.setup();
        });
    }

    /// Scroll policy changes only the layout container and leaves an editor's readability surface intact.
    @Test
    public void leavesScrollableViewOpacityUnderCallerControl() {
        EdtDispatcher.executeAndWait(() -> {
            assertTrue(FlatLightLaf.setup());
            JTextArea editor = new JTextArea("editable content");
            JScrollPane scrollPane = new JScrollPane(editor);

            SwingTransparency.revealBackgroundThroughScrollPane(scrollPane);

            assertAll(
                    () -> assertFalse(scrollPane.isOpaque()),
                    () -> assertFalse(scrollPane.getViewport().isOpaque()),
                    () -> assertEquals(
                            Map.of("arc", 0),
                            scrollPane.getClientProperty(FlatClientProperties.STYLE)),
                    () -> assertTrue(editor.isOpaque()));
        });
    }

    /// Transparent scroll panes suppress FlatLaf's rounded view-background fill under a nonzero global radius.
    @Test
    public void keepsLayoutScrollPaneTransparentAtPositiveGlobalRadius() {
        EdtDispatcher.executeAndWait(() -> {
            assertTrue(FlatLightLaf.setup());
            try {
                UIManager.put("ScrollPane.arc", 40);
                JPanel content = new JPanel();
                content.setOpaque(false);
                JScrollPane scrollPane = new JScrollPane(content);
                SwingTransparency.revealBackgroundThroughScrollPane(scrollPane);
                scrollPane.setSize(360, 180);
                scrollPane.doLayout();

                BufferedImage rendered = renderOnBackdrop(scrollPane, 360, 180);
                assertAll(
                        () -> assertEquals(
                                Map.of("arc", 0),
                                scrollPane.getClientProperty(FlatClientProperties.STYLE)),
                        () -> assertEquals(BACKDROP.getRGB(), rendered.getRGB(180, 90)));
            } finally {
                FlatLightLaf.setup();
            }
        });
    }

    /// Paints one component over a recognizable color without involving a native window.
    ///
    /// @param component configured Swing surface
    /// @param width render width
    /// @param height render height
    /// @return rendered pixels
    private static BufferedImage renderOnBackdrop(JComponent component, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(BACKDROP);
            graphics.fillRect(0, 0, width, height);
            component.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}

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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.MotionPolicy;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingDesignTokens;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.SystemThemeDetector;
import space.minecraftstl.xyml.ui.swing.ThemeMode;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies lazy navigation, stable bounds, accessibility, and fixed-size headless rendering.
@NotNullByDefault
public final class AppShellPanelTest {
    /// Fixed screenshot width matching the shell's preferred width.
    private static final int RENDER_WIDTH = AppShellPanel.PREFERRED_WIDTH;

    /// Fixed screenshot height matching the shell's preferred height.
    private static final int RENDER_HEIGHT = AppShellPanel.PREFERRED_HEIGHT;

    /// Navigation reuses a destination component and keeps all buttons keyboard reachable.
    @Test
    public void navigatesAndCreatesEachPageOnce() {
        EnumMap<ShellPageId, AtomicInteger> creationCounts = creationCounts();
        AppShellPanel panel = createPanel(creationCounts);
        AtomicReference<@Nullable JComponent> firstInstancesPage = new AtomicReference<>();
        AtomicReference<@Nullable JComponent> secondInstancesPage = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            panel.navigateTo(ShellPageId.INSTANCES);
            firstInstancesPage.set(panel.activePage());
            panel.navigateTo(ShellPageId.HOME);
            panel.navigationButton(ShellPageId.INSTANCES).doClick();
            secondInstancesPage.set(panel.activePage());
        });

        assertAll(
                () -> assertEquals(ShellPageId.INSTANCES, panel.selectedPage()),
                () -> assertSame(firstInstancesPage.get(), secondInstancesPage.get()),
                () -> assertEquals(1, creationCounts.get(ShellPageId.HOME).get()),
                () -> assertEquals(1, creationCounts.get(ShellPageId.INSTANCES).get()),
                () -> assertEquals(2, panel.cachedPageCount()),
                () -> assertFalse(panel.isPageCached(ShellPageId.DOWNLOADS)));

        for (ShellPageId page : ShellPageId.values()) {
            ShellNavigationButton button = panel.navigationButton(page);
            ShellPagePresentation presentation = ShellPagePresentations.englishFallback().get(page);
            FlatSVGIcon icon = assertInstanceOf(FlatSVGIcon.class, button.getIcon());
            assertAll(
                    () -> assertTrue(button.isFocusable()),
                    () -> assertEquals(presentation.mnemonic(), button.getMnemonic()),
                    () -> assertEquals(presentation.label(), button.getAccessibleContext().getAccessibleName()),
                    () -> assertTrue(icon.hasFound(), page + " navigation SVG was not found"));
        }
    }

    /// The generic file-tool slot stays hidden until configured and remains keyboard accessible.
    @Test
    public void configuresDiscoverableFileToolCommand() {
        AppShellPanel panel = createPanel(creationCounts());
        AtomicInteger invocations = new AtomicInteger();

        EdtDispatcher.executeAndWait(() -> {
            assertFalse(panel.fileToolButton().isVisible());
            panel.configureFileTool("Open NBT file", invocations::incrementAndGet);
            JButton button = panel.fileToolButton();
            FlatSVGIcon icon = assertInstanceOf(FlatSVGIcon.class, button.getIcon());
            assertAll(
                    () -> assertTrue(button.isVisible()),
                    () -> assertTrue(button.isFocusable()),
                    () -> assertTrue(icon.hasFound()),
                    () -> assertEquals("Open NBT file", button.getText()),
                    () -> assertEquals("Open NBT file",
                            button.getAccessibleContext().getAccessibleName()));
            button.doClick();
        });

        assertEquals(1, invocations.get());
    }

    /// The preferred layout uses normal Swing painting, produces varied pixels, and keeps navigation text bounded.
    @Test
    public void rendersFixedSizeShellWithoutTextOverflow() throws IOException {
        AppShellPanel panel = createPanel(creationCounts());
        BufferedImage image = new BufferedImage(RENDER_WIDTH, RENDER_HEIGHT, BufferedImage.TYPE_INT_ARGB);

        EdtDispatcher.executeAndWait(() -> {
            panel.setSize(new Dimension(RENDER_WIDTH, RENDER_HEIGHT));
            layoutTree(panel);
            Graphics2D graphics = image.createGraphics();
            try {
                panel.paint(graphics);
            } finally {
                graphics.dispose();
            }
        });

        long opaquePixels = countOpaquePixels(image);
        Set<Integer> sampledColors = sampledColors(image);
        assertAll(
                () -> assertEquals(RENDER_WIDTH, panel.getWidth()),
                () -> assertEquals(RENDER_HEIGHT, panel.getHeight()),
                () -> assertTrue(opaquePixels > (long) RENDER_WIDTH * RENDER_HEIGHT * 9 / 10),
                () -> assertTrue(sampledColors.size() >= 8));

        for (ShellPageId page : ShellPageId.values()) {
            ShellNavigationButton button = panel.navigationButton(page);
            FontMetrics metrics = button.getFontMetrics(button.getFont());
            int requiredWidth = button.getInsets().left
                    + button.getIcon().getIconWidth()
                    + button.getIconTextGap()
                    + metrics.stringWidth(button.getText())
                    + button.getInsets().right;
            assertTrue(requiredWidth <= button.getWidth(), page + " navigation text exceeds its stable button width");
        }

        Path report = Path.of("build", "reports", "swing-shell", "app-shell.png").toAbsolutePath();
        Files.createDirectories(report.getParent());
        assertTrue(ImageIO.write(image, "png", report.toFile()));
    }

    /// Closing the shell releases only cached page resources once and rejects later navigation.
    @Test
    public void closesCachedPagesExactlyOnce() {
        AtomicInteger closes = new AtomicInteger();
        EnumMap<ShellPageId, ShellPageFactory<? extends JComponent>> factories =
                new EnumMap<>(ShellPageId.class);
        for (ShellPageId page : ShellPageId.values()) {
            factories.put(page, () -> new CloseablePanel(closes));
        }
        AtomicReference<@Nullable AppShellPanel> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(new AppShellPanel(
                factories,
                ShellPageId.HOME,
                ShellPagePresentations.englishFallback(),
                new SwingAnimator(MotionPolicy.OFF, 16),
                Duration.ZERO)));
        AppShellPanel panel = java.util.Objects.requireNonNull(result.get());

        EdtDispatcher.executeAndWait(() -> {
            panel.navigateTo(ShellPageId.INSTANCES);
            panel.setTransferHandler(new ShellFileDropHandler(path -> true, ignored -> { }));
            panel.close();
            panel.close();
            assertEquals(0, panel.cachedPageCount());
            assertNull(panel.getTransferHandler());
            assertThrows(IllegalStateException.class, () -> panel.navigateTo(ShellPageId.SETTINGS));
        });

        assertEquals(2, closes.get());
    }

    /// Creates and initializes a light FlatLaf shell with motion disabled for deterministic testing.
    ///
    /// @param creationCounts mutable factory call counters
    /// @return the shell panel created on the EDT
    private static AppShellPanel createPanel(EnumMap<ShellPageId, AtomicInteger> creationCounts) {
        AtomicReference<@Nullable AppShellPanel> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            SwingThemeManager themeManager = new SwingThemeManager(
                    ThemeMode.LIGHT,
                    new SwingDesignTokens(8),
                    SystemThemeDetector.lightFallback());
            themeManager.initialize();
            result.set(new AppShellPanel(
                    pageFactories(creationCounts),
                    ShellPageId.HOME,
                    ShellPagePresentations.englishFallback(),
                    new SwingAnimator(MotionPolicy.OFF, 16),
                    Duration.ofMillis(180)));
        });
        return java.util.Objects.requireNonNull(result.get());
    }

    /// Creates one factory counter for every destination.
    ///
    /// @return complete zero-valued counters
    private static EnumMap<ShellPageId, AtomicInteger> creationCounts() {
        EnumMap<ShellPageId, AtomicInteger> counts = new EnumMap<>(ShellPageId.class);
        for (ShellPageId page : ShellPageId.values()) {
            counts.put(page, new AtomicInteger());
        }
        return counts;
    }

    /// Creates complete lazy sample-page factories for shell tests and the rendered artifact.
    ///
    /// @param creationCounts counters incremented by the corresponding factory
    /// @return one factory for every destination
    private static EnumMap<ShellPageId, ShellPageFactory<? extends JComponent>> pageFactories(
            EnumMap<ShellPageId, AtomicInteger> creationCounts) {
        EnumMap<ShellPageId, ShellPageFactory<? extends JComponent>> factories =
                new EnumMap<>(ShellPageId.class);
        for (ShellPageId page : ShellPageId.values()) {
            factories.put(page, () -> {
                creationCounts.get(page).incrementAndGet();
                return samplePage(page);
            });
        }
        return factories;
    }

    /// Creates a compact operational page used only by layout rendering tests.
    ///
    /// @param page the represented destination
    /// @return an unframed page with stable controls and rows
    private static JComponent samplePage(ShellPageId page) {
        JPanel pagePanel = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 1",
                "[grow,fill]",
                "[]16[]18[grow,fill]"));
        JLabel heading = new JLabel(ShellPagePresentations.englishFallback().get(page).label());
        heading.setFont(heading.getFont().deriveFont(24.0f));

        JPanel toolbar = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", "[]"));
        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Search");
        JButton action = new JButton(page == ShellPageId.INSTANCES ? "Add" : "Open");
        toolbar.add(search, "wmin 180");
        toolbar.add(action);

        JPanel rows = new JPanel(new MigLayout("insets 0, fillx, wrap 1", "[grow,fill]", "[]10[]10[]"));
        rows.add(sampleRow("Minecraft 1.21", "Fabric"), "growx, h 68!");
        rows.add(sampleRow("Creative World", "Local"), "growx, h 68!");
        rows.add(sampleRow("Modded Profile", "Ready"), "growx, h 68!");

        pagePanel.add(heading);
        pagePanel.add(toolbar, "growx");
        pagePanel.add(new JScrollPane(rows), "grow");
        return pagePanel;
    }

    /// Creates one bounded sample row for the screenshot fixture.
    ///
    /// @param title row title
    /// @param status short row status
    /// @return the un-nested row panel
    private static JComponent sampleRow(String title, String status) {
        JPanel row = new JPanel(new MigLayout("insets 10 14, fill", "[grow][]", "[grow,fill]"));
        row.add(new JLabel(title));
        row.add(new JLabel(status));
        return row;
    }

    /// Recursively lays out a non-displayable Swing tree after assigning its fixed test size.
    ///
    /// @param container the tree root to lay out
    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container childContainer) {
                layoutTree(childContainer);
            }
        }
    }

    /// Counts non-transparent pixels in a rendered image.
    ///
    /// @param image the rendered shell image
    /// @return the number of pixels with non-zero alpha
    private static long countOpaquePixels(BufferedImage image) {
        long count = 0L;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    /// Samples rendered RGB values on a regular grid to detect blank or one-color output.
    ///
    /// @param image the rendered shell image
    /// @return distinct sampled RGB values
    private static Set<Integer> sampledColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y += 8) {
            for (int x = 0; x < image.getWidth(); x += 8) {
                colors.add(image.getRGB(x, y));
            }
        }
        return colors;
    }

    /// Closeable page panel used to verify shell-owned resource cleanup.
    @NotNullByDefault
    private static final class CloseablePanel extends JPanel implements AutoCloseable {
        /// Shared close invocation counter.
        private final AtomicInteger closes;

        /// Creates one closeable page panel.
        ///
        /// @param closes shared close counter
        private CloseablePanel(AtomicInteger closes) {
            this.closes = closes;
        }

        /// Records one shell-owned close invocation.
        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}

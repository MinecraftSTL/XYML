// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.platform.hardware.CentralProcessor;
import space.minecraftstl.xyml.util.platform.hardware.GraphicsCard;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Exercises environment resource mapping and real Swing icon rendering with synthetic hardware.
@NotNullByDefault
public final class AboutPanelEnvironmentIconTest {
    /// Pages owned by each test, including pages created before a failed assertion.
    private final List<AboutPanel> panels = new ArrayList<>();

    /// Original application theme restored after every test outcome.
    private @Nullable LookAndFeel originalLookAndFeel;

    /// Captures the current theme before installing deterministic FlatLaf palettes.
    @BeforeEach
    public void rememberTheme() {
        EdtDispatcher.executeAndWait(() -> originalLookAndFeel = UIManager.getLookAndFeel());
    }

    /// Closes owned pages and restores global Swing state on the EDT.
    @AfterEach
    public void restoreTheme() {
        EdtDispatcher.executeAndWait(() -> {
            panels.forEach(AboutPanel::close);
            try {
                UIManager.setLookAndFeel(Objects.requireNonNull(originalLookAndFeel));
            } catch (javax.swing.UnsupportedLookAndFeelException exception) {
                throw new IllegalStateException("Cannot restore the original theme", exception);
            }
        });
    }

    /// Each supported environment kind keeps its text, order, and bundled single-SVG declaration.
    @Test
    public void mapsFiveEnvironmentKindsToBundledIcons() {
        @Unmodifiable List<AboutPanel.AboutEntry> rows = sampleEnvironment();
        assertEquals(List.of("app-window", "memory-stick", "cpu", "gpu", "local-cafe"),
                rows.stream().map(row -> Objects.requireNonNull(row.lightImageResource())
                        .replace("/assets/swing/icons/", "").replace(".svg", "")).toList());
        assertEquals(List.of(i18n("about.environment.system"), i18n("about.environment.memory"),
                        i18n("about.environment.cpu"), i18n("about.environment.gpu"), "Java"),
                rows.stream().map(AboutPanel.AboutEntry::title).toList());
        assertEquals("Test OS", rows.get(0).subtitle());
        assertEquals("16.0 GiB", rows.get(1).subtitle());
        assertEquals("Test GPU", rows.get(3).subtitle());
        assertEquals("Test Java", rows.get(4).subtitle());
        assertTrue(rows.stream().allMatch(row -> row.darkImageResource() == null && row.externalLink() == null));
        assertTrue(rows.stream().allMatch(row -> AboutPanel.hasClasspathResource(
                Objects.requireNonNull(row.lightImageResource()))));
        assertThrows(UnsupportedOperationException.class, () -> rows.add(rows.get(0)));
    }

    /// Repeated processors and visible adapters retain icons and consecutive numbering after filtering.
    @Test
    public void mapsEverySocketAndRenumberedAdapter() {
        CentralProcessor cpu = new CentralProcessor.Builder().setName("Dual CPU")
                .setCores(new CentralProcessor.Cores(16, 32, 2)).build();
        GraphicsCard virtual = GraphicsCard.builder().setName("Virtual").setVirtualDisplayAdapter(true).build();
        @Unmodifiable List<AboutPanel.AboutEntry> rows = AboutPanel.environmentEntries("OS", 0, cpu,
                List.of(virtual, GraphicsCard.builder().setName("First GPU").build(), virtual,
                        GraphicsCard.builder().setName("Second GPU").build()), "Java");
        assertEquals(6, rows.size());
        assertEquals(List.of(i18n("about.environment.cpu.n", 1), i18n("about.environment.cpu.n", 2),
                        i18n("about.environment.gpu.n", 1), i18n("about.environment.gpu.n", 2)),
                rows.subList(1, 5).stream().map(AboutPanel.AboutEntry::title).toList());
        assertEquals(List.of("cpu.svg", "cpu.svg", "gpu.svg", "gpu.svg"), rows.subList(1, 5).stream()
                .map(row -> Objects.requireNonNull(row.lightImageResource()).replace("/assets/swing/icons/", ""))
                .toList());
        EdtDispatcher.executeAndWait(() -> {
            AboutPanel panel = create(rows);
            for (AboutPanel.AboutEntry row : rows)
                assertVisibleSvg(iconLabel(panel, row.title()));
        });
    }

    /// Unavailable memory, absent hardware, and fully filtered adapters never fabricate environment rows.
    @Test
    public void omitsUnavailableHardwareAndUnknownMemory() {
        GraphicsCard virtual = GraphicsCard.builder().setVirtualDisplayAdapter(true).build();
        for (long memory : new long[] {0, -1}) {
            @Unmodifiable List<AboutPanel.AboutEntry> rows = AboutPanel.environmentEntries(
                    "OS", memory, null, List.of(virtual), "Java");
            assertEquals(List.of(i18n("about.environment.system"), "Java"),
                    rows.stream().map(AboutPanel.AboutEntry::title).toList());
        }
        assertEquals(2, AboutPanel.environmentEntries("OS", 0, null, null, "Java").size());
        assertEquals(2, AboutPanel.environmentEntries("OS", 0, null, List.of(), "Java").size());
        CentralProcessor unknown = new CentralProcessor.Builder().setName("Unknown topology").build();
        @Unmodifiable List<AboutPanel.AboutEntry> rows = AboutPanel.environmentEntries("OS", 0, unknown, null, "Java");
        assertEquals("Unknown topology", rows.get(1).subtitle());
        assertEquals("/assets/swing/icons/cpu.svg", rows.get(1).lightImageResource());
    }

    /// Real environment controls remain visible and aligned while the same SVG instances change theme palette.
    @Test
    public void rendersEnvironmentInLightAndDarkThemes() {
        EdtDispatcher.executeAndWait(() -> {
            assertTrue(FlatLightLaf.setup());
            @Unmodifiable List<AboutPanel.AboutEntry> rows = sampleEnvironment();
            AboutPanel panel = create(rows);
            @Unmodifiable List<JLabel> labels = rows.stream().map(row -> iconLabel(panel, row.title())).toList();
            @Unmodifiable List<Icon> icons = labels.stream().map(label -> Objects.requireNonNull(label.getIcon())).toList();
            @Unmodifiable List<Integer> lightPixels = labels.stream().map(label -> pixelHash(paintIcon(label))).toList();
            renderEnvironment(panel, rows, "environment-light.png");
            assertTrue(FlatDarkLaf.setup());
            SwingUtilities.updateComponentTreeUI(panel);
            renderEnvironment(panel, rows, "environment-dark.png");
            for (int index = 0; index < labels.size(); index++) {
                JLabel label = labels.get(index);
                assertSame(icons.get(index), label.getIcon());
                assertNotEquals(lightPixels.get(index), pixelHash(paintIcon(label)), "Theme palette must change");
            }
        });
    }

    /// Single PNGs and existing light/dark raster pairs keep their original theme behavior.
    @Test
    public void preservesSingleAndThemePairedRasterIcons() {
        EdtDispatcher.executeAndWait(() -> {
            assertTrue(FlatLightLaf.setup());
            AboutPanel panel = create(List.of(
                    new AboutPanel.AboutEntry("/assets/img/github.png", null, "Single PNG", "single", null),
                    new AboutPanel.AboutEntry("/assets/img/github.png", "/assets/img/github-white.png",
                            "Paired PNG", "paired", null)));
            JLabel single = iconLabel(panel, "Single PNG");
            JLabel paired = iconLabel(panel, "Paired PNG");
            assertInstanceOf(ImageIcon.class, single.getIcon());
            assertFalse(paired.getIcon() instanceof FlatSVGIcon);
            int singleLight = pixelHash(paintIcon(single));
            int pairedLight = pixelHash(paintIcon(paired));
            assertTrue(visiblePixelCount(paintIcon(single), Color.WHITE) > 20);
            assertTrue(FlatDarkLaf.setup());
            SwingUtilities.updateComponentTreeUI(panel);
            assertEquals(singleLight, pixelHash(paintIcon(single)));
            assertNotEquals(pairedLight, pixelHash(paintIcon(paired)));
            assertTrue(visiblePixelCount(paintIcon(paired), Color.BLACK) > 20);
        });
    }

    /// Missing or malformed SVG resources preserve both text lines and use transparent placeholders.
    @Test
    public void fallsBackForMissingAndMalformedSvg() {
        EdtDispatcher.executeAndWait(() -> {
            @Unmodifiable List<AboutPanel.AboutEntry> rows = List.of(
                    new AboutPanel.AboutEntry("/assets/swing/icons/does-not-exist.svg", null,
                            "Missing SVG", "still readable", null),
                    new AboutPanel.AboutEntry("/assets/swing/icons/invalid-about-environment.svg", null,
                            "Invalid SVG", "also readable", null));
            AboutPanel panel = create(rows);
            for (AboutPanel.AboutEntry entry : rows) {
                JLabel label = iconLabel(panel, entry.title());
                assertInstanceOf(ImageIcon.class, label.getIcon());
                assertEquals(0, visiblePixelCount(paintIcon(label), Color.WHITE));
                assertTrue(Arrays.stream(label.getParent().getComponents()).anyMatch(component ->
                        component instanceof JTextArea text && text.getText().equals(entry.subtitle())));
            }
        });
    }

    /// A supplier failure leaves a usable page rather than exposing partial environment icons.
    @Test
    public void handlesFailedEnvironmentSupplier() {
        EdtDispatcher.executeAndWait(() -> {
            AboutPanel panel = new AboutPanel(uri -> { }, Runnable::run, () -> {
                throw new IllegalStateException("Synthetic environment failure");
            });
            panels.add(panel);
            assertTrue(panel.environmentSnapshot().isEmpty());
            assertTrue(labels(panel).stream().anyMatch(label -> i18n("about.environment.failed").equals(label.getText())));
        });
    }

    /// Creates deterministic environment data without reading host hardware.
    ///
    /// @return immutable rows covering all five environment kinds
    private static @Unmodifiable List<AboutPanel.AboutEntry> sampleEnvironment() {
        return AboutPanel.environmentEntries("Test OS", 16L * 1024 * 1024 * 1024,
                new CentralProcessor.Builder().setName("Test CPU").setCores(new CentralProcessor.Cores(8, 16, 1)).build(),
                List.of(GraphicsCard.builder().setName("Test GPU").build()), "Test Java");
    }

    /// Constructs and registers a page on its caller's EDT with completed synthetic environment work.
    ///
    /// @param rows synthetic environment rows
    /// @return page owned by this test
    private AboutPanel create(@Unmodifiable List<AboutPanel.AboutEntry> rows) {
        EdtDispatcher.requireEventDispatchThread();
        AboutPanel panel = new AboutPanel(uri -> { }, Runnable::run, () -> rows);
        panels.add(panel);
        assertEquals(rows, panel.environmentSnapshot());
        return panel;
    }

    /// Finds the icon beside an environment title in the rendered component hierarchy.
    ///
    /// @param panel constructed page
    /// @param title exact localized row title
    /// @return icon label from the same row
    private static JLabel iconLabel(AboutPanel panel, String title) {
        EdtDispatcher.requireEventDispatchThread();
        JLabel heading = labels(panel).stream().filter(label -> "aboutEntryTitle".equals(label.getName())
                && title.equals(label.getText())).findFirst().orElseThrow();
        return labels(heading.getParent()).stream().filter(label -> "aboutEntryIcon".equals(label.getName()))
                .findFirst().orElseThrow();
    }

    /// Collects labels recursively without retaining a mutable view of the hierarchy.
    ///
    /// @param root component container
    /// @return immutable label snapshot
    private static @Unmodifiable List<JLabel> labels(Container root) {
        List<JLabel> result = new ArrayList<>();
        for (Component component : root.getComponents()) {
            if (component instanceof JLabel label)
                result.add(label);
            if (component instanceof Container child)
                result.addAll(labels(child));
        }
        return List.copyOf(result);
    }

    /// Checks actual vector loading and opaque, contrasting glyph pixels rather than page background pixels.
    ///
    /// @param label real page icon control
    private static void assertVisibleSvg(JLabel label) {
        FlatSVGIcon icon = assertInstanceOf(FlatSVGIcon.class, label.getIcon());
        assertTrue(icon.hasFound());
        assertEquals(32, icon.getIconWidth());
        assertEquals(32, icon.getIconHeight());
        assertTrue(visiblePixelCount(paintIcon(label), Objects.requireNonNull(UIManager.getColor("Panel.background"))) > 20);
    }

    /// Paints only one icon onto a transparent image on the EDT.
    ///
    /// @param label component used for icon theme lookup
    /// @return painted 32-pixel icon
    private static BufferedImage paintIcon(JLabel label) {
        EdtDispatcher.requireEventDispatchThread();
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            Objects.requireNonNull(label.getIcon()).paintIcon(label, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /// Counts opaque glyph pixels with meaningful contrast against the selected theme background.
    ///
    /// @param image transparent icon rendering
    /// @param background target surface color
    /// @return count of visible contrasting pixels
    private static int visiblePixelCount(BufferedImage image, Color background) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                Color pixel = new Color(image.getRGB(x, y), true);
                int contrast = Math.max(Math.abs(pixel.getRed() - background.getRed()),
                        Math.max(Math.abs(pixel.getGreen() - background.getGreen()),
                                Math.abs(pixel.getBlue() - background.getBlue())));
                if (pixel.getAlpha() >= 128 && contrast > 45)
                    count++;
            }
        }
        return count;
    }

    /// Returns a stable fingerprint including the alpha channel for raster and palette comparisons.
    ///
    /// @param image icon rendering
    /// @return ARGB pixel hash
    private static int pixelHash(BufferedImage image) {
        return Arrays.hashCode(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
    }

    /// Renders a complete page and saves its environment region after validating common icon and text columns.
    ///
    /// @param panel synthetic page
    /// @param rows expected environment rows
    /// @param filename screenshot file name within the ignored build report directory
    private static void renderEnvironment(AboutPanel panel, @Unmodifiable List<AboutPanel.AboutEntry> rows, String filename) {
        JPanel stage = new JPanel(new BorderLayout());
        stage.add(panel, BorderLayout.CENTER);
        stage.setSize(980, Math.max(3400, stage.getPreferredSize().height));
        layoutTree(stage);
        @Nullable Integer textX = null;
        for (AboutPanel.AboutEntry entry : rows) {
            JLabel label = iconLabel(panel, entry.title());
            assertVisibleSvg(label);
            assertEquals(32, label.getWidth());
            assertEquals(32, label.getHeight());
            JLabel title = labels(label.getParent()).stream()
                    .filter(value -> "aboutEntryTitle".equals(value.getName())).findFirst().orElseThrow();
            if (textX == null)
                textX = title.getX();
            assertEquals(textX.intValue(), title.getX());
            assertTrue(title.getX() >= label.getX() + label.getWidth());
        }
        BufferedImage image = new BufferedImage(stage.getWidth(), stage.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            stage.paint(graphics);
        } finally {
            graphics.dispose();
        }
        Container environment = iconLabel(panel, rows.get(0).title()).getParent().getParent();
        Rectangle bounds = SwingUtilities.convertRectangle(environment, new Rectangle(environment.getSize()), stage);
        assertTrue(bounds.width > 0 && bounds.height > 0);
        Path report = Path.of("build", "reports", "swing-about", filename);
        try {
            Files.createDirectories(Objects.requireNonNull(report.getParent()));
            assertTrue(ImageIO.write(image.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height), "png", report.toFile()));
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot save environment screenshot", exception);
        }
        stage.remove(panel);
    }

    /// Lays out all descendants without a display peer or timing assumptions.
    ///
    /// @param root container and descendants to lay out
    private static void layoutTree(Container root) {
        root.doLayout();
        for (Component component : root.getComponents()) {
            if (component instanceof Container child)
                layoutTree(child);
        }
    }
}

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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Verifies that the Swing About page restores the legacy bundled acknowledgement resources.
@NotNullByDefault
public final class AboutPanelTest {
    /// The acknowledgement list is read in JSON order with localized titles and intentional non-links preserved.
    @Test
    public void loadsLegacyAcknowledgementsInBundledOrder() {
        @Unmodifiable List<AboutPanel.AboutEntry> acknowledgements =
                AboutPanel.loadListResource(AboutPanel.THANKS_RESOURCE);

        assertAll(
                () -> assertEquals(16, acknowledgements.size()),
                () -> assertEquals(expectedAcknowledgementTitles(), acknowledgements.stream()
                        .map(AboutPanel.AboutEntry::title)
                        .toList()),
                () -> assertNull(acknowledgements.get(10).externalLink()),
                () -> assertEquals(
                        URI.create("https://github.com/mcmod-info-mirror"),
                        acknowledgements.get(11).externalLink()),
                () -> assertTrue(allConfiguredImagesExist(acknowledgements)));
    }

    /// The dependency list is restored beside the acknowledgement list and remains resource-driven.
    @Test
    public void loadsLegacyDependenciesInBundledOrder() {
        @Unmodifiable List<AboutPanel.AboutEntry> dependencies =
                AboutPanel.loadListResource(AboutPanel.DEPENDENCIES_RESOURCE);

        assertAll(
                () -> assertEquals(15, dependencies.size()),
                () -> assertEquals("Gson", dependencies.get(0).title()),
                () -> assertEquals("uuid-tools", dependencies.get(14).title()),
                () -> assertNotNull(dependencies.get(0).externalLink()),
                () -> assertTrue(allConfiguredImagesExist(dependencies)));
    }

    /// The concrete panel exposes all four legacy sections while keeping external opening delegated to settings.
    @Test
    public void createsCompleteGroupedPanel() {
        AtomicReference<@Nullable URI> opened = new AtomicReference<>();
        AtomicReference<@Nullable AboutPanel> panelRef = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> panelRef.set(new AboutPanel(opened::set)));

        AboutPanel panel = Objects.requireNonNull(panelRef.get());
        assertAll(
                () -> assertEquals(8, panel.getComponentCount()),
                () -> assertEquals(16, panel.acknowledgements().size()),
                () -> assertEquals(15, panel.dependencies().size()),
                () -> assertFalse(panel.isOpaque()),
                () -> assertNull(opened.get()));
    }

    /// The rendered About page keeps the legacy grouped layout visible.
    @Test
    public void rendersLegacyGroupedAboutPage() {
        AtomicReference<@Nullable AboutPanel> panelRef = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> panelRef.set(new AboutPanel(uri -> { })));

        AboutPanel panel = Objects.requireNonNull(panelRef.get());
        JPanel stage = new JPanel(new BorderLayout());
        stage.setBorder(BorderFactory.createEmptyBorder());
        stage.setBackground(Color.WHITE);
        stage.setOpaque(true);
        stage.add(panel, BorderLayout.CENTER);

        EdtDispatcher.executeAndWait(() -> {
            Dimension preferred = stage.getPreferredSize();
            stage.setSize(new Dimension(980, Math.max(3400, preferred.height)));
            layoutTree(stage);
            BufferedImage image = new BufferedImage(stage.getWidth(), stage.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                stage.paint(graphics);
            } finally {
                graphics.dispose();
            }

            long opaquePixels = countOpaquePixels(image);
            assertTrue(opaquePixels > (long) image.getWidth() * image.getHeight() / 8);

            Path report = Path.of("build", "reports", "swing-about", "about-panel.png").toAbsolutePath();
            try {
                Files.createDirectories(Objects.requireNonNull(report.getParent()));
                assertTrue(ImageIO.write(image, "png", report.toFile()));
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to write about screenshot", exception);
            }
        });
    }

    /// Returns the expected localized acknowledgement titles in the JSON resource order.
    ///
    /// @return immutable expected title order
    private static @Unmodifiable List<String> expectedAcknowledgementTitles() {
        return List.of(
                "yushijinhun",
                "bangbang93",
                "Glavo",
                "ZekerZhayard",
                "Zkitefly",
                "Burning_TNT",
                "ShulkerSakura",
                "gamerteam",
                "Red_lnn",
                i18n("about.thanks_to.mcmod"),
                i18n("about.thanks_to.mcbbs"),
                i18n("about.thanks_to.mcim"),
                i18n("about.thanks_to.8mi-tech"),
                i18n("about.thanks_to.contributors"),
                "IMMC\u6210\u5458",
                i18n("about.thanks_to.users"));
    }

    /// Returns whether every configured local image resource can be loaded from the classpath.
    ///
    /// @param entries rows to inspect
    /// @return whether all image resources exist
    private static boolean allConfiguredImagesExist(@Unmodifiable List<AboutPanel.AboutEntry> entries) {
        for (AboutPanel.AboutEntry entry : entries) {
            if (!imageExists(entry.lightImageResource()) || !imageExists(entry.darkImageResource())) {
                return false;
            }
        }
        return true;
    }

    /// Returns whether an optional image resource exists when configured.
    ///
    /// @param resourcePath optional classpath resource path
    /// @return whether absent or present on the classpath
    private static boolean imageExists(@Nullable String resourcePath) {
        return resourcePath == null || AboutPanel.hasClasspathResource(resourcePath);
    }

    /// Lays out a container tree from the root down.
    ///
    /// @param root container to layout
    private static void layoutTree(Container root) {
        root.doLayout();
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof Container childContainer) {
                layoutTree(childContainer);
            }
        }
    }

    /// Counts every opaque pixel in a rendered screenshot.
    ///
    /// @param image rendered image
    /// @return number of non-transparent pixels
    private static long countOpaquePixels(BufferedImage image) {
        long opaquePixels = 0L;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) != 0) {
                    opaquePixels++;
                }
            }
        }
        return opaquePixels;
    }
}

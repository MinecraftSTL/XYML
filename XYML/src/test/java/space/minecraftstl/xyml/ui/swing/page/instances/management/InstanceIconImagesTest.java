// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.setting.GameInstanceIconType;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/// Verifies exact-size Swing adaptation of the shared bounded instance icon loader.
@NotNullByDefault
final class InstanceIconImagesTest {
    /// Temporary custom image directory.
    @TempDir
    private @Nullable Path temporaryDirectory;

    /// Preloads bundled pixels on the test worker before any EDT-owned chooser construction.
    @BeforeAll
    static void preloadBundledPixels() {
        InstanceIconImages.preloadBuiltIns();
    }

    /// Adapts every bundled choice at the exact 40-pixel overview size.
    @Test
    void loadsEveryBundledChoiceAtExactPreviewSize() {
        for (GameInstanceIconType iconType : InstanceIconChooserDialog.builtInTypes()) {
            ImageIcon icon = InstanceIconImages.loadBuiltIn(iconType, 40);
            assertEquals(40, icon.getIconWidth());
            assertEquals(40, icon.getIconHeight());
        }
    }

    /// Preserves transparent padding produced when the bounded loader fits a non-square custom image.
    @Test
    void fitsCustomImageInsideTransparentSquare() throws IOException {
        Path imagePath = temporaryDirectory().resolve("wide.png");
        BufferedImage source = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = source.createGraphics();
        try {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        } finally {
            graphics.dispose();
        }
        ImageIO.write(source, "PNG", imagePath.toFile());

        ImageIcon icon = InstanceIconImages.load(
                new InstanceIconStore.Snapshot(GameInstanceIconType.FORGE, imagePath),
                40);
        BufferedImage rendered = assertInstanceOf(BufferedImage.class, icon.getImage());
        assertEquals(40, rendered.getWidth());
        assertEquals(40, rendered.getHeight());
        assertEquals(0, new Color(rendered.getRGB(20, 2), true).getAlpha());
        assertEquals(Color.RED.getRGB(), rendered.getRGB(20, 20));
    }

    /// Adapts the shared loader's mandatory default fallback when custom content is corrupt.
    @Test
    void corruptCustomImageFallsBackToBundledDefault() throws IOException {
        Path corruptImage = temporaryDirectory().resolve("broken.png");
        Files.writeString(corruptImage, "not an image");

        ImageIcon fallback = InstanceIconImages.load(
                new InstanceIconStore.Snapshot(GameInstanceIconType.FORGE, corruptImage),
                40);
        ImageIcon expected = InstanceIconImages.loadBuiltIn(GameInstanceIconType.DEFAULT, 40);
        assertSamePixels(expected, fallback);
    }

    /// Compares every exact-size ARGB pixel in two Swing icons.
    ///
    /// @param expected expected image
    /// @param actual actual image
    private static void assertSamePixels(ImageIcon expected, ImageIcon actual) {
        BufferedImage expectedImage = assertInstanceOf(BufferedImage.class, expected.getImage());
        BufferedImage actualImage = assertInstanceOf(BufferedImage.class, actual.getImage());
        assertEquals(expectedImage.getWidth(), actualImage.getWidth());
        assertEquals(expectedImage.getHeight(), actualImage.getHeight());
        for (int y = 0; y < expectedImage.getHeight(); y++) {
            for (int x = 0; x < expectedImage.getWidth(); x++) {
                assertEquals(expectedImage.getRGB(x, y), actualImage.getRGB(x, y));
            }
        }
    }

    /// Returns the JUnit-injected temporary directory after lifecycle initialization.
    ///
    /// @return injected temporary directory
    private Path temporaryDirectory() {
        return java.util.Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
    }
}

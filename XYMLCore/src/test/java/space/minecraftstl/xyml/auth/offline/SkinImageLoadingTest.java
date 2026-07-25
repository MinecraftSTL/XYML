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
package space.minecraftstl.xyml.auth.offline;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.auth.yggdrasil.TextureModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies bundled and local offline skins load through JDK image APIs in a headless workflow.
@NotNullByDefault
final class SkinImageLoadingTest {
    /// Temporary directory for local skin and cape fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// A bundled source resolves its expected model and 64-by-64 image from the Core classpath.
    @Test
    void loadsBundledSkinWithoutJavaFx() throws Exception {
        Skin skin = new Skin(Skin.Type.ALEX, null, null, null, null);

        Skin.LoadedSkin loaded = Objects.requireNonNull(skin.load("Player").run());

        assertEquals(TextureModel.SLIM, loaded.model());
        Texture texture = Objects.requireNonNull(loaded.skin());
        assertEquals(64, texture.image().getWidth());
        assertEquals(64, texture.image().getHeight());
        assertNull(loaded.cape());
    }

    /// Local skin and cape files are independently decoded and retain the configured arm model.
    @Test
    void loadsLocalSkinAndCapeWithImageIo() throws Exception {
        Path skinFile = temporaryDirectory.resolve("skin.png");
        Path capeFile = temporaryDirectory.resolve("cape.png");
        writeFixture(skinFile, 64, 64, 0xFF123456);
        writeFixture(capeFile, 64, 32, 0xFF654321);
        Skin skin = new Skin(
                Skin.Type.LOCAL_FILE,
                null,
                TextureModel.WIDE,
                skinFile.toString(),
                capeFile.toString());

        Skin.LoadedSkin loaded = Objects.requireNonNull(skin.load("Player").run());

        assertEquals(TextureModel.WIDE, loaded.model());
        assertEquals(0xFF123456, Objects.requireNonNull(loaded.skin()).image().getRGB(3, 4));
        assertEquals(0xFF654321, Objects.requireNonNull(loaded.cape()).image().getRGB(3, 4));
    }

    /// The default source continues to signal that the caller should select a UUID-based skin.
    @Test
    void leavesDefaultSkinSelectionToCaller() throws Exception {
        Skin skin = new Skin(Skin.Type.DEFAULT, null, null, null, null);
        assertNull(skin.load("Player").run());
    }

    /// Writes one solid PNG fixture.
    ///
    /// @param path output path
    /// @param width image width
    /// @param height image height
    /// @param argb solid pixel value
    private static void writeFixture(Path path, int width, int height, int argb) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        assertTrue(ImageIO.write(image, "PNG", path.toFile()));
    }
}

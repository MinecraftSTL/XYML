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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.auth.offline.Skin;
import space.minecraftstl.xyml.auth.yggdrasil.TextureModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests local image validation and persisted skin construction without any network or graphical toolkit.
@NotNullByDefault
public final class OfflineSkinServiceTest {
    /// Isolated filesystem root for selected skin fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// A readable local image is retained as a normalized absolute local-file skin configuration.
    @Test
    public void createsPersistedLocalSkinForDecodableImage() throws IOException {
        Path image = temporaryDirectory.resolve("skin.png");
        BufferedImage pixels = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        assertTrue(ImageIO.write(pixels, "PNG", image.toFile()));

        Skin skin = OfflineSkinService.createLocalSkin(image, TextureModel.SLIM);

        assertAll(
                () -> assertEquals(Skin.Type.LOCAL_FILE, skin.type()),
                () -> assertEquals(TextureModel.SLIM, skin.textureModel()),
                () -> assertEquals(image.toAbsolutePath().normalize().toString(), skin.localSkinPath()),
                () -> assertNull(skin.localCapePath()));
    }

    /// A non-image file is rejected before it can be written into offline-account metadata.
    @Test
    public void rejectsUndecodableSelectedFile() throws IOException {
        Path invalid = temporaryDirectory.resolve("not-a-skin.png");
        Files.writeString(invalid, "not an image");

        assertThrows(
                IOException.class,
                () -> OfflineSkinService.createLocalSkin(invalid, TextureModel.WIDE));
    }

    /// A validated optional cape is retained alongside the normalized local skin path.
    @Test
    public void createsLocalSkinWithOptionalCape() throws IOException {
        Path skinFile = writePng("skin.png", 64, 64);
        Path capeFile = writePng("cape.png", 64, 32);

        Skin skin = OfflineSkinService.createLocalSkin(skinFile, capeFile, TextureModel.WIDE);

        assertAll(
                () -> assertEquals(skinFile.toAbsolutePath().normalize().toString(), skin.localSkinPath()),
                () -> assertEquals(capeFile.toAbsolutePath().normalize().toString(), skin.localCapePath()),
                () -> assertEquals(TextureModel.WIDE, skin.textureModel()));
    }

    /// A decodable image of another format is rejected even when its filename ends in PNG.
    @Test
    public void rejectsRenamedNonPngImage() throws IOException {
        Path renamedJpeg = temporaryDirectory.resolve("renamed.png");
        BufferedImage pixels = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(pixels, "JPEG", renamedJpeg.toFile()));

        assertThrows(
                IOException.class,
                () -> OfflineSkinService.createLocalSkin(renamedJpeg, TextureModel.WIDE));
    }

    /// Provider creation accepts HTTPS-defaulted hosts and preserves the user's trimmed endpoint.
    @Test
    public void validatesCustomProviderWithoutNetworkAccess() {
        Skin littleSkin = OfflineSkinService.createProviderSkin(Skin.Type.LITTLE_SKIN, null);
        Skin custom = OfflineSkinService.createProviderSkin(
                Skin.Type.CUSTOM_SKIN_LOADER_API,
                "  skins.example.test/csl  ");

        assertAll(
                () -> assertEquals(Skin.Type.LITTLE_SKIN, littleSkin.type()),
                () -> assertNull(littleSkin.cslApi()),
                () -> assertEquals(Skin.Type.CUSTOM_SKIN_LOADER_API, custom.type()),
                () -> assertEquals("skins.example.test/csl", custom.cslApi()),
                () -> assertNull(OfflineSkinService.normalizeProviderAddress("ftp://skins.example.test")),
                () -> assertNull(OfflineSkinService.normalizeProviderAddress("https://user@skins.example.test")),
                () -> assertNull(OfflineSkinService.normalizeProviderAddress("https://skins.example.test?a=b")));
    }

    /// Every selectable bundled source receives an exact no-network persisted configuration.
    @Test
    public void createsBundledSkinConfiguration() {
        Skin alex = OfflineSkinService.createBundledSkin(Skin.Type.ALEX);

        assertAll(
                () -> assertEquals(Skin.Type.ALEX, alex.type()),
                () -> assertNull(alex.cslApi()),
                () -> assertNull(alex.localSkinPath()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> OfflineSkinService.createBundledSkin(Skin.Type.LOCAL_FILE)),
                () -> assertNotNull(OfflineSkinService.normalizeProviderAddress("https://skins.example.test/csl")));
    }

    /// Writes one PNG fixture into the isolated test directory.
    ///
    /// @param name fixture filename
    /// @param width image width
    /// @param height image height
    /// @return written fixture path
    /// @throws IOException when the image cannot be written
    private Path writePng(String name, int width, int height) throws IOException {
        Path image = temporaryDirectory.resolve(name);
        BufferedImage pixels = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        assertTrue(ImageIO.write(pixels, "PNG", image.toFile()));
        return image;
    }
}

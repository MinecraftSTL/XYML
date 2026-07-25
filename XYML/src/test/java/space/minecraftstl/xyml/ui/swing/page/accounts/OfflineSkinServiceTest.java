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
}

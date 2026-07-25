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
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.auth.offline.Skin;
import space.minecraftstl.xyml.auth.yggdrasil.TextureModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/// Validates local skin files and creates the exact persisted [Skin] configuration for them.
///
/// The service intentionally performs no network access and does not copy the image. The selected path remains
/// the user-owned file path consumed by [Skin.Type#LOCAL_FILE] during game launch.
@NotNullByDefault
public final class OfflineSkinService {
    /// Prevents utility instantiation.
    private OfflineSkinService() {
    }

    /// Validates a decodable local image and constructs a persisted local-skin configuration.
    ///
    /// @param skinFile user-selected image path
    /// @param textureModel requested arm model
    /// @return skin configuration that references the normalized absolute local image path
    /// @throws IOException when the selected path is not a readable supported image file
    public static Skin createLocalSkin(Path skinFile, TextureModel textureModel) throws IOException {
        Objects.requireNonNull(skinFile, "skinFile");
        Objects.requireNonNull(textureModel, "textureModel");
        Path normalized = skinFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("Selected skin file does not exist: " + normalized);
        }

        try (InputStream input = Files.newInputStream(normalized)) {
            @Nullable BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IOException("Selected skin file is not a supported image: " + normalized);
            }
        }

        return new Skin(Skin.Type.LOCAL_FILE, null, textureModel, normalized.toString(), null);
    }
}

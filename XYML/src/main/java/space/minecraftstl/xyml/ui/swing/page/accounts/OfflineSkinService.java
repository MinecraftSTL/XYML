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
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;

/// Validates offline skin settings and creates their exact persisted [Skin] configurations.
///
/// The service intentionally performs no network access and does not copy local images. Selected paths remain
/// user-owned files consumed by [Skin.Type#LOCAL_FILE] during game launch.
@NotNullByDefault
public final class OfflineSkinService {
    /// Prevents utility instantiation.
    private OfflineSkinService() {
    }

    /// Validates a local PNG image and constructs a persisted local-skin configuration.
    ///
    /// @param skinFile user-selected image path
    /// @param textureModel requested arm model
    /// @return skin configuration that references the normalized absolute local image path
    /// @throws IOException when the selected path is not a readable PNG image
    public static Skin createLocalSkin(Path skinFile, TextureModel textureModel) throws IOException {
        return createLocalSkin(skinFile, null, textureModel);
    }

    /// Validates local skin and optional cape PNG files and constructs their persisted configuration.
    ///
    /// @param skinFile user-selected skin image path
    /// @param capeFile optional user-selected cape image path
    /// @param textureModel requested arm model
    /// @return skin configuration that references normalized absolute local image paths
    /// @throws IOException when either selected path is not a readable PNG image
    public static Skin createLocalSkin(
            Path skinFile,
            @Nullable Path capeFile,
            TextureModel textureModel) throws IOException {
        Objects.requireNonNull(skinFile, "skinFile");
        Objects.requireNonNull(textureModel, "textureModel");
        Path normalizedSkin = validatePng(skinFile);
        @Nullable Path normalizedCape = capeFile == null ? null : validatePng(capeFile);
        return new Skin(
                Skin.Type.LOCAL_FILE,
                null,
                textureModel,
                normalizedSkin.toString(),
                normalizedCape == null ? null : normalizedCape.toString());
    }

    /// Creates a persisted configuration for a bundled player skin.
    ///
    /// @param type bundled skin source
    /// @return persisted bundled-skin configuration
    /// @throws IllegalArgumentException when the source is not a bundled player skin
    public static Skin createBundledSkin(Skin.Type type) {
        Objects.requireNonNull(type, "type");
        if (!isBundledSkin(type)) {
            throw new IllegalArgumentException("Not a bundled skin source: " + type);
        }
        return new Skin(type, null, null, null, null);
    }

    /// Creates a persisted custom-skin-loader provider configuration.
    ///
    /// @param type LittleSkin or a user-specified custom-skin-loader source
    /// @param customApi custom endpoint text, required only for a custom source
    /// @return persisted provider configuration
    /// @throws IllegalArgumentException when the source or custom endpoint is invalid
    public static Skin createProviderSkin(Skin.Type type, @Nullable String customApi) {
        Objects.requireNonNull(type, "type");
        if (type == Skin.Type.LITTLE_SKIN) {
            return new Skin(type, null, null, null, null);
        }
        if (type != Skin.Type.CUSTOM_SKIN_LOADER_API) {
            throw new IllegalArgumentException("Not a custom-skin-loader source: " + type);
        }
        @Nullable String normalizedApi = normalizeProviderAddress(customApi);
        if (normalizedApi == null) {
            throw new IllegalArgumentException("Invalid custom-skin-loader endpoint");
        }
        return new Skin(type, normalizedApi, null, null, null);
    }

    /// Reports whether a source is backed by a launcher-bundled player texture.
    ///
    /// @param type skin source
    /// @return whether the source names one bundled player texture
    public static boolean isBundledSkin(Skin.Type type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case ALEX, ARI, EFE, KAI, MAKENA, NOOR, STEVE, SUNNY, ZURI -> true;
            default -> false;
        };
    }

    /// Validates and normalizes an optional custom-skin-loader endpoint.
    ///
    /// Missing schemes are interpreted as HTTPS, matching the launch-time loader. Only HTTP and HTTPS endpoints
    /// with a host are accepted; credentials, queries, and fragments are rejected because they are not endpoint roots.
    ///
    /// @param address user-entered endpoint text, or null
    /// @return trimmed endpoint text suitable for persistence, or null when invalid
    public static @Nullable String normalizeProviderAddress(@Nullable String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String trimmed = address.trim();
        String parseable = trimmed.contains("://") ? trimmed : "https://" + trimmed;
        try {
            URI uri = new URI(parseable);
            @Nullable String rawScheme = uri.getScheme();
            @Nullable String scheme = rawScheme == null ? null : rawScheme.toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                return null;
            }
            return trimmed;
        } catch (URISyntaxException | IllegalArgumentException failure) {
            return null;
        }
    }

    /// Validates one local PNG and returns its normalized absolute path.
    ///
    /// The decoder is intentionally opened through ImageIO's structured reader API so a renamed non-PNG image
    /// cannot be accepted merely because another installed decoder understands its bytes.
    ///
    /// @param file selected image path
    /// @return normalized absolute path
    /// @throws IOException when the path is missing, unreadable, non-PNG, or undecodable
    static Path validatePng(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        Path normalized = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("Selected image file does not exist: " + normalized);
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(normalized.toFile())) {
            if (input == null) {
                throw new IOException("Selected image file cannot be read: " + normalized);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Selected image file is not decodable: " + normalized);
            }
            ImageReader reader = readers.next();
            try {
                if (!"PNG".equalsIgnoreCase(reader.getFormatName())) {
                    throw new IOException("Selected image file is not PNG: " + normalized);
                }
                reader.setInput(input, true, true);
                @Nullable BufferedImage image = reader.read(0);
                if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                    throw new IOException("Selected PNG image is empty: " + normalized);
                }
            } finally {
                reader.dispose();
            }
        }
        return normalized;
    }
}

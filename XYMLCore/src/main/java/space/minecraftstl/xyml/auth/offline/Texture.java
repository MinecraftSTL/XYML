/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

/// One decoded offline-auth texture, addressed by a stable hash of its normalized pixels.
///
/// The hash format deliberately preserves the launcher's historical algorithm: image width and
/// height are followed by column-major ARGB pixels, and RGB data is cleared for fully transparent
/// pixels. Decoding uses only JDK APIs so authentication does not require a UI toolkit.
///
/// @param hash lowercase SHA-256 hash of the normalized dimensions and pixels
/// @param image decoded texture image
@NotNullByDefault
public record Texture(String hash, BufferedImage image) {
    /// Canonical texture instances indexed by their stable pixel hashes.
    private static final Map<String, Texture> TEXTURES = new ConcurrentHashMap<>();

    /// Validates a texture value before it enters the canonical cache.
    ///
    /// @param hash lowercase SHA-256 texture hash
    /// @param image decoded image represented by that hash
    public Texture(String hash, BufferedImage image) {
        this.hash = requireNonNull(hash, "hash");
        this.image = requireNonNull(image, "image");
    }

    /// Tests whether the process-local texture cache contains a hash.
    ///
    /// @param hash texture hash
    /// @return whether a matching texture is cached
    public static boolean hasTexture(String hash) {
        return TEXTURES.containsKey(requireNonNull(hash, "hash"));
    }

    /// Returns the cached texture for a hash.
    ///
    /// @param hash texture hash
    /// @return cached texture, or null when the hash is unknown
    public static @Nullable Texture getTexture(String hash) {
        return TEXTURES.get(requireNonNull(hash, "hash"));
    }

    /// Computes the historical dimension-and-pixel SHA-256 texture hash.
    ///
    /// @param image decoded image
    /// @return lowercase SHA-256 hash
    private static String computeTextureHash(BufferedImage image) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }

        int width = image.getWidth();
        int height = image.getHeight();
        byte[] buffer = new byte[4096];

        putInt(buffer, 0, width);
        putInt(buffer, 4, height);
        int position = 8;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                putInt(buffer, position, image.getRGB(x, y));
                if (buffer[position] == 0) {
                    buffer[position + 1] = 0;
                    buffer[position + 2] = 0;
                    buffer[position + 3] = 0;
                }
                position += Integer.BYTES;
                if (position == buffer.length) {
                    position = 0;
                    digest.update(buffer, 0, buffer.length);
                }
            }
        }
        if (position > 0) {
            digest.update(buffer, 0, position);
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    /// Stores one integer in network byte order.
    ///
    /// @param array destination byte array
    /// @param offset destination offset
    /// @param value integer value
    private static void putInt(byte[] array, int offset, int value) {
        array[offset] = (byte) (value >> 24 & 0xff);
        array[offset + 1] = (byte) (value >> 16 & 0xff);
        array[offset + 2] = (byte) (value >> 8 & 0xff);
        array[offset + 3] = (byte) (value & 0xff);
    }

    /// Decodes and caches a texture from an input stream, closing the stream after the attempt.
    ///
    /// @param input encoded image stream, or null when no texture is configured
    /// @return canonical decoded texture, or null for an absent stream
    /// @throws IOException when the stream does not contain a supported image
    public static @Nullable Texture loadTexture(@Nullable InputStream input) throws IOException {
        if (input == null) {
            return null;
        }

        @Nullable BufferedImage image;
        try (InputStream stream = input) {
            image = ImageIO.read(stream);
        }
        if (image == null) {
            throw new IOException("No supported image found");
        }
        return loadTexture(image);
    }

    /// Computes a texture's stable hash and returns its canonical process-local instance.
    ///
    /// @param image decoded image, or null when no texture is configured
    /// @return canonical texture, or null for an absent image
    public static @Nullable Texture loadTexture(@Nullable BufferedImage image) {
        if (image == null) {
            return null;
        }

        String hash = computeTextureHash(image);
        return TEXTURES.computeIfAbsent(hash, ignored -> new Texture(hash, image));
    }
}

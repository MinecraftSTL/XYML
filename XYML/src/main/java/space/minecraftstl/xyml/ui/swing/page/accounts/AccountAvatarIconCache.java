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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.auth.offline.Skin;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.util.io.NetworkUtils;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/// Lazily derives account head icons from detached online, offline, or launcher-bundled skin sources.
///
/// The first renderer request returns immediately. Network access, filesystem access, image decoding, and
/// nearest-neighbor head extraction run on the shared I/O scheduler, then only a repaint is posted to the EDT.
/// The process-wide future cache lets the accounts page and top selector share in-flight and completed work.
@NotNullByDefault
final class AccountAvatarIconCache {
    /// Fixed account avatar edge matching the stable row icon slot.
    static final int ICON_SIZE = 40;

    /// Maximum encoded online texture size accepted before decoding.
    private static final int MAX_REMOTE_TEXTURE_BYTES = 4 * 1024 * 1024;

    /// Maximum decoded skin edge accepted before head extraction.
    private static final int MAX_TEXTURE_EDGE = 4096;

    /// Process-wide in-flight and completed icon loads keyed by immutable presentation state.
    private static final ConcurrentMap<AvatarKey, CompletableFuture<Icon>> ICONS = new ConcurrentHashMap<>();

    /// Pending repaint callbacks already registered by this renderer cache.
    private final ConcurrentMap<AvatarKey, Boolean> pendingRepaints = new ConcurrentHashMap<>();

    /// Returns a completed icon or null while its detached source is still loading.
    ///
    /// @param item loaded account row
    /// @param list owning list repainted after asynchronous completion
    /// @return decoded icon, or null while loading
    @Nullable Icon iconFor(AccountListItem item, JList<?> list) {
        AccountListItem row = Objects.requireNonNull(item, "item");
        JList<?> owner = Objects.requireNonNull(list, "list");
        AvatarKey key = new AvatarKey(row.displayName(), row.profileId(), row.avatarSource());
        CompletableFuture<Icon> future = ICONS.computeIfAbsent(key, ignored ->
                CompletableFuture.supplyAsync(() -> loadIcon(key), Schedulers.io())
                        .exceptionally(AccountAvatarIconCache::failureIcon));
        if (!future.isDone() && pendingRepaints.putIfAbsent(key, Boolean.TRUE) == null) {
            future.whenComplete((@Nullable Icon ignoredIcon, @Nullable Throwable ignoredFailure) ->
                    SwingUtilities.invokeLater(() -> {
                        pendingRepaints.remove(key);
                        owner.repaint();
                    }));
        }
        return future.getNow(null);
    }

    /// Replaces an unexpected source-and-fallback failure with a stable non-null marker.
    ///
    /// @param failure avatar decoding failure
    /// @return fixed failure icon retained in the cache
    private static Icon failureIcon(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new java.awt.Color(180, 64, 64, 48));
            graphics.fillRoundRect(0, 0, ICON_SIZE, ICON_SIZE, 6, 6);
            graphics.setColor(new java.awt.Color(180, 64, 64));
            graphics.drawString("!", 18, 25);
        } finally {
            graphics.dispose();
        }
        return new ImageIcon(image);
    }

    /// Loads one detached skin source, falls back to the UUID-derived bundled skin, and extracts its head.
    ///
    /// @param key immutable profile identity
    /// @return crisp fixed-size head icon
    private static Icon loadIcon(AvatarKey key) {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Account avatars must not load on the EDT");
        }
        try {
            return createHeadIcon(loadSourceTexture(key));
        } catch (Exception sourceFailure) {
            try {
                return createHeadIcon(loadDefaultTexture(key));
            } catch (IOException | RuntimeException fallbackFailure) {
                fallbackFailure.addSuppressed(sourceFailure);
                throw new IllegalStateException("Failed to load account avatar and bundled fallback", fallbackFailure);
            }
        }
    }

    /// Loads one detached account texture without touching a live account object.
    ///
    /// @param key immutable avatar request
    /// @return decoded skin texture
    /// @throws Exception when the selected source cannot be fetched or decoded
    private static BufferedImage loadSourceTexture(AvatarKey key) throws Exception {
        AccountAvatarSource source = key.source();
        if (source instanceof AccountAvatarSource.RemoteSource remoteSource) {
            return loadRemoteTexture(remoteSource);
        }
        if (source instanceof AccountAvatarSource.OfflineSource offlineSource) {
            @Nullable Skin.LoadedSkin loaded = offlineSource.toSkin().load(key.profileName()).run();
            if (loaded == null || loaded.skin() == null) {
                throw new IOException("Configured offline skin did not provide a texture");
            }
            return loaded.skin().image();
        }
        return loadDefaultTexture(key);
    }

    /// Downloads and decodes one bounded public texture response.
    ///
    /// @param source validated remote texture source
    /// @return decoded skin image
    /// @throws IOException when the response is unavailable, oversized, or undecodable
    private static BufferedImage loadRemoteTexture(AccountAvatarSource.RemoteSource source) throws IOException {
        HttpURLConnection connection = NetworkUtils.resolveConnection(
                NetworkUtils.createHttpConnection(source.uri()));
        try {
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_REMOTE_TEXTURE_BYTES) {
                throw new IOException("Remote account texture exceeds the encoded size limit");
            }
            byte @Unmodifiable [] encoded;
            try (InputStream input = connection.getInputStream()) {
                encoded = input.readNBytes(MAX_REMOTE_TEXTURE_BYTES + 1);
            }
            if (encoded.length > MAX_REMOTE_TEXTURE_BYTES) {
                throw new IOException("Remote account texture exceeds the encoded size limit");
            }
            return decodeRemoteTexture(encoded);
        } finally {
            connection.disconnect();
        }
    }

    /// Checks remote image dimensions before allocating its complete decoded raster.
    ///
    /// @param encoded bounded remote response bytes
    /// @return decoded supported skin image
    /// @throws IOException when no reader accepts the image or dimensions exceed the avatar limit
    private static BufferedImage decodeRemoteTexture(byte @Unmodifiable [] encoded) throws IOException {
        try (@Nullable ImageInputStream imageInput = ImageIO.createImageInputStream(
                new ByteArrayInputStream(encoded))) {
            if (imageInput == null) {
                throw new IOException("Remote account texture is not a supported image");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new IOException("Remote account texture is not a supported image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateTextureDimensions(width, height);
                @Nullable BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IOException("Remote account texture could not be decoded");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    /// Loads the exact UUID-derived launcher-bundled fallback for one profile.
    ///
    /// @param key immutable profile identity
    /// @return decoded bundled texture
    /// @throws IOException when the packaged texture is missing or invalid
    private static BufferedImage loadDefaultTexture(AvatarKey key) throws IOException {
        return OfflineSkinPreviewLoader.load(null, key.profileName(), key.profileId()).skin();
    }

    /// Validates a Minecraft skin layout and extracts its base and hat head layers.
    ///
    /// @param texture decoded skin texture
    /// @return crisp fixed-size head icon
    /// @throws IOException when the texture dimensions are not a supported scaled skin layout
    private static Icon createHeadIcon(BufferedImage texture) throws IOException {
        int width = texture.getWidth();
        int height = texture.getHeight();
        validateTextureDimensions(width, height);
        int textureScale = width / 64;
        BufferedImage head = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = head.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            drawLayer(graphics, texture, 8, 8, textureScale);
            drawLayer(graphics, texture, 40, 8, textureScale);
        } finally {
            graphics.dispose();
        }
        return new ImageIcon(head);
    }

    /// Validates one decoded or reader-reported image against supported Minecraft skin layouts.
    ///
    /// @param width texture width
    /// @param height texture height
    /// @throws IOException when dimensions cannot represent a bounded scaled Minecraft skin
    private static void validateTextureDimensions(int width, int height) throws IOException {
        if (width <= 0
                || height <= 0
                || width > MAX_TEXTURE_EDGE
                || height > MAX_TEXTURE_EDGE
                || width % 64 != 0
                || !(width == height || width == height * 2)) {
            throw new IOException("Invalid account skin dimensions: " + width + "x" + height);
        }
    }

    /// Draws one canonical eight-pixel head layer into the fixed icon surface.
    ///
    /// @param graphics destination graphics
    /// @param texture decoded bundled skin
    /// @param canonicalX canonical source X coordinate
    /// @param canonicalY canonical source Y coordinate
    /// @param textureScale source pixels per canonical pixel
    private static void drawLayer(
            Graphics2D graphics,
            BufferedImage texture,
            int canonicalX,
            int canonicalY,
            int textureScale) {
        int sourceX = canonicalX * textureScale;
        int sourceY = canonicalY * textureScale;
        int sourceSize = 8 * textureScale;
        graphics.drawImage(
                texture,
                0,
                0,
                ICON_SIZE,
                ICON_SIZE,
                sourceX,
                sourceY,
                sourceX + sourceSize,
                sourceY + sourceSize,
                null);
    }

    /// Immutable key for one account avatar presentation state.
    ///
    /// @param profileName profile name used only if the profile ID is malformed
    /// @param profileId stable Minecraft profile UUID text
    /// @param source detached selected texture source
    @NotNullByDefault
    private record AvatarKey(String profileName, String profileId, AccountAvatarSource source) {
        /// Validates one immutable avatar key.
        private AvatarKey {
            Objects.requireNonNull(profileName, "profileName");
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(source, "source");
        }
    }
}

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
package space.minecraftstl.xyml.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.setting.GameInstanceIconType;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.AlphaComposite;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Objects;

/// Safely decodes bundled or custom instance icons into immutable toolkit-neutral pixel data.
///
/// All public loading methods perform classpath or filesystem I/O and image decoding. They must run on a caller-owned
/// background executor; an explicit event-dispatch-thread assertion prevents accidental UI stalls. Untrusted files
/// are bounded before decode, probed through an ImageIO reader for dimensions first, and never followed through a
/// symbolic link. A rejected custom source falls back to the mandatory bundled default icon. Successfully decoded
/// bundled icons are cached under a lock; custom files are intentionally decoded per request.
@NotNullByDefault
public final class InstanceIconLoader {
    /// Maximum encoded bytes accepted from a bundled or custom icon.
    public static final int MAXIMUM_ENCODED_BYTES = 4 * 1024 * 1024;

    /// Maximum width or height accepted from ImageIO header metadata before pixel decode.
    public static final int MAXIMUM_SOURCE_EDGE = 8_192;

    /// Maximum source pixels accepted from ImageIO header metadata before pixel decode.
    public static final long MAXIMUM_SOURCE_PIXELS = 16L * 1024L * 1024L;

    /// Lock serializing bundled resource decode and cache publication.
    private static final Object BUILT_IN_CACHE_LOCK = new Object();

    /// Successfully decoded immutable bundled icons indexed by requested icon type.
    private static final EnumMap<GameInstanceIconType, InstanceIconData> BUILT_IN_CACHE =
            new EnumMap<>(GameInstanceIconType.class);

    /// Prevents utility-class construction.
    private InstanceIconLoader() {
    }

    /// Loads a custom icon when supplied, otherwise loads the selected bundled icon.
    ///
    /// A missing, linked, non-regular, oversized, malformed, unsupported, or over-dimensioned custom file returns
    /// the bundled [GameInstanceIconType#DEFAULT] icon. A missing or invalid non-default bundled resource also falls back
    /// to that default. This blocking operation throws when called from the AWT event-dispatch thread.
    ///
    /// @param builtInIcon bundled icon used when no custom file is supplied
    /// @param customIconFile optional untrusted custom icon file
    /// @return immutable normalized icon data
    /// @throws IllegalStateException when called on the event-dispatch thread or the mandatory default is unavailable
    public static InstanceIconData load(
            GameInstanceIconType builtInIcon,
            @Nullable Path customIconFile) {
        requireBackgroundThread();
        GameInstanceIconType selectedBuiltIn = Objects.requireNonNull(builtInIcon, "builtInIcon");
        if (customIconFile == null) {
            return loadBuiltInUnchecked(selectedBuiltIn);
        }
        try {
            return decode(readCustomIcon(customIconFile));
        } catch (IOException | RuntimeException ignored) {
            return loadRequiredDefault();
        }
    }

    /// Loads one selected bundled icon using its higher-resolution `@2x` raster.
    ///
    /// An absent or invalid non-default resource falls back to [GameInstanceIconType#DEFAULT]. This blocking operation
    /// throws when called from the AWT event-dispatch thread.
    ///
    /// @param iconType selected bundled icon type
    /// @return immutable normalized icon data
    /// @throws IllegalStateException when called on the event-dispatch thread or the mandatory default is unavailable
    public static InstanceIconData loadBuiltIn(GameInstanceIconType iconType) {
        requireBackgroundThread();
        return loadBuiltInUnchecked(Objects.requireNonNull(iconType, "iconType"));
    }

    /// Derives the higher-resolution bundled raster path for one configured icon type.
    ///
    /// This package-visible pure function supports resource coverage verification without performing I/O.
    ///
    /// @param iconType configured icon type
    /// @return matching `@2x` classpath resource path
    static String bundledResourcePath(GameInstanceIconType iconType) {
        String resourcePath = Objects.requireNonNull(iconType, "iconType").resourcePath();
        int extensionOffset = resourcePath.lastIndexOf('.');
        if (extensionOffset <= 0 || extensionOffset == resourcePath.length() - 1) {
            throw new IllegalArgumentException("Bundled icon path has no file extension: " + resourcePath);
        }
        return resourcePath.substring(0, extensionOffset)
                + "@2x"
                + resourcePath.substring(extensionOffset);
    }

    /// Loads one bundled icon and falls back to the mandatory default resource when necessary.
    ///
    /// @param iconType selected bundled icon type
    /// @return immutable normalized icon data
    private static InstanceIconData loadBuiltInUnchecked(GameInstanceIconType iconType) {
        synchronized (BUILT_IN_CACHE_LOCK) {
            @Nullable InstanceIconData cached = BUILT_IN_CACHE.get(iconType);
            if (cached != null) {
                return cached;
            }

            InstanceIconData loaded;
            try {
                loaded = decode(readBundledIcon(iconType));
            } catch (IOException | RuntimeException failure) {
                if (iconType == GameInstanceIconType.DEFAULT) {
                    throw new IllegalStateException("Bundled default instance icon is unavailable", failure);
                }
                loaded = loadRequiredDefault();
            }
            BUILT_IN_CACHE.put(iconType, loaded);
            return loaded;
        }
    }

    /// Loads the mandatory bundled default or reports a broken application package.
    ///
    /// @return immutable normalized default icon
    /// @throws IllegalStateException when the mandatory resource is missing or invalid
    private static InstanceIconData loadRequiredDefault() {
        synchronized (BUILT_IN_CACHE_LOCK) {
            @Nullable InstanceIconData cached = BUILT_IN_CACHE.get(GameInstanceIconType.DEFAULT);
            if (cached != null) {
                return cached;
            }
            try {
                InstanceIconData loaded = decode(readBundledIcon(GameInstanceIconType.DEFAULT));
                BUILT_IN_CACHE.put(GameInstanceIconType.DEFAULT, loaded);
                return loaded;
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException("Bundled default instance icon is unavailable", failure);
            }
        }
    }

    /// Reads one bounded classpath icon resource.
    ///
    /// @param iconType selected bundled icon type
    /// @return bounded encoded image
    /// @throws IOException when the resource is missing, empty, oversized, or unreadable
    private static EncodedImage readBundledIcon(GameInstanceIconType iconType) throws IOException {
        String resourcePath = bundledResourcePath(iconType);
        try (@Nullable InputStream input = InstanceIconLoader.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new FileNotFoundException("Missing bundled instance icon " + resourcePath);
            }
            return EncodedImage.read(input, MAXIMUM_ENCODED_BYTES);
        }
    }

    /// Reads one untrusted regular file without following symbolic links and with a hard byte ceiling.
    ///
    /// The metadata size rejects obviously oversized content before allocation. The bounded stream read repeats the
    /// actual-byte check so file replacement or growth between metadata and transfer cannot bypass the ceiling.
    ///
    /// @param iconFile untrusted custom icon path
    /// @return bounded encoded image
    /// @throws IOException when the path is linked, non-regular, empty, oversized, replaced, or unreadable
    private static EncodedImage readCustomIcon(Path iconFile) throws IOException {
        Path selectedFile = Objects.requireNonNull(iconFile, "iconFile");
        BasicFileAttributes attributes = Files.readAttributes(
                selectedFile,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        validateCustomFileAttributes(
                attributes.isSymbolicLink(),
                attributes.isRegularFile(),
                attributes.size());

        try (InputStream input = Files.newInputStream(
                selectedFile,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            return EncodedImage.read(input, MAXIMUM_ENCODED_BYTES);
        }
    }

    /// Validates captured no-follow filesystem attributes before opening an untrusted icon.
    ///
    /// This package-visible pure validation keeps symbolic-link and exact byte-boundary behavior deterministic on
    /// platforms where the current account cannot create a symbolic-link fixture.
    ///
    /// @param symbolicLink whether the no-follow attributes identify a symbolic link
    /// @param regularFile whether the no-follow attributes identify a regular file
    /// @param encodedBytes advertised file byte count
    /// @throws IOException when the source is linked, non-regular, empty, or oversized
    static void validateCustomFileAttributes(
            boolean symbolicLink,
            boolean regularFile,
            long encodedBytes) throws IOException {
        if (symbolicLink || !regularFile) {
            throw new IOException("Custom instance icon must be a direct regular file");
        }
        if (encodedBytes <= 0L || encodedBytes > MAXIMUM_ENCODED_BYTES) {
            throw new IOException("Custom instance icon exceeds its encoded byte limit");
        }
    }

    /// Probes dimensions before decoding pixels, then normalizes the first image frame.
    ///
    /// @param encoded bounded encoded image
    /// @return immutable normalized icon data
    /// @throws IOException when no ImageIO reader exists or dimensions/content are invalid
    private static InstanceIconData decode(EncodedImage encoded) throws IOException {
        try (InputStream input = encoded.openStream();
             ImageInputStream imageInput = new MemoryCacheImageInputStream(input)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new IOException("Unsupported instance icon image format");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int sourceWidth = reader.getWidth(0);
                int sourceHeight = reader.getHeight(0);
                validateSourceDimensions(sourceWidth, sourceHeight);

                @Nullable BufferedImage source = reader.read(0);
                if (source == null) {
                    throw new IOException("ImageIO returned no instance icon pixels");
                }
                try {
                    validateSourceDimensions(source.getWidth(), source.getHeight());
                    return normalize(source);
                } finally {
                    source.flush();
                }
            } finally {
                reader.dispose();
            }
        }
    }

    /// Rejects invalid, excessively long, or excessive-area image dimensions before allocation-heavy decode.
    ///
    /// @param width reported source width
    /// @param height reported source height
    /// @throws IOException when either dimension violates the source safety bounds
    private static void validateSourceDimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0) {
            throw new IOException("Instance icon has invalid dimensions");
        }
        if (width > MAXIMUM_SOURCE_EDGE || height > MAXIMUM_SOURCE_EDGE) {
            throw new IOException("Instance icon exceeds its dimension limit");
        }
        long sourcePixels = (long) width * height;
        if (sourcePixels > MAXIMUM_SOURCE_PIXELS) {
            throw new IOException("Instance icon exceeds its pixel limit");
        }
    }

    /// Scales one decoded image proportionally into a centered transparent 40-by-40 ARGB canvas.
    ///
    /// @param source validated decoded image
    /// @return immutable normalized icon data
    private static InstanceIconData normalize(BufferedImage source) {
        double scale = Math.min(
                (double) InstanceIconData.WIDTH / source.getWidth(),
                (double) InstanceIconData.HEIGHT / source.getHeight());
        int scaledWidth = Math.min(
                InstanceIconData.WIDTH,
                Math.max(1, (int) Math.round(source.getWidth() * scale)));
        int scaledHeight = Math.min(
                InstanceIconData.HEIGHT,
                Math.max(1, (int) Math.round(source.getHeight() * scale)));
        int targetX = (InstanceIconData.WIDTH - scaledWidth) / 2;
        int targetY = (InstanceIconData.HEIGHT - scaledHeight) / 2;

        BufferedImage normalized = new BufferedImage(
                InstanceIconData.WIDTH,
                InstanceIconData.HEIGHT,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(
                    RenderingHints.KEY_ALPHA_INTERPOLATION,
                    RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(
                    source,
                    targetX,
                    targetY,
                    scaledWidth,
                    scaledHeight,
                    null);
        } finally {
            graphics.dispose();
        }

        try {
            int[] pixels = normalized.getRGB(
                    0,
                    0,
                    InstanceIconData.WIDTH,
                    InstanceIconData.HEIGHT,
                    null,
                    0,
                    InstanceIconData.WIDTH);
            return new InstanceIconData(pixels);
        } finally {
            normalized.flush();
        }
    }

    /// Rejects blocking resource, filesystem, and ImageIO work on the AWT event-dispatch thread.
    ///
    /// @throws IllegalStateException when invoked from the event-dispatch thread
    private static void requireBackgroundThread() {
        if (EventQueue.isDispatchThread()) {
            throw new IllegalStateException("Instance icon loading must run outside the event-dispatch thread");
        }
    }
}

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
package space.minecraftstl.xyml.ui.swing.page.instances.management.backups;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// Reads only a world's optional PNG thumbnail without constructing a World or extracting archive files.
@NotNullByDefault
final class WorldBackupThumbnailLoader {
    /// Shared backup row thumbnail edge in logical pixels.
    static final int ICON_SIZE = 40;

    /// Maximum uncompressed ZIP-entry bytes accepted for an encoded PNG.
    static final int MAX_ICON_BYTES = 2 * 1024 * 1024;

    /// Source edge limit matching the existing world icon decoder.
    private static final int MAX_SOURCE_EDGE = 8_192;

    /// Source pixel limit matching the existing world icon decoder.
    private static final long MAX_SOURCE_PIXELS = 16L * 1024L * 1024L;

    /// Prevents utility-class construction.
    private WorldBackupThumbnailLoader() {
    }

    /// Reads and owns one normalized image on a background worker, closing all ZIP handles before returning.
    ///
    /// @param archive immutable path and file metadata captured by the shallow catalog
    /// @return owned 40-by-40 image, or null for a missing, ambiguous, oversized, or stale thumbnail
    /// @throws IOException when the archive or encoded image cannot be read
    static @Nullable BufferedImage load(WorldBackupArchive archive) throws IOException {
        Objects.requireNonNull(archive, "archive");
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Backup thumbnails must not be read on the Swing EDT");
        }
        if (!metadataMatches(archive)) return null;
        @Nullable BufferedImage image;
        try (ZipFile zip = new ZipFile(archive.archive().toFile())) {
            @Nullable ZipEntry entry = findIcon(zip);
            if (entry == null || entry.isDirectory() || entry.getSize() < 0L || entry.getSize() > MAX_ICON_BYTES) {
                return null;
            }
            byte[] encoded;
            try (InputStream input = zip.getInputStream(entry)) {
                encoded = input.readNBytes(MAX_ICON_BYTES + 1);
                if (encoded.length > MAX_ICON_BYTES) return null;
            }
            image = decode(encoded);
        }
        if (image != null && !metadataMatches(archive)) {
            image.flush();
            return null;
        }
        return image;
    }

    /// Checks current metadata only on the worker so replaced archives cannot be published under stale cache keys.
    ///
    /// @param archive shallow catalog identity
    /// @return whether the current regular file still matches the indexed bytes and modification time
    /// @throws IOException when current metadata cannot be read
    private static boolean metadataMatches(WorldBackupArchive archive) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(archive.archive(), BasicFileAttributes.class);
        return attributes.isRegularFile() && attributes.size() == archive.sizeBytes()
                && attributes.lastModifiedTime().toInstant().equals(archive.lastModified());
    }

    /// Recognizes a root world or one unambiguous world folder using entry names only, never NBT or region data.
    ///
    /// @param zip already opened archive
    /// @return unique world icon entry, or null for an unsupported or missing layout
    private static @Nullable ZipEntry findIcon(ZipFile zip) {
        boolean rootWorld = hasLevelFile(zip, "");
        @Nullable String root = rootWorld ? "" : null;
        @Nullable String iconName = rootWorld ? "icon.png" : null;
        @Nullable ZipEntry icon = null;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.startsWith("/") || name.indexOf('\\') >= 0 || hasTraversalSegment(name)) return null;
            if (!rootWorld) {
                int slash = name.indexOf('/');
                if (slash <= 0) return null;
                if (root == null) {
                    root = name.substring(0, slash + 1);
                    iconName = root + "icon.png";
                } else if (slash != root.length() - 1 || !name.startsWith(root)) {
                    return null;
                }
            }
            if (name.equals(iconName) && !entry.isDirectory()) {
                if (icon != null) return null;
                icon = entry;
            }
        }
        if (root == null || !hasLevelFile(zip, root)) return null;
        return icon;
    }

    /// Checks only the metadata entry kind, never the contents of either supported world metadata filename.
    ///
    /// @param zip opened archive
    /// @param root direct or wrapped world root
    /// @return whether a non-directory level metadata entry exists
    private static boolean hasLevelFile(ZipFile zip, String root) {
        @Nullable ZipEntry level = zip.getEntry(root + "level.dat");
        @Nullable ZipEntry special = zip.getEntry(root + "special_level.dat");
        return level != null && !level.isDirectory() || special != null && !special.isDirectory();
    }

    /// Rejects ambiguous dot and parent segments without extracting or resolving names on the host filesystem.
    ///
    /// @param name ZIP entry name
    /// @return whether a dot or parent traversal segment appears
    private static boolean hasTraversalSegment(String name) {
        return name.equals(".") || name.equals("..") || name.startsWith("./") || name.startsWith("../")
                || name.contains("/./") || name.contains("/../") || name.endsWith("/.") || name.endsWith("/..");
    }

    /// Validates PNG dimensions before reading pixels and downsamples large accepted sources before final scaling.
    ///
    /// @param encoded bounded encoded PNG bytes
    /// @return normalized owned image, or null for an unsupported format or excessive dimensions
    /// @throws IOException when PNG decoding fails
    private static @Nullable BufferedImage decode(byte[] encoded) throws IOException {
        if (encoded.length < 8 || encoded[0] != (byte) 0x89 || encoded[1] != 'P' || encoded[2] != 'N'
                || encoded[3] != 'G' || encoded[4] != 13 || encoded[5] != 10 || encoded[6] != 26 || encoded[7] != 10) {
            return null;
        }
        try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_SOURCE_EDGE || height > MAX_SOURCE_EDGE
                        || (long) width * height > MAX_SOURCE_PIXELS) return null;
                ImageReadParam parameters = reader.getDefaultReadParam();
                parameters.setSourceSubsampling(Math.max(1, width / ICON_SIZE), Math.max(1, height / ICON_SIZE), 0, 0);
                @Nullable BufferedImage source = reader.read(0, parameters);
                if (source == null) return null;
                try {
                    BufferedImage target = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = target.createGraphics();
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        graphics.drawImage(source, 0, 0, ICON_SIZE, ICON_SIZE, null);
                    } finally {
                        graphics.dispose();
                    }
                    return target;
                } finally {
                    source.flush();
                }
            } finally {
                reader.dispose();
            }
        }
    }
}

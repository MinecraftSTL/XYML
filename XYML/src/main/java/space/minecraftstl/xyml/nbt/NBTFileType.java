// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/// Identifies the three NBT file families supported by the launcher NBT page.
@NotNullByDefault
public enum NBTFileType {
    /// A standalone NBT tag, whose bytes may be raw, GZIP-, ZLIB-, or LZ4-compressed.
    /// The `.xyml_old` suffix is the launcher's rolling recovery copy and remains directly openable.
    TAG("nbt", "dat", "dat_old", "xyml_old"),

    /// A modern Anvil chunk-region file.
    ANVIL("mca"),

    /// A legacy McRegion chunk-region file.
    REGION("mcr");

    /// Supported lowercase filename extensions without a leading dot.
    private final String @Unmodifiable [] extensions;

    /// Creates one file family with its stable extension set.
    ///
    /// @param extensions lowercase filename extensions without a leading dot
    NBTFileType(String... extensions) {
        this.extensions = extensions.clone();
    }

    /// Detects a supported NBT file family from its final filename extension.
    ///
    /// Extension matching is case-insensitive so behavior is consistent on case-sensitive and
    /// case-insensitive filesystems. This method performs no filesystem I/O and therefore does not
    /// claim that the path contains valid NBT data.
    ///
    /// @param file candidate path
    /// @return matching type, or `null` when the extension is unsupported
    public static @Nullable NBTFileType detect(Path file) {
        Objects.requireNonNull(file, "file");
        @Nullable Path fileName = file.getFileName();
        if (fileName == null) {
            return null;
        }
        String name = fileName.toString();
        int separator = name.lastIndexOf('.');
        if (separator < 0 || separator == name.length() - 1) {
            return null;
        }
        String extension = name.substring(separator + 1).toLowerCase(Locale.ROOT);
        for (NBTFileType type : values()) {
            if (type.acceptsExtension(extension)) {
                return type;
            }
        }
        return null;
    }

    /// Reports whether a candidate path has a supported NBT filename extension.
    ///
    /// @param file candidate path
    /// @return whether `detect` resolves a supported family
    public static boolean supports(Path file) {
        return detect(file) != null;
    }

    /// Tests one normalized extension against this type's fixed extension set.
    ///
    /// @param extension lowercase extension without a leading dot
    /// @return whether this type accepts the extension
    private boolean acceptsExtension(String extension) {
        for (String supportedExtension : extensions) {
            if (supportedExtension.equals(extension)) {
                return true;
            }
        }
        return false;
    }
}

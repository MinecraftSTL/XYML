// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.nbt;

import space.minecraftstl.xyml.library.nbt.io.NBTFileEncoding;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Legacy launcher view of the on-disk envelope detected and preserved by XoyzNBT.
@NotNullByDefault
public enum NBTStorageEncoding {
    /// An uncompressed standalone NBT tag.
    RAW,

    /// A standalone NBT tag wrapped in a GZIP stream.
    GZIP,

    /// A standalone NBT tag wrapped in a zlib stream.
    ZLIB,

    /// A standalone NBT tag wrapped in the LZ4 block-stream format used by XoyzNBT.
    LZ4,

    /// A Minecraft chunk-region container with per-chunk compression.
    REGION;

    /// Maps the generic library encoding without performing any launcher-side detection.
    ///
    /// @param encoding XoyzNBT file-session encoding
    /// @return corresponding legacy launcher value
    static NBTStorageEncoding fromFileEncoding(NBTFileEncoding encoding) {
        return switch (Objects.requireNonNull(encoding, "encoding")) {
            case RAW -> RAW;
            case GZIP -> GZIP;
            case ZLIB -> ZLIB;
            case LZ4 -> LZ4;
            case REGION -> REGION;
        };
    }
}

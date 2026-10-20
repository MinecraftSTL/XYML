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

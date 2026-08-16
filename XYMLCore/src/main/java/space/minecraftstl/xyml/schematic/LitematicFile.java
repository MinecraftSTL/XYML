/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.schematic;

import space.minecraftstl.xyml.library.nbt.io.NBTCodec;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.IntArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.IntTag;
import space.minecraftstl.xyml.library.nbt.tag.LongTag;
import space.minecraftstl.xyml.library.nbt.tag.StringTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/// Immutable metadata parsed from one compressed Litematic file.
///
/// The parser reads only file-level version and metadata tags. Missing optional metadata is represented
/// explicitly by nullable accessors. Preview pixels are defensively copied so callers cannot mutate the
/// parsed snapshot. No desktop-toolkit type is exposed by this core-domain class.
///
/// @author Glavo
/// @see <a href="https://litemapy.readthedocs.io/en/v0.9.0b0/litematics.html">The Litematic file format</a>
@NotNullByDefault
public final class LitematicFile {
    /// Source file whose metadata was parsed.
    private final Path file;

    /// Top-level Litematic format version.
    private final int version;

    /// Optional format sub-version represented as zero when absent.
    private final int subVersion;

    /// Minecraft data version represented as zero when absent.
    private final int minecraftDataVersion;

    /// Number of named regions in the root tag.
    private final int regionCount;

    /// Immutable preview pixel snapshot, or null when the metadata has no integer-array preview.
    private final int @Nullable @Unmodifiable [] previewImageData;

    /// User-authored schematic name, or null when absent or stored with another tag type.
    private final @Nullable String name;

    /// User-authored schematic author, or null when absent or stored with another tag type.
    private final @Nullable String author;

    /// User-authored schematic description, or null when absent or stored with another tag type.
    private final @Nullable String description;

    /// Creation time decoded from epoch milliseconds, or null when absent or malformed.
    private final @Nullable Instant timeCreated;

    /// Modification time decoded from epoch milliseconds, or null when absent or malformed.
    private final @Nullable Instant timeModified;

    /// Metadata total block count represented as zero when absent.
    private final int totalBlocks;

    /// Metadata total volume represented as zero when absent.
    private final int totalVolume;

    /// Non-negative enclosing dimensions, or null when absent, malformed, or negative.
    private final @Nullable EnclosingSize enclosingSize;

    /// Creates one parsed immutable metadata snapshot.
    ///
    /// @param file source file
    /// @param metadata required metadata compound
    /// @param version top-level format version
    /// @param subVersion optional format sub-version
    /// @param minecraftDataVersion Minecraft data version
    /// @param regionCount number of root regions
    private LitematicFile(
            Path file,
            CompoundTag metadata,
            int version,
            int subVersion,
            int minecraftDataVersion,
            int regionCount) {
        this.file = Objects.requireNonNull(file, "file");
        Objects.requireNonNull(metadata, "metadata");
        this.version = version;
        this.subVersion = subVersion;
        this.minecraftDataVersion = minecraftDataVersion;
        this.regionCount = regionCount;

        @Nullable Tag previewImageTag = metadata.get("PreviewImageData");
        previewImageData = previewImageTag instanceof IntArrayTag intArrayTag
                ? intArrayTag.getArray().clone()
                : null;

        name = tryGetString(metadata.get("Name"));
        author = tryGetString(metadata.get("Author"));
        description = tryGetString(metadata.get("Description"));
        timeCreated = metadata.get("TimeCreated") instanceof LongTag time
                ? Instant.ofEpochMilli(time.getValue())
                : null;
        timeModified = metadata.get("TimeModified") instanceof LongTag time
                ? Instant.ofEpochMilli(time.getValue())
                : null;
        totalBlocks = metadata.getIntOrZero("TotalBlocks");
        totalVolume = metadata.getIntOrZero("TotalVolume");

        @Nullable EnclosingSize parsedEnclosingSize = null;
        if (metadata.get("EnclosingSize") instanceof CompoundTag sizeTag) {
            int x = sizeTag.getIntOrZero("x");
            int y = sizeTag.getIntOrZero("y");
            int z = sizeTag.getIntOrZero("z");
            if (x >= 0 && y >= 0 && z >= 0) {
                parsedEnclosingSize = new EnclosingSize(x, y, z);
            }
        }
        enclosingSize = parsedEnclosingSize;
    }

    /// Loads and validates required metadata from one GZIP-compressed Litematic file.
    ///
    /// @param file source Litematic path
    /// @return immutable parsed metadata
    /// @throws IOException when I/O, compression, NBT decoding, or required-tag validation fails
    public static LitematicFile load(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        CompoundTag root;
        try (InputStream input = new GZIPInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))) {
            root = NBTCodec.of().readTag(input, TagType.COMPOUND);
        }

        @Nullable Tag versionTag = root.get("Version");
        if (versionTag == null) {
            throw new IOException("Version tag not found");
        }
        if (!(versionTag instanceof IntTag intVersionTag)) {
            throw new IOException("Version tag is not an integer");
        }

        @Nullable Tag metadataTag = root.get("Metadata");
        if (metadataTag == null) {
            throw new IOException("Metadata tag not found");
        }
        if (!(metadataTag instanceof CompoundTag metadata)) {
            throw new IOException("Metadata tag is not a compound tag");
        }

        int regions = root.get("Regions") instanceof CompoundTag regionsTag
                ? regionsTag.size()
                : 0;
        return new LitematicFile(
                file,
                metadata,
                intVersionTag.getValue(),
                root.getIntOrZero("SubVersion"),
                root.getIntOrZero("MinecraftDataVersion"),
                regions);
    }

    /// Returns a string tag value or null for absence and mismatched tag types.
    ///
    /// @param tag optional metadata tag
    /// @return decoded string, or null
    private static @Nullable String tryGetString(@Nullable Tag tag) {
        return tag instanceof StringTag stringTag ? stringTag.get() : null;
    }

    /// Returns the exact source path.
    ///
    /// @return source file
    public Path getFile() {
        return file;
    }

    /// Returns the top-level Litematic version.
    ///
    /// @return format version
    public int getVersion() {
        return version;
    }

    /// Returns the format sub-version or zero when absent.
    ///
    /// @return format sub-version
    public int getSubVersion() {
        return subVersion;
    }

    /// Returns the Minecraft data version or zero when absent.
    ///
    /// @return Minecraft data version
    public int getMinecraftDataVersion() {
        return minecraftDataVersion;
    }

    /// Returns an independent preview pixel snapshot.
    ///
    /// @return independent mutable pixel copy, or null when no preview was decoded
    public int @Nullable [] getPreviewImageData() {
        return previewImageData == null ? null : previewImageData.clone();
    }

    /// Returns the optional schematic name.
    ///
    /// @return name, or null
    public @Nullable String getName() {
        return name;
    }

    /// Returns the optional schematic author.
    ///
    /// @return author, or null
    public @Nullable String getAuthor() {
        return author;
    }

    /// Returns the optional schematic description.
    ///
    /// @return description, or null
    public @Nullable String getDescription() {
        return description;
    }

    /// Returns the optional creation time.
    ///
    /// @return creation time, or null
    public @Nullable Instant getTimeCreated() {
        return timeCreated;
    }

    /// Returns the optional modification time.
    ///
    /// @return modification time, or null
    public @Nullable Instant getTimeModified() {
        return timeModified;
    }

    /// Returns the metadata total block count.
    ///
    /// @return total block count
    public int getTotalBlocks() {
        return totalBlocks;
    }

    /// Returns the metadata total volume.
    ///
    /// @return total volume
    public int getTotalVolume() {
        return totalVolume;
    }

    /// Returns optional non-negative enclosing dimensions.
    ///
    /// @return enclosing dimensions, or null
    public @Nullable EnclosingSize getEnclosingSize() {
        return enclosingSize;
    }

    /// Returns the number of root regions.
    ///
    /// @return region count
    public int getRegionCount() {
        return regionCount;
    }

    /// Immutable non-negative enclosing dimensions independent of JavaFX geometry.
    ///
    /// @param x extent along the X axis
    /// @param y extent along the Y axis
    /// @param z extent along the Z axis
    @NotNullByDefault
    public record EnclosingSize(int x, int y, int z) {
        /// Validates non-negative dimensions.
        public EnclosingSize {
            if (x < 0 || y < 0 || z < 0) {
                throw new IllegalArgumentException("Enclosing dimensions must not be negative");
            }
        }

        /// Returns the X extent through the legacy bean-style source API.
        ///
        /// @return X extent
        public int getX() {
            return x;
        }

        /// Returns the Y extent through the legacy bean-style source API.
        ///
        /// @return Y extent
        public int getY() {
            return y;
        }

        /// Returns the Z extent through the legacy bean-style source API.
        ///
        /// @return Z extent
        public int getZ() {
            return z;
        }
    }
}

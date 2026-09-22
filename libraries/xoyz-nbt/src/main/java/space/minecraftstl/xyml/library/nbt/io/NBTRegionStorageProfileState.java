/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Added by MinecraftSTL in 2026 for XoyzNBT Region storage-profile state.
package space.minecraftstl.xyml.library.nbt.io;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.library.nbt.internal.ChunkUtils;

import java.util.ArrayList;
import java.util.List;

/// Owns Region storage-profile derivation without duplicating the Region file's mutable slot arrays.
///
/// The supplied arrays remain owned by the enclosing [NBTRegionFile]. This helper only reads their current values and
/// retains immutable change records after a header publication becomes visible.
@NotNullByDefault
final class NBTRegionStorageProfileState {
    /// Current compression identifier for each slot.
    private final byte[] compressionTypes;

    /// Current external-companion flag for each slot.
    private final boolean[] external;

    /// Current allocated sector count for each slot.
    private final int[] sectorLengths;

    /// Storage-profile changes emitted by the most recent flush attempt.
    private final List<StorageProfileChange> changes = new ArrayList<>();

    /// Creates a view over arrays exclusively owned and mutated by one Region session.
    ///
    /// @param compressionTypes current per-slot compression identifiers
    /// @param external current per-slot external-companion flags
    /// @param sectorLengths current per-slot allocated sector counts
    NBTRegionStorageProfileState(byte[] compressionTypes, boolean[] external, int[] sectorLengths) {
        this.compressionTypes = compressionTypes;
        this.external = external;
        this.sectorLengths = sectorLengths;
    }

    /// Creates an immutable snapshot of every slot's current storage profile.
    ///
    /// @return detached 1024-slot Region storage profile
    synchronized StorageProfile snapshot() {
        byte[] markers = compressionTypes.clone();
        boolean[] externalFlags = external.clone();
        boolean[] occupied = new boolean[ChunkUtils.CHUNKS_PRE_REGION];
        for (int localIndex = 0; localIndex < occupied.length; localIndex++) {
            occupied[localIndex] = sectorLengths[localIndex] != 0;
        }
        return StorageProfile.region(markers, externalFlags, occupied);
    }

    /// Returns changes whose header publications became visible during the most recent flush.
    ///
    /// @return immutable profile-change snapshot in publication order
    synchronized @Unmodifiable List<StorageProfileChange> changes() {
        return List.copyOf(changes);
    }

    /// Clears changes before a new flush attempt starts.
    synchronized void clearChanges() {
        changes.clear();
    }

    /// Derives one pending before/after change without publishing it.
    ///
    /// @param localIndex affected local chunk index
    /// @param marker replacement compression marker
    /// @param isExternal whether the replacement uses an external companion
    /// @param occupied whether the replacement occupies a slot
    /// @return detached change, or null when the storage profile is unchanged
    synchronized @Nullable StorageProfileChange change(
            int localIndex,
            byte marker,
            boolean isExternal,
            boolean occupied) {
        StorageProfile.RegionSlot before = new StorageProfile.RegionSlot(
                Byte.toUnsignedInt(compressionTypes[localIndex]),
                external[localIndex],
                sectorLengths[localIndex] != 0);
        StorageProfile.RegionSlot after = new StorageProfile.RegionSlot(
                Byte.toUnsignedInt(marker),
                isExternal,
                occupied);
        return before.equals(after) ? null : new StorageProfileChange(localIndex, before, after);
    }

    /// Records a change only after its replacement header is externally visible.
    ///
    /// @param change profile change, or null for a no-op publication
    synchronized void remember(@Nullable StorageProfileChange change) {
        if (change != null) {
            changes.add(change);
        }
    }

    /// Selects a pending or existing compression type, defaulting a new slot to ZLIB.
    ///
    /// @param localIndex affected local chunk index
    /// @param pendingCompression explicit pending compression, or null when the slot has no pending replacement
    /// @return compression type to retain or use by default
    NBTRegionFile.CompressionType preferredCompression(
            int localIndex,
            @Nullable NBTRegionFile.CompressionType pendingCompression) {
        if (pendingCompression != null) {
            return pendingCompression;
        }
        if (sectorLengths[localIndex] != 0) {
            @Nullable NBTRegionFile.CompressionType existing = compressionType(
                    Byte.toUnsignedInt(compressionTypes[localIndex]));
            if (existing != null) {
                return existing;
            }
        }
        return NBTRegionFile.CompressionType.ZLIB;
    }

    /// Preserves an occupied slot's known marker while accepting the requested type for a new or unknown slot.
    ///
    /// @param localIndex affected local chunk index
    /// @param requested caller-requested compression type
    /// @return retained known compression, or the requested type
    NBTRegionFile.CompressionType compressionForPublication(
            int localIndex,
            NBTRegionFile.CompressionType requested) {
        if (sectorLengths[localIndex] != 0) {
            @Nullable NBTRegionFile.CompressionType existing = compressionType(
                    Byte.toUnsignedInt(compressionTypes[localIndex]));
            if (existing != null) {
                return existing;
            }
        }
        return requested;
    }

    /// Resolves one known compression identifier without selecting a replacement for an extension marker.
    ///
    /// @param id low-seven-bit compression identifier
    /// @return known compression type, or null for an extension marker
    static @Nullable NBTRegionFile.CompressionType compressionType(int id) {
        for (NBTRegionFile.CompressionType type : NBTRegionFile.CompressionType.values()) {
            if (type.id() == id) {
                return type;
            }
        }
        return null;
    }
}

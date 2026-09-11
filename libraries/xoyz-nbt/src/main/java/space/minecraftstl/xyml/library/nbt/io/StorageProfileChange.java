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
// Added by MinecraftSTL in 2026 for explicit XoyzNBT storage-profile deltas.
package space.minecraftstl.xyml.library.nbt.io;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Immutable before/after metadata for one published Region storage slot.
///
/// A change is emitted only after the replacement location header becomes visible. The snapshots
/// therefore describe the profile that was visible immediately before publication and the profile
/// that a subsequent [NBTRegionFile#storageProfile()] call observes. In particular,
/// [#changedToExternal()] identifies an inline payload which exceeded the representable 255-sector
/// location entry and was published through its `.mcc` companion.
@NotNullByDefault
public final class StorageProfileChange {
    private final int localIndex;
    private final StorageProfile.RegionSlot before;
    private final StorageProfile.RegionSlot after;

    /// Creates one immutable slot-profile change.
    ///
    /// @param localIndex fixed local Region slot from 0 through 1023
    /// @param before profile visible before publication
    /// @param after profile visible after publication
    /// @throws IndexOutOfBoundsException when the slot index is outside the Region table
    public StorageProfileChange(int localIndex, StorageProfile.RegionSlot before,
                                StorageProfile.RegionSlot after) {
        if (localIndex < 0 || localIndex >= StorageProfile.REGION_SLOT_COUNT) {
            throw new IndexOutOfBoundsException("localIndex: " + localIndex);
        }
        this.localIndex = localIndex;
        this.before = Objects.requireNonNull(before, "before");
        this.after = Objects.requireNonNull(after, "after");
        if (before.equals(after)) {
            throw new IllegalArgumentException("A storage-profile change must alter the slot profile");
        }
    }

    /// Returns the fixed local Region slot index.
    ///
    /// @return local slot from 0 through 1023
    @Contract(pure = true)
    public int localIndex() {
        return localIndex;
    }

    /// Returns the profile visible before publication.
    ///
    /// @return immutable previous slot profile
    @Contract(pure = true)
    public StorageProfile.RegionSlot before() {
        return before;
    }

    /// Returns the profile visible after publication.
    ///
    /// @return immutable published slot profile
    @Contract(pure = true)
    public StorageProfile.RegionSlot after() {
        return after;
    }

    /// Returns whether an inline slot was moved to an external companion payload.
    ///
    /// @return `true` only for an inline-to-external transition
    @Contract(pure = true)
    public boolean changedToExternal() {
        return !before.external() && after.external();
    }

    /// Returns whether an external slot was moved back to inline sectors.
    ///
    /// @return `true` only for an external-to-inline transition
    @Contract(pure = true)
    public boolean changedFromExternal() {
        return before.external() && !after.external();
    }

    /// Bean-style alias for [#localIndex()].
    ///
    /// @return local slot from 0 through 1023
    public int getLocalIndex() {
        return localIndex();
    }

    /// Bean-style alias for [#before()].
    ///
    /// @return immutable previous slot profile
    public StorageProfile.RegionSlot getBefore() {
        return before();
    }

    /// Bean-style alias for [#after()].
    ///
    /// @return immutable published slot profile
    public StorageProfile.RegionSlot getAfter() {
        return after();
    }

    /// Compares all immutable change fields.
    @Override
    public boolean equals(Object object) {
        return this == object || object instanceof StorageProfileChange other
                && localIndex == other.localIndex
                && before.equals(other.before)
                && after.equals(other.after);
    }

    /// Returns a hash code consistent with [#equals(Object)].
    @Override
    public int hashCode() {
        return Objects.hash(localIndex, before, after);
    }

    /// Returns a concise diagnostic representation.
    @Override
    public String toString() {
        return "StorageProfileChange[localIndex=" + localIndex
                + ", before=" + before + ", after=" + after + ']';
    }
}

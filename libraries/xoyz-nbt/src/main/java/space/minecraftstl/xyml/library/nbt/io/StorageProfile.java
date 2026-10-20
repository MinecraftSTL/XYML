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
// Added by MinecraftSTL in 2026 for immutable XoyzNBT storage profiles.
package space.minecraftstl.xyml.library.nbt.io;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Immutable description of the on-disk compression profile of one NBT source.
///
/// A standalone profile contains one [NBTFileEncoding] category. A region profile contains all
/// 1024 fixed slots. Each [RegionSlot] retains the unsigned low-seven-bit compression marker,
/// the external-payload flag, and whether the slot was occupied when the profile was captured.
/// Compression parameters which are not represented by NBT headers (for example GZIP metadata,
/// zlib levels, dictionaries, or LZ4 block settings) are intentionally not part of this profile.
@NotNullByDefault
public final class StorageProfile {
    /// Number of fixed local slots in one Java Edition region file.
    public static final int REGION_SLOT_COUNT = 1024;

    private final NBTFileEncoding encoding;
    private final @Unmodifiable List<RegionSlot> regionSlots;

    /// Creates a standalone profile for one supported outer encoding.
    ///
    /// @param encoding standalone encoding category
    /// @throws IllegalArgumentException if `encoding` is `REGION`
    public StorageProfile(NBTFileEncoding encoding) {
        this(requireStandaloneEncoding(encoding), List.of());
    }

    /// Creates a region profile from all fixed slots.
    ///
    /// The supplied list is copied immediately and must contain exactly 1024 entries. Use
    /// [#region(List)] when a named factory is clearer at a call site.
    ///
    /// @param slots region slot snapshots
    /// @throws IllegalArgumentException if the list does not contain 1024 entries
    public StorageProfile(List<RegionSlot> slots) {
        this(NBTFileEncoding.REGION, requireRegionSlots(slots));
    }

    /// Creates a profile with a validated encoding and detached slot list.
    private StorageProfile(NBTFileEncoding encoding, List<RegionSlot> slots) {
        this.encoding = Objects.requireNonNull(encoding, "encoding");
        this.regionSlots = List.copyOf(new ArrayList<>(Objects.requireNonNull(slots, "slots")));
    }

    /// Creates an immutable standalone profile.
    ///
    /// @param encoding standalone encoding category
    /// @return standalone profile
    /// @throws IllegalArgumentException if `encoding` is `REGION`
    @Contract("_ -> new")
    public static StorageProfile standalone(NBTFileEncoding encoding) {
        return new StorageProfile(encoding);
    }

    /// Alias for callers which prefer an explicit factory name.
    ///
    /// @param encoding standalone encoding category
    /// @return standalone profile
    /// @throws IllegalArgumentException if `encoding` is `REGION`
    @Contract("_ -> new")
    public static StorageProfile forStandalone(NBTFileEncoding encoding) {
        return standalone(encoding);
    }

    /// Creates an immutable region profile from all fixed slots.
    ///
    /// @param slots region slot snapshots
    /// @return region profile
    /// @throws IllegalArgumentException if the list does not contain 1024 entries
    @Contract("_ -> new")
    public static StorageProfile region(List<RegionSlot> slots) {
        return new StorageProfile(slots);
    }

    /// Alias for callers which prefer an explicit factory name.
    ///
    /// @param slots region slot snapshots
    /// @return region profile
    /// @throws IllegalArgumentException if the list does not contain 1024 entries
    @Contract("_ -> new")
    public static StorageProfile forRegion(List<RegionSlot> slots) {
        return region(slots);
    }

    /// Creates a region profile from low-seven-bit markers and external flags.
    ///
    /// Every array is copied. A slot is considered occupied when its marker is non-zero or its
    /// external flag is set. Use [#region(byte[], boolean[], boolean[])] when malformed input
    /// needs to preserve an explicit occupancy bit.
    ///
    /// @param markers unsigned compression marker IDs in the range 0 through 127
    /// @param external external-payload flags
    /// @return region profile
    /// @throws IllegalArgumentException if array lengths differ or are not 1024, or a marker is
    /// outside the low-seven-bit range
    @Contract("_, _ -> new")
    public static StorageProfile region(byte[] markers, boolean[] external) {
        Objects.requireNonNull(markers, "markers");
        Objects.requireNonNull(external, "external");
        if (markers.length != REGION_SLOT_COUNT || external.length != REGION_SLOT_COUNT) {
            throw new IllegalArgumentException("A region profile must contain exactly 1024 slots");
        }
        boolean[] occupied = new boolean[REGION_SLOT_COUNT];
        for (int index = 0; index < REGION_SLOT_COUNT; index++) {
            occupied[index] = Byte.toUnsignedInt(markers[index]) != 0 || external[index];
        }
        return region(markers, external, occupied);
    }

    /// Creates a region profile from low-seven-bit markers, external flags, and occupancy bits.
    ///
    /// All arrays are copied before the profile is returned.
    ///
    /// @param markers unsigned compression marker IDs in the range 0 through 127
    /// @param external external-payload flags
    /// @param occupied whether each slot had a non-empty location entry
    /// @return region profile
    /// @throws IllegalArgumentException if an array length is not 1024 or a marker is invalid
    @Contract("_, _, _ -> new")
    public static StorageProfile region(byte[] markers, boolean[] external, boolean[] occupied) {
        Objects.requireNonNull(markers, "markers");
        Objects.requireNonNull(external, "external");
        Objects.requireNonNull(occupied, "occupied");
        if (markers.length != REGION_SLOT_COUNT
                || external.length != REGION_SLOT_COUNT
                || occupied.length != REGION_SLOT_COUNT) {
            throw new IllegalArgumentException("A region profile must contain exactly 1024 slots");
        }
        List<RegionSlot> slots = new ArrayList<>(REGION_SLOT_COUNT);
        for (int index = 0; index < REGION_SLOT_COUNT; index++) {
            slots.add(new RegionSlot(Byte.toUnsignedInt(markers[index]), external[index], occupied[index]));
        }
        return region(slots);
    }

    /// Returns the complete profile encoding category.
    ///
    /// `REGION` identifies a region profile; standalone callers can use [#standaloneEncoding()].
    ///
    /// @return encoding category
    @Contract(pure = true)
    public NBTFileEncoding encoding() {
        return encoding;
    }

    /// Bean-style alias for [#encoding()].
    ///
    /// @return encoding category
    @Contract(pure = true)
    public NBTFileEncoding getEncoding() {
        return encoding;
    }

    /// Returns whether this profile describes a standalone tag.
    ///
    /// @return `true` for RAW, GZIP, ZLIB, or LZ4 profiles
    @Contract(pure = true)
    public boolean isStandalone() {
        return encoding != NBTFileEncoding.REGION;
    }

    /// Returns whether this profile describes a region container.
    ///
    /// @return `true` for a 1024-slot region profile
    @Contract(pure = true)
    public boolean isRegion() {
        return encoding == NBTFileEncoding.REGION;
    }

    /// Returns the standalone encoding category.
    ///
    /// @return standalone encoding
    /// @throws IllegalStateException when this is a region profile
    @Contract(pure = true)
    public NBTFileEncoding standaloneEncoding() {
        if (!isStandalone()) {
            throw new IllegalStateException("A region profile has no standalone encoding");
        }
        return encoding;
    }

    /// Bean-style alias for [#standaloneEncoding()].
    ///
    /// @return standalone encoding
    /// @throws IllegalStateException when this is a region profile
    @Contract(pure = true)
    public NBTFileEncoding getStandaloneEncoding() {
        return standaloneEncoding();
    }

    /// Returns an immutable snapshot of all region slots, or an empty list for standalone data.
    ///
    /// @return immutable region slot list
    @Contract(pure = true)
    public @Unmodifiable List<RegionSlot> regionSlots() {
        return regionSlots;
    }

    /// Bean-style alias for [#regionSlots()].
    ///
    /// @return immutable region slot list
    @Contract(pure = true)
    public @Unmodifiable List<RegionSlot> getRegionSlots() {
        return regionSlots;
    }

    /// Returns one region slot snapshot.
    ///
    /// @param localIndex local slot from 0 through 1023
    /// @return immutable slot snapshot
    /// @throws IllegalStateException when this is a standalone profile
    /// @throws IndexOutOfBoundsException when the index is outside the region
    @Contract(pure = true)
    public RegionSlot regionSlot(int localIndex) {
        if (!isRegion()) {
            throw new IllegalStateException("A standalone profile has no region slots");
        }
        return regionSlots.get(localIndex);
    }

    /// Bean-style alias for [#regionSlot(int)].
    ///
    /// @param localIndex local slot from 0 through 1023
    /// @return immutable slot snapshot
    /// @throws IllegalStateException when this is a standalone profile
    /// @throws IndexOutOfBoundsException when the index is outside the region
    @Contract(pure = true)
    public RegionSlot getRegionSlot(int localIndex) {
        return regionSlot(localIndex);
    }

    /// Returns the number of slots represented by this profile.
    ///
    /// @return 1024 for region profiles, otherwise zero
    @Contract(pure = true)
    public int regionSlotCount() {
        return regionSlots.size();
    }

    /// Compares the encoding category and all immutable slot snapshots.
    @Override
    public boolean equals(Object object) {
        return this == object || object instanceof StorageProfile other
                && encoding == other.encoding
                && regionSlots.equals(other.regionSlots);
    }

    /// Returns a hash code consistent with [#equals(Object)].
    @Override
    public int hashCode() {
        return Objects.hash(encoding, regionSlots);
    }

    /// Returns a concise immutable diagnostic representation.
    @Override
    public String toString() {
        return "StorageProfile[encoding=" + encoding + ", regionSlots=" + regionSlots + ']';
    }

    /// Validates a standalone encoding category.
    private static NBTFileEncoding requireStandaloneEncoding(NBTFileEncoding encoding) {
        NBTFileEncoding selected = Objects.requireNonNull(encoding, "encoding");
        if (selected == NBTFileEncoding.REGION) {
            throw new IllegalArgumentException("REGION is not a standalone storage encoding");
        }
        return selected;
    }

    /// Validates and copies a fixed-size region slot list for constructor delegation.
    private static @Unmodifiable List<RegionSlot> requireRegionSlots(List<RegionSlot> slots) {
        List<RegionSlot> selected = List.copyOf(new ArrayList<>(Objects.requireNonNull(slots, "slots")));
        if (selected.size() != REGION_SLOT_COUNT) {
            throw new IllegalArgumentException("A region profile must contain exactly 1024 slots");
        }
        return selected;
    }

    /// Immutable profile metadata for one fixed region slot.
    @NotNullByDefault
    public static final class RegionSlot {
        private final int marker;
        private final boolean external;
        private final boolean occupied;

        /// Creates a slot snapshot and derives occupancy from its marker and external flag.
        ///
        /// @param marker unsigned low-seven-bit compression marker ID
        /// @param external whether the payload is stored in a companion file
        public RegionSlot(int marker, boolean external) {
            this(marker, external, marker != 0 || external);
        }

        /// Creates a slot snapshot with explicit occupancy state.
        ///
        /// @param marker unsigned low-seven-bit compression marker ID
        /// @param external whether the payload is stored in a companion file
        /// @param occupied whether the location entry had a non-zero sector length
        public RegionSlot(int marker, boolean external, boolean occupied) {
            if (marker < 0 || marker > 0x7F) {
                throw new IllegalArgumentException("Region compression marker must be between 0 and 127");
            }
            this.marker = marker;
            this.external = external;
            this.occupied = occupied;
        }

        /// Builds a slot from the raw Anvil marker byte.
        ///
        /// @param encodedMarker raw unsigned marker, including the external high bit
        /// @param occupied whether the location entry had a non-zero sector length
        /// @return immutable slot snapshot
        @Contract("_, _ -> new")
        public static RegionSlot fromEncodedMarker(int encodedMarker, boolean occupied) {
            if (encodedMarker < 0 || encodedMarker > 0xFF) {
                throw new IllegalArgumentException("Encoded region marker must be between 0 and 255");
            }
            return new RegionSlot(encodedMarker & 0x7F, (encodedMarker & 0x80) != 0, occupied);
        }

        /// Builds a slot from a compression marker ID and external flag.
        ///
        /// @param marker unsigned low-seven-bit compression marker ID
        /// @param external whether the payload is stored in a companion file
        /// @return immutable slot snapshot
        @Contract("_, _ -> new")
        public static RegionSlot of(int marker, boolean external) {
            return new RegionSlot(marker, external);
        }

        /// Returns the unsigned low-seven-bit compression marker ID.
        ///
        /// @return marker ID from 0 through 127
        @Contract(pure = true)
        public int marker() {
            return marker;
        }

        /// Bean-style alias for [#marker()].
        ///
        /// @return marker ID from 0 through 127
        @Contract(pure = true)
        public int getMarker() {
            return marker;
        }

        /// Returns the compression marker ID without the external high bit.
        ///
        /// @return marker ID from 0 through 127
        @Contract(pure = true)
        public int compressionId() {
            return marker;
        }

        /// Returns the raw marker byte which would be written to an Anvil chunk frame.
        ///
        /// @return unsigned marker including the external high bit
        @Contract(pure = true)
        public int encodedMarker() {
            return marker | (external ? 0x80 : 0);
        }

        /// Alias for [#encodedMarker()].
        ///
        /// @return unsigned marker including the external high bit
        @Contract(pure = true)
        public int rawMarker() {
            return encodedMarker();
        }

        /// Returns whether the payload is stored in a companion file.
        @Contract(pure = true)
        public boolean external() {
            return external;
        }

        /// Bean-style alias for [#external()].
        @Contract(pure = true)
        public boolean isExternal() {
            return external;
        }

        /// Returns whether the corresponding location entry was occupied.
        @Contract(pure = true)
        public boolean occupied() {
            return occupied;
        }

        /// Bean-style alias for [#occupied()].
        @Contract(pure = true)
        public boolean isOccupied() {
            return occupied;
        }

        /// Compares all slot metadata.
        @Override
        public boolean equals(Object object) {
            return this == object || object instanceof RegionSlot other
                    && marker == other.marker
                    && external == other.external
                    && occupied == other.occupied;
        }

        /// Returns a hash code consistent with [#equals(Object)].
        @Override
        public int hashCode() {
            return Objects.hash(marker, external, occupied);
        }

        /// Returns an immutable diagnostic representation.
        @Override
        public String toString() {
            return "RegionSlot[marker=" + marker + ", external=" + external
                    + ", occupied=" + occupied + ']';
        }
    }
}

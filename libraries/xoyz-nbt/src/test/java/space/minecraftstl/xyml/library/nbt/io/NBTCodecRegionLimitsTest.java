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
// Added by MinecraftSTL in 2026 for legacy region read-limit coverage.
package space.minecraftstl.xyml.library.nbt.io;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that every legacy strict region entry enforces the same structural limits.
@NotNullByDefault
public final class NBTCodecRegionLimitsTest {
    /// Temporary region path used by the path-backed entry.
    @TempDir
    private Path temporaryDirectory;

    /// Accepts an ordinary bounded tree through every public legacy region input.
    ///
    /// @param input public region input variant
    /// @throws Exception if fixture construction unexpectedly fails
    @ParameterizedTest
    @EnumSource(RegionInput.class)
    void acceptsRegionWithinStructureLimits(RegionInput input) throws Exception {
        byte[] region = regionWithPayload(compoundWithNestedLists(1));

        assertDoesNotThrow(() -> input.read(temporaryDirectory.resolve("valid.mca"), region));
    }

    /// Rejects a leaf list whose declared nodes exceed the document-wide default.
    ///
    /// @param input public region input variant
    /// @throws Exception if fixture construction unexpectedly fails
    @ParameterizedTest
    @EnumSource(RegionInput.class)
    void rejectsRegionNodeLimit(RegionInput input) throws Exception {
        byte[] region = regionWithPayload(compoundWithLeafList(
                Math.toIntExact(ReadLimits.defaults().maxNodes())));

        IOException failure = assertThrows(IOException.class,
                () -> input.read(temporaryDirectory.resolve("nodes.mca"), region));
        assertTrue(hasMessage(failure, "node count"));
    }

    /// Rejects a list count above the default collection limit before element allocation.
    ///
    /// @param input public region input variant
    /// @throws Exception if fixture construction unexpectedly fails
    @ParameterizedTest
    @EnumSource(RegionInput.class)
    void rejectsRegionArrayLengthLimit(RegionInput input) throws Exception {
        int oversized = Math.toIntExact(ReadLimits.defaults().maxArrayLength() + 1L);
        byte[] region = regionWithPayload(compoundWithLeafList(oversized));

        IOException failure = assertThrows(IOException.class,
                () -> input.read(temporaryDirectory.resolve("length.mca"), region));
        assertTrue(hasMessage(failure, "collection length"));
    }

    /// Rejects a primitive array above the default element-count limit before array allocation.
    ///
    /// @param input public region input variant
    /// @throws Exception if fixture construction unexpectedly fails
    @ParameterizedTest
    @EnumSource(RegionInput.class)
    void rejectsRegionPrimitiveArrayLengthLimit(RegionInput input) throws Exception {
        int oversized = Math.toIntExact(ReadLimits.defaults().maxArrayLength() + 1L);
        byte[] region = regionWithPayload(compoundWithByteArray(oversized));

        IOException failure = assertThrows(IOException.class,
                () -> input.read(temporaryDirectory.resolve("array.mca"), region));
        assertTrue(hasMessage(failure, "collection length"));
    }

    /// Rejects a valid tree deeper than the default nesting budget before unbounded recursion.
    ///
    /// @param input public region input variant
    /// @throws Exception if fixture construction unexpectedly fails
    @ParameterizedTest
    @EnumSource(RegionInput.class)
    void rejectsRegionDepthLimit(RegionInput input) throws Exception {
        byte[] region = regionWithPayload(compoundWithNestedLists(
                Math.toIntExact(ReadLimits.defaults().maxDepth())));

        IOException failure = assertThrows(IOException.class,
                () -> input.read(temporaryDirectory.resolve("depth.mca"), region));
        assertTrue(hasMessage(failure, "nesting depth"));
    }

    /// Builds a compound whose named byte list declares the requested element count.
    ///
    /// The elements are intentionally omitted because each tested limit must reject the declaration
    /// before reading or allocating the first element.
    ///
    /// @param count declared byte-tag count
    /// @return raw Java Edition NBT payload
    private static byte[] compoundWithLeafList(int count) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(10); // root compound
        output.write(0);
        output.write(0); // root name
        output.write(9); // named list
        output.write(0);
        output.write(4);
        output.writeBytes(new byte[]{'l', 'i', 's', 't'});
        output.write(1); // byte element type
        output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(count).array());
        return output.toByteArray();
    }

    /// Builds a compound whose named byte array declares the requested element count.
    ///
    /// @param count declared byte-array size
    /// @return raw Java Edition NBT payload
    private static byte[] compoundWithByteArray(int count) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(10); // root compound
        output.write(0);
        output.write(0); // root name
        output.write(7); // named byte array
        output.write(0);
        output.write(5);
        output.writeBytes(new byte[]{'a', 'r', 'r', 'a', 'y'});
        output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(count).array());
        return output.toByteArray();
    }

    /// Builds a valid compound containing enough nested lists to exceed the depth limit.
    ///
    /// @param listCount number of nested list tags including the named outer list
    /// @return raw Java Edition NBT payload
    private static byte[] compoundWithNestedLists(int listCount) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(10); // root compound
        output.write(0);
        output.write(0); // root name
        output.write(9); // named outer list
        output.write(0);
        output.write(6);
        output.writeBytes(new byte[]{'n', 'e', 's', 't', 'e', 'd'});
        for (int index = 0; index < listCount; index++) {
            output.write(index + 1 == listCount ? 1 : 9);
            output.writeBytes(new byte[]{0, 0, 0, 1});
        }
        output.write(42); // final byte-list element
        output.write(0); // root TAG_End
        return output.toByteArray();
    }

    /// Wraps one raw NBT payload in a complete single-slot ZLIB region.
    ///
    /// @param payload uncompressed named compound
    /// @return complete sector-aligned region bytes
    /// @throws IOException if compression unexpectedly fails
    private static byte[] regionWithPayload(byte[] payload) throws IOException {
        ByteArrayOutputStream compressedOutput = new ByteArrayOutputStream();
        try (DeflaterOutputStream compressor = new DeflaterOutputStream(compressedOutput)) {
            compressor.write(payload);
        }
        byte[] compressed = compressedOutput.toByteArray();
        int frameBytes = Integer.BYTES + 1 + compressed.length;
        int sectors = Math.max(1, (frameBytes + 4095) / 4096);
        byte[] region = new byte[(2 + sectors) * 4096];
        region[2] = 2; // first slot starts after the two header sectors
        region[3] = (byte) sectors;
        ByteBuffer frame = ByteBuffer.wrap(region, 2 * 4096, sectors * 4096);
        frame.putInt(compressed.length + 1);
        frame.put((byte) 2); // ZLIB marker
        frame.put(compressed);
        return region;
    }

    /// Searches an exception chain for one stable limit diagnostic fragment.
    ///
    /// @param failure top-level read failure
    /// @param fragment expected diagnostic fragment
    /// @return whether any cause contains the fragment
    private static boolean hasMessage(Throwable failure, String fragment) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(fragment)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /// Public legacy region inputs which must remain behaviorally consistent.
    @NotNullByDefault
    private enum RegionInput {
        /// Path-backed region input.
        PATH {
            /// {@inheritDoc}
            @Override
            void read(Path path, byte[] bytes) throws IOException {
                Files.write(path, bytes);
                NBTCodec.of().readRegion(path);
            }
        },
        /// Stream-backed region input.
        INPUT_STREAM {
            /// {@inheritDoc}
            @Override
            void read(Path path, byte[] bytes) throws IOException {
                try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                    NBTCodec.of().readRegion(input);
                }
            }
        },
        /// Channel-backed region input.
        READABLE_BYTE_CHANNEL {
            /// {@inheritDoc}
            @Override
            void read(Path path, byte[] bytes) throws IOException {
                try (var channel = Channels.newChannel(new ByteArrayInputStream(bytes))) {
                    NBTCodec.of().readRegion(channel);
                }
            }
        };

        /// Reads one complete region through this public input form.
        ///
        /// @param path disposable path for path-backed input
        /// @param bytes complete region bytes
        /// @throws IOException expected for a rejected limit fixture
        abstract void read(Path path, byte[] bytes) throws IOException;
    }
}

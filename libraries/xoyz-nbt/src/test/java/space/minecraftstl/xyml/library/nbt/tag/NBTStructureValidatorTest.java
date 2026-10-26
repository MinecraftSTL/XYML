// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

// Added by MinecraftSTL in 2026 for XoyzNBT structural validation coverage.
package space.minecraftstl.xyml.library.nbt.tag;

import space.minecraftstl.xyml.library.nbt.io.NBTCodec;
import space.minecraftstl.xyml.library.nbt.validation.NBTStructureValidator;
import space.minecraftstl.xyml.library.nbt.validation.NBTValidationException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies complete tree validation and validation-before-write ordering.
@NotNullByDefault
public final class NBTStructureValidatorTest {
    /// Ensures inconsistent ownership metadata is rejected before a writer emits its first byte.
    @Test
    void invalidOwnershipWritesNoBytes() {
        CompoundTag root = new CompoundTag().addInt("value", 1);
        Tag child = root.get("value");
        child.setIndex(7);

        assertThrows(NBTValidationException.class, () -> NBTStructureValidator.validate(root));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> NBTCodec.of().writeTag(output, root));
        assertEquals(0, output.size());
        IOException byteArrayFailure = assertThrows(IOException.class,
                () -> NBTCodec.of().writeTagToByteArray(root));
        assertInstanceOf(NBTValidationException.class, byteArrayFailure.getCause());
    }

    /// Ensures a stale extra compound name-index entry cannot pass complete validation.
    @Test
    void rejectsCompoundNameIndexCardinalityMismatch() {
        CompoundTag root = new CompoundTag().addInt("value", 1);
        root.size = 0;

        assertThrows(NBTValidationException.class, () -> NBTStructureValidator.validate(root));
    }

    /// Ensures validation inspects only existing lazy array wrappers and does not create new ones.
    @Test
    void validatesPrimitiveArrayWithoutMaterializingChildren() throws NBTValidationException {
        ByteArrayTag array = new ByteArrayTag(new byte[4096]);
        Tag materialized = array.getTag(3);
        Tag[] storage = array.tags;

        NBTStructureValidator.validate(array);

        assertSame(storage, array.tags);
        assertSame(materialized, array.tags[3]);
    }

    /// Ensures primitive array logical sizes cannot exceed their backing value storage.
    @Test
    void rejectsPrimitiveArrayBackingLengthMismatch() {
        ByteArrayTag array = new ByteArrayTag(new byte[]{1, 2});
        array.values = new byte[1];

        assertThrows(NBTValidationException.class, () -> NBTStructureValidator.validate(array));
    }

    /// Ensures Java Edition string lengths are measured in encoded bytes.
    @Test
    void rejectsStringsBeyondUnsignedShortEncodingLimit() {
        CompoundTag accepted = new CompoundTag().addString("value", "a".repeat(0xFFFF));
        CompoundTag rejected = new CompoundTag().addString("value", "a".repeat(0x10000));

        assertDoesNotThrow(() -> NBTStructureValidator.validate(accepted));
        assertThrows(NBTValidationException.class, () -> NBTStructureValidator.validate(rejected));
    }

    /// Ensures a valid attached child can still be serialized as a standalone subtree.
    @Test
    void writerAcceptsAttachedSubtree() throws IOException {
        CompoundTag parent = new CompoundTag().addInt("value", 42);
        Tag attached = parent.get("value");

        byte[] bytes = NBTCodec.of().writeTagToByteArray(attached);

        assertEquals(attached, NBTCodec.of().readTag(bytes));
    }
}

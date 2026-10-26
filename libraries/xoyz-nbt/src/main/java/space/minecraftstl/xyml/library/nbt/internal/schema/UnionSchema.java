// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt.internal.schema;

import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.validation.NBTSchema;
import space.minecraftstl.xyml.library.nbt.validation.NBTValidationException;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

public record UnionSchema<T extends Tag>(@Unmodifiable List<NBTSchema<? extends T>> schemas) implements TagSchema<T> {

    public UnionSchema {
        assert schemas.size() > 1;
    }

    @Override
    public boolean testTag(Tag tag) {
        for (NBTSchema<?> schema : schemas) {
            if (((TagSchema<?>) schema).testTag(tag)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void validateTag(Tag tag) throws NBTValidationException {
        if (testTag(tag)) {
            return;
        }

        var exception = new NBTValidationException("No schema matched");
        for (NBTSchema<?> schema : schemas) {
            try {
                ((TagSchema<?>) schema).validateTag(tag);
                throw new AssertionError("Unexpected success");
            } catch (NBTValidationException e) {
                exception.addSuppressed(e);
            }
        }
        throw exception;
    }
}

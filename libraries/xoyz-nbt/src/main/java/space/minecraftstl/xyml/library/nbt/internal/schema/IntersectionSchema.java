// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt.internal.schema;

import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.validation.NBTSchema;
import space.minecraftstl.xyml.library.nbt.validation.NBTValidationException;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

public record IntersectionSchema<T extends Tag>(
        @Unmodifiable List<NBTSchema<? extends T>> schemas) implements TagSchema<T> {

    public IntersectionSchema {
        assert schemas.size() > 1;
    }

    @Override
    public boolean testTag(Tag tag) {
        for (NBTSchema<?> schema : schemas) {
            if (!((TagSchema<?>) schema).testTag(tag)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void validateTag(Tag tag) throws NBTValidationException {
        for (NBTSchema<?> schema : schemas) {
            try {
                ((TagSchema<?>) schema).validateTag(tag);
            } catch (NBTValidationException e) {
                throw new NBTValidationException("Failed to validate tag against schema: " + schema, e);
            }
        }
    }
}

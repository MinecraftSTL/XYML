// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt.internal.schema;

import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import space.minecraftstl.xyml.library.nbt.validation.NBTValidationException;

public record TypeIsSchema<T extends Tag>(TagType<? extends T> type) implements TagSchema<T> {
    @Override
    public boolean testTag(Tag tag) {
        return type.equals(tag.getType());
    }

    @Override
    public void validateTag(Tag tag) throws NBTValidationException {
        if (!testTag(tag)) {
            throw new NBTValidationException("Expected " + type + ", but got " + tag.getType());
        }
    }
}

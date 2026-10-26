// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt.internal.schema;

import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.validation.NBTSchema;
import space.minecraftstl.xyml.library.nbt.validation.NBTValidationException;
import org.jetbrains.annotations.ApiStatus;

public sealed interface TagSchema<T extends Tag> extends NBTSchema<T>
        permits CompoundTagSchema, IntersectionSchema, MatchSchema, TypeIsSchema, UnionSchema {

    boolean testTag(Tag tag);

    default void validateTag(Tag tag) throws NBTValidationException {
        if (!testTag(tag)) {
            throw new NBTValidationException("Invalid NBT tag: " + tag);
        }
    }

    @Override
    @ApiStatus.NonExtendable
    default boolean test(T t) {
        return testTag(t);
    }

    @Override
    @ApiStatus.NonExtendable
    default void validate(T element) throws NBTValidationException {
        validateTag(element);
    }
}

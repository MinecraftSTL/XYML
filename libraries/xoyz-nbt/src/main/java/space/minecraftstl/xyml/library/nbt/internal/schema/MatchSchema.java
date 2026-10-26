// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt.internal.schema;

import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.validation.NBTValidationException;
import org.jetbrains.annotations.Unmodifiable;

public record MatchSchema<T extends Tag>(@Unmodifiable T expected) implements TagSchema<T> {
    public static boolean match(Tag tag, CompoundTag expected) {
        if (tag instanceof CompoundTag compoundTag) {
            for (Tag expectedSubTag : expected) {
                Tag actualSubTag = compoundTag.get(expectedSubTag.getName());
                if (actualSubTag == null || expectedSubTag.getClass() != actualSubTag.getClass()) {
                    return false;
                }

                if (expectedSubTag instanceof CompoundTag) {
                    if (!match(actualSubTag, (CompoundTag) expectedSubTag)) {
                        return false;
                    }
                } else {
                    if (!expectedSubTag.contentEquals(actualSubTag)) {
                        return false;
                    }
                }

            }
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean testTag(Tag tag) {
        if (expected instanceof CompoundTag compoundTag) {
            return match(tag, compoundTag);
        } else {
            return expected.equals(tag);
        }
    }

    @Override
    public void validateTag(Tag tag) throws NBTValidationException {
        if (!testTag(tag)) {
            throw new NBTValidationException("Tag does not match the expected schema: " + expected);
        }
    }
}

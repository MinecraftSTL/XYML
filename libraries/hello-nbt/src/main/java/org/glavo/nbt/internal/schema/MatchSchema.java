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
package org.glavo.nbt.internal.schema;

import org.glavo.nbt.tag.CompoundTag;
import org.glavo.nbt.tag.Tag;
import org.glavo.nbt.validation.NBTValidationException;
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

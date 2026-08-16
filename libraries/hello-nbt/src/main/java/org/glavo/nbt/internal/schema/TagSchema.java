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

import org.glavo.nbt.tag.Tag;
import org.glavo.nbt.validation.NBTSchema;
import org.glavo.nbt.validation.NBTValidationException;
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

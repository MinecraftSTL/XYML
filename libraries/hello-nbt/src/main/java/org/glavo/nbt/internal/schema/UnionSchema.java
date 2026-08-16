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

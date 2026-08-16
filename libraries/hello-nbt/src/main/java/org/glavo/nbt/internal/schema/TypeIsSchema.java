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
import org.glavo.nbt.tag.TagType;
import org.glavo.nbt.validation.NBTValidationException;

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


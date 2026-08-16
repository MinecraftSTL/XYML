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
import org.glavo.nbt.tag.TagType;
import org.glavo.nbt.validation.NBTSchema;
import org.glavo.nbt.validation.NBTValidationException;
import org.jetbrains.annotations.Unmodifiable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public record CompoundTagSchema(@Unmodifiable List<SchemaEntry> entries) implements TagSchema<CompoundTag> {

    @Override
    public boolean testTag(Tag tag) {
        if (!(tag instanceof CompoundTag compoundTag)) {
            return false;
        }

        for (SchemaEntry entry : entries) {
            Tag subTag = compoundTag.get(entry.name());
            if (subTag == null) {
                if (entry.required) {
                    return false;
                }
            } else {
                if (!entry.schema.testTag(subTag)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void validateTag(Tag tag) throws NBTValidationException {
        if (!(tag instanceof CompoundTag compoundTag)) {
            throw new NBTValidationException("Expected TAG_Compound, but got " + tag.getType());
        }

        for (SchemaEntry entry : entries) {
            Tag subTag = compoundTag.get(entry.name());
            if (subTag != null) {
                try {
                    entry.schema.validateTag(subTag);
                } catch (NBTValidationException e) {
                    throw new NBTValidationException("Invalid subtag " + entry.name(), e);
                }
            } else {
                if (entry.required) {
                    throw new NBTValidationException("Missing required subtag: " + entry.name());
                }
            }
        }
    }

    public record SchemaEntry(String name, TagSchema<?> schema, boolean required) {
    }

    public static final class Builder implements NBTSchema.Builder.OfCompoundTag {
        private final LinkedHashMap<String, SchemaEntry> entries = new LinkedHashMap<>();

        private void add(String name, NBTSchema<?> schema, boolean required) {
            entries.put(name, new SchemaEntry(name, (TagSchema<?>) schema, required));
        }

        @Override
        public OfCompoundTag addRequired(String name, NBTSchema<?> schema) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(schema, "schema");

            add(name, schema, true);
            return this;
        }

        @Override
        public OfCompoundTag addOptional(String name, NBTSchema<?> schema) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(schema, "schema");

            add(name, schema, false);
            return this;
        }

        @Override
        public OfCompoundTag addRequired(String name, TagType<?> type) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");

            add(name, new TypeIsSchema<>(type), true);
            return this;
        }

        @Override
        public OfCompoundTag addOptional(String name, TagType<?> type) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");

            add(name, new TypeIsSchema<>(type), false);
            return this;
        }

        @Override
        public OfCompoundTag addRequired(String name, Tag tag) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(tag, "tag");

            add(name, new MatchSchema<>(tag.clone()), true);
            return this;
        }

        @Override
        public OfCompoundTag addOptional(String name, Tag tag) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(tag, "tag");

            add(name, new MatchSchema<>(tag.clone()), false);
            return this;
        }

        @Override
        public NBTSchema<CompoundTag> end() {
            return new CompoundTagSchema(List.copyOf(entries.values()));
        }
    }
}

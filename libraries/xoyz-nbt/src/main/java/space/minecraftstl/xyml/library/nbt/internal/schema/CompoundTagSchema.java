// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt.internal.schema;

import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import space.minecraftstl.xyml.library.nbt.validation.NBTSchema;
import space.minecraftstl.xyml.library.nbt.validation.NBTValidationException;
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

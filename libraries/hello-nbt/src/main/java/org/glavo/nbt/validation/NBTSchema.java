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
package org.glavo.nbt.validation;

import org.glavo.nbt.internal.schema.*;
import org.glavo.nbt.tag.CompoundTag;
import org.glavo.nbt.tag.Tag;
import org.glavo.nbt.tag.TagType;
import org.jetbrains.annotations.Contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// A schema for validating NBT elements.
///
/// This interface defines a schema for validating NBT elements.
/// It provides methods for creating schemas for specific types of NBT elements,
/// as well as for combining schemas using logical operators.
public sealed interface NBTSchema<T extends Tag> extends NBTValidator<T>
        permits org.glavo.nbt.internal.schema.TagSchema {

    /// Narrow the schema to a specific type.
    static <T extends Tag> NBTSchema<T> narrow(NBTSchema<? extends T> schema) {
        @SuppressWarnings("unchecked")
        var casted = (NBTSchema<T>) schema;
        return casted;
    }

    /// Creates a schema that validates a tag of the given type.
    ///
    /// @param type The type of the tag.
    /// @return A schema that validates a tag of the given type.
    static <T extends Tag> NBTSchema<T> typeIs(TagType<? extends T> type) {
        Objects.requireNonNull(type, "type");
        return new TypeIsSchema<>(type);
    }

    /// Creates a schema that validates a tag that matches the given expected tag.
    ///
    /// If the expected tag is a compound tag, the schema will validate if the actual tag is a compound tag and contains all the subtags of the expected tag;
    /// otherwise, it will validate if the actual tag is equal to the expected tag.
    ///
    /// @param expected The expected tag.
    /// @return A schema that validates a tag that matches the given expected tag.
    static <T extends Tag> NBTSchema<T> match(T expected) {
        Objects.requireNonNull(expected, "expected");
        @SuppressWarnings("unchecked")
        T cloned = (T) expected.clone();
        return new MatchSchema<>(cloned);
    }

    /// Creates a schema that validates any of the given schemas.
    ///
    /// @throws IllegalArgumentException if no schemas are provided.
    @SafeVarargs
    @Contract(pure = true)
    static <T extends Tag> NBTSchema<T> union(NBTSchema<? extends T>... schemas) {
        if (schemas.length == 0) {
            throw new IllegalArgumentException("No schemas provided");
        }
        if (schemas.length == 1) {
            return narrow(schemas[0]);
        }

        var list = new ArrayList<NBTSchema<? extends T>>(schemas.length);
        for (NBTSchema<? extends T> schema : schemas) {
            if (schema instanceof UnionSchema<? extends T> union) {
                list.addAll(union.schemas());
            } else {
                list.add(schema);
            }
        }
        return new UnionSchema<>(List.copyOf(list));
    }

    /// Creates a schema that validates only if all the given schemas validate.
    ///
    /// @throws IllegalArgumentException if no schemas are provided.
    @SafeVarargs
    @Contract(pure = true)
    static <T extends Tag> NBTSchema<T> intersection(NBTSchema<? extends T>... schemas) {
        if (schemas.length == 0) {
            throw new IllegalArgumentException("No schemas provided");
        }
        if (schemas.length == 1) {
            return narrow(schemas[0]);
        }

        var list = new ArrayList<NBTSchema<? extends T>>(schemas.length);
        for (NBTSchema<? extends T> schema : schemas) {
            if (schema instanceof IntersectionSchema<? extends T> intersection) {
                list.addAll(intersection.schemas());
            } else {
                list.add(schema);
            }
        }
        return new IntersectionSchema<>(List.copyOf(list));
    }


    @Contract(value = " -> new", pure = true)
    static NBTSchema.Builder.OfCompoundTag beginCompound() {
        return new CompoundTagSchema.Builder();
    }

    /// Creates a schema that validates only if both of the given schemas validate.
    ///
    /// @throws IllegalArgumentException if either schema is null.
    default NBTSchema<T> and(NBTSchema<? extends T> other) {
        return intersection(this, other);
    }

    /// Creates a schema that validates if either of the given schemas validate.
    ///
    /// @throws IllegalArgumentException if either schema is null.
    default NBTSchema<T> or(NBTSchema<? extends T> other) {
        return union(this, other);
    }

    sealed interface Builder<T extends Tag> {
        /// Builds a schema for validating NBT elements.
        @Contract(pure = true)
        NBTSchema<T> end();

        sealed interface OfCompoundTag extends Builder<CompoundTag>
                permits org.glavo.nbt.internal.schema.CompoundTagSchema.Builder {

            /// Adds a required subtag to the schema.
            @Contract(value = "_, _ -> this", mutates = "this")
            OfCompoundTag addRequired(String name, NBTSchema<?> schema);

            /// Adds an optional subtag to the schema.
            @Contract(value = "_, _ -> this", mutates = "this")
            OfCompoundTag addOptional(String name, NBTSchema<?> schema);

            /// Adds a required subtag of the given type to the schema.
            @Contract(value = "_, _ -> this", mutates = "this")
            OfCompoundTag addRequired(String name, TagType<?> type);

            /// Adds an optional subtag of the given type to the schema.
            @Contract(value = "_, _ -> this", mutates = "this")
            OfCompoundTag addOptional(String name, TagType<?> type);

            /// Adds a required subtag of the given tag to the schema.
            ///
            /// @see #match(Tag)
            @Contract(value = "_, _ -> this", mutates = "this")
            OfCompoundTag addRequired(String name, Tag tag);

            /// Adds an optional subtag of the given tag to the schema.
            ///
            /// @see #match(Tag)
            @Contract(value = "_, _ -> this", mutates = "this")
            OfCompoundTag addOptional(String name, Tag tag);
        }
    }
}

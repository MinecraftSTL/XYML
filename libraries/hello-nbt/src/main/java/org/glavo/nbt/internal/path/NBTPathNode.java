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
package org.glavo.nbt.internal.path;

import org.glavo.nbt.internal.schema.MatchSchema;
import org.glavo.nbt.internal.snbt.SNBTWriter;
import org.glavo.nbt.io.SNBTCodec;
import org.glavo.nbt.tag.*;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.stream.Stream;

public sealed interface NBTPathNode {

    @Unmodifiable
    CompoundTag EMPTY_COMPOUND_TAG = new CompoundTag();


    private static boolean isListOrArray(ParentTag<?> tag) {
        return tag instanceof ListTag || tag instanceof ArrayTag;
    }

    private static String toString(NBTPathNode node) {
        var writer = new SNBTWriter<>(SNBTCodec.ofCompact(), new StringBuilder());
        try {
            node.appendTo(writer);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return writer.getAppendable().toString();
    }

    Stream<? extends Tag> operate(Stream<? extends Tag> tags);

    @Nullable TagType<?> getTagType();

    default boolean needDot() {
        return this instanceof NamedSubTag || this instanceof NamedSubCompoundTag;
    }

    void appendTo(SNBTWriter<StringBuilder> writer) throws IOException;

    record Root(@Unmodifiable CompoundTag tags) implements NBTPathNode {
        public static final Root EMPTY = new Root(EMPTY_COMPOUND_TAG);

        @Override
        public Stream<? extends Tag> operate(Stream<? extends Tag> tags) {
            return tags.filter(tag -> MatchSchema.match(tag, this.tags));
        }

        @Override
        public TagType<?> getTagType() {
            return TagType.COMPOUND;
        }

        @Override
        public void appendTo(SNBTWriter<StringBuilder> writer) throws IOException {
            writer.writeTag(tags);
        }

        @Override
        public String toString() {
            return tags.isEmpty() ? "{}" : NBTPathNode.toString(this);
        }
    }

    record NamedSubTag(String name) implements NBTPathNode {
        @Override
        public Stream<? extends Tag> operate(Stream<? extends Tag> tags) {
            //noinspection NullableProblems
            return tags.flatMap(tag -> tag instanceof CompoundTag compoundTag
                    ? Stream.ofNullable(compoundTag.get(name))
                    : Stream.empty());
        }

        @Override
        public @Nullable TagType<?> getTagType() {
            return null;
        }

        @Override
        public void appendTo(SNBTWriter<StringBuilder> writer) throws IOException {
            writer.writeTagName(name);
        }

        @Override
        public String toString() {
            return NBTPathNode.toString(this);
        }
    }

    record NamedSubCompoundTag(String name, @Unmodifiable CompoundTag tags) implements NBTPathNode {

        @Override
        public Stream<? extends Tag> operate(Stream<? extends Tag> tags) {
            return tags.flatMap(tag -> {
                if (tag instanceof CompoundTag compoundTag) {
                    Tag subTag = compoundTag.get(name);
                    if (subTag != null && MatchSchema.match(subTag, this.tags)) {
                        return Stream.of(subTag);
                    }
                }

                return Stream.empty();
            });
        }

        @Override
        public TagType<?> getTagType() {
            return TagType.COMPOUND;
        }

        @Override
        public void appendTo(SNBTWriter<StringBuilder> writer) throws IOException {
            writer.writeTagName(name);
            writer.writeTag(tags);
        }

        @Override
        public String toString() {
            return NBTPathNode.toString(this);
        }
    }

    record AllElements() implements NBTPathNode {
        public static final AllElements INSTANCE = new AllElements();

        @Override
        public Stream<? extends Tag> operate(Stream<? extends Tag> tags) {
            return tags.flatMap(tag -> {
                if (tag instanceof ParentTag<?> parentTag && isListOrArray(parentTag)) {
                    return parentTag.stream();
                } else {
                    return Stream.empty();
                }
            });
        }

        @Override
        public @Nullable TagType<?> getTagType() {
            return null;
        }

        @Override
        public void appendTo(SNBTWriter<StringBuilder> writer) throws IOException {
            writer.getAppendable().append("[]");
        }

        @Override
        public String toString() {
            return "[]";
        }
    }

    record Index(int index) implements NBTPathNode {

        @Override
        public Stream<? extends Tag> operate(Stream<? extends Tag> tags) {
            return tags.flatMap(tag -> {
                if (tag instanceof ParentTag<?> parentTag && isListOrArray(parentTag)) {
                    int actualIndex;
                    if (index >= 0) {
                        actualIndex = index;
                    } else {
                        actualIndex = parentTag.size() + index;
                    }

                    if (actualIndex >= 0 && actualIndex < parentTag.size()) {
                        return Stream.of(parentTag.getTag(actualIndex));
                    }
                }
                return Stream.empty();
            });
        }

        @Override
        public @Nullable TagType<?> getTagType() {
            return null;
        }

        @Override
        public void appendTo(SNBTWriter<StringBuilder> writer) throws IOException {
            writer.getAppendable().append("[").append(index).append(']');
        }

        @Override
        public String toString() {
            return "[" + index + "]";
        }
    }

    record CompoundElements(@Unmodifiable CompoundTag tags) implements NBTPathNode {
        public static final CompoundElements EMPTY = new CompoundElements(EMPTY_COMPOUND_TAG);

        @Override
        public Stream<? extends Tag> operate(Stream<? extends Tag> tags) {
            return tags.flatMap(tag -> {
                if (tag instanceof ListTag<?> listTag) {
                    return listTag.stream().filter(subTag -> MatchSchema.match(subTag, this.tags));
                }

                return Stream.empty();
            });
        }

        @Override
        public TagType<?> getTagType() {
            return TagType.COMPOUND;
        }

        @Override
        public void appendTo(SNBTWriter<StringBuilder> writer) throws IOException {
            writer.getAppendable().append('[');
            writer.writeTag(tags);
            writer.getAppendable().append(']');
        }

        @Override
        public String toString() {
            return NBTPathNode.toString(this);
        }
    }
}

// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt.internal.path;

import space.minecraftstl.xyml.library.nbt.internal.schema.MatchSchema;
import space.minecraftstl.xyml.library.nbt.internal.snbt.SNBTWriter;
import space.minecraftstl.xyml.library.nbt.io.SNBTCodec;
import space.minecraftstl.xyml.library.nbt.tag.*;
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

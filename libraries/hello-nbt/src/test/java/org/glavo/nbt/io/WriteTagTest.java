/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glavo.nbt.io;

import com.github.steveice10.opennbt.NBTIO;
import org.glavo.nbt.tag.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class WriteTagTest {

    sealed interface Validator {
        MinecraftEdition edition();

        byte[] toByteArray(Tag tag) throws IOException;

        default void assertTagEquals(
                com.github.steveice10.opennbt.tag.builtin.Tag expected,
                Tag actual) throws IOException {
            assertEquals(expected, NBTIO.readTag(
                    new ByteArrayInputStream(toByteArray(actual)),
                    edition() == MinecraftEdition.BEDROCK_EDITION));
        }

        default void assertByteSize(Tag tag) throws IOException {
            assertEquals(toByteArray(tag).length, NBTCodec.of(edition()).byteSize(tag));
        }

        record OutputStream(MinecraftEdition edition) implements Validator {
            @Override
            public byte[] toByteArray(Tag tag) throws IOException {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                NBTCodec.of(edition).writeTag(buffer, tag);
                return buffer.toByteArray();
            }
        }

        record ByteChannel(MinecraftEdition edition) implements Validator {
            @Override
            public byte[] toByteArray(Tag tag) throws IOException {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (WritableByteChannel channel = Channels.newChannel(buffer)) {
                    NBTCodec.of(edition).writeTag(channel, tag);
                }
                return buffer.toByteArray();
            }
        }

        record ByteBuffer(MinecraftEdition edition) implements Validator {
            @Override
            public byte[] toByteArray(Tag tag) throws IOException {
                return NBTCodec.of(edition).writeTagToByteArray(tag);
            }
        }
    }

    static Stream<Validator> validators() {
        return Stream.of(MinecraftEdition.values()).flatMap(edition -> Stream.of(
                new Validator.OutputStream(edition),
                new Validator.ByteChannel(edition),
                new Validator.ByteBuffer(edition)
        ));
    }

    @ParameterizedTest
    @MethodSource("validators")
    void testWriteSimpleTag(Validator validator) throws IOException {
        validator.assertTagEquals(new com.github.steveice10.opennbt.tag.builtin.ByteTag("Meow", (byte) 42), new ByteTag((byte) 42).setName("Meow"));
        validator.assertTagEquals(new com.github.steveice10.opennbt.tag.builtin.ShortTag("Meow", (short) 42), new ShortTag((short) 42).setName("Meow"));
        validator.assertTagEquals(new com.github.steveice10.opennbt.tag.builtin.IntTag("Meow", 42), new IntTag(42).setName("Meow"));
        validator.assertTagEquals(new com.github.steveice10.opennbt.tag.builtin.LongTag("Meow", 42L), new LongTag(42L).setName("Meow"));
        validator.assertTagEquals(new com.github.steveice10.opennbt.tag.builtin.FloatTag("Meow", 42.0f), new FloatTag(42.0f).setName("Meow"));
        validator.assertTagEquals(new com.github.steveice10.opennbt.tag.builtin.DoubleTag("Meow", 42.0), new DoubleTag(42.0).setName("Meow"));
        validator.assertTagEquals(new com.github.steveice10.opennbt.tag.builtin.StringTag("Meow", "Glavo"), new StringTag("Glavo").setName("Meow"));
        validator.assertTagEquals(new com.github.steveice10.opennbt.tag.builtin.ByteArrayTag("Meow", new byte[]{1, 2, 3}), new ByteArrayTag(new byte[]{1, 2, 3}).setName("Meow"));
        validator.assertTagEquals(new com.github.steveice10.opennbt.tag.builtin.IntArrayTag("Meow", new int[]{1, 2, 3}), new IntArrayTag(new int[]{1, 2, 3}).setName("Meow"));
        validator.assertTagEquals(new com.github.steveice10.opennbt.tag.builtin.LongArrayTag("Meow", new long[]{1, 2, 3}), new LongArrayTag(new long[]{1, 2, 3}).setName("Meow"));

        {
            var expected = new com.github.steveice10.opennbt.tag.builtin.ListTag("Meow", com.github.steveice10.opennbt.tag.builtin.IntTag.class);
            var actual = new ListTag<>(TagType.INT).setName("Meow");

            for (int i = 0; i < 10000; i++) {
                expected.add(new com.github.steveice10.opennbt.tag.builtin.IntTag("", i));
                actual.addTag(new IntTag(i));
            }

            validator.assertTagEquals(expected, actual);
        }

        {
            var expected = new com.github.steveice10.opennbt.tag.builtin.CompoundTag("Meow");
            expected.put(new com.github.steveice10.opennbt.tag.builtin.ByteTag("Sub0", (byte) 42));
            expected.put(new com.github.steveice10.opennbt.tag.builtin.ShortTag("Sub1", (short) 42));
            expected.put(new com.github.steveice10.opennbt.tag.builtin.IntTag("Sub2", 42));
            expected.put(new com.github.steveice10.opennbt.tag.builtin.LongTag("Sub3", 42L));
            expected.put(new com.github.steveice10.opennbt.tag.builtin.FloatTag("Sub4", 42.0f));
            expected.put(new com.github.steveice10.opennbt.tag.builtin.DoubleTag("Sub5", 42.0));
            expected.put(new com.github.steveice10.opennbt.tag.builtin.StringTag("Sub6", "Glavo"));
            expected.put(new com.github.steveice10.opennbt.tag.builtin.ByteArrayTag("Sub7", new byte[]{1, 2, 3}));
            expected.put(new com.github.steveice10.opennbt.tag.builtin.IntArrayTag("Sub8", new int[]{1, 2, 3}));
            expected.put(new com.github.steveice10.opennbt.tag.builtin.LongArrayTag("Sub9", new long[]{1, 2, 3}));
            {
                var sub10 = new com.github.steveice10.opennbt.tag.builtin.CompoundTag("Sub10");
                sub10.put(new com.github.steveice10.opennbt.tag.builtin.ByteTag("Sub10Sub0", (byte) 42));
                expected.put(sub10);
            }
            {
                var sub11 = new com.github.steveice10.opennbt.tag.builtin.ListTag("Sub11", com.github.steveice10.opennbt.tag.builtin.IntTag.class);
                for (int i = 0; i < 10000; i++) {
                    sub11.add(new com.github.steveice10.opennbt.tag.builtin.IntTag("", i));
                }
                expected.put(sub11);
            }

            var actual = new CompoundTag().setName("Meow");
            actual.addTag("Sub0", new ByteTag((byte) 42));
            actual.addTag("Sub1", new ShortTag((short) 42));
            actual.addTag("Sub2", new IntTag(42));
            actual.addTag("Sub3", new LongTag(42L));
            actual.addTag("Sub4", new FloatTag(42.0f));
            actual.addTag("Sub5", new DoubleTag(42.0));
            actual.addTag("Sub6", new StringTag("Glavo"));
            actual.addTag("Sub7", new ByteArrayTag(new byte[]{1, 2, 3}));
            actual.addTag("Sub8", new IntArrayTag(new int[]{1, 2, 3}));
            actual.addTag("Sub9", new LongArrayTag(new long[]{1, 2, 3}));
            {
                var sub10 = new CompoundTag().setName("Sub10");
                sub10.addTag("Sub10Sub0", new ByteTag((byte) 42));
                actual.addTag(sub10);
            }
            {
                var sub11 = new ListTag<>(TagType.INT).setName("Sub11");
                for (int i = 0; i < 10000; i++) {
                    sub11.addTag(new IntTag(i));
                }
                actual.addTag(sub11);
            }

            validator.assertTagEquals(expected, actual);
        }
    }

    @ParameterizedTest
    @MethodSource("validators")
    void testWriteModifiedUTF8String(Validator validator) throws IOException {
        String value = "ABCǾ喵喵喵🐱ABC123";

        var expected = new com.github.steveice10.opennbt.tag.builtin.StringTag("Meow", value);
        var actual = new StringTag(value).setName("Meow");
        validator.assertTagEquals(expected, actual);
    }

    @ParameterizedTest
    @MethodSource("validators")
    void testByteSize(Validator validator) throws IOException {
        validator.assertByteSize(new ByteTag());
        validator.assertByteSize(new ByteTag((byte) 42));
        validator.assertByteSize(new ByteTag((byte) 42).setName("Meow"));
        validator.assertByteSize(new ShortTag());
        validator.assertByteSize(new ShortTag((short) 42));
        validator.assertByteSize(new ShortTag((short) 42).setName("Meow"));
        validator.assertByteSize(new IntTag());
        validator.assertByteSize(new IntTag(42));
        validator.assertByteSize(new IntTag(42).setName("Meow"));
        validator.assertByteSize(new LongTag());
        validator.assertByteSize(new LongTag(42L));
        validator.assertByteSize(new LongTag(42L).setName("Meow"));
        validator.assertByteSize(new FloatTag());
        validator.assertByteSize(new FloatTag(42.0f));
        validator.assertByteSize(new FloatTag(42.0f).setName("Meow"));
        validator.assertByteSize(new DoubleTag());
        validator.assertByteSize(new DoubleTag(42.0));
        validator.assertByteSize(new DoubleTag(42.0).setName("Meow"));
        validator.assertByteSize(new StringTag());
        validator.assertByteSize(new StringTag("Meow"));
        validator.assertByteSize(new StringTag("Meow").setName("Meow"));
        validator.assertByteSize(new StringTag("Meow\0你好😄"));
        validator.assertByteSize(new ByteArrayTag());
        validator.assertByteSize(new ByteArrayTag(new byte[]{1, 2, 3}));
        validator.assertByteSize(new ByteArrayTag(new byte[]{1, 2, 3}).setName("Meow"));
        validator.assertByteSize(new IntArrayTag());
        validator.assertByteSize(new IntArrayTag(new int[]{1, 2, 3}));
        validator.assertByteSize(new IntArrayTag(new int[]{1, 2, 3}).setName("Meow"));
        validator.assertByteSize(new LongArrayTag());
        validator.assertByteSize(new LongArrayTag(new long[]{1, 2, 3}));
        validator.assertByteSize(new LongArrayTag(new long[]{1, 2, 3}).setName("Meow"));
        validator.assertByteSize(new ListTag<>());
        validator.assertByteSize(new ListTag<>(TagType.INT));
        validator.assertByteSize(new ListTag<>(TagType.INT)
                .addTag(new IntTag(42))
                .addTag(new IntTag(43))
                .addTag(new IntTag(44)));
        validator.assertByteSize(new ListTag<>()
                .addAnyTag(new IntTag(42))
                .addAnyTag(new StringTag("Meow"))
                .addAnyTag(new LongArrayTag(new long[]{1, 2, 3})));
        validator.assertByteSize(new CompoundTag());
        validator.assertByteSize(new CompoundTag()
                .addByte("int", (byte) 42)
                .addString("string", "Meow"));
    }
}

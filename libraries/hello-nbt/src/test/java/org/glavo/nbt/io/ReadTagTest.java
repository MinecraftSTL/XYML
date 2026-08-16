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
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class ReadTagTest {

    sealed interface Validator {
        MinecraftEdition edition();

        Tag fromByteArray(byte[] bytes) throws IOException;

        default void assertTagEquals(
                Tag expected,
                com.github.steveice10.opennbt.tag.builtin.Tag actual) throws IOException {
            var buffer = new ByteArrayOutputStream();
            NBTIO.writeTag(buffer, actual, edition() == MinecraftEdition.BEDROCK_EDITION);

            assertEquals(expected, fromByteArray(buffer.toByteArray()));
        }

        record InputStream(MinecraftEdition edition) implements Validator {
            @Override
            public Tag fromByteArray(byte[] bytes) throws IOException {
                return NBTCodec.of(edition).readTag(new ByteArrayInputStream(bytes));
            }
        }

        record ByteChannel(MinecraftEdition edition) implements Validator {
            @Override
            public Tag fromByteArray(byte[] bytes) throws IOException {
                try (var channel = Channels.newChannel(new ByteArrayInputStream(bytes))) {
                    return NBTCodec.of(edition).readTag(channel);
                }
            }
        }

        record ByteArray(MinecraftEdition edition) implements Validator {
            @Override
            public Tag fromByteArray(byte[] bytes) throws IOException {
                return NBTCodec.of(edition).readTag(bytes);
            }
        }

        record HeapByteBuffer(MinecraftEdition edition) implements Validator {
            @Override
            public Tag fromByteArray(byte[] bytes) throws IOException {
                return NBTCodec.of(edition).readTag(ByteBuffer.wrap(bytes));
            }
        }

        record DirectByteBuffer(MinecraftEdition edition) implements Validator {
            @Override
            public Tag fromByteArray(byte[] bytes) throws IOException {
                return NBTCodec.of(edition).readTag(ByteBuffer.allocateDirect(bytes.length).put(bytes).flip());
            }
        }
    }

    static Stream<Validator> validators() {
        return Stream.of(MinecraftEdition.values()).flatMap(edition -> Stream.of(
                new Validator.InputStream(edition),
                new Validator.ByteChannel(edition),
                new Validator.ByteArray(edition),
                new Validator.HeapByteBuffer(edition),
                new Validator.DirectByteBuffer(edition)
        ));
    }

    @ParameterizedTest
    @MethodSource("validators")
    void testReadSimpleTag(Validator validator) throws IOException {
        validator.assertTagEquals(new ByteTag((byte) 42).setName("Meow"), new com.github.steveice10.opennbt.tag.builtin.ByteTag("Meow", (byte) 42));
        validator.assertTagEquals(new ShortTag((short) 42).setName("Meow"), new com.github.steveice10.opennbt.tag.builtin.ShortTag("Meow", (short) 42));
        validator.assertTagEquals(new IntTag(42).setName("Meow"), new com.github.steveice10.opennbt.tag.builtin.IntTag("Meow", 42));
        validator.assertTagEquals(new LongTag(42L).setName("Meow"), new com.github.steveice10.opennbt.tag.builtin.LongTag("Meow", 42L));
        validator.assertTagEquals(new FloatTag(42.0f).setName("Meow"), new com.github.steveice10.opennbt.tag.builtin.FloatTag("Meow", 42.0f));
        validator.assertTagEquals(new DoubleTag(42.0).setName("Meow"), new com.github.steveice10.opennbt.tag.builtin.DoubleTag("Meow", 42.0));
        validator.assertTagEquals(new StringTag("Glavo").setName("Meow"), new com.github.steveice10.opennbt.tag.builtin.StringTag("Meow", "Glavo"));
        validator.assertTagEquals(new ByteArrayTag(new byte[]{1, 2, 3}).setName("Meow"), new com.github.steveice10.opennbt.tag.builtin.ByteArrayTag("Meow", new byte[]{1, 2, 3}));
        validator.assertTagEquals(new IntArrayTag(new int[]{1, 2, 3}).setName("Meow"), new com.github.steveice10.opennbt.tag.builtin.IntArrayTag("Meow", new int[]{1, 2, 3}));
        validator.assertTagEquals(new LongArrayTag(new long[]{1, 2, 3}).setName("Meow"), new com.github.steveice10.opennbt.tag.builtin.LongArrayTag("Meow", new long[]{1, 2, 3}));

        {
            var expected = new ListTag<>(TagType.INT).setName("Meow");
            var actual = new com.github.steveice10.opennbt.tag.builtin.ListTag("Meow", com.github.steveice10.opennbt.tag.builtin.IntTag.class);

            for (int i = 0; i < 10000; i++) {
                expected.addTag(new IntTag(i));
                actual.add(new com.github.steveice10.opennbt.tag.builtin.IntTag("", i));
            }

            validator.assertTagEquals(expected, actual);
        }

        {
            var expected = new CompoundTag().setName("Meow");
            expected.addTag("Sub0", new ByteTag((byte) 42));
            expected.addTag("Sub1", new ShortTag((short) 42));
            expected.addTag("Sub2", new IntTag(42));
            expected.addTag("Sub3", new LongTag(42L));
            expected.addTag("Sub4", new FloatTag(42.0f));
            expected.addTag("Sub5", new DoubleTag(42.0));
            expected.addTag("Sub6", new StringTag("Glavo"));
            expected.addTag("Sub7", new ByteArrayTag(new byte[]{1, 2, 3}));
            expected.addTag("Sub8", new IntArrayTag(new int[]{1, 2, 3}));
            expected.addTag("Sub9", new LongArrayTag(new long[]{1, 2, 3}));
            {
                var sub10 = new CompoundTag().setName("Sub10");
                sub10.addTag("Sub10Sub0", new ByteTag((byte) 42));
                expected.addTag(sub10);
            }
            {
                var sub11 = new ListTag<>(TagType.INT).setName("Sub11");
                for (int i = 0; i < 10000; i++) {
                    sub11.addTag(new IntTag(i));
                }
                expected.addTag(sub11);
            }

            var actual = new com.github.steveice10.opennbt.tag.builtin.CompoundTag("Meow");
            actual.put(new com.github.steveice10.opennbt.tag.builtin.ByteTag("Sub0", (byte) 42));
            actual.put(new com.github.steveice10.opennbt.tag.builtin.ShortTag("Sub1", (short) 42));
            actual.put(new com.github.steveice10.opennbt.tag.builtin.IntTag("Sub2", 42));
            actual.put(new com.github.steveice10.opennbt.tag.builtin.LongTag("Sub3", 42L));
            actual.put(new com.github.steveice10.opennbt.tag.builtin.FloatTag("Sub4", 42.0f));
            actual.put(new com.github.steveice10.opennbt.tag.builtin.DoubleTag("Sub5", 42.0));
            actual.put(new com.github.steveice10.opennbt.tag.builtin.StringTag("Sub6", "Glavo"));
            actual.put(new com.github.steveice10.opennbt.tag.builtin.ByteArrayTag("Sub7", new byte[]{1, 2, 3}));
            actual.put(new com.github.steveice10.opennbt.tag.builtin.IntArrayTag("Sub8", new int[]{1, 2, 3}));
            actual.put(new com.github.steveice10.opennbt.tag.builtin.LongArrayTag("Sub9", new long[]{1, 2, 3}));
            {
                var sub10 = new com.github.steveice10.opennbt.tag.builtin.CompoundTag("Sub10");
                sub10.put(new com.github.steveice10.opennbt.tag.builtin.ByteTag("Sub10Sub0", (byte) 42));
                actual.put(sub10);
            }
            {
                var sub11 = new com.github.steveice10.opennbt.tag.builtin.ListTag("Sub11", com.github.steveice10.opennbt.tag.builtin.IntTag.class);
                for (int i = 0; i < 10000; i++) {
                    sub11.add(new com.github.steveice10.opennbt.tag.builtin.IntTag("", i));
                }
                actual.put(sub11);
            }

            validator.assertTagEquals(expected, actual);
        }
    }

    @ParameterizedTest
    @MethodSource("validators")
    void testReadModifiedUTF8String(Validator validator) throws IOException {
        String value = "ABCǾ喵喵喵🐱ABC123";

        var expected = new StringTag(value).setName("Meow");
        var actual = new com.github.steveice10.opennbt.tag.builtin.StringTag("Meow", value);
        validator.assertTagEquals(expected, actual);
    }
}

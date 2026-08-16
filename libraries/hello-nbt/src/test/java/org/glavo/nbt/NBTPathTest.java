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
package org.glavo.nbt;

import org.glavo.nbt.io.NBTCodec;
import org.glavo.nbt.tag.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class NBTPathTest {

    private static CompoundTag createSampleRoot() {
        return new CompoundTag()
                .addTag("player", new CompoundTag()
                        .setString("name", "Alex")
                        .setInt("score", 42))
                .addTag("players", new ListTag<>(TagType.COMPOUND)
                        .addTag(new CompoundTag()
                                .setString("name", "Alex")
                                .setInt("score", 10))
                        .addTag(new CompoundTag()
                                .setString("name", "Steve")
                                .setInt("score", 20)))
                .addTag("profiles", new ListTag<>(TagType.COMPOUND)
                        .addTag(new CompoundTag())
                        .addTag(new CompoundTag().setString("name", "Alex"))
                        .addTag(new CompoundTag()
                                .setString("name", "Alex")
                                .setInt("score", 10)))
                .addTag("empty", new CompoundTag())
                .addTag("metadata", new CompoundTag()
                        .setString("display name", "Alex The Great")
                        .setString("quote\"key", "Escaped"))
                .setString("player.name", "literal")
                .setIntArray("numbers", new int[]{3, 5, 8})
                .setLongArray("longs", new long[]{13L, 21L});
    }

    @Test
    void testLevelDat() throws Exception {
        CompoundTag levelDat = NBTCodec.of().readTag(
                TestResources.getResource("/assets/nbt/level.dat"),
                TagType.COMPOUND
        );

        var path = NBTPath.of("Data.Version.Name").withTagType(TagType.STRING);
        assertEquals("1.21.11", levelDat.getFirstString(path));
    }

    @Test
    void testListSelection() {
        CompoundTag root = createSampleRoot();

        assertEquals("Alex", root.getFirstString(NBTPath.of("players[0].name").withTagType(TagType.STRING)));
        assertEquals("Steve", root.getFirstString(NBTPath.of("players[-1].name").withTagType(TagType.STRING)));
        assertEquals("Steve", root.getFirstString(NBTPath.of(" players [ -1 ] . name ").withTagType(TagType.STRING)));
        assertNull(root.getFirstStringOrNull(NBTPath.of("players[2].name").withTagType(TagType.STRING)));

        assertIterableEquals(List.of("Alex", "Steve"),
                root.getAllTags(NBTPath.of("players[].name").withTagType(TagType.STRING))
                        .map(StringTag::getValue)
                        .toList());
    }

    @Test
    void testArraySelection() {
        CompoundTag root = createSampleRoot();

        assertEquals(5, root.getFirstInt(NBTPath.of("numbers[1]").withTagType(TagType.INT)));
        assertEquals(8, root.getFirstInt(NBTPath.of("numbers[-1]").withTagType(TagType.INT)));
        assertEquals(13L, root.getFirstLong(NBTPath.of("longs[-2]").withTagType(TagType.LONG)));

        assertIterableEquals(List.of(3, 5, 8),
                root.getAllTags(NBTPath.of("numbers[]").withTagType(TagType.INT))
                        .map(IntTag::getValue)
                        .toList());
        assertIterableEquals(List.of(13L, 21L),
                root.getAllTags(NBTPath.of("longs[]").withTagType(TagType.LONG))
                        .map(LongTag::getValue)
                        .toList());
    }

    @Test
    void testQuotedKeysAndEscapes() {
        CompoundTag root = createSampleRoot();

        assertEquals("literal", root.getFirstString(NBTPath.of("\"player.name\"").withTagType(TagType.STRING)));
        assertEquals("Alex The Great", root.getFirstString(NBTPath.of("metadata.\"display name\"").withTagType(TagType.STRING)));
        assertEquals("Escaped", root.getFirstString(NBTPath.of("metadata.\"quote\\\"key\"").withTagType(TagType.STRING)));
    }

    @Test
    void testCompoundMatchSelection() {
        CompoundTag root = createSampleRoot();
        CompoundTag emptyRoot = new CompoundTag();

        assertSame(emptyRoot, emptyRoot.getFirstTag(NBTPath.of("{}").withTagType(TagType.COMPOUND)));

        assertSame(root.get("empty"), root.getFirstTag(NBTPath.of("empty{}").withTagType(TagType.COMPOUND)));
        assertSame(root.get("player"), root.getFirstTag(NBTPath.of("player{}").withTagType(TagType.COMPOUND)));

        assertSame(root.get("player"), root.getFirstTag(NBTPath.of("player{name:\"Alex\",score:42}").withTagType(TagType.COMPOUND)));
        assertSame(root.get("player"), root.getFirstTag(NBTPath.of("player{name:\"Alex\"}").withTagType(TagType.COMPOUND)));
        assertNull(root.getFirstTagOrNull(NBTPath.of("player{name:\"Glavo\"}").withTagType(TagType.COMPOUND)));

        assertEquals(3L, root.getAllTags(NBTPath.of("profiles[{}]").withTagType(TagType.COMPOUND)).count());
        assertEquals("Alex", root.getFirstString(NBTPath.of("profiles[{name:\"Alex\"}].name").withTagType(TagType.STRING)));
        assertEquals(10, root.getFirstInt(NBTPath.of("profiles[{name:\"Alex\",score:10}].score").withTagType(TagType.INT)));
        assertEquals(10, root.getFirstInt(NBTPath.of("profiles[{name:\"Alex\"}].score").withTagType(TagType.INT)));
        assertNull(root.getFirstTagOrNull(NBTPath.of("players[{name:\"Glavo\"}]").withTagType(TagType.COMPOUND)));
    }

    @Test
    void testTraversalBoundariesAndMissingMatches() {
        CompoundTag root = createSampleRoot();

        assertNull(root.getFirstIntOrNull(NBTPath.of("numbers[3]").withTagType(TagType.INT)));
        assertNull(root.getFirstIntOrNull(NBTPath.of("numbers[-4]").withTagType(TagType.INT)));
        assertNull(root.getFirstStringOrNull(NBTPath.of("player[].name").withTagType(TagType.STRING)));
        assertNull(root.getFirstStringOrNull(NBTPath.of("numbers.name").withTagType(TagType.STRING)));
        assertEquals(0L, root.getAllTags(NBTPath.of("players[].score").withTagType(TagType.STRING)).count());
    }

    @Test
    void testWithTagTypeBehavior() {
        CompoundTag root = createSampleRoot();

        NBTPath<?> untypedPath = NBTPath.of("player.name");
        assertNull(untypedPath.getTagType());

        var stringPath = untypedPath.withTagType(TagType.STRING);
        assertEquals(TagType.STRING, stringPath.getTagType());
        assertSame(stringPath, stringPath.withTagType(TagType.STRING));
        assertEquals("Alex", root.getFirstString(stringPath));
        assertNull(root.getFirstTagOrNull(untypedPath.withTagType(TagType.INT)));

        NBTPath<?> fixedCompoundPath = NBTPath.of("profiles[{}]");
        assertEquals(TagType.COMPOUND, fixedCompoundPath.getTagType());
        assertSame(fixedCompoundPath, fixedCompoundPath.withTagType(TagType.COMPOUND));

        assertThrows(IllegalStateException.class, () -> NBTPath.of("{}").withTagType(TagType.STRING));
        assertThrows(IllegalStateException.class, () -> NBTPath.of("profiles[{}]").withTagType(TagType.STRING));
    }

    @Test
    void testPathEqualityAndHashCode() {
        var path1 = NBTPath.of("players[-1].name").withTagType(TagType.STRING);
        var path2 = NBTPath.of("players[-1].name").withTagType(TagType.STRING);
        var path3 = NBTPath.of("players[-1].name");
        var path4 = NBTPath.of("players[0].name").withTagType(TagType.STRING);

        assertEquals(path1, path2);
        assertEquals(path1.hashCode(), path2.hashCode());
        assertNotEquals(path1, path3);
        assertNotEquals(path1, path4);
    }

    @Test
    void testInvalidPathSyntax() {
        assertThrows(IllegalArgumentException.class, () -> NBTPath.of(""));
        assertThrows(IllegalArgumentException.class, () -> NBTPath.of("player."));
        assertThrows(IllegalArgumentException.class, () -> NBTPath.of("players..name"));
        assertThrows(IllegalArgumentException.class, () -> NBTPath.of("player{}name"));
        assertThrows(IllegalArgumentException.class, () -> NBTPath.of("numbers[abc]"));
        assertThrows(IllegalArgumentException.class, () -> NBTPath.of("numbers[1"));
        assertThrows(IllegalArgumentException.class, () -> NBTPath.of("numbers[1]."));
        assertThrows(IllegalArgumentException.class, () -> NBTPath.of("\"unterminated"));
        assertThrows(IllegalArgumentException.class, () -> NBTPath.of("[2147483648]"));
    }
}

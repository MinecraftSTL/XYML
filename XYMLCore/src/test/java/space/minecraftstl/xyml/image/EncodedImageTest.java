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
package space.minecraftstl.xyml.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests defensive storage, stream isolation, and value semantics for encoded images.
@NotNullByDefault
public final class EncodedImageTest {
    /// Constructor input is copied and every stream starts from immutable original content.
    @Test
    public void copiesInputAndOpensIndependentStreams() throws IOException {
        byte[] source = {1, 2, 3, 4};
        EncodedImage image = new EncodedImage(source);
        source[0] = 99;

        try (var first = image.openStream(); var second = image.openStream()) {
            assertEquals(1, first.read());
            assertArrayEquals(new byte[]{1, 2, 3, 4}, second.readAllBytes());
            assertArrayEquals(new byte[]{2, 3, 4}, first.readAllBytes());
        }
        assertEquals(4, image.byteCount());
    }

    /// Empty and null encoded values are rejected before publication.
    @Test
    public void rejectsMissingEncodedContent() {
        assertThrows(NullPointerException.class, () -> new EncodedImage(null));
        assertThrows(IllegalArgumentException.class, () -> new EncodedImage(new byte[0]));
    }

    /// Equality and diagnostics depend on encoded content without expanding it.
    @Test
    public void usesEncodedValueSemantics() {
        EncodedImage first = new EncodedImage(new byte[]{1, 2, 3});
        EncodedImage same = new EncodedImage(new byte[]{1, 2, 3});
        EncodedImage different = new EncodedImage(new byte[]{1, 2, 4});

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, different);
        assertEquals("EncodedImage[byteCount=3]", first.toString());
    }

    /// Bounded reads accept exact content and reject one byte beyond the caller's limit.
    @Test
    public void enforcesBoundedStreamReads() throws IOException {
        EncodedImage exact = EncodedImage.read(
                new ByteArrayInputStream(new byte[]{1, 2, 3}), 3);

        assertEquals(3, exact.byteCount());
        assertThrows(
                IOException.class,
                () -> EncodedImage.read(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> EncodedImage.read(new ByteArrayInputStream(new byte[]{1}), 0));
        assertThrows(
                IOException.class,
                () -> EncodedImage.read(new ByteArrayInputStream(new byte[0]), 3));
    }
}

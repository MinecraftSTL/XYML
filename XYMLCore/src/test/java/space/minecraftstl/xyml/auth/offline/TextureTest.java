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
package space.minecraftstl.xyml.auth.offline;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies stable toolkit-neutral texture decoding, hashing, and canonicalization.
@NotNullByDefault
final class TextureTest {
    /// The JDK implementation reproduces the historical column-major normalized-ARGB hash.
    @Test
    void preservesHistoricalPixelHashAndCanonicalizesTransparentRgb() {
        BufferedImage first = createHashFixture(0x00123456);
        Texture firstTexture = Objects.requireNonNull(Texture.loadTexture(first));

        assertEquals(
                "78b46eb674fe176ef3da91eb961b01170ab64802fc62ad01662fdca58e103f62",
                firstTexture.hash());
        assertSame(first, firstTexture.image());

        BufferedImage equivalent = createHashFixture(0x00ABCDEF);
        Texture equivalentTexture = Objects.requireNonNull(Texture.loadTexture(equivalent));
        assertSame(firstTexture, equivalentTexture);
    }

    /// ImageIO decoding preserves dimensions and straight ARGB values without starting JavaFX.
    @Test
    void decodesPngWithImageIo() throws IOException {
        BufferedImage source = createHashFixture(0x00123456);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "PNG", output));

        Texture texture = Objects.requireNonNull(
                Texture.loadTexture(new ByteArrayInputStream(output.toByteArray())));

        assertEquals(2, texture.image().getWidth());
        assertEquals(2, texture.image().getHeight());
        assertEquals(0xFF112233, texture.image().getRGB(0, 0));
        assertEquals(0x80445566, texture.image().getRGB(1, 0));
    }

    /// Unsupported bytes fail explicitly instead of producing a partially initialized texture.
    @Test
    void rejectsUnsupportedImageBytes() {
        assertThrows(
                IOException.class,
                () -> Texture.loadTexture(new ByteArrayInputStream(new byte[]{1, 2, 3, 4})));
    }

    /// Creates the fixed two-by-two image used by the historical hash vector.
    ///
    /// @param transparentPixel fully transparent pixel with arbitrary hidden RGB data
    /// @return deterministic image fixture
    private static BufferedImage createHashFixture(int transparentPixel) {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFF112233);
        image.setRGB(0, 1, transparentPixel);
        image.setRGB(1, 0, 0x80445566);
        image.setRGB(1, 1, 0xFFFFFFFF);
        return image;
    }
}

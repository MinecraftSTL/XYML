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
package space.minecraftstl.xyml.util.skin;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies skin normalization and arm-model detection without starting a graphical toolkit.
@NotNullByDefault
public class NormalizedSkinTest {
    /// Loads one bundled test skin.
    ///
    /// @param name skin resource name
    /// @param slim whether to load the slim fixture
    /// @return normalized skin
    /// @throws IOException if the image cannot be read
    /// @throws InvalidSkinException if the image is missing or malformed
    private static NormalizedSkin getSkin(String name, boolean slim) throws IOException, InvalidSkinException {
        var path = Paths.get(String.format(
                        "../XYMLCore/src/main/resources/assets/img/skin/%s/%s.png",
                        slim ? "slim" : "wide",
                        name))
                .normalize()
                .toAbsolutePath();
        @Nullable BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) {
            throw new InvalidSkinException("Failed to read skin image " + path);
        }
        return new NormalizedSkin(image);
    }

    /// Distinguishes every bundled slim fixture from its wide counterpart.
    ///
    /// @throws Exception if a fixture cannot be loaded or normalized
    @Test
    public void testIsSlim() throws Exception {
        @Unmodifiable List<String> names = List.of(
                "alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri");

        for (String skin : names) {
            assertTrue(getSkin(skin, true).isSlim());
            assertFalse(getSkin(skin, false).isSlim());
        }
    }
}

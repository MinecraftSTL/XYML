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
package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies shader-pack rows retain local descriptions without a package-icon field.
@NotNullByDefault
final class ShaderPackCatalogItemTest {
    /// Optional presentation metadata is immutable and available to the details view.
    @Test
    void retainsPresentationMetadata() {
        ShaderPackCatalogItem item = new ShaderPackCatalogItem(
                Path.of("shaderpacks", "example.zip"),
                "example.zip",
                "Example",
                true,
                Set.of(ShaderPackBackend.IRIS_OCULUS),
                "Local description");

        assertEquals("Local description", item.description());
        assertEquals("Example", item.displayText());
    }
}

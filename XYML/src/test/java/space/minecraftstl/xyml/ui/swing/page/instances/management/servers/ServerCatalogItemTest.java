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
package space.minecraftstl.xyml.ui.swing.page.instances.management.servers;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies editable server values and preservation of optional Minecraft metadata.
@NotNullByDefault
final class ServerCatalogItemTest {
    /// Editing name and address retains optional fields from the original NBT entry.
    @Test
    void retainsOptionalMetadataWhenEdited() {
        CompoundTag source = new CompoundTag().addString("icon", "texture");
        ServerCatalogItem edited = new ServerCatalogItem("Old", "old.example", source)
                .withValues("New", "new.example");

        assertEquals("New", edited.name());
        assertEquals("new.example", edited.address());
        assertEquals("texture", edited.toTag().getString("icon"));
    }

    /// Blank values are rejected before they can reach servers.dat.
    @Test
    void rejectsBlankValues() {
        assertThrows(IllegalArgumentException.class, () -> new ServerCatalogItem("", "example.com"));
        assertThrows(IllegalArgumentException.class, () -> new ServerCatalogItem("Example", " "));
    }
}

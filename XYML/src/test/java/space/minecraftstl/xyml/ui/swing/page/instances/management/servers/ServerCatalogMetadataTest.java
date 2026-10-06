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

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/// Verifies server-list presentation metadata is read without changing editable fields.
@NotNullByDefault
final class ServerCatalogMetadataTest {
    /// Reads the local MOTD and bounded icon while retaining those values after an edit.
    @Test
    void readsDescriptionAndIconMetadata() {
        String encodedIcon = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
        ServerCatalogItem item = new ServerCatalogItem(
                "Example",
                "example.test",
                new CompoundTag()
                        .addString("motd", "Local description")
                        .addString("icon", encodedIcon));

        assertEquals("Local description", item.description());
        assertNotNull(item.icon());
        assertEquals("Local description", item.withValues("Edited", "edited.test").description());
        assertNotNull(item.withValues("Edited", "edited.test").icon());
    }

    /// Missing or malformed metadata falls back without rejecting the server row.
    @Test
    void malformedMetadataFallsBack() {
        ServerCatalogItem item = new ServerCatalogItem(
                "Example",
                "example.test",
                new CompoundTag().addString("icon", "not-base64"));

        assertEquals("", item.description());
        assertEquals(null, item.icon());
    }
}

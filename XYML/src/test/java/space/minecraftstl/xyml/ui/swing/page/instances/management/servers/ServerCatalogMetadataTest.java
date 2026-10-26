// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

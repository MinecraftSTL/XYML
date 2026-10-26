// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

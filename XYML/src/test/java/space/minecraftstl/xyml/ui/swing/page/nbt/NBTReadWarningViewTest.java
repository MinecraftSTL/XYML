// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.library.nbt.io.NBTFileEncoding;
import space.minecraftstl.xyml.library.nbt.io.NBTReadReport;
import space.minecraftstl.xyml.library.nbt.io.StorageProfile;
import space.minecraftstl.xyml.library.nbt.io.StorageProfileChange;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that the NBT warning details expose storage-profile before/after metadata.
@NotNullByDefault
public final class NBTReadWarningViewTest {
    /// Formats an inline-to-external transition with both slot profiles intact.
    @Test
    void formatsStorageProfileChange() {
        StorageProfile.RegionSlot before = new StorageProfile.RegionSlot(3, false, true);
        StorageProfile.RegionSlot after = new StorageProfile.RegionSlot(3, true, true);
        StorageProfileChange change = new StorageProfileChange(5, before, after);
        String details = NBTReadWarningView.formatReadReport(
                NBTReadReport.clean(NBTFileEncoding.REGION),
                StorageProfile.region(new byte[StorageProfile.REGION_SLOT_COUNT],
                        new boolean[StorageProfile.REGION_SLOT_COUNT],
                        new boolean[StorageProfile.REGION_SLOT_COUNT]),
                List.of(change),
                NBTEditorStrings.english());

        assertTrue(details.contains("Region slot x=5, z=0 storage profile changed"));
        assertTrue(details.contains("marker=3, external=false, occupied=true"));
        assertTrue(details.contains("marker=3, external=true, occupied=true"));
        assertTrue(details.contains("inline payload moved to an external companion"));
    }
}

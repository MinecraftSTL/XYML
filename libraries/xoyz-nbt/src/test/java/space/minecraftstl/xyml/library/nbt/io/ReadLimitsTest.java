// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

// Added by MinecraftSTL in 2026 for bounded XoyzNBT read-policy coverage.
package space.minecraftstl.xyml.library.nbt.io;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the fixed read limits and the distinction between warnings and repairs.
@NotNullByDefault
public final class ReadLimitsTest {
    /// Confirms the documented default values are binary MiB and cumulative per-document.
    @Test
    void defaultsMatchSafetyContract() {
        ReadLimits limits = ReadLimits.defaults();
        assertEquals(65L * 1024L * 1024L, limits.maxEncodedBytes());
        assertEquals(64L * 1024L * 1024L, limits.maxDecompressedBytes());
        assertEquals(256L * 1024L * 1024L, limits.maxDocumentDecompressedBytes());
        assertEquals(1_000_000L, limits.maxNodes());
        assertEquals(512L, limits.maxDepth());
        assertEquals(16L * 1024L * 1024L, limits.maxStringBytes());
        assertEquals(16L * 1024L * 1024L, limits.maxArrayBytes());
    }

    /// Confirms that a legal LZ4 extension is visible but does not require a repair save.
    @Test
    void legalLz4ExtensionIsInformational() {
        NBTReadIssue issue = new NBTReadIssue(NBTReadIssue.Severity.INFORMATIONAL,
                "REGION_LZ4_EXTENSION", "slot[3]", "LZ4 extension");
        NBTReadReport report = new NBTReadReport(NBTFileEncoding.REGION, false, List.of(issue));

        assertFalse(report.requiresRepair());
        assertTrue(report.hasInformationalIssues());
        assertEquals(List.of(), report.repairIssues());
        assertEquals(List.of(issue), report.informationalIssues());
    }

    /// Confirms that a cumulative budget rejects output before it can be materialized.
    @Test
    void cumulativeBudgetIsStrict() throws IOException {
        ReadLimits limits = new ReadLimits(10L, 10L, 12L, 10L, 10L, 10L, 10L, 10L);
        ReadLimits.Budget budget = limits.newDocumentBudget();
        budget.consume(12L);
        assertEquals(0L, budget.remaining());
        assertThrows(IOException.class, () -> budget.consume(1L));
    }

    /// Confirms storage profiles copy their slot metadata and expose no mutable collection.
    @Test
    void storageProfileIsImmutable() {
        byte[] markers = new byte[StorageProfile.REGION_SLOT_COUNT];
        boolean[] external = new boolean[StorageProfile.REGION_SLOT_COUNT];
        boolean[] occupied = new boolean[StorageProfile.REGION_SLOT_COUNT];
        markers[7] = 4;
        external[7] = true;
        occupied[7] = true;

        StorageProfile region = StorageProfile.region(markers, external, occupied);
        StorageProfile standalone = StorageProfile.standalone(NBTFileEncoding.GZIP);
        markers[7] = 1;
        external[7] = false;

        assertEquals(NBTFileEncoding.GZIP, standalone.standaloneEncoding());
        assertEquals(4, region.regionSlot(7).marker());
        assertEquals(0x84, region.regionSlot(7).encodedMarker());
        assertTrue(region.regionSlot(7).isExternal());
        assertTrue(region.regionSlot(7).isOccupied());
        assertThrows(UnsupportedOperationException.class,
                () -> region.regionSlots().add(new StorageProfile.RegionSlot(1, false)));

        List<StorageProfile.RegionSlot> slots = new ArrayList<>(region.regionSlots());
        slots.set(7, new StorageProfile.RegionSlot(2, false));
        assertEquals(4, region.regionSlot(7).marker());
    }
}

/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.util.platform.hardware.CentralProcessor;
import space.minecraftstl.xyml.util.platform.hardware.GraphicsCard;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Verifies hardware presentation using snapshots instead of probing host devices.
@NotNullByDefault
final class AboutPanelHardwareTest {
    /// One processor shows localized physical and logical core counts.
    @Test
    void formatsSingleProcessor() {
        @Unmodifiable List<AboutPanel.AboutEntry> rows = AboutPanel.hardwareRows(processor(new CentralProcessor.Cores(4, 8, 1)), null);
        assertEquals(1, rows.size());
        assertEquals(i18n("about.environment.cpu"), rows.get(0).title());
        assertEquals(i18n("about.environment.cpu.name.cores.hyper_threading", "Test CPU", 4, 8),
                rows.get(0).subtitle());
    }

    /// Multiple packages use the upstream per-package count calculation and consecutive titles.
    @Test
    void formatsMultipleProcessors() {
        @Unmodifiable List<AboutPanel.AboutEntry> rows = AboutPanel.hardwareRows(processor(new CentralProcessor.Cores(16, 32, 2)), null);
        assertEquals(2, rows.size());
        for (int index = 0; index < rows.size(); index++) {
            assertEquals(i18n("about.environment.cpu.n", index + 1), rows.get(index).title());
            assertEquals(i18n("about.environment.cpu.name.cores.hyper_threading", "Test CPU", 8, 16),
                    rows.get(index).subtitle());
        }
    }

    /// Missing topology shows only the name, and equal physical/logical counts omit thread wording.
    @Test
    void handlesUnknownTopologyAndNoHyperThreading() {
        assertEquals("Test CPU", AboutPanel.hardwareRows(processor(null), null).get(0).subtitle());
        assertEquals(i18n("about.environment.cpu.name.cores", "Test CPU", 8),
                AboutPanel.hardwareRows(processor(new CentralProcessor.Cores(8, 8, 1)), null).get(0).subtitle());
    }

    /// Only known virtual adapters are hidden and remaining adapters are renumbered.
    @Test
    void filtersAndRenumbersAdapters() {
        GraphicsCard virtual = GraphicsCard.builder().setName("Virtual").setVirtualDisplayAdapter(true).build();
        GraphicsCard first = GraphicsCard.builder().setName("First").build();
        GraphicsCard second = GraphicsCard.builder().setName("Second").build();
        @Unmodifiable List<AboutPanel.AboutEntry> rows = AboutPanel.hardwareRows(null, List.of(virtual, first, virtual, second));
        assertEquals(2, rows.size());
        assertEquals(i18n("about.environment.gpu.n", 1), rows.get(0).title());
        assertEquals(i18n("about.environment.gpu.n", 2), rows.get(1).title());
        assertEquals("First", rows.get(0).subtitle());
        assertEquals("Second", rows.get(1).subtitle());
        @Unmodifiable List<AboutPanel.AboutEntry> single = AboutPanel.hardwareRows(null, List.of(virtual, first));
        assertEquals(i18n("about.environment.gpu"), single.get(0).title());
        assertFalse(first.isVirtualDisplayAdapter());
    }

    /// Empty or fully filtered hardware produces immutable empty snapshots.
    @Test
    void handlesMissingAndFullyVirtualHardware() {
        assertTrue(AboutPanel.hardwareRows(null, null).isEmpty());
        assertTrue(AboutPanel.hardwareRows(null, List.of()).isEmpty());
        GraphicsCard virtual = GraphicsCard.builder().setVirtualDisplayAdapter(true).build();
        assertTrue(AboutPanel.hardwareRows(null, List.of(virtual)).isEmpty());
        @Unmodifiable List<AboutPanel.AboutEntry> rows = AboutPanel.hardwareRows(processor(null), List.of());
        assertThrows(UnsupportedOperationException.class, () -> rows.add(rows.get(0)));
    }

    /// Creates one CPU snapshot with optional topology.
    ///
    /// @param cores topology, or null when unavailable
    /// @return synthetic processor metadata
    private static CentralProcessor processor(@Nullable CentralProcessor.Cores cores) {
        CentralProcessor.Builder builder = new CentralProcessor.Builder().setName("Test CPU");
        if (cores != null)
            builder.setCores(cores);
        return builder.build();
    }
}

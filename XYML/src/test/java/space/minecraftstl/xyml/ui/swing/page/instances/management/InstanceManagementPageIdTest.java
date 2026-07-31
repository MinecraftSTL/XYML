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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies the complete, stable destination order and its bundled icon contract.
@NotNullByDefault
final class InstanceManagementPageIdTest {
    /// Every recovered management function retains one stable declaration-order destination.
    @Test
    void preservesAllThirteenDestinationsInGroupedOrder() {
        @Unmodifiable List<InstanceManagementPageId> expectedPages = List.of(
                InstanceManagementPageId.OVERVIEW,
                InstanceManagementPageId.MODS,
                InstanceManagementPageId.RESOURCE_PACKS,
                InstanceManagementPageId.WORLDS,
                InstanceManagementPageId.DATA_PACKS,
                InstanceManagementPageId.SCHEMATICS,
                InstanceManagementPageId.GAME_SETTINGS,
                InstanceManagementPageId.AUTOMATIC_INSTALL,
                InstanceManagementPageId.MAINTENANCE_TOOLS,
                InstanceManagementPageId.BACKUPS,
                InstanceManagementPageId.FILE_UPDATE_CHECK,
                InstanceManagementPageId.MODPACK_EXPORT,
                InstanceManagementPageId.INSTANCE_OPERATIONS);

        assertEquals(expectedPages, InstanceManagementPageId.orderedValues());
        assertEquals(List.of(InstanceManagementPageId.OVERVIEW), InstanceManagementPageGroup.OVERVIEW.pages());
        assertEquals(
                expectedPages.subList(1, 6),
                InstanceManagementPageGroup.CONTENT.pages());
        assertEquals(
                expectedPages.subList(6, 8),
                InstanceManagementPageGroup.CONFIGURATION.pages());
        assertEquals(
                expectedPages.subList(8, 12),
                InstanceManagementPageGroup.MAINTENANCE.pages());
        assertEquals(
                expectedPages.subList(12, 13),
                InstanceManagementPageGroup.INSTANCE.pages());
        assertThrows(
                UnsupportedOperationException.class,
                () -> InstanceManagementPageId.orderedValues().add(InstanceManagementPageId.OVERVIEW));
        assertThrows(
                UnsupportedOperationException.class,
                () -> InstanceManagementPageGroup.orderedValues().add(InstanceManagementPageGroup.OVERVIEW));
    }

    /// Every destination points to a non-empty classpath SVG already bundled by the launcher.
    @Test
    void resolvesEveryBundledNavigationIcon() {
        ClassLoader loader = InstanceManagementPageIdTest.class.getClassLoader();
        for (InstanceManagementPageId page : InstanceManagementPageId.orderedValues()) {
            assertNotNull(loader.getResource(page.iconResource()), page::iconResource);
        }
    }
}

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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.page.downloads.DownloadPageTarget;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies old category-only implementations remain functional with the optional search boundary.
@NotNullByDefault
final class InstanceContentNavigationTest {
    /// Falls back to Mods navigation for an existing lambda implementation.
    @Test
    void preservesFunctionalInterfaceFallback() {
        List<DownloadPageTarget> targets = new ArrayList<>();
        InstanceContentNavigation navigation = targets::add;
        navigation.openModSearch(" Litematica ");
        assertEquals(List.of(DownloadPageTarget.MODS), targets);
        assertThrows(IllegalArgumentException.class, () -> navigation.openModSearch(" \t"));
        assertThrows(NullPointerException.class, () -> navigation.openModSearch(null));
        assertEquals(1, targets.size());
        InstanceContentNavigation.disabled().openModSearch("Litematica");
    }
}

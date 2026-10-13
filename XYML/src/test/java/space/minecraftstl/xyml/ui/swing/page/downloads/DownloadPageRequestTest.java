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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.GameInstanceID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies optional Mods queries without changing legacy category and instance requests.
@NotNullByDefault
final class DownloadPageRequestTest {
    /// Preserves category-only and explicit-instance construction without a query.
    @Test
    void preservesLegacyConstructors() {
        GameInstanceID instanceId = new GameInstanceID("instance");
        DownloadPageRequest request = new DownloadPageRequest(DownloadPageTarget.MODS, instanceId);
        assertEquals(instanceId, request.targetInstanceId());
        assertNull(request.searchText());
        for (DownloadPageTarget target : DownloadPageTarget.values()) {
            DownloadPageRequest plain = DownloadPageRequest.of(target);
            assertEquals(target, plain.target());
            assertNull(plain.targetInstanceId());
            assertNull(plain.searchText());
        }
    }

    /// Normalizes non-blank project names while retaining their casing and instance context.
    @Test
    void trimsProjectQueries() {
        GameInstanceID instanceId = new GameInstanceID("instance");
        for (String name : new String[] {"Litematica", "fabric-api", "中文模组"}) {
            DownloadPageRequest request = new DownloadPageRequest(DownloadPageTarget.MODS, instanceId, " " + name + "\t");
            assertEquals(name, request.searchText());
            assertEquals(instanceId, request.targetInstanceId());
        }
    }

    /// Rejects blank queries, non-Mods queries, and missing categories before navigation.
    @Test
    void rejectsInvalidQueries() {
        for (String blank : new String[] {"", " ", "\t\r\n"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DownloadPageRequest(DownloadPageTarget.MODS, null, blank));
        }
        for (DownloadPageTarget target : DownloadPageTarget.values()) {
            if (target != DownloadPageTarget.MODS) {
                assertThrows(IllegalArgumentException.class,
                        () -> new DownloadPageRequest(target, null, "Litematica"));
            }
        }
        assertThrows(NullPointerException.class, () -> new DownloadPageRequest(null, null, "Litematica"));
    }
}

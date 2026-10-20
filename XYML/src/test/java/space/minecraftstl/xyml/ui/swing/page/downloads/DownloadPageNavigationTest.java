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
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies queued download-center requests retain their optional instance context until the page attaches.
@NotNullByDefault
final class DownloadPageNavigationTest {
    /// Delivers one queued instance-bound request when the download page attaches.
    @Test
    void deliversQueuedInstanceRequestOnAttach() {
        EdtDispatcher.executeAndWait(() -> {
            DownloadPageNavigation navigation = new DownloadPageNavigation();
            GameInstanceID instanceId = new GameInstanceID("instance");
            List<DownloadPageRequest> delivered = new ArrayList<>();

            navigation.request(new DownloadPageRequest(DownloadPageTarget.MODS, instanceId));
            assertTrue(delivered.isEmpty());

            navigation.attach(delivered::add);

            assertEquals(1, delivered.size());
            assertEquals(DownloadPageTarget.MODS, delivered.get(0).target());
            assertEquals(instanceId, delivered.get(0).targetInstanceId());
        });
    }

    /// Delivers one plain category request without an instance context.
    @Test
    void deliversPlainCategoryRequestWithoutInstanceContext() {
        EdtDispatcher.executeAndWait(() -> {
            DownloadPageNavigation navigation = new DownloadPageNavigation();
            List<DownloadPageRequest> delivered = new ArrayList<>();
            navigation.attach(delivered::add);

            navigation.request(DownloadPageTarget.RESOURCE_PACKS);

            assertEquals(1, delivered.size());
            assertEquals(DownloadPageTarget.RESOURCE_PACKS, delivered.get(0).target());
            assertNull(delivered.get(0).targetInstanceId());
        });
    }

    /// Rejects a second different download-page attachment.
    @Test
    void rejectsCompetingAttachment() {
        EdtDispatcher.executeAndWait(() -> {
            DownloadPageNavigation navigation = new DownloadPageNavigation();
            navigation.attach(request -> { });

            assertThrows(IllegalStateException.class, () -> navigation.attach(request -> { }));
        });
    }
}

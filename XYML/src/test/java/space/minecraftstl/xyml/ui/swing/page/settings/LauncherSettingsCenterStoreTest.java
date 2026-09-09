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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies immediate persistence through the launcher settings-center store.
@NotNullByDefault
public final class LauncherSettingsCenterStoreTest {

    /// Persists each MCP deletion-confirmation preference without changing the other category.
    @Test
    public void persistsMcpDeletionConfirmationsIndependently() {
        LauncherStateDispatcher.executeAndWait(() -> {
            LauncherSettings settings = new LauncherSettings();
            LauncherSettingsCenterStore store = new LauncherSettingsCenterStore(settings, () -> true);
            try {
                assertTrue(store.snapshot().mcpConfirmInstanceDeletion());
                assertTrue(store.snapshot().mcpConfirmModDeletion());
                assertEquals("", store.snapshot().mcpBearerToken());
                assertTrue(store.snapshot().showMcpEnablementWarning());

                store.setMcpBearerToken("configured-token");
                store.setShowMcpEnablementWarning(false);

                assertEquals("configured-token", settings.mcpBearerTokenProperty().get());
                assertFalse(settings.showMcpEnablementWarningProperty().get());
                assertEquals("configured-token", store.snapshot().mcpBearerToken());
                assertFalse(store.snapshot().showMcpEnablementWarning());
                store.setMcpBearerToken("token with spaces");
                assertEquals("token with spaces", settings.mcpBearerTokenProperty().get());

                store.setMcpConfirmInstanceDeletion(false);

                assertFalse(settings.mcpConfirmInstanceDeletionProperty().get());
                assertTrue(settings.mcpConfirmModDeletionProperty().get());
                assertFalse(store.snapshot().mcpConfirmInstanceDeletion());
                assertTrue(store.snapshot().mcpConfirmModDeletion());

                store.setMcpConfirmModDeletion(false);

                assertFalse(settings.mcpConfirmInstanceDeletionProperty().get());
                assertFalse(settings.mcpConfirmModDeletionProperty().get());
                assertFalse(store.snapshot().mcpConfirmInstanceDeletion());
                assertFalse(store.snapshot().mcpConfirmModDeletion());
            } finally {
                store.close();
            }
        });
    }
}

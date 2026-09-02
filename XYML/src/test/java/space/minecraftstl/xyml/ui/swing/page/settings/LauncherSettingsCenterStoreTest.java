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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies immediate persistence through the launcher settings-center store.
@NotNullByDefault
public final class LauncherSettingsCenterStoreTest {

    /// Persists the MCP deletion-confirmation preference and republishes its snapshot immediately.
    @Test
    public void persistsMcpDeletionConfirmationImmediately() {
        LauncherStateDispatcher.executeAndWait(() -> {
            LauncherSettings settings = new LauncherSettings();
            LauncherSettingsCenterStore store = new LauncherSettingsCenterStore(settings, () -> true);
            try {
                assertTrue(store.snapshot().mcpConfirmDeletion());

                store.setMcpConfirmDeletion(false);

                assertFalse(settings.mcpConfirmDeletionProperty().get());
                assertFalse(store.snapshot().mcpConfirmDeletion());
            } finally {
                store.close();
            }
        });
    }
}

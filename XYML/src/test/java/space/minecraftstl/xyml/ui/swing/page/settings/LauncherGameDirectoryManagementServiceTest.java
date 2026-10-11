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
import space.minecraftstl.xyml.setting.GameDirectory;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.util.PortablePath;
import space.minecraftstl.xyml.util.i18n.LocalizedText;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies the canonical-store selection boundary without touching global user settings.
@NotNullByDefault
public final class LauncherGameDirectoryManagementServiceTest {
    /// Selection uses the merged authoritative entry and refuses an unaccepted new identifier.
    @Test
    void requiresAcceptedCanonicalEntry() {
        GameDirectoryID id = new GameDirectoryID(UUID.randomUUID());
        GameDirectory directory = new GameDirectory(id, LocalizedText.plain("Accepted"), PortablePath.of("instances"));
        assertSame(directory, LauncherGameDirectoryManagementService.findAddedDirectory(List.of(directory), id));
        assertThrows(IllegalStateException.class,
                () -> LauncherGameDirectoryManagementService.findAddedDirectory(List.of(), id));
        assertThrows(IllegalStateException.class,
                () -> LauncherGameDirectoryManagementService.findAddedDirectory(List.of(directory), new GameDirectoryID(UUID.randomUUID())));
    }
}

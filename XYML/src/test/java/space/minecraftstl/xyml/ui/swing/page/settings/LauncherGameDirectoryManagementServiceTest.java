// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

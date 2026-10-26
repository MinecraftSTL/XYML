// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.setting.GameSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that MCP instance memory updates retain GameSettings inheritance semantics.
@NotNullByDefault
final class XYMLMcpServiceSettingsTest {

    /// Confirms a null memory value removes the corresponding instance override.
    @Test
    void nullMemoryValueRestoresInheritance() {
        GameSettings.Instance setting = new GameSettings.Instance();

        XYMLMcpService.applyMemoryOverride(
                setting, GameSettings.PROPERTY_MIN_MEMORY, setting.minMemoryProperty(), 1024);
        assertTrue(setting.getOverrideProperties().contains(GameSettings.PROPERTY_MIN_MEMORY));
        assertEquals(1024, setting.minMemoryProperty().getValue());

        XYMLMcpService.applyMemoryOverride(
                setting, GameSettings.PROPERTY_MIN_MEMORY, setting.minMemoryProperty(), null);
        assertFalse(setting.getOverrideProperties().contains(GameSettings.PROPERTY_MIN_MEMORY));
    }

    /// Confirms partial heap updates are checked against their unchanged effective counterpart.
    @Test
    void rejectsInvalidPartialMemoryBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> XYMLMcpService.validateMemoryUpdate(4096, null, 512, 2048));
        assertThrows(IllegalArgumentException.class,
                () -> XYMLMcpService.validateMemoryUpdate(null, 1024, 2048, 4096));
    }
}

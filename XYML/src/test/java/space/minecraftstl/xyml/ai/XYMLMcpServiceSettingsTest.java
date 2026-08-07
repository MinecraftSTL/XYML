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
package space.minecraftstl.xyml.ai;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.setting.GameSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}

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
package space.minecraftstl.xyml.setting;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests persistence and defaults for Swing appearance settings.
@NotNullByDefault
public final class LauncherAppearanceSettingsTest {
    /// A new settings object uses the documented default surface radius.
    @Test
    public void usesDefaultCornerRadius() {
        LauncherSettings settings = new LauncherSettings();

        assertEquals(LauncherSettings.DEFAULT_CORNER_RADIUS, settings.cornerRadiusProperty().get());
    }

    /// A changed radius survives launcher-settings JSON serialization and deserialization.
    @Test
    public void persistsChangedCornerRadius() {
        LauncherSettings settings = new LauncherSettings();
        settings.cornerRadiusProperty().set(14);

        JsonObject serialized = LauncherSettings.SETTINGS_GSON.toJsonTree(settings).getAsJsonObject();
        LauncherSettings restored = Objects.requireNonNull(LauncherSettings.fromJson(serialized));

        assertEquals(14, serialized.get("cornerRadius").getAsInt());
        assertEquals(14, restored.cornerRadiusProperty().get());
    }
}

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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.gson.JsonObject;
import space.minecraftstl.xyml.util.FileSaver;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for current user settings behavior.
@NotNullByDefault
public final class UserSettingsTest {
    /// Tests that detached settings auto-save from the toolkit-neutral aggregate revision.
    @Test
    public void autoSavesNeutralPropertyChanges() throws IOException, InterruptedException {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path location = fileSystem.getPath("/user-settings.json");
            JsonSettingFile<UserSettings> file = new JsonSettingFile<>(
                    location,
                    "user settings",
                    UserSettings.class,
                    UserSettings.CURRENT_SCHEMA,
                    UserSettings::new);
            UserSettings settings = new UserSettings();
            file.installAutoSave(settings);

            settings.logRetentionProperty().set(42);
            FileSaver.waitForAllSaves();

            JsonObject saved = Objects.requireNonNull(JsonUtils.fromJsonFile(location, JsonObject.class));
            assertEquals(42, saved.get("logRetention").getAsInt());
        }
    }

}

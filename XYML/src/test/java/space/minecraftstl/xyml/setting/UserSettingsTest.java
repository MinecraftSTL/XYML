// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

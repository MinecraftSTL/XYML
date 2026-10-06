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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests immutable automatic migration policy invariants and launcher-settings persistence.
@NotNullByDefault
class InstanceConfigMigrationPolicyTest {
    /// Verifies the product default requested for absent persisted configuration.
    @Test
    void defaultsEnableAllGlobalContent() {
        InstanceConfigMigrationPolicy policy = InstanceConfigMigrationPolicy.defaults();
        assertTrue(policy.enabled());
        assertEquals(InstanceConfigMigrationSourceType.GLOBAL, policy.sourceType());
        assertEquals(EnumSet.allOf(InstanceConfigMigrationContent.class), policy.contents());
    }

    /// Verifies caller-owned sets cannot mutate a constructed policy.
    @Test
    void copiesAndProtectsContentSet() {
        EnumSet<InstanceConfigMigrationContent> contents = EnumSet.of(InstanceConfigMigrationContent.OPTIONS);
        InstanceConfigMigrationPolicy policy = new InstanceConfigMigrationPolicy(
                true, InstanceConfigMigrationSourceType.GLOBAL, null, null, contents);
        contents.add(InstanceConfigMigrationContent.SERVERS);
        assertEquals(Set.of(InstanceConfigMigrationContent.OPTIONS), policy.contents());
        assertThrows(UnsupportedOperationException.class,
                () -> policy.contents().add(InstanceConfigMigrationContent.SERVERS));
    }

    /// Verifies an explicit instance source requires both stable identifiers.
    @Test
    void rejectsIncompleteInstanceSource() {
        assertThrows(IllegalArgumentException.class, () -> new InstanceConfigMigrationPolicy(
                true,
                InstanceConfigMigrationSourceType.INSTANCE,
                new GameDirectoryID(UUID.randomUUID()),
                null,
                EnumSet.allOf(InstanceConfigMigrationContent.class)));
    }

    /// Verifies launcher settings preserve a cross-directory source and selected content.
    @Test
    void launcherSettingsRoundTripPolicy() {
        InstanceConfigMigrationPolicy expected = new InstanceConfigMigrationPolicy(
                true,
                InstanceConfigMigrationSourceType.INSTANCE,
                new GameDirectoryID(UUID.randomUUID()),
                new GameInstanceID("source"),
                EnumSet.of(InstanceConfigMigrationContent.OPTIONS, InstanceConfigMigrationContent.MOD_CONFIG));
        LauncherSettings settings = new LauncherSettings();
        settings.instanceConfigMigrationPolicyProperty().setValue(expected);
        LauncherSettings restored = LauncherSettings.fromJson(
                LauncherSettings.SETTINGS_GSON.fromJson(settings.toJson(), com.google.gson.JsonObject.class));
        assertEquals(expected, restored.instanceConfigMigrationPolicyProperty().getValue());
    }
}

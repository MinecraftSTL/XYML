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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests immutable policy invariants and independent preset persistence without legacy migration.
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

    /// Exact cross-directory source identity follows a successful instance rename without changing other values.
    @Test
    void followsExactSourceRename() {
        GameDirectoryID directoryId = new GameDirectoryID(UUID.randomUUID());
        GameInstanceID previous = new GameInstanceID("previous");
        GameInstanceID renamed = new GameInstanceID("renamed");
        InstanceConfigMigrationPolicy policy = new InstanceConfigMigrationPolicy(
                true,
                InstanceConfigMigrationSourceType.INSTANCE,
                directoryId,
                previous,
                EnumSet.of(InstanceConfigMigrationContent.OPTIONS));

        InstanceConfigMigrationPolicy updated = policy.renameSource(directoryId, previous, renamed);

        assertEquals(renamed, updated.sourceInstance());
        assertEquals(policy.contents(), updated.contents());
        assertSame(policy, policy.renameSource(new GameDirectoryID(UUID.randomUUID()), previous, renamed));
    }

    /// Preset settings independently preserve exact cross-directory sources and content selections.
    @Test
    void presetsRoundTripPoliciesIndependently() {
        InstanceConfigMigrationPolicy expected = new InstanceConfigMigrationPolicy(true,
                InstanceConfigMigrationSourceType.INSTANCE, GameDirectoryID.generate(), new GameInstanceID("source"),
                Set.of(InstanceConfigMigrationContent.OPTIONS, InstanceConfigMigrationContent.MOD_CONFIG));
        GameSettings.Preset first = new GameSettings.Preset(GameSettingsPresetID.generate());
        GameSettings.Preset second = new GameSettings.Preset(GameSettingsPresetID.generate());
        first.instanceConfigMigrationPolicyProperty().setValue(expected);
        GameSettingsPresets presets = new GameSettingsPresets();
        presets.getPresets().add(first);
        presets.getPresets().add(second);
        GameSettingsPresets restored = space.minecraftstl.xyml.util.gson.JsonUtils.GSON.fromJson(space.minecraftstl.xyml.util.gson.JsonUtils.GSON.toJson(presets),
                GameSettingsPresets.class);
        assertEquals(expected, restored.getPresets().get(0).instanceConfigMigrationPolicyProperty().getValue());
        assertEquals(InstanceConfigMigrationPolicy.defaults(),
                restored.getPresets().get(1).instanceConfigMigrationPolicyProperty().getValue());
    }

    /// An absent preset field gets its own default and never imports the obsolete launcher field.
    @Test
    void absentPresetPolicyIgnoresObsoleteLauncherPolicy() {
        com.google.gson.JsonObject presetJson = new com.google.gson.JsonObject();
        presetJson.addProperty("id", GameSettingsPresetID.generate().toString());
        GameSettings.Preset restored = space.minecraftstl.xyml.util.gson.JsonUtils.GSON.fromJson(presetJson, GameSettings.Preset.class);
        assertEquals(InstanceConfigMigrationPolicy.defaults(), restored.instanceConfigMigrationPolicyProperty().getValue());
        assertEquals(false, presetJson.has("instanceConfigMigration"));
    }
}

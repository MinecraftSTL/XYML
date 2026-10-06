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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.setting.InstanceConfigMigrationContent;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.setting.InstanceConfigMigrationPolicy;
import space.minecraftstl.xyml.setting.InstanceConfigMigrationSourceType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.setting.SettingsManager.settings;

/// Tests launcher-wide migration policy defaults in the global game-settings page.
@NotNullByDefault
public final class InstanceConfigMigrationPolicyPanelTest {
    /// Process-wide settings field used to install a lightweight test configuration.
    private static java.lang.reflect.Field launcherSettingsField;

    /// Default policy renders enabled, global, and with all seven content categories selected.
    @Test
    public void rendersDefaultAutomaticMigrationPolicy() {
        AtomicReference<@Nullable InstanceConfigMigrationPolicyPanel> panelReference = new AtomicReference<>();
        AtomicReference<@Nullable InstanceConfigMigrationPolicy> previousReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            try {
                launcherSettingsField = SettingsManager.class.getDeclaredField("launcherSettings");
                launcherSettingsField.setAccessible(true);
                launcherSettingsField.set(null, new LauncherSettings());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unable to install test launcher settings", failure);
            }
            previousReference.set(settings().instanceConfigMigrationPolicyProperty().getValue());
            settings().instanceConfigMigrationPolicyProperty().setValue(InstanceConfigMigrationPolicy.defaults());
            panelReference.set(new InstanceConfigMigrationPolicyPanel());
        });
        try {
            EdtDispatcher.executeAndWait(() -> {
                InstanceConfigMigrationPolicyPanel panel = java.util.Objects.requireNonNull(panelReference.get(), "panel");
                assertTrue(findNamed(panel, "instanceConfigMigrationEnabled", JCheckBox.class).isSelected());
                assertEquals(
                        InstanceConfigMigrationSourceType.GLOBAL,
                        findNamed(panel, "instanceConfigMigrationSourceType", JComboBox.class).getSelectedItem());
                for (InstanceConfigMigrationContent content : InstanceConfigMigrationContent.values()) {
                    assertTrue(findNamed(
                            panel,
                            "instanceConfigMigrationContent" + content.name(),
                            JCheckBox.class).isSelected());
                }
            });
        } finally {
            EdtDispatcher.executeAndWait(() -> {
                try {
                    launcherSettingsField.set(null, null);
                } catch (IllegalAccessException failure) {
                    throw new IllegalStateException("Unable to restore test launcher settings", failure);
                }
            });
        }
    }

    /// Finds a required named descendant.
    private static <T extends JComponent> T findNamed(Container root, String name, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                @Nullable T nested = findOptional(child, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        throw new AssertionError("Missing component: " + name);
    }

    /// Finds an optional named descendant.
    private static <T extends JComponent> @Nullable T findOptional(Container root, String name, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                @Nullable T nested = findOptional(child, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}

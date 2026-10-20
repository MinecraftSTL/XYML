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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.GameDirectory;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.setting.InstanceConfigMigrationContent;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.i18n.LocalizedText;
import space.minecraftstl.xyml.util.PortablePath;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests manual migration defaults and the isolated-target restriction.
@NotNullByDefault
public final class InstanceConfigManualMigrationPanelTest {
    /// Process-wide settings field used to install a lightweight test configuration.
    private static java.lang.reflect.Field launcherSettingsField;

    /// Temporary repository root.
    @TempDir
    Path temporaryDirectory;

    /// Manual migration defaults to all categories and replacement while rejecting a non-isolated target.
    @Test
    public void defaultsToAllContentAndReplacementForNonIsolatedTarget() {
        try {
            launcherSettingsField = SettingsManager.class.getDeclaredField("launcherSettings");
            launcherSettingsField.setAccessible(true);
            launcherSettingsField.set(null, new LauncherSettings());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to install test launcher settings", failure);
        }
        XYMLGameRepository repository = new XYMLGameRepository(new GameDirectory(
                GameDirectoryID.generate(),
                LocalizedText.plain("Migration panel test"),
                PortablePath.of(temporaryDirectory.toString())));
        AtomicReference<@Nullable InstanceConfigManualMigrationPanel> panelReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> panelReference.set(new InstanceConfigManualMigrationPanel(
                repository,
                new GameInstanceID("target"),
                Runnable::run)));
        EdtDispatcher.executeAndWait(() -> {
            InstanceConfigManualMigrationPanel panel = java.util.Objects.requireNonNull(panelReference.get(), "panel");
            assertTrue(findNamed(panel, "instanceConfigManualMigrationReplace", JCheckBox.class).isSelected());
            for (InstanceConfigMigrationContent content : InstanceConfigMigrationContent.values()) {
                assertTrue(findNamed(
                        panel,
                        "instanceConfigManualMigrationContent" + content.name(),
                        JCheckBox.class).isSelected());
            }
            assertFalse(findFirstLabelText(panel).isBlank());
            panel.close();
        });
        try {
            launcherSettingsField.set(null, null);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Unable to restore test launcher settings", failure);
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

    /// Returns the first non-empty status label text.
    private static String findFirstLabelText(Container root) {
        for (Component component : root.getComponents()) {
            if (component instanceof JLabel label && label.getText() != null && !label.getText().isBlank()) {
                return label.getText();
            }
            if (component instanceof Container child) {
                String nested = findFirstLabelText(child);
                if (!nested.isBlank()) {
                    return nested;
                }
            }
        }
        return "";
    }
}

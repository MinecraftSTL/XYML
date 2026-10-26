// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.setting.InstanceConfigMigrationContent;
import space.minecraftstl.xyml.setting.InstanceConfigMigrationPolicy;
import space.minecraftstl.xyml.setting.InstanceConfigMigrationSourceType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests preset policy defaults, unavailable sources, and read-only draft controls.
@NotNullByDefault
public final class InstanceConfigMigrationPolicyPanelTest {
    /// Default policy renders enabled, global, and with all seven content categories selected.
    @Test
    public void rendersDefaultAutomaticMigrationPolicy() {
        EdtDispatcher.executeAndWait(() -> {
            InstanceConfigMigrationPolicyPanel panel = new InstanceConfigMigrationPolicyPanel();
            assertTrue(findNamed(panel, "instanceConfigMigrationEnabled", JCheckBox.class).isSelected());
            assertEquals(InstanceConfigMigrationSourceType.GLOBAL,
                    findNamed(panel, "instanceConfigMigrationSourceType", JComboBox.class).getSelectedItem());
            for (InstanceConfigMigrationContent content : InstanceConfigMigrationContent.values()) {
                assertTrue(findNamed(panel, "instanceConfigMigrationContent" + content.name(),
                        JCheckBox.class).isSelected());
            }
            assertEquals(InstanceConfigMigrationPolicy.defaults(), panel.editedPolicy());
        });
    }

    /// Invalid sources remain visible and round-trip without fallback into another preset.
    @Test
    public void retainsUnavailableSourceAndFreezesReadOnlyDraft() {
        EdtDispatcher.executeAndWait(() -> {
            InstanceConfigMigrationPolicy policy = new InstanceConfigMigrationPolicy(true,
                    InstanceConfigMigrationSourceType.INSTANCE,
                    space.minecraftstl.xyml.setting.GameDirectoryID.generate(),
                    new space.minecraftstl.xyml.game.GameInstanceID("missing"),
                    java.util.Set.of(InstanceConfigMigrationContent.OPTIONS));
            InstanceConfigMigrationPolicyPanel panel = new InstanceConfigMigrationPolicyPanel();
            panel.loadPolicy(policy);
            assertEquals(policy, panel.editedPolicy());
            assertEquals(false, ((InstanceConfigMigrationChoice) findNamed(panel,
                    "instanceConfigMigrationSourceInstance", JComboBox.class).getSelectedItem()).available());
            panel.setInteractionEnabled(false);
            assertEquals(false, findNamed(panel, "instanceConfigMigrationEnabled", JCheckBox.class).isEnabled());
            panel.loadPolicy(InstanceConfigMigrationPolicy.defaults());
            assertEquals(InstanceConfigMigrationPolicy.defaults(), panel.editedPolicy());
            assertEquals("", findNamed(panel, "instanceConfigMigrationStatus", javax.swing.JLabel.class).getText());
        });
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

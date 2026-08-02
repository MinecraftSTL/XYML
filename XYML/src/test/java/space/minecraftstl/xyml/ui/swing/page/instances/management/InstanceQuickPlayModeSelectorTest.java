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

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.QuickPlayType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Quick Play mode ordering, inheritance, mutual exclusion, and target-row ownership.
@NotNullByDefault
final class InstanceQuickPlayModeSelectorTest {
    /// Exposes inheritance before every local mode and keeps all choices mutually exclusive.
    @Test
    void instanceSelectorUsesInheritanceAndOrderedRadioModes() {
        EdtDispatcher.executeAndWait(() -> {
            InstanceQuickPlayModeSelector selector = selector(true);
            addRows(selector);

            assertEquals(
                    java.util.List.of(
                            QuickPlayType.NONE,
                            QuickPlayType.MULTIPLAYER,
                            QuickPlayType.SINGLEPLAYER,
                            QuickPlayType.REALMS),
                    InstanceQuickPlayModeSelector.displayOrder());
            assertTrue(selector.isInherited());
            for (QuickPlayType type : InstanceQuickPlayModeSelector.displayOrder()) {
                selector.button(type).doClick();
                assertFalse(selector.isInherited());
                assertEquals(type, selector.selectedType());
            }
            selector.inheritanceButton().doClick();
            assertTrue(selector.isInherited());
        });
    }

    /// Omits the inheritance row for global presets and applies durable values without callbacks.
    @Test
    void globalSelectorStartsWithNoneAndAppliesWithoutEmittingActions() {
        EdtDispatcher.executeAndWait(() -> {
            InstanceQuickPlayModeSelector selector = selector(false);
            AtomicInteger selections = new AtomicInteger();
            selector.addSelectionListener(selections::incrementAndGet);
            addRows(selector);

            assertNull(selector.inheritanceButton().getParent());
            assertEquals(QuickPlayType.NONE, selector.selectedType());
            selector.apply(settings(true, QuickPlayType.SINGLEPLAYER));
            assertEquals(QuickPlayType.SINGLEPLAYER, selector.selectedType());
            assertEquals(0, selections.get());
            selector.button(QuickPlayType.REALMS).doClick();
            assertEquals(1, selections.get());
            assertEquals(QuickPlayType.REALMS, selector.selectedType());
        });
    }

    /// Uses one radio per row and enables its target without rendering a second selection control.
    @Test
    void targetRowsUseRadioModesWithoutOverrideControls() {
        EdtDispatcher.executeAndWait(() -> {
            JPanel section = section();
            InheritedControl<JTextField> multiplayer = control("multiplayer");
            InheritedControl<JTextField> singleplayer = control("singleplayer");
            InheritedControl<JTextField> realms = control("realms");
            InstanceQuickPlayModeSelector selector = new InstanceQuickPlayModeSelector(
                    true,
                    multiplayer,
                    singleplayer,
                    realms);
            selector.addRows(section);

            assertNull(multiplayer.overrideBox().getParent());
            assertSame(section, multiplayer.editor().getParent());
            assertSame(section, selector.button(QuickPlayType.MULTIPLAYER).getParent());
            assertEquals(
                    selector.button(QuickPlayType.MULTIPLAYER).getText(),
                    multiplayer.editor().getAccessibleContext().getAccessibleName());

            for (QuickPlayType type : InstanceQuickPlayModeSelector.displayOrder()) {
                selector.button(type).doClick();
                selector.updateAvailability(true);
                InstanceGameSettingsSnapshot.QuickPlaySettings edited =
                        selector.editedSettings(settings(false, QuickPlayType.NONE));
                assertTrue(edited.typeOverridden());
                assertEquals(type, edited.type());
                assertEquals(type == QuickPlayType.MULTIPLAYER, edited.multiplayerOverridden());
                assertEquals(type == QuickPlayType.SINGLEPLAYER, edited.singleplayerOverridden());
                assertEquals(type == QuickPlayType.REALMS, edited.realmsOverridden());
                assertEquals(type == QuickPlayType.MULTIPLAYER, multiplayer.editor().isEnabled());
                assertEquals(type == QuickPlayType.SINGLEPLAYER, singleplayer.editor().isEnabled());
                assertEquals(type == QuickPlayType.REALMS, realms.editor().isEnabled());
            }
            selector.inheritanceButton().doClick();
            selector.updateAvailability(true);
            InstanceGameSettingsSnapshot.QuickPlaySettings inherited =
                    selector.editedSettings(settings(false, QuickPlayType.MULTIPLAYER));
            assertFalse(inherited.typeOverridden());
            assertFalse(inherited.multiplayerOverridden());
            assertFalse(inherited.singleplayerOverridden());
            assertFalse(inherited.realmsOverridden());
            assertFalse(multiplayer.editor().isEnabled());
            assertFalse(singleplayer.editor().isEnabled());
            assertFalse(realms.editor().isEnabled());

            selector.setEnabled(false);
            assertFalse(selector.inheritanceButton().isEnabled());
            for (QuickPlayType type : InstanceQuickPlayModeSelector.displayOrder()) {
                assertFalse(selector.button(type).isEnabled());
            }
        });
    }

    /// Preserves independently inherited legacy markers until the user deliberately edits the Quick Play group.
    @Test
    void preservesMixedOverrideMarkersUntilQuickPlayIsEdited() {
        EdtDispatcher.executeAndWait(() -> {
            InheritedControl<JTextField> multiplayer = control("multiplayer");
            InheritedControl<JTextField> singleplayer = control("singleplayer");
            InheritedControl<JTextField> realms = control("realms");
            InstanceQuickPlayModeSelector selector = new InstanceQuickPlayModeSelector(
                    true,
                    multiplayer,
                    singleplayer,
                    realms);
            selector.addRows(section());

            InstanceGameSettingsSnapshot.QuickPlaySettings targetOnly =
                    new InstanceGameSettingsSnapshot.QuickPlaySettings(
                            false,
                            QuickPlayType.MULTIPLAYER,
                            true,
                            "instance.example",
                            false,
                            "world",
                            false,
                            "realm");
            selector.apply(targetOnly);
            selector.updateAvailability(true);
            assertFalse(selector.isInherited());
            assertEquals(QuickPlayType.MULTIPLAYER, selector.selectedType());
            assertTrue(multiplayer.editor().isEnabled());
            assertSame(targetOnly, selector.editedSettings(targetOnly));

            InstanceGameSettingsSnapshot.QuickPlaySettings typeOnly =
                    new InstanceGameSettingsSnapshot.QuickPlaySettings(
                            true,
                            QuickPlayType.MULTIPLAYER,
                            false,
                            "global.example",
                            false,
                            "world",
                            false,
                            "realm");
            selector.apply(typeOnly);
            assertSame(typeOnly, selector.editedSettings(typeOnly));
            multiplayer.editor().setText("edited.example");
            InstanceGameSettingsSnapshot.QuickPlaySettings edited = selector.editedSettings(typeOnly);
            assertTrue(edited.typeOverridden());
            assertTrue(edited.multiplayerOverridden());
            assertEquals("edited.example", edited.multiplayer());

            selector.apply(targetOnly);
            selector.button(QuickPlayType.SINGLEPLAYER).doClick();
            InstanceGameSettingsSnapshot.QuickPlaySettings switched = selector.editedSettings(targetOnly);
            assertTrue(switched.typeOverridden());
            assertEquals(QuickPlayType.SINGLEPLAYER, switched.type());
            assertFalse(switched.multiplayerOverridden());
            assertTrue(switched.singleplayerOverridden());
            assertFalse(switched.realmsOverridden());
        });
    }

    /// Adds disposable target controls to one selector for selection-only tests.
    ///
    /// @param selector selector under test
    private static void addRows(InstanceQuickPlayModeSelector selector) {
        selector.addRows(section());
    }

    /// Creates one selector with disposable target controls.
    ///
    /// @param inheritanceAvailable whether to expose inheritance
    /// @return selector under test
    private static InstanceQuickPlayModeSelector selector(boolean inheritanceAvailable) {
        return new InstanceQuickPlayModeSelector(
                inheritanceAvailable,
                control("multiplayer"),
                control("singleplayer"),
                control("realms"));
    }

    /// Creates one complete Quick Play snapshot for mode-application tests.
    ///
    /// @param overridden whether the mode is local
    /// @param type effective mode
    /// @return complete Quick Play settings
    private static InstanceGameSettingsSnapshot.QuickPlaySettings settings(
            boolean overridden,
            QuickPlayType type) {
        return new InstanceGameSettingsSnapshot.QuickPlaySettings(
                overridden,
                type,
                overridden && type == QuickPlayType.MULTIPLAYER,
                "server",
                overridden && type == QuickPlayType.SINGLEPLAYER,
                "world",
                overridden && type == QuickPlayType.REALMS,
                "realm");
    }

    /// Creates a production-shaped three-column section.
    ///
    /// @return empty settings section
    private static JPanel section() {
        return new JPanel(new MigLayout(
                "insets 0, fillx, wrap 3",
                "[26!,center]8[280!,fill]16[grow,fill]",
                "[]10[]"));
    }

    /// Creates one independently inherited text target.
    ///
    /// @param name stable test identity
    /// @return target control
    private static InheritedControl<JTextField> control(String name) {
        JCheckBox override = new JCheckBox();
        override.setName(name + "Override");
        JTextField editor = new JTextField();
        editor.setName(name);
        return new InheritedControl<>(override, editor);
    }
}

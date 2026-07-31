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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies transparent grouped navigation, exclusive selection, accessibility, and keyboard traversal.
@NotNullByDefault
final class InstanceManagementNavigationPanelTest {
    /// All available rows are transparent, icon-backed, accessible, and represented without radio indicators.
    @Test
    void rendersCompleteTransparentSingleSelectionNavigation() {
        EdtDispatcher.executeAndWait(() -> {
            List<InstanceManagementPageId> selections = new ArrayList<>();
            InstanceManagementNavigationPanel panel = new InstanceManagementNavigationPanel(
                    InstanceManagementPageId.orderedValues(),
                    InstanceManagementPageId.OVERVIEW,
                    selections::add);

            JScrollPane scroll = Objects.requireNonNull(findNamed(
                    panel,
                    "instanceManagementNavigationScroll",
                    JScrollPane.class));
            JComponent content = Objects.requireNonNull(findNamed(
                    panel,
                    "instanceManagementNavigationContent",
                    JComponent.class));

            assertAll(
                    () -> assertFalse(panel.isOpaque()),
                    () -> assertFalse(scroll.isOpaque()),
                    () -> assertFalse(scroll.getViewport().isOpaque()),
                    () -> assertFalse(content.isOpaque()),
                    () -> assertEquals(new Dimension(0, 0), panel.getMinimumSize()),
                    () -> assertEquals(new Dimension(0, 0), scroll.getMinimumSize()),
                    () -> assertEquals(new Dimension(0, 0), content.getMinimumSize()),
                    () -> assertEquals(InstanceManagementNavigationPanel.PREFERRED_WIDTH,
                            scroll.getPreferredSize().width),
                    () -> assertEquals(InstanceManagementPageId.OVERVIEW, panel.selectedPage()),
                    () -> assertTrue(panel.button(InstanceManagementPageId.OVERVIEW).isSelected()),
                    () -> assertEquals(1, selectedButtonCount(panel)),
                    () -> assertTrue(selections.isEmpty()));

            for (InstanceManagementPageId page : InstanceManagementPageId.orderedValues()) {
                JToggleButton button = panel.button(page);
                FlatSVGIcon icon = assertInstanceOf(FlatSVGIcon.class, button.getIcon());
                assertAll(
                        () -> assertFalse(button instanceof JRadioButton),
                        () -> assertEquals("toolBarButton", button.getClientProperty("JButton.buttonType")),
                        () -> assertTrue(button.isFocusable()),
                        () -> assertNotNull(button.getAccessibleContext().getAccessibleName()),
                        () -> assertFalse(Objects.requireNonNull(
                                button.getAccessibleContext().getAccessibleName()).isBlank()),
                        () -> assertNotNull(button.getAccessibleContext().getAccessibleDescription()),
                        () -> assertTrue(icon.hasFound()),
                        () -> assertEquals(20, icon.getIconWidth()),
                        () -> assertEquals(20, icon.getIconHeight()),
                        () -> assertNotNull(button.getInputMap(JComponent.WHEN_FOCUSED).get(
                                KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0))),
                        () -> assertNotNull(button.getInputMap(JComponent.WHEN_FOCUSED).get(
                                KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0))),
                        () -> assertNotNull(button.getInputMap(JComponent.WHEN_FOCUSED).get(
                                KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0))),
                        () -> assertNotNull(button.getInputMap(JComponent.WHEN_FOCUSED).get(
                                KeyStroke.getKeyStroke(KeyEvent.VK_END, 0))));
            }

            assertNull(findNamed(
                    panel,
                    "instanceManagementNavigationGroup_OVERVIEW",
                    JLabel.class));
            for (InstanceManagementPageGroup group : List.of(
                    InstanceManagementPageGroup.CONTENT,
                    InstanceManagementPageGroup.CONFIGURATION,
                    InstanceManagementPageGroup.MAINTENANCE,
                    InstanceManagementPageGroup.INSTANCE)) {
                JLabel heading = Objects.requireNonNull(findNamed(
                        panel,
                        "instanceManagementNavigationGroup_" + group.name(),
                        JLabel.class));
                assertEquals(panel.button(group.pages().get(0)), heading.getLabelFor());
            }
        });
    }

    /// Repeated activation is idempotent while a real destination change emits one callback and one selection.
    @Test
    void emitsOnlyRealUserSelectionChanges() {
        EdtDispatcher.executeAndWait(() -> {
            List<InstanceManagementPageId> selections = new ArrayList<>();
            InstanceManagementNavigationPanel panel = new InstanceManagementNavigationPanel(
                    InstanceManagementPageId.orderedValues(),
                    InstanceManagementPageId.OVERVIEW,
                    selections::add);

            panel.button(InstanceManagementPageId.OVERVIEW).doClick();
            assertTrue(selections.isEmpty());

            panel.button(InstanceManagementPageId.MODS).doClick();
            assertAll(
                    () -> assertEquals(List.of(InstanceManagementPageId.MODS), selections),
                    () -> assertEquals(InstanceManagementPageId.MODS, panel.selectedPage()),
                    () -> assertTrue(panel.button(InstanceManagementPageId.MODS).isSelected()),
                    () -> assertFalse(panel.button(InstanceManagementPageId.OVERVIEW).isSelected()),
                    () -> assertEquals(1, selectedButtonCount(panel)));

            panel.button(InstanceManagementPageId.MODS).doClick();
            assertEquals(List.of(InstanceManagementPageId.MODS), selections);

            panel.setSelectedPage(InstanceManagementPageId.GAME_SETTINGS);
            assertAll(
                    () -> assertEquals(List.of(InstanceManagementPageId.MODS), selections),
                    () -> assertEquals(InstanceManagementPageId.GAME_SETTINGS, panel.selectedPage()),
                    () -> assertEquals(1, selectedButtonCount(panel)));
        });
    }

    /// A supported subset is canonicalized, skips empty group headings, and constrains keyboard traversal.
    @Test
    void rendersAndTraversesOnlyAvailablePages() {
        EdtDispatcher.executeAndWait(() -> {
            @Unmodifiable List<InstanceManagementPageId> requestedPages = List.of(
                    InstanceManagementPageId.MODPACK_EXPORT,
                    InstanceManagementPageId.OVERVIEW,
                    InstanceManagementPageId.MODS);
            @Unmodifiable List<InstanceManagementPageId> canonicalPages = List.of(
                    InstanceManagementPageId.OVERVIEW,
                    InstanceManagementPageId.MODS,
                    InstanceManagementPageId.MODPACK_EXPORT);
            List<InstanceManagementPageId> selections = new ArrayList<>();
            InstanceManagementNavigationPanel panel = new InstanceManagementNavigationPanel(
                    requestedPages,
                    InstanceManagementPageId.OVERVIEW,
                    selections::add);

            assertAll(
                    () -> assertEquals(canonicalPages, panel.availablePages()),
                    () -> assertThrows(
                            UnsupportedOperationException.class,
                            () -> panel.availablePages().add(InstanceManagementPageId.WORLDS)),
                    () -> assertThrows(
                            IllegalArgumentException.class,
                            () -> panel.button(InstanceManagementPageId.WORLDS)),
                    () -> assertNull(findNamed(
                            panel,
                            "instanceManagementNavigationGroup_CONFIGURATION",
                            JLabel.class)),
                    () -> assertNull(findNamed(
                            panel,
                            "instanceManagementNavigationGroup_INSTANCE",
                            JLabel.class)));

            invokeKey(panel.button(InstanceManagementPageId.OVERVIEW), KeyEvent.VK_UP);
            assertAll(
                    () -> assertEquals(InstanceManagementPageId.MODPACK_EXPORT, panel.selectedPage()),
                    () -> assertEquals(List.of(InstanceManagementPageId.MODPACK_EXPORT), selections));

            invokeKey(panel.button(InstanceManagementPageId.MODPACK_EXPORT), KeyEvent.VK_DOWN);
            assertAll(
                    () -> assertEquals(InstanceManagementPageId.OVERVIEW, panel.selectedPage()),
                    () -> assertEquals(
                            List.of(
                                    InstanceManagementPageId.MODPACK_EXPORT,
                                    InstanceManagementPageId.OVERVIEW),
                            selections));
        });
    }

    /// Invalid availability contracts fail before a partially configured navigation can escape construction.
    @Test
    void rejectsInvalidAvailablePageContracts() {
        EdtDispatcher.executeAndWait(() -> {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new InstanceManagementNavigationPanel(
                            List.of(),
                            InstanceManagementPageId.OVERVIEW,
                            ignored -> { }));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new InstanceManagementNavigationPanel(
                            List.of(
                                    InstanceManagementPageId.OVERVIEW,
                                    InstanceManagementPageId.OVERVIEW),
                            InstanceManagementPageId.OVERVIEW,
                            ignored -> { }));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new InstanceManagementNavigationPanel(
                            List.of(InstanceManagementPageId.MODS),
                            InstanceManagementPageId.OVERVIEW,
                            ignored -> { }));
        });
    }

    /// Invokes one focused keyboard binding without requiring a native test window.
    ///
    /// @param button focused navigation target
    /// @param keyCode unmodified key code to invoke
    private static void invokeKey(JToggleButton button, int keyCode) {
        KeyStroke stroke = KeyStroke.getKeyStroke(keyCode, 0);
        @Nullable Object actionKey = button.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
        assertNotNull(actionKey);
        @Nullable Action action = button.getActionMap().get(actionKey);
        assertNotNull(action);
        Objects.requireNonNull(action).actionPerformed(new ActionEvent(
                button,
                ActionEvent.ACTION_PERFORMED,
                "keyboard"));
    }

    /// Counts selected destination buttons across the supported subset.
    ///
    /// @param panel navigation panel under test
    /// @return number of selected rows
    private static long selectedButtonCount(InstanceManagementNavigationPanel panel) {
        return panel.availablePages().stream()
                .map(panel::button)
                .filter(JToggleButton::isSelected)
                .count();
    }

    /// Finds one named descendant of the requested Swing type.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @param type requested component type
    /// @param <T> component type
    /// @return matching component, or null when absent
    private static <T extends JComponent> @Nullable T findNamed(
            Container root,
            String name,
            Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                @Nullable T nested = findNamed(child, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}

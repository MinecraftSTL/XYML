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
package space.minecraftstl.xyml.ui.swing.shell;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.Objects;
import java.util.function.Consumer;

/// Renders icon-only primary navigation with settings anchored to the bottom edge.
@NotNullByDefault
final class ShellNavigationRail extends JPanel {
    /// Stable icon button width and height.
    static final int BUTTON_SIZE = 42;

    /// Buttons keyed by the page or persistent list they open.
    private final EnumMap<ShellPageId, ShellNavigationButton> buttons = new EnumMap<>(ShellPageId.class);

    /// Exclusive visual selection group spanning both navigation groups.
    private final ButtonGroup buttonGroup = new ButtonGroup();

    /// Creates the compact left-side navigation rail.
    ///
    /// @param presentations localized page labels and mnemonics
    /// @param toggleCommand callback opening or toggling one destination
    ShellNavigationRail(
            ShellPagePresentations presentations,
            Consumer<ShellPageId> toggleCommand) {
        super(new BorderLayout());
        Objects.requireNonNull(presentations, "presentations");
        Consumer<ShellPageId> toggle = Objects.requireNonNull(toggleCommand, "toggleCommand");
        setName("shellNavigationRail");
        setOpaque(true);
        setBorder(ShellSeparatorBorder.right());

        JPanel primaryGroup = createGroup("insets 10 5 0 5");
        for (ShellPageId page : new ShellPageId[] {
                ShellPageId.ACCOUNTS,
                ShellPageId.INSTANCES,
                ShellPageId.DOWNLOADS}) {
            addNavigationButton(primaryGroup, page, presentations.get(page), toggle);
        }

        JPanel auxiliaryGroup = createGroup("insets 0 5 10 5");
        addNavigationButton(
                auxiliaryGroup,
                ShellPageId.SETTINGS,
                presentations.get(ShellPageId.SETTINGS),
                toggle);
        add(primaryGroup, BorderLayout.NORTH);
        add(auxiliaryGroup, BorderLayout.SOUTH);
    }

    /// Creates one transparent vertical group with caller-selected outer insets.
    ///
    /// @param layoutConstraints MigLayout container constraints including group insets
    /// @return configured navigation group
    private static JPanel createGroup(String layoutConstraints) {
        JPanel group = new JPanel(new MigLayout(
                Objects.requireNonNull(layoutConstraints, "layoutConstraints") + ", flowy, gap 6",
                "[42!]",
                "[]"));
        group.setOpaque(false);
        return group;
    }

    /// Creates, registers, and mounts one icon-only page button.
    ///
    /// @param group owning top or bottom navigation group
    /// @param page represented destination
    /// @param presentation localized accessible presentation
    /// @param toggle destination callback
    private void addNavigationButton(
            JPanel group,
            ShellPageId page,
            ShellPagePresentation presentation,
            Consumer<ShellPageId> toggle) {
        ShellNavigationButton button = new ShellNavigationButton(page, presentation);
        button.setText(null);
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setMargin(new Insets(8, 8, 8, 8));
        button.setPreferredSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        button.setToolTipText(presentation.label());
        button.addActionListener(event -> toggle.accept(page));
        buttonGroup.add(button);
        buttons.put(page, button);
        group.add(button, "w 42!, h 42!");
    }

    /// Synchronizes visual selection with the active list or overlay page.
    ///
    /// @param selectedPage active shell destination
    void setSelectedPage(ShellPageId selectedPage) {
        ShellPageId page = Objects.requireNonNull(selectedPage, "selectedPage");
        buttonGroup.clearSelection();
        ShellNavigationButton selected = buttons.get(page);
        if (selected != null) {
            selected.setSelected(true);
        }
    }

    /// Returns one overlay navigation button for focused tests.
    ///
    /// @param page represented overlay destination
    /// @return matching stable button
    ShellNavigationButton button(ShellPageId page) {
        ShellNavigationButton button = buttons.get(Objects.requireNonNull(page, "page"));
        if (button == null) {
            throw new IllegalArgumentException("Page has no navigation button: " + page);
        }
        return button;
    }

    /// Disables every navigation target during shell cleanup.
    void disableNavigation() {
        for (ShellNavigationButton button : buttons.values()) {
            button.setEnabled(false);
        }
    }
}

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
import java.awt.Dimension;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.Objects;
import java.util.function.Consumer;

/// Renders icon-only navigation for transient pages beside persistent instance management.
@NotNullByDefault
final class ShellNavigationRail extends JPanel {
    /// Stable icon button width and height.
    static final int BUTTON_SIZE = 42;

    /// Buttons keyed by the overlay page they open or close.
    private final EnumMap<ShellPageId, ShellNavigationButton> buttons = new EnumMap<>(ShellPageId.class);

    /// Exclusive visual selection group cleared while the persistent base is exposed.
    private final ButtonGroup buttonGroup = new ButtonGroup();

    /// Creates the compact left-side navigation rail.
    ///
    /// @param presentations localized page labels and mnemonics
    /// @param toggleCommand callback toggling one overlay page
    ShellNavigationRail(
            ShellPagePresentations presentations,
            Consumer<ShellPageId> toggleCommand) {
        super(new MigLayout(
                "insets 10 5, flowy, gap 6",
                "[42!]",
                "[]"));
        Objects.requireNonNull(presentations, "presentations");
        Consumer<ShellPageId> toggle = Objects.requireNonNull(toggleCommand, "toggleCommand");
        setName("shellNavigationRail");
        setOpaque(true);
        setBorder(ShellSeparatorBorder.right());
        for (ShellPageId page : new ShellPageId[] {
                ShellPageId.DOWNLOADS,
                ShellPageId.ACCOUNTS,
                ShellPageId.SETTINGS}) {
            ShellPagePresentation presentation = presentations.get(page);
            ShellNavigationButton button = new ShellNavigationButton(page, presentation);
            button.setText(null);
            button.setHorizontalAlignment(SwingConstants.CENTER);
            button.setMargin(new Insets(8, 8, 8, 8));
            button.setPreferredSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
            button.setToolTipText(presentation.label());
            button.addActionListener(event -> toggle.accept(page));
            buttonGroup.add(button);
            buttons.put(page, button);
            add(button, "w 42!, h 42!");
        }
    }

    /// Synchronizes visual selection with the active base or overlay page.
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
            throw new IllegalArgumentException("Persistent instance management has no navigation button");
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

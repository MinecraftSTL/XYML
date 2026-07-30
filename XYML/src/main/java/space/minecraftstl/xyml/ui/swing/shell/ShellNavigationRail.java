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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.Metadata;

import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Insets;
import java.io.IOException;
import java.net.URI;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Renders icon-only primary navigation with Settings, community, and help actions anchored at the bottom.
@NotNullByDefault
final class ShellNavigationRail extends JPanel {
    /// Stable icon button width and height.
    static final int BUTTON_SIZE = 42;

    /// Stable official-community destination resolved from launcher metadata.
    private static final URI OFFICIAL_GROUP_URI = URI.create(Metadata.GROUPS_URL);

    /// Stable help destination matching the legacy title-bar question-mark action.
    private static final URI HELP_URI = URI.create(Metadata.CONTACT_URL);

    /// Bundled outline question-mark icon inherited from the legacy title-bar action.
    private static final String HELP_ICON_RESOURCE = "assets/swing/icons/help.svg";

    /// Buttons keyed by the page or persistent list they open.
    private final EnumMap<ShellPageId, ShellNavigationButton> buttons = new EnumMap<>(ShellPageId.class);

    /// Exclusive visual selection group spanning both navigation groups.
    private final ButtonGroup buttonGroup = new ButtonGroup();

    /// Independent bottom action opening the official XYML user group.
    private final JButton officialGroupButton;

    /// Independent bottom action opening the XYML help destination.
    private final JButton helpButton;

    /// Creates the compact left-side navigation rail.
    ///
    /// @param presentations localized page labels and mnemonics
    /// @param toggleCommand callback opening or toggling one destination
    ShellNavigationRail(
            ShellPagePresentations presentations,
            Consumer<ShellPageId> toggleCommand) {
        this(presentations, toggleCommand, ShellNavigationRail::browse);
    }

    /// Creates the compact rail with an injectable external-link action for focused tests.
    ///
    /// @param presentations localized page labels and mnemonics
    /// @param toggleCommand callback opening or toggling one destination
    /// @param externalLinkCommand callback opening trusted community and help destinations
    ShellNavigationRail(
            ShellPagePresentations presentations,
            Consumer<ShellPageId> toggleCommand,
            Consumer<URI> externalLinkCommand) {
        super(new BorderLayout());
        Objects.requireNonNull(presentations, "presentations");
        Consumer<ShellPageId> toggle = Objects.requireNonNull(toggleCommand, "toggleCommand");
        Consumer<URI> openExternalLink = Objects.requireNonNull(
                externalLinkCommand,
                "externalLinkCommand");
        setName("shellNavigationRail");
        setOpaque(false);
        setBorder(ShellSeparatorBorder.right());

        JPanel primaryGroup = createGroup("insets 10 5 0 5");
        for (ShellPageId page : List.of(
                ShellPageId.ACCOUNTS,
                ShellPageId.INSTANCES,
                ShellPageId.DOWNLOADS)) {
            addNavigationButton(primaryGroup, page, presentations.get(page), toggle);
        }

        JPanel auxiliaryGroup = createGroup("insets 0 5 10 5");
        addNavigationButton(
                auxiliaryGroup,
                ShellPageId.SETTINGS,
                presentations.get(ShellPageId.SETTINGS),
                toggle);
        officialGroupButton = createOfficialGroupButton(openExternalLink);
        auxiliaryGroup.add(officialGroupButton, "w 42!, h 42!");
        helpButton = createHelpButton(openExternalLink);
        auxiliaryGroup.add(helpButton, "w 42!, h 42!");
        add(primaryGroup, BorderLayout.NORTH);
        add(auxiliaryGroup, BorderLayout.SOUTH);
    }

    /// Creates the icon-only action anchored directly below Settings.
    ///
    /// @param externalLinkCommand callback opening trusted launcher metadata URLs
    /// @return configured independent action button
    private static JButton createOfficialGroupButton(Consumer<URI> externalLinkCommand) {
        String accessibleName = i18n("contact.chat.qq_group");
        @Nullable Icon icon = LauncherIconImages.communityIcon();
        return createExternalActionButton(
                "officialGroupButton",
                accessibleName,
                icon,
                icon == null ? "QQ" : null,
                OFFICIAL_GROUP_URI,
                externalLinkCommand);
    }

    /// Creates the icon-only help action anchored directly below the official-community action.
    ///
    /// @param externalLinkCommand callback opening trusted launcher metadata URLs
    /// @return configured independent help button
    private static JButton createHelpButton(Consumer<URI> externalLinkCommand) {
        FlatSVGIcon loadedIcon = new FlatSVGIcon(HELP_ICON_RESOURCE, 24, 24);
        @Nullable Icon icon = loadedIcon.hasFound() ? loadedIcon : null;
        return createExternalActionButton(
                "helpButton",
                i18n("help"),
                icon,
                icon == null ? "?" : null,
                HELP_URI,
                externalLinkCommand);
    }

    /// Creates one accessible icon-only action that opens a trusted external destination.
    ///
    /// @param componentName stable Swing component name
    /// @param accessibleName localized tooltip and accessible name
    /// @param icon bundled action icon, or `null` when its resource is unavailable
    /// @param fallbackText compact fallback text, or `null` when the icon is available
    /// @param destination trusted metadata destination
    /// @param externalLinkCommand callback opening trusted launcher metadata URLs
    /// @return configured independent external action button
    private static JButton createExternalActionButton(
            String componentName,
            String accessibleName,
            @Nullable Icon icon,
            @Nullable String fallbackText,
            URI destination,
            Consumer<URI> externalLinkCommand) {
        String name = Objects.requireNonNull(componentName, "componentName");
        String label = Objects.requireNonNull(accessibleName, "accessibleName");
        URI target = Objects.requireNonNull(destination, "destination");
        Consumer<URI> openExternalLink = Objects.requireNonNull(externalLinkCommand, "externalLinkCommand");
        JButton button = new JButton(icon);
        button.setName(name);
        button.setText(fallbackText);
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setMargin(new Insets(8, 8, 8, 8));
        button.setPreferredSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        button.setToolTipText(label);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFocusable(true);
        button.setFocusPainted(true);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.getAccessibleContext().setAccessibleName(label);
        button.addActionListener(event -> openExternalLink.accept(target));
        return button;
    }

    /// Opens one trusted external link with the platform browser and reports unavailable integration locally.
    ///
    /// @param destination trusted destination URI
    private static void browse(URI destination) {
        URI target = Objects.requireNonNull(destination, "destination");
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("Desktop integration is unavailable");
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                throw new IOException("Browser opening is unavailable");
            }
            desktop.browse(target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            JOptionPane.showMessageDialog(
                    null,
                    target.toString(),
                    i18n("message.error"),
                    JOptionPane.ERROR_MESSAGE);
        }
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
        @Nullable ShellNavigationButton selected = buttons.get(page);
        if (selected != null) {
            selected.setSelected(true);
        }
    }

    /// Returns one overlay navigation button for focused tests.
    ///
    /// @param page represented overlay destination
    /// @return matching stable button
    ShellNavigationButton button(ShellPageId page) {
        @Nullable ShellNavigationButton button = buttons.get(Objects.requireNonNull(page, "page"));
        if (button == null) {
            throw new IllegalArgumentException("Page has no navigation button: " + page);
        }
        return button;
    }

    /// Returns the official-community action button for focused layout and accessibility tests.
    ///
    /// @return stable independent community action button
    JButton officialGroupButton() {
        return officialGroupButton;
    }

    /// Returns the help action button for focused layout and accessibility tests.
    ///
    /// @return stable independent help action button
    JButton helpButton() {
        return helpButton;
    }

    /// Disables every navigation target during shell cleanup.
    void disableNavigation() {
        for (ShellNavigationButton button : buttons.values()) {
            button.setEnabled(false);
        }
        officialGroupButton.setEnabled(false);
        helpButton.setEnabled(false);
    }
}
